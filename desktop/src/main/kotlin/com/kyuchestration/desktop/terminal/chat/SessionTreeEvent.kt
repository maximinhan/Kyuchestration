package com.kyuchestration.desktop.terminal.chat

import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put

/**
 * 상위에게 전할 일 하나(session-tree-design.md 6.6 의 `SessionEvent`).
 *
 * `await_session` 이 이것을 기다린다 — 하위가 **끝날 때까지**가 아니라 **전할 일이 생길 때까지**다. 상위가 우리 도구로
 * 기다리고 있으면 그 도구의 답으로 가고, 쉬고 있으면 사용자 메시지로 간다(원칙 18). 길만 다르고 사건은 같다.
 */
sealed interface SessionTreeEvent {

    /** 이 사건이 어느 하위의 것인가. 기다림의 `ids` 가 이 값으로 사건을 고른다. */
    val childId: String

    /**
     * 하위의 턴 하나가 끝났다.
     *
     * @param result 그 턴의 마지막 답 텍스트(`result.result`). 상위가 하위의 일을 받는 자리가 이것이다 — 하위에게
     *   "결과를 알리는" 도구가 따로 없는 이유다(6.3).
     * @param triggeredBy 그 턴을 누가 열었는가. 사람이 하위 탭에서 시킨 턴의 결과를 상위가 제 일의 답으로 읽지 않게 한다.
     */
    data class TurnCompleted(
        override val childId: String,
        val result: String,
        val isError: Boolean,
        val triggeredBy: TurnTrigger,
        val costUsd: Double,
        val durationMillis: Long,
    ) : SessionTreeEvent

    /**
     * 하위가 끝났다.
     *
     * @param exitCode 프로세스가 남긴 종료 코드. 앱이 끝낸 하위는 이 사건을 낼 때 아직 거두지 못해 null 이다.
     */
    data class SessionEnded(
        override val childId: String,
        val reason: ChildEndReason,
        val exitCode: Int?,
    ) : SessionTreeEvent
}

/** 하위의 턴을 누가 열었는가(6.6 의 `triggeredBy`). */
enum class TurnTrigger(val wireName: String) {
    Parent("parent"),
    Human("human"),
}

/** 사건 하나를 엔진이 모델에게 그대로 옮길 모양으로 적는다. 종류는 `type` 으로 가른다. */
internal fun JsonObjectBuilder.putSessionTreeEvent(event: SessionTreeEvent) {
    when (event) {
        is SessionTreeEvent.TurnCompleted -> {
            put("type", "turn_completed")
            put("id", event.childId)
            put("result", event.result)
            put("isError", event.isError)
            put("triggeredBy", event.triggeredBy.wireName)
            put("costUsd", event.costUsd)
            put("durationMs", event.durationMillis)
        }

        is SessionTreeEvent.SessionEnded -> {
            put("type", "session_ended")
            put("id", event.childId)
            put("reason", event.reason.wireName)
            event.exitCode?.let { put("exitCode", it) }
        }
    }
}

/**
 * 하위의 턴을 누가 열었는가 — 그 턴에서 처음 되돌아온 사용자 메시지의 주인이다.
 *
 * 턴이 열린 뒤 사람이 덧붙인 말이 같은 턴에 합쳐져도(chat-ui 3.11) 그 턴을 연 것은 첫 말이다.
 *
 * @param entriesBeforeTurnEnded 그 턴의 끝이 접히기 **전**의 전사.
 */
internal fun turnTriggerOf(entriesBeforeTurnEnded: List<ChatEntry>): TurnTrigger {
    val thisTurnEntries = entriesBeforeTurnEnded.takeLastWhile { it !is ChatEntry.TurnEnded }
    val opener = thisTurnEntries.firstOrNull { it is ChatEntry.UserSaid } as? ChatEntry.UserSaid
    return if (opener?.sender == UserMessageSender.ParentSession) TurnTrigger.Parent else TurnTrigger.Human
}
