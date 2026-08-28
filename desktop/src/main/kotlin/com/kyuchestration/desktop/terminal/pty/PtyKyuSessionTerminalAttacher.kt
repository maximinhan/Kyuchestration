package com.kyuchestration.desktop.terminal.pty

import com.jediterm.terminal.TtyConnector
import com.kyuchestration.desktop.kyu.findKyuExecutable
import com.kyuchestration.desktop.terminal.SessionEntryPlan
import com.kyuchestration.desktop.terminal.SessionTarget
import com.kyuchestration.desktop.terminal.SessionTerminalAttacher
import com.kyuchestration.desktop.terminal.TerminalSessionFailure
import com.kyuchestration.desktop.terminal.planSessionEntry
import com.pty4j.PtyProcessBuilder
import java.io.IOException
import java.nio.file.Path

/**
 * 진짜 PTY 를 열고 그 안에서 `kyu attach` 를 실행한다.
 *
 * **이 클래스가 이 기능의 전부다.** `kyu attach` 는 자기 표준 입출력을 tmux 에게 그대로 넘기는데,
 * tmux 는 TTY 가 아닌 곳에서는 attach 자체를 하지 못한다(tmux.go 의 Attach 가 파이프 대신
 * os.Stdin 을 물려주는 이유). 보통의 자식 프로세스는 파이프로 이어지므로 여기서 막힌다.
 * PTY 는 커널이 만든 진짜 터미널 장치라 그 요구가 그대로 충족되고, 그래서 앱 안의 창 하나가
 * 터미널 창과 같은 자격으로 세션에 들어간다.
 *
 * @param fixedKyuExecutablePath 부를 실행 파일을 못 박는다. 통합 테스트가 설치 여부와 무관하게
 *   자기가 빌드한 바이너리를 겨누기 위한 것이고, 앱은 이 값을 주지 않는다.
 * @param parentEnvironment 자식에게 물려줄 바탕 환경. 통합 테스트가 사용자의 기본 tmux 서버 대신
 *   격리된 서버(TMUX_TMPDIR)를 겨누기 위한 것이고, 앱은 이 값을 주지 않는다.
 */
class PtyKyuSessionTerminalAttacher(
    private val fixedKyuExecutablePath: Path? = null,
    private val parentEnvironment: Map<String, String> = System.getenv(),
) : SessionTerminalAttacher {

    override fun attachTo(workDirPath: Path, target: SessionTarget): TtyConnector {
        val executable = fixedKyuExecutablePath
            ?: findKyuExecutable()
            ?: throw TerminalSessionFailure.KyuExecutableNotFound()

        val plan = planSessionEntry(
            kyuExecutablePath = executable,
            workDirPath = workDirPath,
            target = target,
            parentEnvironment = parentEnvironment,
        )

        startSessionBeforeAttaching(plan, target)
        return openPtyRunningAttach(plan, target)
    }

    /**
     * 붙기 전에 세션을 띄운다. 이미 떠 있으면 kyu 가 안내만 하고 성공으로 끝낸다.
     *
     * 이쪽은 PTY 가 아니라 보통의 자식 프로세스다 — kyu start 는 대화하지 않고 곧바로 끝나므로
     * 터미널이 필요 없고, 실패하면 그 이유를 stderr 에서 읽어 화면에 올려야 한다.
     */
    private fun startSessionBeforeAttaching(plan: SessionEntryPlan, target: SessionTarget) {
        val process = try {
            ProcessBuilder(plan.startCommand)
                .directory(plan.workingDirectory.toFile())
                // stdout 은 "진입: kyu attach <이름>" 안내다. 그것을 지금 하려는 참이라 읽을 것이
                // 없고, 읽지 않은 채 두면 파이프가 차서 kyu 가 쓰기에서 멈춘다.
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .withEnvironment(plan.environment)
                .start()
        } catch (failure: IOException) {
            throw TerminalSessionFailure.KyuFailedToStart(failure)
        }

        val standardError = process.errorStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw TerminalSessionFailure.SessionStartRejected(
                targetLabel = target.label,
                exitCode = exitCode,
                standardError = standardError.trim(),
            )
        }
    }

    private fun openPtyRunningAttach(plan: SessionEntryPlan, target: SessionTarget): TtyConnector {
        val ptyProcess = try {
            PtyProcessBuilder(plan.attachCommand.toTypedArray())
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

        return KyuAttachTtyConnector(ptyProcess, target)
    }
}

/**
 * 환경을 통째로 갈아 끼운다.
 *
 * ProcessBuilder 는 기본으로 부모 환경을 물려주는데, 진입 계획이 정한 환경은 그 부모 환경에서
 * TMUX 를 덜어낸 것이다. 덧씌우기만 하면 덜어낸 것이 도로 살아난다.
 */
private fun ProcessBuilder.withEnvironment(replacement: Map<String, String>): ProcessBuilder = apply {
    environment().clear()
    environment().putAll(replacement)
}

private const val INITIAL_COLUMNS = 80
private const val INITIAL_ROWS = 24
