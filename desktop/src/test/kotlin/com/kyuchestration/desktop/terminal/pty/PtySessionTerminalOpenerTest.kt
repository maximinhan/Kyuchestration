package com.kyuchestration.desktop.terminal.pty

import com.jediterm.terminal.ProcessTtyConnector
import com.jediterm.terminal.TtyConnector
import com.kyuchestration.desktop.terminal.SessionCommandAnswer
import com.kyuchestration.desktop.terminal.SessionCommandSource
import com.kyuchestration.desktop.terminal.SessionTarget
import com.kyuchestration.desktop.terminal.TerminalSessionFailure
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 엔진이 답한 것을 앱이 진짜 PTY 에서 정말 그대로 띄우는지, 그리고 통로를 닫으면 그 프로세스가
 * 정말 끝나는지 본다.
 *
 * **PTY 안에서 도는 것은 claude 가 아니라 자기가 받은 것을 적어 두는 sh 다.** 확인하려는 것이
 * "무엇이 어떤 자리에서 어떤 환경으로 떴는가" 라, 그 답을 claude 에게 물을 이유가 없다 —
 * 오히려 claude 를 요구하면 이 검증이 CI 에서 돌지 못한다. 엔진이 실제로 그 argv 를 답한다는
 * 것은 KyuCliSessionCommandSourceIntegrationTest 가 진짜 kyu 에게 물어 확인한다.
 *
 * 그래서 이 시험에는 kyu 도 tmux 도 claude 도 필요 없다. 필요한 것은 PTY 하나와 sh 뿐이다.
 */
class PtySessionTerminalOpenerTest {

    private val temporaryDirectory = createTempDirectory("kyu-pty-test")

    private val openedConnectors = mutableListOf<TtyConnector>()

    @AfterTest
    fun 열어_둔_세션과_임시_디렉토리를_치운다() {
        openedConnectors.forEach { it.close() }
        temporaryDirectory.toFile().deleteRecursively()
    }

    @Test
    fun `엔진이 답한 명령을 그 자리에서 그 환경으로 띄운다`() {
        val sessionDirectory = temporaryDirectory.resolve("proj-a").createDirectories()
        val reportPath = temporaryDirectory.resolve("세션이-받은-것.txt")

        openSession(
            answer = SessionCommandAnswer(
                command = recordAndStayAlive(reportPath),
                workingDirectory = sessionDirectory,
                environmentToAdd = mapOf("CLAUDE_CODE_ADDITIONAL_DIRECTORIES_CLAUDE_MD" to "1"),
            ),
            baseEnvironment = mapOf("PATH" to System.getenv("PATH"), "TERM" to "dumb"),
        )

        val report = waitForReport(reportPath)
        assertEquals(sessionDirectory.toRealPath().toString(), report.workingDirectory, "엔진이 답한 cwd 에서 떠야 한다")
        assertEquals("xterm-256color", report.terminalType, "JediTerm 이 흉내 내는 화면을 자식이 알아야 한다")
        assertEquals("1", report.repoClaudeMd, "엔진이 더하라고 한 환경이 자식까지 닿아야 한다")
        assertEquals(
            "${temporaryDirectory.fileName}-proj-a",
            report.appSessionMarker,
            "고아를 ps 에서 찾을 표식이 자식 환경에 있어야 한다",
        )
    }

    @Test
    fun `통로를 닫으면 그 세션의 프로세스가 끝난다`() {
        val reportPath = temporaryDirectory.resolve("세션이-받은-것.txt")
        val connector = openSession(
            answer = SessionCommandAnswer(
                command = recordAndStayAlive(reportPath),
                workingDirectory = temporaryDirectory,
                environmentToAdd = emptyMap(),
            ),
        )
        val sessionProcessId = waitForReport(reportPath).processId
        assertTrue(connector.isConnected, "세션이 살아 있어야 한다")

        connector.close()

        // 설계 6.1 — 닫기가 detach 에서 종료로 바뀐 자리다. 예전에는 이 자리에서 tmux 세션이
        // 살아남는 것을 확인했고, 이제는 그 반대를 확인한다.
        (connector as ProcessTtyConnector).waitForWithin(PROCESS_EXIT_TIMEOUT_MILLIS)
        assertTrue(processIsGone(sessionProcessId), "세션의 프로세스가 남아 있으면 안 된다 (pid $sessionProcessId)")
        assertFalse(connector.isConnected, "닫힌 통로는 끊긴 것으로 보여야 한다")
    }

