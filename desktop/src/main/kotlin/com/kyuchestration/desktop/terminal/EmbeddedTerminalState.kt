package com.kyuchestration.desktop.terminal

import com.jediterm.terminal.TtyConnector

/**
 * 앱 안의 터미널 자리가 지금 보여줄 것.
 *
 * 대시보드 상태와 같은 이유로 갈래를 나눈다 — "통로와 진행 여부와 오류" 를 각각 따로 든 세 값으로
 * 두면 오류와 통로가 동시에 있는 조합처럼 뜻이 없는 상태가 표현 가능해진다.
 */
sealed interface EmbeddedTerminalState {

    /** 지금 붙어 있는 세션. 아무 터미널도 열지 않았으면 null. */
    val target: SessionTarget?

    data object NoTerminalOpen : EmbeddedTerminalState {
        override val target: SessionTarget? = null
    }

    /** 세션을 띄우고 PTY 를 여는 중이다. 첫 진입이든 다른 세션으로 옮겨가는 중이든 여기다. */
    data class SessionEntryRunning(
        override val target: SessionTarget,
    ) : EmbeddedTerminalState

    /**
     * 붙었다.
     *
     * @param ttyConnector JediTerm 이 이 통로로 읽고 쓴다. 닫으면 PTY 가 끝나고 tmux 클라이언트가
     *   떨어진다 — 세션은 그대로 살아 있다(설계 원칙 5).
     */
    data class TerminalAttached(
        override val target: SessionTarget,
        val ttyConnector: TtyConnector,
    ) : EmbeddedTerminalState

    data class SessionEntryFailed(
        override val target: SessionTarget,
        val failure: TerminalSessionFailure,
    ) : EmbeddedTerminalState
}
