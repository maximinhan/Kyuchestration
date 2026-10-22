@file:OptIn(ExperimentalSerializationApi::class)

package com.kyuchestration.desktop.protocol

import com.kyuchestration.desktop.terminal.SessionTarget
import com.kyuchestration.desktop.terminal.chat.ChatEntry
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 화면 → 코어의 틀(core-ui-split-design.md 6.4). 줄 하나가 틀 하나다.
 *
 * **겉 열쇠가 `kind` 가 아니라 `frame` 이다.** 앱 소켓은 `kind` 로 요청을 가르고 `kind` 가 없는 줄을 승인 물음으로 읽는다
 * (AppSocket). 화면의 틀이 어떤 실수로 앱 소켓에 닿아도 승인 물음으로 읽히지 않게, 그리고 로그를 읽는 사람이 두 규약을
 * 한눈에 가르게 열쇠 자체를 다르게 둔다.
 */
@Serializable
@JsonClassDiscriminator("frame")
sealed interface FrameToCore {

    /** 붙었다 — 스냅샷을 달라. `seq` 가 건너뛴 화면도 이것을 다시 보내 처음부터 받는다(6.4). */
    @Serializable
    @SerialName("hello")
    data object Hello : FrameToCore

    /** @param id 화면이 지은 이름. 코어가 같은 id 로 답한다. */
    @Serializable
    @SerialName("command")
    data class Command(val id: String, val command: ScreenCommand) : FrameToCore
}

/** 코어 → 화면의 틀. */
@Serializable
@JsonClassDiscriminator("frame")
sealed interface FrameToScreen {

    /**
     * 인사에 대한 답. 스냅샷보다 먼저 온다.
     *
     * @param engineDirectory 엔진을 받아 두는 자리. 엔진은 코어 옆에 놓이므로 코어 OS 의 사실이다(6.2 (다)).
     */
    @Serializable
    @SerialName("welcome")
    data class Welcome(val engineDirectory: String) : FrameToScreen

    /**
     * 스냅샷 한 조각(6.6). 첫 조각은 작은 상태 전부이고 나머지는 대화의 변화다. 화면은 [last] 를 받기 전까지 거울을 바꾸지
     * 않는다 — 반쯤 온 스냅샷을 그리면 대화가 잘려 보인다.
     *
     * @param seq 이 스냅샷이 담은 마지막 변화의 번호. 다음 변화는 이것보다 하나 크다.
     */
    @Serializable
    @SerialName("snapshot")
    data class Snapshot(val seq: Long, val part: ScreenChange, val last: Boolean) : FrameToScreen

    /** @param seq 코어가 낸 변화마다 1 씩 는다. 하나라도 건너뛰면 화면은 다시 인사한다(6.4). */
    @Serializable
    @SerialName("change")
    data class Change(val seq: Long, val change: ScreenChange) : FrameToScreen

    /**
     * 명령을 받았다 · 거절했다. **결과가 아니다** — 결과는 언제나 [Change] 로 온다(6.4). 결과가 오는 길이 둘이면 화면이
     * 둘을 맞춰야 한다.
     *
     * 코어는 명령이 지금 상태에 남긴 변화를 이 답보다 먼저 보낸다. 그래서 답을 받은 화면은 그 명령의 즉각적인 결과(띄우는
     * 중인 진입 같은 것)를 이미 거울에 들고 있다.
     */
    @Serializable
    @SerialName("reply")
    data class Reply(val id: String, val outcome: CommandOutcome, val reason: String? = null) : FrameToScreen
}

@Serializable
enum class CommandOutcome {
    @SerialName("accepted")
    Accepted,

    @SerialName("rejected")
    Rejected,
}

/**
 * 틀을 줄로 쓰고 읽는 규칙.
 *
 * - 모르는 필드는 건너뛴다. 필드를 더하는 변경은 판을 올리지 않는다(6.14).
 * - null 은 싣지 않는다. 틀은 사람이 읽는 줄이기도 하고, 큰 대화의 스냅샷에서 빈 필드가 차지하는 몫이 작지 않다.
 */
