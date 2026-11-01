package com.kyuchestration.desktop.wsl

import com.kyuchestration.desktop.diagnostics.DiagnosticLog
import com.kyuchestration.desktop.diagnostics.DiagnosticLogEntry
import java.nio.file.Path
import kotlin.time.TimeSource

/** 배포판 안에서 셸 없이 리눅스 명령을 돌리는 세 모양. [WslCommandLine] 이 `wsl.exe` 로 한다. */
interface WslDistributionCommands {

    suspend fun run(distributionName: String, linuxCommand: List<String>): WslCommandResult

    suspend fun runFeedingStandardInput(distributionName: String, linuxCommand: List<String>, standardInput: Path): WslCommandResult

    suspend fun runReportingEachLine(distributionName: String, linuxCommand: List<String>, onOutputLine: (String) -> Unit): Int
}

enum class WslCoreInstallationStep {
    FindingLinuxHome,
    PreparingScratchDirectory,
    ExtractingBundle,
    RunningInstallScript,
    RemovingScratchDirectory,
}

/** @param reportLines 설치 스크립트가 지금까지 쓴 마지막 몇 줄([WSL_CORE_INSTALL_REPORT_LINES_KEPT]). */
data class WslCoreInstallationProgress(val step: WslCoreInstallationStep, val reportLines: List<String>)

sealed interface WslCoreInstallationOutcome {

    data object Installed : WslCoreInstallationOutcome

    data class Failed(val step: WslCoreInstallationStep, val exitCode: Int, val lastOutputLines: List<String>) : WslCoreInstallationOutcome

    /**
     * 리눅스 홈 경로에 빈칸이 있다(또는 비었다). `wsl.exe` 의 인자는 윈도우 명령줄 문자열 하나로 합쳐졌다 다시 갈리고 따옴표는
     * 사라진다(3.7) — 빈칸이 든 `env` 의 값은 두 낱말로 가 엉뚱한 자리를 가리킨다.
     */
    data class LinuxHomeUnusable(val linuxHome: String) : WslCoreInstallationOutcome
}

/**
 * msi 에 실린 묶음으로 고른 배포판에 코어와 kyu 를 놓는다(core-ui-split-design.md 6.17 의 4b 정정).
 *
 * 화면은 묶음을 `wsl.exe` 의 stdin 으로 넘겨 우리 데이터 디렉토리 안의 임시 자리에 풀고, 묶음 안의 `install.sh --with-core` 를 묶음 안의
 * 파일로 돌린다. 풀어 띄워 본 뒤 자리 바꾸기 · 실패하면 쓰던 코어 되돌리기 · 고정 링크는 `install.sh` 의 것 그대로다 — 같은 판단을 여기서
 * 다시 짜면 두 벌이 된다. 이 클래스는 그 앞뒤의 걸음과 실패 갈래만 정하고 `wsl.exe` 를 모른다.
 */
