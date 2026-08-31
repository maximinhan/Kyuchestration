package com.kyuchestration.desktop.terminal.pty

import com.jediterm.core.util.TermSize
import com.jediterm.terminal.ProcessTtyConnector
import com.kyuchestration.desktop.terminal.SessionTarget
import com.pty4j.PtyProcess
import com.pty4j.WinSize

/**
 * JediTerm 의 화면과 세션의 `claude` 를 실행 중인 PTY 를 잇는 통로.
 *
 * 읽기·쓰기·종료는 ProcessTtyConnector 가 이미 안다(PtyProcess 는 java.lang.Process 다).
 * 이 클래스가 더하는 것은 크기 전달 하나뿐이다.
 *
 * **닫기의 뜻이 바뀐 자리이기도 하다.** ProcessTtyConnector.close 는 자식 프로세스를 destroy
 * 하는데, 예전에 그 자식은 `kyu attach` 라서 닫기가 detach 였다. 이제 그 자식이 세션 자체이므로
 * 닫기가 곧 세션 종료다(설계 문서 6.1).
 */
internal class SessionTtyConnector(
    private val ptyProcess: PtyProcess,
    private val target: SessionTarget,
) : ProcessTtyConnector(ptyProcess, Charsets.UTF_8) {

    override fun getName(): String = target.label

    /**
     * 창 크기가 바뀌면 PTY 의 winsize 를 따라 바꾼다.
     *
     * 반드시 재정의해야 한다. TtyConnector 의 기본 구현은 폐기된 Dimension 짝으로 흘러가다가
     * "should override TtyConnector.resize(TermSize)" 라며 IllegalStateException 을 던진다.
     *
     * 이 한 줄이 이제 `claude` 에게 바로 닿는다 — 사이에 `kyu attach` 도 tmux 도 없어서 홉이
     * 둘 줄었다. 빠뜨리면 창만 커지고 세션 안의 화면은 처음 크기에 갇힌다.
     */
    override fun resize(termSize: TermSize) {
        ptyProcess.setWinSize(WinSize(termSize.columns, termSize.rows))
    }
}
