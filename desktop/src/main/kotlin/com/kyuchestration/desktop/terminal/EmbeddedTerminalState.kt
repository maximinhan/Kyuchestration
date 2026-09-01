package com.kyuchestration.desktop.terminal

import com.jediterm.terminal.TtyConnector

/**
 * 앱 안의 터미널 자리가 지금 보여줄 것.
 *
 * 대시보드 상태와 같은 이유로 갈래를 나눈다 — "통로와 진행 여부와 오류" 를 각각 따로 든 세 값으로
 * 두면 오류와 통로가 동시에 있는 조합처럼 뜻이 없는 상태가 표현 가능해진다.
 */
sealed interface EmbeddedTerminalState {

    /** 지금 보고 있는 세션. 아무 터미널도 열지 않았으면 null. */
    val target: SessionTarget?

    data object NoTerminalOpen : EmbeddedTerminalState {
        override val target: SessionTarget? = null
    }

    /** 무엇을 띄울지 묻고 PTY 를 여는 중이다. 첫 진입이든 다른 세션으로 옮겨가는 중이든 여기다. */
    data class SessionEntryRunning(
        override val target: SessionTarget,
    ) : EmbeddedTerminalState

    /**
     * 세션이 화면에 떠 있다.
     *
     * @param ttyConnector JediTerm 이 이 통로로 읽고 쓴다. 닫으면 PTY 안의 `claude` 가 끝난다 —
     *   앱이 그 프로세스를 보유하고 있으므로 detach 라는 중간 상태가 없다(설계 문서 5.3).
     */
    data class SessionOnScreen(
        override val target: SessionTarget,
        val ttyConnector: TtyConnector,
    ) : EmbeddedTerminalState

    /**
     * 세션이 스스로 끝났고, 마지막 화면이 아직 남아 있다.
     *
     * 화면에서 곧바로 내리지 않는 이유가 이 갈래의 전부다. `claude` 가 마지막으로 남긴 줄이
     * 사용자가 왜 끝났는지 아는 유일한 자리이고 — 이어갈 대화가 없어 즉시 죽은 경우가 특히
     * 그렇다 — 그것을 치우면 사용자는 터미널이 깜빡였다는 것만 보게 된다(설계 문서 8 절 9 번).
     *
     * @param exitCode 세션이 남긴 종료 코드. 예외로 끝나 알 수 없으면 null.
     * @param resumedConversationId 이 세션이 이어가려던 대화의 ID. 새 대화였으면 null.
     */
    data class SessionEndedOnScreen(
        override val target: SessionTarget,
        val ttyConnector: TtyConnector,
        val exitCode: Int?,
        val resumedConversationId: String?,
    ) : EmbeddedTerminalState {

        /**
         * 이어가려던 대화를 열지 못한 것으로 보인다 — 이어가기로 연 세션이 0 이 아닌 코드로 끝났다.
         *
         * **단정하지 않는 이름인 것이 뜻이다.** 대화가 열린 뒤 `claude` 가 다른 이유로 죽어도
         * 같은 모양이 된다. 그래서 화면은 이 자리에서 대신 새 대화를 열어 주지 않고, 왜 끝났는지는
         * 남아 있는 마지막 화면이 말하게 두고(전사가 없다는 `claude` 의 한 줄이 거기 있다),
         * 새로 시작할지는 사용자가 정한다(설계 문서 5.5.4 — 실패한 자리에서 자동으로 다시 열지 않는다).
         *
         * 종료 코드를 모르면 말하지 않는다. 예외로 끝나 코드가 없는 자리까지 실패로 세면
         * 화면이 일어나지 않은 일을 알리게 된다.
         */
        val resumeFailureSuspected: Boolean
            get() = resumeFailureSuspected(resumedConversationId, exitCode)
    }

    data class SessionEntryFailed(
        override val target: SessionTarget,
        val failure: TerminalSessionFailure,
    ) : EmbeddedTerminalState
}

/**
 * 이어가려던 대화를 열지 못한 것으로 보이는가.
 *
 * 화면(SessionEndedOnScreen)과 진단 기록(EmbeddedTerminalStateHolder)이 함께 쓴다. 둘이 같은
 * 판단을 각자 적어 두면 한쪽만 고쳐지는 날이 오고, 그날 화면과 기록은 같은 세션을 두고 다른
 * 말을 한다 — 받은 기록으로 화면에서 본 것을 확인하려던 사람이 가장 먼저 걸리는 자리다.
 *
 * 종료 코드를 모르면 말하지 않는다. 예외로 끝나 코드가 없는 자리까지 실패로 세면 일어나지 않은
 * 일을 알리게 된다.
 */
internal fun resumeFailureSuspected(resumedConversationId: String?, exitCode: Int?): Boolean =
    resumedConversationId != null && exitCode != null && exitCode != 0
