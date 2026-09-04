package com.kyuchestration.desktop.terminal.chat

import com.kyuchestration.desktop.terminal.SessionTarget
import com.kyuchestration.desktop.terminal.TerminalSessionFailure

/**
 * 셸의 대화 자리가 지금 보여줄 것.
 *
 * 터미널 자리의 갈래(EmbeddedTerminalState)와 같은 이유로 나눈다 — "대화와 진행 여부와 오류" 를
 * 각각 따로 든 세 값으로 두면 오류와 대화가 동시에 있는, 뜻이 없는 상태가 표현 가능해진다.
 *
 * **끝난 세션의 갈래를 따로 두지 않는다.** 터미널에서는 그것이 갈래였는데(SessionEndedOnScreen),
 * 챗에서는 끝났다는 사실이 대화 안에 있다([ChatConversation.endedExitCode]) — 전사는 그대로
 * 보이고 입력창만 닫히는, 같은 화면의 다른 상태다.
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
        val failure: TerminalSessionFailure,
    ) : ChatScreenState
}
