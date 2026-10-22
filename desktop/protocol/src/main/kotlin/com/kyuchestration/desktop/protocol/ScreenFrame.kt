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
 * 틀 규약의 판(core-ui-split-design.md 6.14). 정수 하나다.
 *
 * 필드를 더하는 변경은 올리지 않는다 — 받는 쪽이 모르는 필드는 건너뛴다(아래 screenProtocolJson). 뜻이 바뀌거나 필드가 빠지면
 * 올린다. `internal/workdir/conversations.go` 의 `schemaVersion` 규약과 같은 선이다.
 */
const val SCREEN_PROTOCOL_VERSION: Int = 1

/**
 * 판을 싣기 전의 상대가 말하는 규약 판. 1 단계(PR #76)의 화면 · 코어는 인사와 인사 답에 판을 싣지 않았다.
 *
 * 판 필드가 없는 틀을 읽지 못하는 틀로 버리지 않고 이 값으로 읽는다. 버리면 판 불일치의 길(6.14)에 닿지 못한다 — 새 코어는 옛
 * 화면의 인사를 조용히 건너뛰어 그 화면이 오지 않을 인사 답을 영영 기다리고, 새 화면은 옛 코어의 인사 답을 읽다 죽는다.
 */
const val PROTOCOL_VERSION_BEFORE_NEGOTIATION: Int = 0

/** 판을 싣지 않은 상대의 판 라벨. 판 불일치 화면이 "어느 쪽이 낡았는가" 를 보일 때 쓴다. */
const val UNKNOWN_VERSION_LABEL: String = "판을 알리지 않는 옛 판"

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

    /**
     * 붙었다 — 스냅샷을 달라. `seq` 가 건너뛴 화면도 이것을 다시 보내 처음부터 받는다(6.4).
     *
     * @param protocol 화면이 말하는 틀 규약의 판. 코어의 것과 다르면 코어는 인사 답 뒤에 [FrameToScreen.Bye] 로 닫는다.
     * @param screenVersion 화면의 판 라벨("v0.15.0" · "개발 빌드"). 판이 갈렸을 때 어느 쪽이 낡았는지 사람에게 보이려고 싣는다.
     * @param screenOs 화면이 도는 OS 의 낱말([operatingSystemWordOf]). 코어가 어느 화면이 붙었는지를 자기 진단 기록에 남긴다 —
     *   윈도우 화면과 WSLg 화면이 같은 코어에 붙는 날, 받은 기록만으로 둘을 가른다. 옛 화면은 싣지 않는다(null).
     */
    @Serializable
    @SerialName("hello")
    data class Hello(
        val protocol: Int = PROTOCOL_VERSION_BEFORE_NEGOTIATION,
        val screenVersion: String = UNKNOWN_VERSION_LABEL,
        val screenOs: String? = null,
    ) : FrameToCore

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
     * 인사에 대한 답. 스냅샷보다 먼저 온다. 규약의 판이 다르면 스냅샷 대신 [Bye] 가 뒤따른다.
     *
     * @param protocol 코어가 말하는 틀 규약의 판.
     * @param coreVersion 코어의 판 라벨. 화면과 코어는 같은 태그에서 같은 판으로 나가지만(6.14) 따로 깔리는 날이 온다.
     * @param coreOs 코어가 도는 OS 의 낱말([operatingSystemWordOf]). 화면이 자기 진단 기록에 남긴다.
     * @param wslDistro 코어가 WSL 안에서 돌면 그 배포판 이름(`WSL_DISTRO_NAME`), 아니면 null. 윈도우 화면이 자기 경로와 코어 경로를
     *   서로 옮기는 근거다 — `\\wsl.localhost\<배포판>\…` 는 이 배포판의 것만 코어 경로가 된다(6.9). 화면이 `-d` 로 고른 이름이
     *   아니라 코어가 말한 이름을 쓰는 것은 경로를 푸는 규칙이 코어 쪽의 사실이라서다.
     * @param coreDiagnosticLog 코어가 진단을 적는 파일. 화면의 것과 다른 프로세스의 파일이라, 오류 자리에서 둘을 함께 알린다(6.2 (다)).
     * @param engineDirectory 엔진을 받아 두는 자리. 엔진은 코어 옆에 놓이므로 코어 OS 의 사실이다(6.2 (다)).
     */
    @Serializable
    @SerialName("welcome")
    data class Welcome(
        val protocol: Int = PROTOCOL_VERSION_BEFORE_NEGOTIATION,
        val coreVersion: String = UNKNOWN_VERSION_LABEL,
        val coreOs: String? = null,
        val wslDistro: String? = null,
        val coreDiagnosticLog: String = "",
        val engineDirectory: String,
    ) : FrameToScreen

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

    /** 코어가 이 연결을 닫는다. 이 틀 뒤로는 아무것도 오지 않는다. */
    @Serializable
    @SerialName("bye")
    data class Bye(val reason: ByeReason) : FrameToScreen
}

/**
 * JVM 의 `os.name` 을 틀이 싣는 OS 낱말(`windows` · `macos` · `linux`)로 옮긴다. 이 셋 밖이면 null — 이 앱이 패키지를 내지 않는 OS 다.
 *
 * 틀에는 열거형이 아니라 낱말로 싣는다. 새 판의 상대가 모르는 낱말을 보내도 인사 틀 전체를 읽지 못하는 틀로 만들지 않으려는 것이다
 * (필드를 더하는 변경은 판을 올리지 않는다 — 6.14).
 */
fun operatingSystemWordOf(osName: String): String? = when {
    osName.startsWith("Windows", ignoreCase = true) -> "windows"
    osName.startsWith("Mac", ignoreCase = true) -> "macos"
    osName.startsWith("Linux", ignoreCase = true) -> "linux"
    else -> null
}

/**
 * 코어가 연결을 닫는 까닭. 지금은 하나다 — 화면을 갈아 끼우거나 코어를 끝내는 까닭(설계 6.4 의 나머지 둘)은 코어가 화면 없이
 * 사는 5 단계에서 생긴다.
 */
@Serializable
enum class ByeReason {
    /** 화면과 코어의 틀 규약 판이 다르다(6.14). 어느 쪽이 낡았는지는 [FrameToScreen.Welcome] 의 판이 말한다. */
    @SerialName("protocol_mismatch")
    ProtocolMismatch,
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
