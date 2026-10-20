package com.kyuchestration.desktop.terminal.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * 상위의 `kyu mcp serve` 가 앱 소켓으로 보낸 세션 트리 요청(session-tree-design.md 6.4).
 *
 * **모양의 출처는 엔진의 요청 타입이다**(mcp_session_tree.go 의 spawnSessionRequest · sendToSessionRequest ·
 * endSessionRequest · listSessionsRequest). 모델이 무엇을 더 붙여 보내든 앱이 받는 모양은 그쪽에서 정해진다.
 */
sealed interface SessionTreeRequest {

    /**
     * @param requestedCwd 상위가 고른 자리. 생략했으면 null — 워크디렉토리 루트라는 뜻이고, 그 풀이는 엔진이 한다(6.8).
     * @param title 화면의 탭 이름. 생략했으면 null — 엔진이 cwd 의 마지막 이름으로 채운다.
     */
    data class SpawnSession(
        val prompt: String,
        val requestedCwd: String?,
        val createDirectory: Boolean,
        val title: String?,
    ) : SessionTreeRequest

    /**
     * 상위가 하위들에게 전할 일이 생길 때까지 기다린다(6.6).
     *
     * @param childIds 기다릴 하위들. null 이면 살아 있는 하위 전부다 — 엔진이 빈 배열을 거절하므로 "아무도" 는 오지 않는다.
     * @param waitSeconds 아무 일 없이 기다릴 최대 시간. 범위(1..3000)는 엔진이 이미 지켰다(6.7).
     */
    data class AwaitSessions(val childIds: List<String>?, val waitSeconds: Int) : SessionTreeRequest

    /** 상위가 하위에게 말한다(6.2 의 `send_to_session`). */
    data class SendToSession(val childId: String, val message: String) : SessionTreeRequest

    data class EndSession(val childId: String) : SessionTreeRequest

    data object ListSessions : SessionTreeRequest
}

/** 앱이 세션 트리 요청에 하는 답. 엔진이 모델에게 그대로 옮긴다. */
sealed interface SessionTreeAnswer {

    /**
     * 하위를 띄우고 첫 프롬프트를 넣었다(6.2 — 하위의 턴이 끝나기를 기다리지 않는다).
     *
     * @param notice 상위에게 덧붙일 사실. 사람이 연 레포 세션이 같은 자리에 살아 있으면 그 말이다(6.8) —
     *   막지는 않되, 상위가 사람에게 물을 수 있게 알린다.
     */
    data class Spawned(
        val childId: String,
        val relativeCwd: String,
        val title: String,
        val conversationId: String,
        val notice: String?,
    ) : SessionTreeAnswer

    /**
     * 상위의 말을 하위의 stdin 에 사용자 메시지로 넣었다(6.5). 하위의 **새 턴**을 연다 — 하위가 일하는 중이면
     * 그 턴의 도구 경계에서 끼어든다(3.1). 상위가 그 차이를 알아야 해서 통로를 답에 싣는다(6.2).
     */
    data object DeliveredAsMessage : SessionTreeAnswer

    /**
     * 기다림이 끝났다 — 전할 일이 생겼거나, 아무 일 없이 시간이 다 됐다(6.6).
     *
     * @param events 그때까지 쌓인 사건 전부. 시간이 다 된 것이면 비어 있다.
     * @param stillRunning 기다린 하위 중 아직 살아 있는 것. 상위가 다시 기다릴지를 정하는 재료다.
     */
    data class Awaited(val events: List<SessionTreeEvent>, val stillRunning: List<String>) : SessionTreeAnswer

    data class Ended(val childId: String) : SessionTreeAnswer

    data class Listed(val children: List<ChildSessionNode>) : SessionTreeAnswer

    /** 하지 못했다. 엔진이 이 문구를 도구 오류로 올려, 모델이 하위가 안 뜬 것과 고칠 것을 함께 안다. */
    data class Refused(val reason: String) : SessionTreeAnswer
}

/**
 * 아직 답하지 않은 세션 트리 요청 하나 — 그 내용과, 답을 되돌릴 자리.
 *
 * [AskedPermission] 과 같은 자리다. 기다리는 쪽은 소켓 연결이고 답하는 쪽은 상태 홀더다 — spawn 은 관문
 * 대화 상자에서 사람이 답할 때까지 열려 있을 수 있다(6.9).
 */
class AskedSessionTreeRequest(val request: SessionTreeRequest) {

    private val answered = CompletableDeferred<SessionTreeAnswer>()

    /** 답한다. 두 번 불러도 첫 답만 간다. */
    fun answerWith(answer: SessionTreeAnswer) {
        answered.complete(answer)
    }

    internal suspend fun awaitAnswer(): SessionTreeAnswer = answered.await()
}

/** 세션 트리 요청의 답을 엔진이 읽을 한 줄로 옮긴다. */
internal fun answerLineFor(answer: SessionTreeAnswer): String = when (answer) {
    is SessionTreeAnswer.Spawned -> buildJsonObject {
        put("id", answer.childId)
        put("cwd", answer.relativeCwd)
        put("title", answer.title)
        put("conversationId", answer.conversationId)
        put("state", RUNNING_STATE)
        answer.notice?.let { put("notice", it) }
    }

    is SessionTreeAnswer.DeliveredAsMessage -> buildJsonObject { put("delivered", DELIVERED_AS_MESSAGE) }

    is SessionTreeAnswer.Awaited -> buildJsonObject {
        putJsonArray("events") { answer.events.forEach { event -> addJsonObject { putSessionTreeEvent(event) } } }
        putJsonArray("stillRunning") { answer.stillRunning.forEach { add(it) } }
    }

    is SessionTreeAnswer.Ended -> buildJsonObject {
        put("id", answer.childId)
        put("state", ENDED_STATE)
    }

    is SessionTreeAnswer.Listed -> buildJsonObject {
        putJsonArray("sessions") {
            answer.children.forEach { child -> addJsonObject { putChildSessionFacts(child) } }
        }
    }

    is SessionTreeAnswer.Refused -> buildJsonObject { put("error", answer.reason) }
}.toString()

