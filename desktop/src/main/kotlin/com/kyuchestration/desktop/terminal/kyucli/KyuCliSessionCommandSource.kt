package com.kyuchestration.desktop.terminal.kyucli

import com.kyuchestration.desktop.kyu.KyuCommandFailure
import com.kyuchestration.desktop.kyu.KyuCommandRunner
import com.kyuchestration.desktop.terminal.SessionCommandAnswer
import com.kyuchestration.desktop.terminal.SessionCommandSource
import com.kyuchestration.desktop.terminal.SessionTarget
import com.kyuchestration.desktop.terminal.TerminalSessionFailure
import java.nio.file.Path

/**
 * kyu CLI 에게 이 세션이 무엇을 띄울지 묻는다.
 *
 * 앱은 세션 명령을 스스로 조립하지 않는다. 그 지식은 `kyu start` 안에 있고, 이 표면이 그것을
 * 프로세스 경계 너머로 건네준다(설계 문서 5.2).
 */
class KyuCliSessionCommandSource(private val kyuCommandRunner: KyuCommandRunner) : SessionCommandSource {

    override fun sessionCommandFor(workDirPath: Path, target: SessionTarget): SessionCommandAnswer {
        val arguments = sessionCommandArguments(target)

        val result = try {
            // 자리를 인자가 아니라 작업 디렉토리로 준다. 이 명령은 경로 인자를 받지 않고
            // 실행된 디렉토리를 워크디렉토리로 읽는다(session_command.go 의 currentWorkDir).
            kyuCommandRunner.run(arguments, workingDirectory = workDirPath)
        } catch (failure: KyuCommandFailure) {
            // 실행기는 "부르지 못했다" 는 사실만 던진다. 그 사실을 세션에 들어가려던 사람에게 할
            // 말로 옮기는 것이 이 어댑터의 몫이다.
            throw when (failure) {
                is KyuCommandFailure.ExecutableNotFound -> TerminalSessionFailure.KyuExecutableNotFound()
                is KyuCommandFailure.FailedToStart ->
                    TerminalSessionFailure.KyuFailedToStart(failure.processStartFailure)
            }
        }

        if (result.exitCode != 0) {
            throw TerminalSessionFailure.SessionCommandRefused(
                targetLabel = target.label,
                exitCode = result.exitCode,
                arguments = arguments,
                standardError = result.standardError.trim(),
            )
        }

        return parseKyuSessionCommandOutput(result.standardOutput)
    }
}

/**
 * `kyu session-command [repo] --json`.
 *
 * 메인 세션은 레포 이름 자리를 비운다. `kyu attach main` 과 문법이 갈리는 자리이고, 이쪽은
 * `kyu start` 와 같은 규칙이다 — 인자 없는 실행을 메인 세션으로 읽는다(session_command.go 의
 * parseSessionCommandArgs). when 으로 가르면 세션 종류가 하나 늘 때 이 파일이 컴파일되지 않는다.
 */
private fun sessionCommandArguments(target: SessionTarget): List<String> {
    val repoArgument = when (target) {
        is SessionTarget.Main -> emptyList()
        is SessionTarget.Repo -> listOf(target.repoName)
    }
    return listOf("session-command") + repoArgument + "--json"
}
