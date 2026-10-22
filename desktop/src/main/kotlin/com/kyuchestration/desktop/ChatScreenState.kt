package com.kyuchestration.desktop

import com.kyuchestration.desktop.protocol.FailureView
import com.kyuchestration.desktop.terminal.PersonOpenedSessionTarget
import com.kyuchestration.desktop.terminal.SessionTarget
import com.kyuchestration.desktop.terminal.chat.ChatConversation
import com.kyuchestration.desktop.terminal.chat.SessionEntryState

/**
 * 셸의 대화 자리가 지금 보여줄 것.
 *
 * 터미널 자리의 갈래(EmbeddedTerminalState)와 같은 이유로 나눈다 — "대화와 진행 여부와 오류" 를
 * 각각 따로 든 세 값으로 두면 오류와 대화가 동시에 있는, 뜻이 없는 상태가 표현 가능해진다.
 *
 * **끝난 세션의 갈래를 따로 두지 않는다.** 터미널에서는 그것이 갈래였는데(SessionEndedOnScreen),
 * 챗에서는 끝났다는 사실이 대화 안에 있다([ChatConversation.endedExitCode]) — 전사는 그대로
 * 보이고 입력창만 닫히는, 같은 화면의 다른 상태다.
 *
 * **화면이 짓는 값이다.** 챗 홀더는 대화와 진입 상태를 세션마다 내놓고, 어느 세션을 볼지는 화면이 정한다
 * (core-ui-split-design.md 6.8 (가)). 이 값은 그 둘을 [chatScreenStateFor] 로 합친 것이다.
 */
sealed interface ChatScreenState {

    /** 지금 보고 있는 세션. 아무 챗도 열지 않았으면 null. */
    val target: SessionTarget?

    data object NoChatOpen : ChatScreenState {
        override val target: SessionTarget? = null
    }

    /** 무엇을 띄울지 엔진에게 묻고 프로세스를 띄우는 중이다. */
    data class ChatSessionEntryRunning(override val target: SessionTarget) : ChatScreenState

    /** 대화가 화면에 떠 있다. 세션이 이미 끝났어도 전사가 남아 있는 동안은 이 갈래다. */
    data class ChatOnScreen(
        override val target: SessionTarget,
        val conversation: ChatConversation,
    ) : ChatScreenState

    data class ChatSessionEntryFailed(
        override val target: SessionTarget,
        val failure: FailureView,
    ) : ChatScreenState
}

/**
 * 사람이 고른 세션 중 아직 볼 것이 있는 것. 대화도 진입 상태도 없으면(세션을 끝냈거나, 워크디렉토리를 닫았거나, 메인을
 * 새로 띄워 그 하위를 놓았다) null 이다 — 보이지 않는 세션을 "보는 중" 으로 들면 패널이 없는 줄을 짚고 명령이 허공으로 간다.
 */
fun viewedTargetStillThere(
    chosenTarget: SessionTarget?,
    conversations: Map<SessionTarget, ChatConversation>,
    sessionEntries: Map<PersonOpenedSessionTarget, SessionEntryState>,
): SessionTarget? = chosenTarget?.takeIf { it in conversations || it in sessionEntries }

/**
 * 사람이 보던 세션을 끝낸 뒤 화면이 볼 세션.
 *
 * 하위를 끝내면 메인으로 돌아간다 — 하위는 메인이 시킨 일이라 사람이 돌아갈 자리가 메인이다. 그 밖의 세션을 끝내면
 * 빈 자리다. 볼 대화가 없으면(메인도 끝났다) [chatScreenStateFor] 가 빈 자리로 그린다.
 */
fun viewedTargetAfterEnding(endedTarget: SessionTarget): SessionTarget? =
    if (endedTarget is SessionTarget.Child) SessionTarget.Main else null

/**
 * 화면이 [from] 에서 [to] 로 옮길 때 닫을 진입 실패. [from] 이 띄우지 못한 세션이면 그 세션이고, 아니면 null 이다.
 *
 * 실패는 사람이 그 자리를 보는 동안의 안내다. 떠나면 닫는다 — 남겨 두면 진입 상태 맵에 영영 남고, 다시 돌아온 사람은
 * 이미 지난 실패를 본다(다시 띄우려면 그 줄을 누르면 되고, 그것이 새 진입이다). 같은 자리에 머무는 것은 다시 띄우기를
 * 누른 것이라 닫지 않는다 — 새 진입이 그 자리를 덮는다.
 */
fun entryFailureLeftBehind(
    from: SessionTarget?,
    to: SessionTarget?,
    sessionEntries: Map<PersonOpenedSessionTarget, SessionEntryState>,
): PersonOpenedSessionTarget? {
    val leftTarget = from as? PersonOpenedSessionTarget ?: return null
    if (leftTarget == to || sessionEntries[leftTarget] !is SessionEntryState.Failed) {
        return null
    }
    return leftTarget
}

/**
 * 보고 있는 세션 하나의 화면을 짓는다.
 *
 * 진입 상태가 대화보다 먼저다. 홀더는 진입이 끝날 때 대화를 먼저 세우고 진입 줄을 나중에 빼므로, 둘이 함께 있는
 * 순간은 아직 진입이 끝나지 않은 것으로 읽는다.
 *
 * 볼 것이 없으면(대화도 진입도 없다 — 세션을 끝냈거나 그 하위를 놓았다) 빈 자리다.
 */
fun chatScreenStateFor(
    viewedTarget: SessionTarget?,
    conversations: Map<SessionTarget, ChatConversation>,
    sessionEntries: Map<PersonOpenedSessionTarget, SessionEntryState>,
): ChatScreenState {
    if (viewedTarget == null) {
        return ChatScreenState.NoChatOpen
    }
    return when (val entry = sessionEntries[viewedTarget]) {
        SessionEntryState.Running -> ChatScreenState.ChatSessionEntryRunning(viewedTarget)
        is SessionEntryState.Failed -> ChatScreenState.ChatSessionEntryFailed(viewedTarget, entry.failure)
        null -> conversations[viewedTarget]
            ?.let { ChatScreenState.ChatOnScreen(viewedTarget, it) }
            ?: ChatScreenState.NoChatOpen
    }
}