private val screenProtocolJson = Json {
    classDiscriminator = "type"
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

fun encodeFrameToCore(frame: FrameToCore): String = screenProtocolJson.encodeToString(FrameToCore.serializer(), frame)

fun decodeFrameToCore(line: String): FrameToCore = screenProtocolJson.decodeFromString(FrameToCore.serializer(), line)

fun encodeFrameToScreen(frame: FrameToScreen): String = screenProtocolJson.encodeToString(FrameToScreen.serializer(), frame)

fun decodeFrameToScreen(line: String): FrameToScreen = screenProtocolJson.decodeFromString(FrameToScreen.serializer(), line)

/**
 * 읽지 못한 줄이 명령 틀이면 그 id. 틀의 겉모양(frame · id)은 읽히는데 명령이 낯선 줄 — 화면이 이 코어보다 새 판일 때 —
 * 에 거절로 답할 곳을 찾는다. 겉모양조차 읽히지 않으면 null 이다.
 */
fun commandIdOfUnreadableFrame(line: String): String? {
    val frame = try {
        screenProtocolJson.parseToJsonElement(line)
    } catch (notJson: SerializationException) {
        return null
    } as? JsonObject ?: return null
    if ((frame["frame"] as? JsonPrimitive)?.contentOrNull != "command") {
        return null
    }
    return (frame["id"] as? JsonPrimitive)?.contentOrNull
}

/**
 * 틀 위의 모양으로 한 번 써 둔 대화 줄. 스냅샷은 줄마다 크기를 재어 1 MiB 안쪽 조각으로 나누는데(6.6), 잴 때 쓴 문자열을
 * 조각의 줄에 그대로 잇는다 — 재려고 한 번, 틀을 쓰며 또 한 번 쓰면 큰 대화의 스냅샷마다 직렬화가 두 배가 된다.
 */
class EncodedChatEntry(val json: String) {
    val byteCount: Int = json.toByteArray(Charsets.UTF_8).size
}

fun encodeChatEntry(entry: ChatEntry): EncodedChatEntry =
    EncodedChatEntry(screenProtocolJson.encodeToString(ChatEntry.serializer(), entry))

/**
 * 스냅샷의 꼬리 조각 한 줄. 줄이 빈 틀을 써 두고 그 빈 자리에 미리 써 둔 줄들을 잇는다 — [encodeFrameToScreen] 으로 같은
 * 틀을 통째로 쓴 것과 바이트까지 같다.
 *
 * 빈 자리(`"entries":[]`)는 틀에 꼭 한 번 나온다. 문자열 값 안의 따옴표는 `\"` 로 쓰이므로 대상 이름이 같은 글자를 담아도
 * 그 모양이 되지 않는다.
 */
fun encodeSnapshotTailLine(
    seq: Long,
    target: SessionTarget,
    fromIndex: Int,
    entries: List<EncodedChatEntry>,
    last: Boolean,
): String {
    val emptyTail = encodeFrameToScreen(
        FrameToScreen.Snapshot(seq, ScreenChange.ConversationTailChanged(target, fromIndex, emptyList()), last),
    )
    val emptyEntriesAt = emptyTail.indexOf(EMPTY_ENTRIES)
    check(emptyEntriesAt >= 0 && emptyTail.indexOf(EMPTY_ENTRIES, emptyEntriesAt + 1) < 0) {
        "꼬리 조각의 빈 틀에서 줄 자리를 하나로 찾지 못했다: $emptyTail"
    }
    return emptyTail.substring(0, emptyEntriesAt) +
        entries.joinToString(separator = ",", prefix = "\"entries\":[", postfix = "]") { it.json } +
        emptyTail.substring(emptyEntriesAt + EMPTY_ENTRIES.length)
}

private const val EMPTY_ENTRIES = "\"entries\":[]"
