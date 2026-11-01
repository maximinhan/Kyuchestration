package com.kyuchestration.desktop.wsl

import com.kyuchestration.desktop.diagnostics.DiagnosticLog
import com.kyuchestration.desktop.diagnostics.DiagnosticLogEntry
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

/**
 * 묶음으로 WSL 에 코어를 까는 걸음들(core-ui-split-design.md 6.17 의 4b 정정). `wsl.exe` 대신 받은 리눅스 명령을 적고 정해 둔 답을 내는
 * 가짜를 세운다 — 순서 · 실패 갈래 · 임시 자리 정리를 본다.
 */
class WslCoreInstallationTest {

    private val bundleArchive: Path = Path.of("C:\\Program Files\\Kyuchestration\\app\\resources\\wsl-core\\kyuchestration-wsl-core.tar.gz")

    private val scratch = ".local/share/kyuchestration/.screen-install"

    private val ranCommands = mutableListOf<List<String>>()

    private val fedFiles = mutableListOf<Path>()

    private val recordedEntries = mutableListOf<DiagnosticLogEntry>()

    private val reportedProgress = mutableListOf<WslCoreInstallationProgress>()

    private fun installation(
        answer: (List<String>) -> WslCommandResult = { command ->
            if (command == listOf("pwd")) WslCommandResult(0, "/home/yawn") else WslCommandResult(0, "")
        },
        installScriptLines: List<String> = listOf("대상: linux_amd64", "코어 설치 완료"),
        installScriptExitCode: Int = 0,
    ) = WslCoreInstallation(
        commands = object : WslDistributionCommands {
            override suspend fun run(distributionName: String, linuxCommand: List<String>): WslCommandResult {
                assertEquals("Ubuntu-24.04", distributionName)
                ranCommands += linuxCommand
                return answer(linuxCommand)
            }

            override suspend fun runFeedingStandardInput(
                distributionName: String,
                linuxCommand: List<String>,
                standardInput: Path,
            ): WslCommandResult {
                assertEquals("Ubuntu-24.04", distributionName)
                ranCommands += linuxCommand
                fedFiles.add(standardInput)
                return answer(linuxCommand)
            }

            override suspend fun runReportingEachLine(
                distributionName: String,
                linuxCommand: List<String>,
                onOutputLine: (String) -> Unit,
            ): Int {
                assertEquals("Ubuntu-24.04", distributionName)
                ranCommands += linuxCommand
                installScriptLines.forEach(onOutputLine)
                return installScriptExitCode
            }
        },
        bundleArchive = bundleArchive,
        diagnosticLog = object : DiagnosticLog {
            override fun record(entry: DiagnosticLogEntry) {
                recordedEntries += entry
            }
        },
    )

    private suspend fun WslCoreInstallation.installOnUbuntu() = install("Ubuntu-24.04") { reportedProgress += it }

    /**
     * 우리 데이터 디렉토리 안의 우리 임시 자리만 지우고 만든다. 환경변수의 값에는 절대 경로가 든다 — `install.sh` 는 `--cd ~` 와 무관한
     * 자리에서 파일을 찾을 수 있어야 한다.
     */
    @Test
    fun `홈을 묻고 임시 자리를 새로 만들어 묶음을 풀고 묶음의 install 을 돌린 뒤 임시 자리를 지운다`() = runTest {
        val outcome = installation().installOnUbuntu()

        assertEquals(WslCoreInstallationOutcome.Installed, outcome)
        assertEquals(
            listOf(
                listOf("pwd"),
                listOf("rm", "-rf", scratch),
                listOf("mkdir", "-p", scratch),
                listOf("tar", "-xzf", "-", "-C", scratch),
                listOf(
                    "env",
                    "KYU_CORE_ARCHIVE=/home/yawn/$scratch/core.tar.gz",
                    "KYU_BINARY_FILE=/home/yawn/$scratch/kyu",
                    "bash",
                    "$scratch/install.sh",
                    "--with-core",
                ),
                listOf("rm", "-rf", scratch),
            ),
            ranCommands,
        )
        assertEquals(listOf(bundleArchive), fedFiles)
    }

    @Test
    fun `걸음마다 알리고 설치 스크립트의 줄은 오는 대로 쌓아 알린다`() = runTest {
        installation().installOnUbuntu()

        assertEquals(
            listOf(
                WslCoreInstallationProgress(WslCoreInstallationStep.FindingLinuxHome, emptyList()),
                WslCoreInstallationProgress(WslCoreInstallationStep.PreparingScratchDirectory, emptyList()),
                WslCoreInstallationProgress(WslCoreInstallationStep.ExtractingBundle, emptyList()),
                WslCoreInstallationProgress(WslCoreInstallationStep.RunningInstallScript, emptyList()),
                WslCoreInstallationProgress(WslCoreInstallationStep.RunningInstallScript, listOf("대상: linux_amd64")),
                WslCoreInstallationProgress(WslCoreInstallationStep.RunningInstallScript, listOf("대상: linux_amd64", "코어 설치 완료")),
                WslCoreInstallationProgress(WslCoreInstallationStep.RemovingScratchDirectory, listOf("대상: linux_amd64", "코어 설치 완료")),
            ),
            reportedProgress,
        )
    }

    @Test
    fun `설치 스크립트의 줄은 마지막 몇 줄만 쥔다`() = runTest {
        val manyLines = (1..30).map { "줄 $it" }

        installation(installScriptLines = manyLines).installOnUbuntu()

        assertEquals(manyLines.takeLast(WSL_CORE_INSTALL_REPORT_LINES_KEPT), reportedProgress.last().reportLines)
    }