class WslCoreInstallation(
    private val commands: WslDistributionCommands,
    private val bundleArchive: Path,
    private val diagnosticLog: DiagnosticLog,
) {

    suspend fun install(distributionName: String, onProgress: (WslCoreInstallationProgress) -> Unit): WslCoreInstallationOutcome =
        InstallationOfDistribution(distributionName, onProgress).install()

    private inner class InstallationOfDistribution(
        private val distributionName: String,
        private val onProgress: (WslCoreInstallationProgress) -> Unit,
    ) {

        suspend fun install(): WslCoreInstallationOutcome {
            onProgress(WslCoreInstallationProgress(WslCoreInstallationStep.FindingLinuxHome, emptyList()))
            val printedHome = timed(listOf("pwd")) { commands.run(distributionName, it) }
            printedHome.failureIn(WslCoreInstallationStep.FindingLinuxHome)?.let { return it }
            val linuxHome = printedHome.output
            if (linuxHome.isEmpty() || linuxHome.any(Char::isWhitespace)) {
                return WslCoreInstallationOutcome.LinuxHomeUnusable(linuxHome)
            }

            onProgress(WslCoreInstallationProgress(WslCoreInstallationStep.PreparingScratchDirectory, emptyList()))
            for (preparation in listOf(listOf("rm", "-rf", SCRATCH_FROM_HOME), listOf("mkdir", "-p", SCRATCH_FROM_HOME))) {
                timed(preparation) { commands.run(distributionName, it) }
                    .failureIn(WslCoreInstallationStep.PreparingScratchDirectory)?.let { return it }
            }

            onProgress(WslCoreInstallationProgress(WslCoreInstallationStep.ExtractingBundle, emptyList()))
            timed(listOf("tar", "-xzf", "-", "-C", SCRATCH_FROM_HOME)) { commands.runFeedingStandardInput(distributionName, it, bundleArchive) }
                .failureIn(WslCoreInstallationStep.ExtractingBundle)?.let { return it }

            val installScript = runInstallScript(linuxHome)
            if (installScript.exitCode != 0) {
                // 임시 자리는 남긴다 — 사람이 풀린 묶음을 들여다볼 수 있게. 다음 설치가 먼저 지운다.
                return WslCoreInstallationOutcome.Failed(WslCoreInstallationStep.RunningInstallScript, installScript.exitCode, installScript.lastLines)
            }

            onProgress(WslCoreInstallationProgress(WslCoreInstallationStep.RemovingScratchDirectory, installScript.lastLines))
            // 지우지 못해도 깔린 것으로 본다. 코어는 이미 놓였고 남은 임시 자리는 다음 설치가 먼저 지운다 — 그 사실은 기록에 남는다.
            timed(listOf("rm", "-rf", SCRATCH_FROM_HOME)) { commands.run(distributionName, it) }
            return WslCoreInstallationOutcome.Installed
        }

        private suspend fun runInstallScript(linuxHome: String): InstallScriptResult {
            val scratch = "$linuxHome/$SCRATCH_FROM_HOME"
            val linuxCommand = listOf(
                "env",
                // install.sh 는 홈과 무관한 자리에서 이 파일들을 찾을 수 있어야 한다 — 절대 경로로 준다.
                "KYU_CORE_ARCHIVE=$scratch/$BUNDLED_CORE_ARCHIVE",
                "KYU_BINARY_FILE=$scratch/$BUNDLED_KYU",
                "bash",
                "$SCRATCH_FROM_HOME/$BUNDLED_INSTALL_SCRIPT",
                "--with-core",
            )
            val lastLines = ArrayDeque<String>()
            onProgress(WslCoreInstallationProgress(WslCoreInstallationStep.RunningInstallScript, emptyList()))
            val mark = TimeSource.Monotonic.markNow()
            val exitCode = commands.runReportingEachLine(distributionName, linuxCommand) { line ->
                lastLines.addLast(line)
                while (lastLines.size > WSL_CORE_INSTALL_REPORT_LINES_KEPT) {
                    lastLines.removeFirst()
                }
                onProgress(WslCoreInstallationProgress(WslCoreInstallationStep.RunningInstallScript, lastLines.toList()))
            }
            record(linuxCommand, exitCode, mark.elapsedNow().inWholeMilliseconds, lastLines.toList())
            return InstallScriptResult(exitCode, lastLines.toList())
        }

        private suspend fun timed(linuxCommand: List<String>, run: suspend (List<String>) -> WslCommandResult): WslCommandResult {
            val mark = TimeSource.Monotonic.markNow()
            val result = run(linuxCommand)
            record(linuxCommand, result.exitCode, mark.elapsedNow().inWholeMilliseconds, result.output.outputLines())
            return result
        }

        private fun record(linuxCommand: List<String>, exitCode: Int, elapsedMillis: Long, outputLines: List<String>) {
            diagnosticLog.record(
                DiagnosticLogEntry.WslCoreInstallCommandFinished(
                    distributionName = distributionName,
                    linuxCommand = linuxCommand,
                    exitCode = exitCode,
                    elapsedMillis = elapsedMillis,
                    failureOutputLines = if (exitCode == 0) emptyList() else outputLines,
                ),
            )
        }
    }

    private class InstallScriptResult(val exitCode: Int, val lastLines: List<String>)
}

private fun WslCommandResult.failureIn(step: WslCoreInstallationStep): WslCoreInstallationOutcome.Failed? =
    if (exitCode == 0) null else WslCoreInstallationOutcome.Failed(step, exitCode, output.outputLines())

private fun String.outputLines(): List<String> = lines().filter { it.isNotBlank() }.takeLast(WSL_CORE_INSTALL_REPORT_LINES_KEPT)

/** 화면이 보이고 실패에 실어 두는 설치 스크립트의 마지막 줄 수. 실패한 걸음의 까닭이 들 만큼 — 화면의 고정폭 칸 높이에 맞춘다. */
internal const val WSL_CORE_INSTALL_REPORT_LINES_KEPT = 12

/**
 * 화면이 묶음을 푸는 임시 자리. 우리 데이터 디렉토리(`install.sh` 가 코어를 두는 `kyuchestration/core` 의 옆) 안이다 — 화면이 돌리는
 * `rm -rf` 는 우리 것 밖에 닿지 않아야 하고, 리눅스 쪽 `/tmp` 의 고정 이름은 다른 사용자 · 프로그램의 것과 겹칠 수 있다.
 */
private const val SCRATCH_FROM_HOME = ".local/share/kyuchestration/.screen-install"

private const val BUNDLED_CORE_ARCHIVE = "core.tar.gz"

private const val BUNDLED_KYU = "kyu"

private const val BUNDLED_INSTALL_SCRIPT = "install.sh"
