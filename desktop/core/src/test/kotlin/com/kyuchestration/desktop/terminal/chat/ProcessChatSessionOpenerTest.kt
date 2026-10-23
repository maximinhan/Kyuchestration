package com.kyuchestration.desktop.terminal.chat

import com.kyuchestration.desktop.terminal.SessionEntryPlan
import com.kyuchestration.desktop.terminal.TerminalSessionFailure
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * 엔진이 답한 계획을 그 자리에서 그 환경으로 정말 띄우는지 본다.
 *
 * 이것이 어긋나면 세션은 열리는데 엉뚱한 디렉토리에서 열린다 — 레포 세션이 그 레포의
 * `.mcp.json`·`CLAUDE.md`·에이전트를 쓰는 근거가 cwd 하나뿐이라(설계 문서 1.3) 조용히 다른
 * 레포의 규칙으로 도는 세션이 된다.
 *
 * 시험 함수의 `: Unit` 에 대해서는 [ProcessChatSessionTest] 의 머리말을 본다.
 */
class ProcessChatSessionOpenerTest {

    private val temporaryDirectory = createTempDirectory("kyu-chat-opener-test")

    @AfterTest
    fun 임시_디렉토리를_지운다() {
        temporaryDirectory.toFile().deleteRecursively()
    }

    @Test
    fun `엔진이 답한 명령을 그 자리에서 그 환경으로 띄운다`(): Unit = runBlocking {
        val sessionDirectory = temporaryDirectory.resolve("proj-a").createDirectories()
        val report = temporaryDirectory.resolve("세션이-받은-것.txt")

        val session = ProcessChatSessionOpener().openChatSession(
            SessionEntryPlan(
                command = listOf("sh", "-c", "{ pwd; echo \"\$CLAUDE_CODE_ADDITIONAL_DIRECTORIES_CLAUDE_MD\"; } > '$report'"),
                workingDirectory = sessionDirectory,
                environment = mapOf(
                    "PATH" to System.getenv("PATH"),
                    "CLAUDE_CODE_ADDITIONAL_DIRECTORIES_CLAUDE_MD" to "1",
                ),
            ),
        )
        session.endSession()

        assertTrue(waitUntilFileAppears(report), "세션이 아무것도 남기지 않았습니다")
        val (workingDirectory, repoClaudeMd) = report.readText().lines()
        assertEquals(sessionDirectory.toRealPath().toString(), workingDirectory, "엔진이 답한 자리에서 떠야 한다")
        assertEquals("1", repoClaudeMd, "엔진이 더하라고 한 환경이 자식까지 닿아야 한다")
    }

    /**
     * 엔진은 명령을 이름(`claude`)으로 답하고, 그 이름을 찾을 PATH 를 환경에 담아 준다. `wsl.exe -e` 로 뜬 코어처럼 이 JVM 의 PATH 에
     * 그 자리가 없어도 띄워야 한다(3 단계 실측 — ProcessBuilder 는 이름을 이 JVM 의 PATH 로 찾는다).
     */
    @Test
    fun `엔진이 답한 이름은 그 환경의 PATH 에서 찾는다`(): Unit = runBlocking {
        val binDirectory = temporaryDirectory.resolve("bin-only-in-plan").createDirectories()
        val report = temporaryDirectory.resolve("띄워졌다.txt")
        binDirectory.resolve("kyu-test-only-claude").apply {
            writeText("#!/bin/sh\necho 떴다 > '$report'\n")
            toFile().setExecutable(true)
        }

        val session = ProcessChatSessionOpener().openChatSession(
            SessionEntryPlan(
                command = listOf("kyu-test-only-claude"),
                workingDirectory = temporaryDirectory,
                environment = mapOf("PATH" to "$binDirectory${java.io.File.pathSeparator}/usr/bin:/bin"),
            ),
        )
        session.endSession()

        assertTrue(waitUntilFileAppears(report), "환경의 PATH 에만 있는 명령이 뜨지 않았다")
    }

    @Test
    fun `띄우지 못하면 이유를 담아 알린다`(): Unit = runBlocking {
        // claude 가 PATH 에 없는 머신이 실제로 있다 — Finder 로 띄운 맥 앱이 그렇다
        // (ChildProcessEnvironment). 조용히 넘어가면 사용자는 빈 화면만 보게 된다.
        val failure = assertFailsWith<TerminalSessionFailure.ChatProcessFailedToStart> {
            ProcessChatSessionOpener().openChatSession(
                SessionEntryPlan(
                    command = listOf("이런-실행-파일은-없다"),
                    workingDirectory = temporaryDirectory,
                    environment = mapOf("PATH" to System.getenv("PATH")),
                ),
            )
        }

        assertTrue(failure.guidance.isNotBlank(), "사람이 지금 할 수 있는 일이 안내에 있어야 한다")
    }
}
