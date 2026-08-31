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
     */
    data class SessionEndedOnScreen(
        override val target: SessionTarget,
        val ttyConnector: TtyConnector,
        val exitCode: Int?,
    ) : EmbeddedTerminalState

    data class SessionEntryFailed(
        override val target: SessionTarget,
        val failure: TerminalSessionFailure,
    ) : EmbeddedTerminalState
}