    /** 임시 자리는 실패 때 지우지 않는다 — 사람이 들여다볼 수 있게. 다음 설치가 먼저 지운다. */
    @Test
    fun `설치 스크립트가 실패하면 그 종료 코드와 마지막 줄들을 돌려주고 임시 자리를 남긴다`() = runTest {
        val outcome = installation(installScriptLines = listOf("대상: linux_amd64", "오류: 코어가 뜨지 않았습니다"), installScriptExitCode = 7)
            .installOnUbuntu()

        assertEquals(
            WslCoreInstallationOutcome.Failed(
                WslCoreInstallationStep.RunningInstallScript,
                exitCode = 7,
                lastOutputLines = listOf("대상: linux_amd64", "오류: 코어가 뜨지 않았습니다"),
            ),
            outcome,
        )
        assertEquals(listOf("rm", "-rf", scratch), ranCommands[1])
        assertEquals("env", ranCommands.last().first())
    }

    @Test
    fun `묶음을 풀지 못하면 설치 스크립트를 돌리지 않고 tar 의 말을 돌려준다`() = runTest {
        val outcome = installation(answer = { command ->
            when (command.first()) {
                "pwd" -> WslCommandResult(0, "/home/yawn")
                "tar" -> WslCommandResult(2, "gzip: stdin: not in gzip format\ntar: Child returned status 1")
                else -> WslCommandResult(0, "")
            }
        }).installOnUbuntu()

        assertEquals(
            WslCoreInstallationOutcome.Failed(
                WslCoreInstallationStep.ExtractingBundle,
                exitCode = 2,
                lastOutputLines = listOf("gzip: stdin: not in gzip format", "tar: Child returned status 1"),
            ),
            outcome,
        )
        assertEquals("tar", ranCommands.last().first())
    }

    @Test
    fun `임시 자리를 만들지 못하면 거기서 멈춘다`() = runTest {
        val outcome = installation(answer = { command ->
            when (command.first()) {
                "pwd" -> WslCommandResult(0, "/home/yawn")
                "mkdir" -> WslCommandResult(1, "mkdir: cannot create directory: Permission denied")
                else -> WslCommandResult(0, "")
            }
        }).installOnUbuntu()

        assertEquals(
            WslCoreInstallationOutcome.Failed(
                WslCoreInstallationStep.PreparingScratchDirectory,
                exitCode = 1,
                lastOutputLines = listOf("mkdir: cannot create directory: Permission denied"),
            ),
            outcome,
        )
        assertEquals("mkdir", ranCommands.last().first())
    }

    @Test
    fun `홈을 묻지 못하면 아무것도 지우거나 만들지 않는다`() = runTest {
        val outcome = installation(answer = { WslCommandResult(-1, "Catastrophic failure") }).installOnUbuntu()

        assertEquals(
            WslCoreInstallationOutcome.Failed(WslCoreInstallationStep.FindingLinuxHome, exitCode = -1, lastOutputLines = listOf("Catastrophic failure")),
            outcome,
        )
        assertEquals(listOf(listOf("pwd")), ranCommands)
    }

    /**
     * `wsl.exe` 의 인자는 윈도우 명령줄 문자열 하나로 합쳐졌다 다시 갈린다. 빈칸이 든 값은 `env` 에 두 낱말로 가 엉뚱한 자리를 가리키고,
     * 따옴표는 사라진다(3.7). 묵살하지 않고 까닭을 돌려준다.
     */
    @Test
    fun `홈 경로에 빈칸이 있으면 깔지 않고 그 홈을 돌려준다`() = runTest {
        val outcome = installation(answer = { WslCommandResult(0, "/home/yawn han") }).installOnUbuntu()

        assertEquals(WslCoreInstallationOutcome.LinuxHomeUnusable("/home/yawn han"), outcome)
        assertEquals(listOf(listOf("pwd")), ranCommands)
    }

    /** 코어는 이미 놓였고 남은 임시 자리는 다음 설치가 먼저 지운다 — 깔린 코어를 실패로 보이지 않는다. 기록에는 남는다. */
    @Test
    fun `깐 뒤 임시 자리를 지우지 못해도 깔린 것으로 본다`() = runTest {
        var removals = 0
        val outcome = installation(answer = { command ->
            when {
                command == listOf("pwd") -> WslCommandResult(0, "/home/yawn")
                command.first() == "rm" && ++removals == 2 -> WslCommandResult(1, "rm: cannot remove: Device or resource busy")
                else -> WslCommandResult(0, "")
            }
        }).installOnUbuntu()

        assertEquals(WslCoreInstallationOutcome.Installed, outcome)
        val lastEntry = recordedEntries.last() as DiagnosticLogEntry.WslCoreInstallCommandFinished
        assertEquals(listOf("rm", "-rf", scratch), lastEntry.linuxCommand)
        assertEquals(1, lastEntry.exitCode)
    }

    @Test
    fun `걸음마다 리눅스 명령과 종료 코드와 소요를 기록에 남긴다`() = runTest {
        installation(installScriptExitCode = 7, installScriptLines = listOf("오류: 코어가 뜨지 않았습니다")).installOnUbuntu()

        val entries = recordedEntries.map { it as DiagnosticLogEntry.WslCoreInstallCommandFinished }
        assertEquals(listOf("pwd", "rm", "mkdir", "tar", "env"), entries.map { it.linuxCommand.first() })
        assertEquals(listOf(0, 0, 0, 0, 7), entries.map { it.exitCode })
        assertEquals(setOf("Ubuntu-24.04"), entries.map { it.distributionName }.toSet())
        assertEquals(listOf("오류: 코어가 뜨지 않았습니다"), entries.last().failureOutputLines)
        assertEquals(emptyList(), entries.first().failureOutputLines)
    }
}
