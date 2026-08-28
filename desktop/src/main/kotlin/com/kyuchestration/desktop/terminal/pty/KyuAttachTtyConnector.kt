package com.kyuchestration.desktop.terminal.pty

import com.jediterm.core.util.TermSize
import com.jediterm.terminal.ProcessTtyConnector
import com.kyuchestration.desktop.terminal.SessionTarget
import com.pty4j.PtyProcess
import com.pty4j.WinSize

/**
 * JediTerm 의 화면과 `kyu attach` 를 실행 중인 PTY 를 잇는 통로.
 *
 * 읽기·쓰기·종료는 ProcessTtyConnector 가 이미 안다(PtyProcess 는 java.lang.Process 다).
 * 이 클래스가 더하는 것은 크기 전달 하나뿐이다.
 */
internal class KyuAttachTtyConnector(
    private val ptyProcess: PtyProcess,
    private val target: SessionTarget,
) : ProcessTtyConnector(ptyProcess, Charsets.UTF_8) {

    override fun getName(): String = "kyu attach ${target.label}"

    /**
     * 창 크기가 바뀌면 PTY 의 winsize 를 따라 바꾼다.
     *
     * 반드시 재정의해야 한다. TtyConnector 의 기본 구현은 폐기된 Dimension 짝으로 흘러가다가
     * "should override TtyConnector.resize(TermSize)" 라며 IllegalStateException 을 던진다.
     *
     * 이 한 줄이 tmux 까지 닿는다 — winsize 가 바뀌면 SIGWINCH 가 tmux 클라이언트에게 가고,
     * 클라이언트가 세션의 창을 그 크기로 다시 그린다. 빠뜨리면 창만 커지고 내용은 처음 크기에 갇힌다.
     */
    override fun resize(termSize: TermSize) {
        ptyProcess.setWinSize(WinSize(termSize.columns, termSize.rows))
    }
}
