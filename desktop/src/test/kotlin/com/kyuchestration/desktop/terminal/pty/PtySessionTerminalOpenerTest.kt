package com.kyuchestration.desktop.terminal.pty

import com.jediterm.terminal.ProcessTtyConnector
import com.jediterm.terminal.TtyConnector
import com.kyuchestration.desktop.terminal.SessionCommandAnswer
import com.kyuchestration.desktop.terminal.SessionCommandSource
import com.kyuchestration.desktop.terminal.SessionTarget
import com.kyuchestration.desktop.terminal.TerminalSessionFailure
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
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
 * PTY 안에서 도는 것은 `claude` 가 아니라 자기가 받은 것을 적어 두는 sh 다(SessionProcessRecording).
 * 엔진이 실제로 그 argv 를 답한다는 것은 KyuCliSessionCommandSourceIntegrationTest 가 진짜 kyu 에게
 * 물어 확인하므로, 이 시험에는 kyu 도 tmux 도 claude 도 필요 없다.
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
                command = recordAndStayAliveCommand(reportPath),
                workingDirectory = sessionDirectory,
                environmentToAdd = mapOf("CLAUDE_CODE_ADDITIONAL_DIRECTORIES_CLAUDE_MD" to "1"),
            ),
            baseEnvironment = mapOf("PATH" to System.getenv("PATH"), "TERM" to "dumb"),
        )

        val report = waitForSessionReport(reportPath)
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
                command = recordAndStayAliveCommand(reportPath),
                workingDirectory = temporaryDirectory,
                environmentToAdd = emptyMap(),
            ),
        )
        val sessionProcessId = waitForSessionReport(reportPath).processId
        assertTrue(connector.isConnected, "세션이 살아 있어야 한다")

        connector.close()

        // 설계 6.1 — 닫기가 detach 에서 종료로 바뀐 자리다. 예전에는 이 자리에서 tmux 세션이
        // 살아남는 것을 확인했고, 이제는 그 반대를 확인한다.
        (connector as ProcessTtyConnector).waitForWithin(PROCESS_EXIT_TIMEOUT_MILLIS)
        assertTrue(waitUntilProcessIsGone(sessionProcessId), "세션의 프로세스가 남아 있으면 안 된다 (pid $sessionProcessId)")
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
        return opener.openSessionIn(temporaryDirectory, target).ttyConnector.also(openedConnectors::add)
    }

    private fun ProcessTtyConnector.waitForWithin(timeoutMillis: Long) {
        val waiting = Thread { runCatching { waitFor() } }.apply { start() }
        waiting.join(timeoutMillis)
        check(!waiting.isAlive) { "세션이 ${timeoutMillis}ms 안에 끝나지 않았습니다" }
    }

    private companion object {
        const val PROCESS_EXIT_TIMEOUT_MILLIS = 5_000L
    }
}
