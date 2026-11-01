package com.kyuchestration.desktop.wsl

import com.kyuchestration.desktop.diagnostics.DiagnosticLog
import com.kyuchestration.desktop.diagnostics.DiagnosticLogEntry
import java.io.IOException
import java.nio.file.Path
import kotlin.time.TimeSource

/**
 * 배포판 안에서 셸 없이 리눅스 명령을 돌리는 세 모양. [WslCommandLine] 이 `wsl.exe` 로 한다.
 *
 * `wsl.exe` 를 띄우지 못하거나 그 stdio 가 끊기면 [IOException] 을 던진다.
 */
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

    /**
     * 한 걸음이 실패했다.
     *
     * @param exitCode 리눅스 쪽 명령의 종료 코드. `wsl.exe` 를 띄우지 못했거나 그 stdio 가 끊겨 받지 못했으면 null.
     * @param screenSideFailure 화면 쪽에서 난 까닭 — `wsl.exe` 를 못 띄움 · 묶음 파일을 다 넘기지 못함. 종료 코드와 함께 올 수 있다(넘기다
     *   끊겼는데 리눅스 쪽도 실패했다).
     * @param lastOutputLines 그 명령이 마지막에 쓴 줄들([WSL_CORE_INSTALL_REPORT_LINES_KEPT]). 전체는 진단 기록에 있다.
     */
    data class Failed(
        val step: WslCoreInstallationStep,
        val exitCode: Int?,
        val screenSideFailure: String?,
        val lastOutputLines: List<String>,
    ) : WslCoreInstallationOutcome

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
 *
 * 걸음에서 나는 [IOException] 은 그 걸음의 [WslCoreInstallationOutcome.Failed] 가 된다. 던지면 준비 창은 단추 없는 "까는 중" 에 머문다.
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
            val linuxHome = when (val printedHome = runStep(WslCoreInstallationStep.FindingLinuxHome, listOf("pwd")) { commands.run(distributionName, it) }) {
                is StepResult.Stopped -> return printedHome.failure
                is StepResult.Succeeded -> printedHome.output
            }
            if (linuxHome.isEmpty() || linuxHome.any(Char::isWhitespace)) {
                return WslCoreInstallationOutcome.LinuxHomeUnusable(linuxHome)
            }

            onProgress(WslCoreInstallationProgress(WslCoreInstallationStep.PreparingScratchDirectory, emptyList()))
            for (preparation in listOf(listOf("rm", "-rf", SCRATCH_FROM_HOME), listOf("mkdir", "-p", SCRATCH_FROM_HOME))) {
                val prepared = runStep(WslCoreInstallationStep.PreparingScratchDirectory, preparation) { commands.run(distributionName, it) }
                if (prepared is StepResult.Stopped) {
                    return prepared.failure
                }
            }

            onProgress(WslCoreInstallationProgress(WslCoreInstallationStep.ExtractingBundle, emptyList()))
            val extracted = runStep(WslCoreInstallationStep.ExtractingBundle, listOf("tar", "-xzf", "-", "-C", SCRATCH_FROM_HOME)) {
                commands.runFeedingStandardInput(distributionName, it, bundleArchive)
            }
            if (extracted is StepResult.Stopped) {
                return extracted.failure
            }

            val installScriptOutput = when (val installScript = runInstallScript(linuxHome)) {
                // 임시 자리는 남긴다 — 사람이 풀린 묶음을 들여다볼 수 있게. 다음 설치가 먼저 지운다.
                is StepResult.Stopped -> return installScript.failure
                is StepResult.Succeeded -> installScript.output
            }

            onProgress(WslCoreInstallationProgress(WslCoreInstallationStep.RemovingScratchDirectory, installScriptOutput.tailLines()))
            // 지우지 못해도 깔린 것으로 본다. 코어는 이미 놓였고 남은 임시 자리는 다음 설치가 먼저 지운다 — 그 사실은 기록에 남는다.
            runStep(WslCoreInstallationStep.RemovingScratchDirectory, listOf("rm", "-rf", SCRATCH_FROM_HOME)) { commands.run(distributionName, it) }
            return WslCoreInstallationOutcome.Installed
        }

        private suspend fun runInstallScript(linuxHome: String): StepResult {
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
            onProgress(WslCoreInstallationProgress(WslCoreInstallationStep.RunningInstallScript, emptyList()))
            val allLines = StringBuilder()
            val lastLines = ArrayDeque<String>()
            return runStep(WslCoreInstallationStep.RunningInstallScript, linuxCommand) {
                val exitCode = commands.runReportingEachLine(distributionName, it) { line ->
                    allLines.appendLine(line)
                    lastLines.addLast(line)
                    while (lastLines.size > WSL_CORE_INSTALL_REPORT_LINES_KEPT) {
                        lastLines.removeFirst()
                    }
                    onProgress(WslCoreInstallationProgress(WslCoreInstallationStep.RunningInstallScript, lastLines.toList()))
                }
                WslCommandResult(exitCode, allLines.toString().trim())
            }
        }

        private suspend fun runStep(
            step: WslCoreInstallationStep,
            linuxCommand: List<String>,
            run: suspend (List<String>) -> WslCommandResult,
        ): StepResult {
            val mark = TimeSource.Monotonic.markNow()
            val result = try {
                run(linuxCommand)
            } catch (couldNotRun: IOException) {
                record(linuxCommand, exitCode = null, mark.elapsedNow().inWholeMilliseconds, couldNotRun.toString(), output = "")
                return StepResult.Stopped(WslCoreInstallationOutcome.Failed(step, null, couldNotRun.toString(), emptyList()))
            }
            record(linuxCommand, result.exitCode, mark.elapsedNow().inWholeMilliseconds, result.standardInputFailure, result.output)
            if (result.exitCode == 0 && result.standardInputFailure == null) {
                return StepResult.Succeeded(result.output)
            }
            return StepResult.Stopped(
                WslCoreInstallationOutcome.Failed(step, result.exitCode, result.standardInputFailure, result.output.tailLines()),
            )
        }

        private fun record(linuxCommand: List<String>, exitCode: Int?, elapsedMillis: Long, screenSideFailure: String?, output: String) {
            diagnosticLog.record(
                DiagnosticLogEntry.WslCoreInstallCommandFinished(
                    distributionName = distributionName,
                    linuxCommand = linuxCommand,
                    exitCode = exitCode,
                    elapsedMillis = elapsedMillis,
                    screenSideFailure = screenSideFailure,
                    output = output.cappedForDiagnosticLog(),
                ),
            )
        }
    }

    private sealed interface StepResult {

        class Succeeded(val output: String) : StepResult

        class Stopped(val failure: WslCoreInstallationOutcome.Failed) : StepResult
    }
}