/**
 * `list_sessions` 의 한 항목(6.2).
 *
 * 끝난 하위는 기다리는 것이 없다 — 끝나기 직전의 값을 그대로 두면 상위는 끝난 하위가 아직 승인을 기다린다고 읽는다.
 */
private fun JsonObjectBuilder.putChildSessionFacts(child: ChildSessionNode) {
    put("id", child.childId)
    put("title", child.title)
    put("cwd", child.relativeCwd)
    when (val lifecycle = child.lifecycle) {
        is ChildLifecycle.Running -> {
            put("state", RUNNING_STATE)
            put("waitingFor", child.waitingFor.wireName)
        }

        is ChildLifecycle.Ended -> {
            put("state", ENDED_STATE)
            put("waitingFor", ChildWaitingFor.Nothing.wireName)
            put("endedReason", lifecycle.reason.wireName)
            lifecycle.exitCode?.let { put("exitCode", it) }
        }
    }
}

/**
 * 소켓 줄에서 세션 트리 요청을 읽는다.
 *
 * @return 이 앱이 받는 kind 가 아니면 [SessionTreeRequestReading.NotSessionTreeKind], 받는 kind 인데 필드가
 *   모자라면 [SessionTreeRequestReading.Unreadable].
 */
internal fun sessionTreeRequestFrom(kind: String, requestObject: JsonObject): SessionTreeRequestReading {
    val request = when (kind) {
        SPAWN_REQUEST_KIND -> requestObject.nonEmptyStringOrNull("prompt")?.let { prompt ->
            SessionTreeRequest.SpawnSession(
                prompt = prompt,
                requestedCwd = requestObject.stringOrNull("cwd"),
                createDirectory = (requestObject["createDirectory"] as? JsonPrimitive)?.booleanOrNull ?: false,
                title = requestObject.stringOrNull("title"),
            )
        }

        AWAIT_REQUEST_KIND -> awaitSessionsFrom(requestObject)

        SEND_REQUEST_KIND -> requestObject.nonEmptyStringOrNull("id")?.let { childId ->
            requestObject.nonEmptyStringOrNull("message")?.let { message -> SessionTreeRequest.SendToSession(childId, message) }
        }

        END_REQUEST_KIND -> requestObject.nonEmptyStringOrNull("id")?.let(SessionTreeRequest::EndSession)

        LIST_REQUEST_KIND -> SessionTreeRequest.ListSessions

        else -> return SessionTreeRequestReading.NotSessionTreeKind
    }
    return request?.let(SessionTreeRequestReading::Read) ?: SessionTreeRequestReading.Unreadable
}

/**
 * `await` 줄을 읽는다. `waitSeconds` 는 엔진이 늘 싣는다 — 없으면 판이 어긋난 것이라 읽지 못한 줄이다.
 *
 * 빈 `ids` 도 읽지 못한 줄로 둔다. 엔진이 그것을 모델에게 거절하므로(awaitSessions) 여기까지 오면 판이 어긋난 것이고,
 * 그것을 "전부" 로 읽으면 상위가 고르지 않은 하위의 사건에 깨어난다.
 */
private fun awaitSessionsFrom(requestObject: JsonObject): SessionTreeRequest.AwaitSessions? {
    val waitSeconds = (requestObject["waitSeconds"] as? JsonPrimitive)?.intOrNull ?: return null
    val childIds = when (val ids = requestObject["ids"]) {
        null, JsonNull -> null
        is JsonArray -> ids.map { (it as? JsonPrimitive)?.takeIf { id -> id.isString }?.content ?: return null }
            .takeIf { it.isNotEmpty() } ?: return null
        else -> return null
    }
    return SessionTreeRequest.AwaitSessions(childIds = childIds, waitSeconds = waitSeconds)
}

/** 줄 하나를 세션 트리 요청으로 읽어 본 결과. */
internal sealed interface SessionTreeRequestReading {
    data class Read(val request: SessionTreeRequest) : SessionTreeRequestReading
    data object Unreadable : SessionTreeRequestReading
    data object NotSessionTreeKind : SessionTreeRequestReading
}

private fun JsonObject.stringOrNull(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.nonEmptyStringOrNull(key: String): String? = stringOrNull(key)?.takeIf { it.isNotEmpty() }

/** 이 앱이 받는 세션 트리 kind 들(mcp_session_tree.go 의 *RequestKind). */
private const val SPAWN_REQUEST_KIND = "spawn"
private const val AWAIT_REQUEST_KIND = "await"
private const val SEND_REQUEST_KIND = "send"
private const val END_REQUEST_KIND = "end"
private const val LIST_REQUEST_KIND = "list"

private const val RUNNING_STATE = "running"
private const val ENDED_STATE = "ended"

/** `send_to_session` 의 `delivered` 낱말(6.2). */
private const val DELIVERED_AS_MESSAGE = "as_message"
