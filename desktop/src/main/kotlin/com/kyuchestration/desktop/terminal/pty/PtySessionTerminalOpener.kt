package com.kyuchestration.desktop.terminal.pty

import com.jediterm.terminal.TtyConnector
import com.kyuchestration.desktop.platform.childProcessEnvironment
import com.kyuchestration.desktop.terminal.SessionCommandSource
import com.kyuchestration.desktop.terminal.SessionEntryPlan
import com.kyuchestration.desktop.terminal.SessionTarget
import com.kyuchestration.desktop.terminal.SessionTerminalOpener
import com.kyuchestration.desktop.terminal.TerminalSessionFailure
import com.kyuchestration.desktop.terminal.planSessionEntry
import com.pty4j.PtyProcessBuilder
import java.io.IOException
import java.nio.file.Path

/**
 * 진짜 PTY 를 열고 그 안에서 세션의 `claude` 를 직접 띄운다.
 *
 * **이 클래스가 이 전환의 전부다.** 예전에는 이 자리가 `kyu attach` 를 실행했고, 그 뒤에 tmux 와
 * 세션이 있었다. 이제 PTY 안의 자식이 곧 세션이다 — 앱이 그 프로세스를 보유하고, 화면 모델은
 * 이 통로에 붙는 JediTerm 위젯이 쥔다(설계 문서 1.3).
 *
 * **모양은 그대로다.** 앞의 비 PTY 호출 하나(무엇을 띄울지 묻는다)와 뒤의 PTY 호출 하나(그것을
 * 띄운다)라는 배치가 전과 같고, 앞 호출이 "세션을 띄운다" 에서 "무엇을 띄울지 묻는다" 로 바뀌었다.
 *
 * @param sessionCommandSource 무엇을 띄울지 답하는 자리. 이 클래스가 스스로 조립하지 않는 것이
 *   설계 원칙 11 이다.
 * @param baseEnvironment 자식에게 물려줄 바탕 환경. 기본값이 앱이 한 자리에서 정해 둔 환경이라
 *   (ChildProcessEnvironment) 여기서 PATH 를 따로 손보지 않는다 — 엔진의 답에 실행 파일 경로가
 *   아니라 `claude` 라는 이름이 들어 있으므로, 그것을 찾는 것은 이 PATH 다.
 */
class PtySessionTerminalOpener(
    private val sessionCommandSource: SessionCommandSource,
    private val baseEnvironment: Map<String, String> = childProcessEnvironment(),
) : SessionTerminalOpener {

    override fun openSessionIn(workDirPath: Path, target: SessionTarget): TtyConnector {
        val sessionCommandAnswer = sessionCommandSource.sessionCommandFor(workDirPath, target)

        val plan = planSessionEntry(
            sessionCommandAnswer = sessionCommandAnswer,
            baseEnvironment = baseEnvironment,
            workDirPath = workDirPath,
            target = target,
        )

        return openPtyRunningSession(plan, target)
    }

    private fun openPtyRunningSession(plan: SessionEntryPlan, target: SessionTarget): TtyConnector {
        val ptyProcess = try {
            PtyProcessBuilder(plan.command.toTypedArray())
                .setDirectory(plan.workingDirectory.toString())
                .setEnvironment(plan.environment)
                // 화면이 자리를 잡으면 JediTerm 이 곧바로 진짜 크기로 다시 부른다. 그 전까지 쓸
                // 값을 JediTermWidget 의 기본값과 같게 두어 첫 그림이 한 번 더 흐트러지지 않게 한다.
                .setInitialColumns(INITIAL_COLUMNS)
                .setInitialRows(INITIAL_ROWS)
                .start()
        } catch (failure: IOException) {
            throw TerminalSessionFailure.PtyFailedToOpen(failure)
        }

        return SessionTtyConnector(ptyProcess, target)
    }
}

private const val INITIAL_COLUMNS = 80
private const val INITIAL_ROWS = 24