private fun String.tailLines(): List<String> = lines().filter { it.isNotBlank() }.takeLast(WSL_CORE_INSTALL_REPORT_LINES_KEPT)

/** 진짜 까닭은 앞쪽 줄에 있을 수 있다 — 꼬리가 아니라 앞을 남기고, 잘랐으면 그렇게 적는다. */
private fun String.cappedForDiagnosticLog(): String =
    if (length <= DIAGNOSTIC_LOG_OUTPUT_CHARACTERS_KEPT) {
        this
    } else {
        take(DIAGNOSTIC_LOG_OUTPUT_CHARACTERS_KEPT) + "\n… (${length - DIAGNOSTIC_LOG_OUTPUT_CHARACTERS_KEPT}자 잘림)"
    }

/** 화면이 보이고 실패에 실어 두는 마지막 줄 수. 실패한 걸음의 까닭이 들 만큼 — 화면의 고정폭 칸 높이에 맞춘다. 전체는 진단 기록에 있다. */
internal const val WSL_CORE_INSTALL_REPORT_LINES_KEPT = 12

/** 진단 기록에 남기는 한 걸음의 출력 상한. `install.sh` 의 출력은 몇 KiB 라 넉넉하고, 고장 난 명령이 기록 파일을 채우지는 못한다. */
internal const val DIAGNOSTIC_LOG_OUTPUT_CHARACTERS_KEPT = 64 * 1024

/**
 * 화면이 묶음을 푸는 임시 자리. 우리 데이터 디렉토리(`install.sh` 가 코어를 두는 `kyuchestration/core` 의 옆) 안이다 — 화면이 돌리는
 * `rm -rf` 는 우리 것 밖에 닿지 않아야 하고, 리눅스 쪽 `/tmp` 의 고정 이름은 다른 사용자 · 프로그램의 것과 겹칠 수 있다.
 */
private const val SCRATCH_FROM_HOME = ".local/share/kyuchestration/.screen-install"

private const val BUNDLED_CORE_ARCHIVE = "core.tar.gz"

private const val BUNDLED_KYU = "kyu"

private const val BUNDLED_INSTALL_SCRIPT = "install.sh"
