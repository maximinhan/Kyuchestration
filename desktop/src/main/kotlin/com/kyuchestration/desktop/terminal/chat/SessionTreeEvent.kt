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

    /**
     * 하위 하나에게 일어난 사건. 상위가 기다리지 않으면 쌓였다가 다음 기다림이나 stdin 으로 간다.
     *
     * 사람이 상위에게 말한 것([UserSpoke])과 갈래를 나눈 이유가 그 "쌓인다" 다 — 사람의 말은 기다림이 열려 있을
     * 때만 사건이고, 그 밖에는 stdin 으로 간 말 그 자체라 쌓아 둘 것이 없다. 쌓이는 목록이 이 타입만 받으므로
     * 사람의 말이 나중의 기다림을 깨우는 자리가 생기지 않는다.
     */
    sealed interface OfChild : SessionTreeEvent {

        /** 이 사건이 어느 하위의 것인가. 기다림의 `ids` 가 이 값으로 사건을 고른다. */
        val childId: String
    }

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
    ) : OfChild

    /**
     * 하위가 끝났다.
     *
     * @param exitCode 프로세스가 남긴 종료 코드. 앱이 끝낸 하위는 이 사건을 낼 때 아직 거두지 못해 null 이다.
     */
    data class SessionEnded(
        override val childId: String,
        val reason: ChildEndReason,
        val exitCode: Int?,
    ) : OfChild

    /**
     * 하위가 `ask_parent` 로 물었다. 하위의 턴은 상위의 답을 기다리며 멈춰 있다.
     *
     * @param askId 그 물음의 이름. 상위가 `send_to_session` 으로 답하면 그 답의 `askId` 로 어느 물음에 답했는지를 안다.
     */
    data class AskedParent(
        override val childId: String,
        val message: String,
        val askId: String,
    ) : OfChild

    /**
     * 사람이 기다리는 상위에게 말했다(6.5 의 셋째 줄).
     *
     * **내용이 없다.** 사람의 말은 stdin 으로 가서 도구 결과 바로 뒤에 같은 턴으로 붙는다(3.1). 내용을 여기에도 담으면
     * 같은 말이 두 번 보인다(6.6).
     */
    data object UserSpoke : SessionTreeEvent
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

        is SessionTreeEvent.AskedParent -> {
            put("type", "asked_parent")
            put("id", event.childId)
            put("message", event.message)
            put("askId", event.askId)
        }

        is SessionTreeEvent.UserSpoke -> put("type", "user_spoke")
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

/**
 * 쉬는 상위에게 사건을 사용자 메시지로 옮길 때의 한 줄(6.5 의 접두).
 *
 * 접두가 모델이 출처를 읽는 재료다(3.5 (가)). 상위의 규약 문구가 `[하위 세션 <id> · …]` 로 시작하는 말을 하위의 것으로
 * 읽는다(session_command_child.go 의 parentSessionConventionText) — 그 모양을 바꾸면 규약 문구도 함께 바꾼다.
 */
internal fun parentNoticeTextFor(event: SessionTreeEvent.OfChild): String = when (event) {
    is SessionTreeEvent.TurnCompleted -> {
        val labels = listOfNotNull(
            "턴 끝",
            "실패".takeIf { event.isError },
            "사람이 시킨 턴".takeIf { event.triggeredBy == TurnTrigger.Human },
        )
        "${childPrefix(event.childId, labels)} ${event.result}"
    }

    is SessionTreeEvent.SessionEnded -> "${childPrefix(event.childId, listOf("끝남"))} ${endedSentenceFor(event)}"

    is SessionTreeEvent.AskedParent -> "${childPrefix(event.childId, listOf("질문"))} ${event.message}"
}

private fun childPrefix(childId: String, labels: List<String>): String =
    "[하위 세션 $childId · ${labels.joinToString(" · ")}]"

private fun endedSentenceFor(event: SessionTreeEvent.SessionEnded): String = when (event.reason) {
    ChildEndReason.Exited -> "스스로 끝났습니다"
    ChildEndReason.Crashed -> "비정상으로 끝났습니다 (종료 코드 ${event.exitCode})"
    ChildEndReason.EndedByHuman -> "사람이 이 하위를 끝냈습니다"
    ChildEndReason.EndedByParent -> "end_session 으로 끝냈습니다"
    ChildEndReason.ParentEnded -> "상위가 끝나 함께 끝났습니다"
}