    @Test
    fun `무엇을 띄울지 묻는 데 실패하면 PTY 를 열지 않는다`() {
        val opener = PtySessionTerminalOpener(
            sessionCommandSource = { _, _ -> throw TerminalSessionFailure.KyuExecutableNotFound() },
            baseEnvironment = emptyMap(),
        )

        assertFailsWith<TerminalSessionFailure.KyuExecutableNotFound> {
            opener.openSessionIn(temporaryDirectory, SessionTarget.Repo("proj-a"))
        }
    }

    private fun openSession(
        answer: SessionCommandAnswer,
        baseEnvironment: Map<String, String> = mapOf("PATH" to System.getenv("PATH")),
        target: SessionTarget = SessionTarget.Repo("proj-a"),
    ): TtyConnector {
        val opener = PtySessionTerminalOpener(
            sessionCommandSource = SessionCommandSource { _, _ -> answer },
            baseEnvironment = baseEnvironment,
        )
        return opener.openSessionIn(temporaryDirectory, target).also(openedConnectors::add)
    }

    /**
     * 자기가 받은 것을 한 줄씩 적어 두고 죽을 때까지 사는 명령.
     *
     * `claude` 자리에 놓는 기록 스텁이다. 세션이 실제로 무엇을 받았는지는 세션 안에서만 알 수 있고,
     * 그것을 파일로 내보내면 시험이 PTY 화면을 읽지 않고도 확인할 수 있다.
     */
    private fun recordAndStayAlive(reportPath: Path): List<String> = listOf(
        "sh", "-c",
        """
        { pwd; echo "${'$'}${'$'}"; echo "${'$'}TERM"; echo "${'$'}KYU_APP_SESSION"; echo "${'$'}CLAUDE_CODE_ADDITIONAL_DIRECTORIES_CLAUDE_MD"; } > "$reportPath"
        while :; do sleep 1; done
        """.trimIndent(),
    )

    /**
     * 기록이 다 적힐 때까지 기다렸다가 읽는다.
     *
     * 고정 시간을 자지 않는다. PTY 생성과 sh 기동에 걸리는 시간을 상수로 두면 느린 기계에서
     * 헛되이 깨지고 빠른 기계에서는 그만큼 기다린다.
     */
    private fun waitForReport(reportPath: Path): SessionReport {
        repeat(POLL_ATTEMPTS) {
            if (reportPath.exists()) {
                val lines = reportPath.readText().lines()
                if (lines.size >= REPORT_LINE_COUNT) {
                    return SessionReport(
                        workingDirectory = lines[0],
                        processId = lines[1].toLong(),
                        terminalType = lines[2],
                        appSessionMarker = lines[3],
                        repoClaudeMd = lines[4],
                    )
                }
            }
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
        error("세션이 기록을 남기지 않았습니다: $reportPath")
    }

    private data class SessionReport(
        val workingDirectory: String,
        val processId: Long,
        val terminalType: String,
        val appSessionMarker: String,
        val repoClaudeMd: String,
    )

    private fun ProcessTtyConnector.waitForWithin(timeoutMillis: Long) {
        val waiting = Thread { runCatching { waitFor() } }.apply { start() }
        waiting.join(timeoutMillis)
        check(!waiting.isAlive) { "세션이 ${timeoutMillis}ms 안에 끝나지 않았습니다" }
    }

    private fun processIsGone(processId: Long): Boolean {
        repeat(POLL_ATTEMPTS) {
            if (ProcessHandle.of(processId).map { !it.isAlive }.orElse(true)) {
                return true
            }
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
        return false
    }

    private companion object {
        const val REPORT_LINE_COUNT = 5
        const val POLL_ATTEMPTS = 100
        const val POLL_INTERVAL_MILLIS = 50L
        const val PROCESS_EXIT_TIMEOUT_MILLIS = 5_000L
    }
}
