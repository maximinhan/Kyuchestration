package com.kyuchestration.desktop.terminal.kyucli

import com.kyuchestration.desktop.kyu.realKyuCommandRunnerOrSkip
import com.kyuchestration.desktop.terminal.SessionTarget
import com.kyuchestration.desktop.terminal.TerminalSessionFailure
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 진짜 kyu 바이너리에게 직접 물어, 앱이 실행할 것을 그대로 받아내는지 본다.
 *
 * 가짜 실행기로 세운 시험은 "이런 문서가 오면 이렇게 읽는다" 까지만 말한다. **이 문서는 앱이 그대로
 * 실행할 것이라 그 한계가 특히 아프다** — 필드를 잘못 읽으면 화면이 어긋나는 것이 아니라 엉뚱한
 * 디렉토리에서 세션이 열린다. 그래서 계약의 양쪽 끝을 여기서 한 번 맞춰 본다.
 *
 * `claude` 는 부르지 않는다. 이 시험이 묻는 것은 "무엇을 띄울 것인가" 이지 "띄우면 무엇이 되는가"
 * 가 아니고, 그 답은 엔진이 `claude` 없이도 낸다.
 *
 * kyu 를 찾지 못하면 건너뛴다(realKyuCommandRunnerOrSkip).
 */
class KyuCliSessionCommandSourceIntegrationTest {

    private val temporaryDirectory = createTempDirectory("kyu-session-command-test")

    @AfterTest
    fun 임시_워크디렉토리를_지운다() {
        temporaryDirectory.toFile().deleteRecursively()
    }

    @Test
    fun `메인 세션은 워크디렉토리에서 하위 레포를 전부 달고 뜬다`() {
        val source = KyuCliSessionCommandSource(realKyuCommandRunnerOrSkip())
        val workDirPath = workDirWithRepos("proj-a", "proj-b")

        val answer = source.sessionCommandFor(workDirPath, SessionTarget.Main)

        // 조립 지식은 엔진의 것이다. 이 시험은 그 지식을 코틀린에 옮겨 적은 것이 아니라,
        // 앱이 그것을 그대로 받아 실행할 수 있는 모양인지를 확인한다.
        assertEquals("claude", answer.command.first())
        assertEquals(workDirPath, answer.workingDirectory)
        assertEquals(
            listOf(workDirPath.resolve("proj-a").toString(), workDirPath.resolve("proj-b").toString()),
            answer.command.windowed(2).filter { it.first() == "--add-dir" }.map { it.last() },
        )
    }

    @Test
    fun `레포 세션은 그 레포 디렉토리에서 뜬다`() {
        val source = KyuCliSessionCommandSource(realKyuCommandRunnerOrSkip())
        val workDirPath = workDirWithRepos("proj-a", "proj-b")

        val answer = source.sessionCommandFor(workDirPath, SessionTarget.Repo("proj-a"))

        // 이 한 줄이 그 레포의 .mcp.json·CLAUDE.md·에이전트를 살린다 — 이 도구의 존재 이유다.
        assertEquals(workDirPath.resolve("proj-a"), answer.workingDirectory)
        assertTrue("--add-dir" !in answer.command, "레포 세션은 다른 레포를 달지 않는다: ${answer.command}")
    }

    @Test
    fun `처음 물으면 새 대화 ID 를 정해 주고 다시 물으면 그것을 이어간다`() {
        val source = KyuCliSessionCommandSource(realKyuCommandRunnerOrSkip())
        val workDirPath = workDirWithRepos("proj-a")

        val firstAnswer = source.sessionCommandFor(workDirPath, SessionTarget.Repo("proj-a"))
        val secondAnswer = source.sessionCommandFor(workDirPath, SessionTarget.Repo("proj-a"))

        // 앱을 껐다 켠 뒤 같은 카드를 누르는 것이 두 번째 물음이다. 3 단계가 복구 화면을 더하기
        // 전에도 이 성질만으로 대화는 이어진다 — 그래서 2 단계 검증에 이 항목이 있다.
        val newConversationId = firstAnswer.command.flagValue("--session-id")
        assertEquals(listOf("--resume", newConversationId), secondAnswer.command.windowed(2).first { it.first() == "--resume" })

        // 기록은 워크디렉토리 안에 사람이 읽을 수 있는 모양으로 남는다.
        val conversationsJson = workDirPath.resolve(".coord/conversations.json").readText()
        assertContains(conversationsJson, newConversationId)
    }

    @Test
    fun `없는 레포를 물으면 kyu 가 남긴 이유를 그대로 받는다`() {
        val source = KyuCliSessionCommandSource(realKyuCommandRunnerOrSkip())
        val workDirPath = workDirWithRepos("proj-a")

        val failure = assertFailsWith<TerminalSessionFailure.SessionCommandRefused> {
            source.sessionCommandFor(workDirPath, SessionTarget.Repo("있지도-않은-레포"))
        }

        assertEquals(1, failure.exitCode)
        assertTrue(failure.guidance.isNotBlank(), "kyu 가 stderr 로 남긴 이유가 안내 문구가 되어야 한다")
    }

    private fun List<String>.flagValue(flagName: String): String =
        windowed(2).first { it.first() == flagName }.last()

    private fun workDirWithRepos(vararg repoNames: String): Path {
        val workDirPath = temporaryDirectory.resolve("WorkDir-featureX").createDirectories()
        repoNames.forEach { gitRepository(workDirPath.resolve(it)) }
        return workDirPath
    }

    private fun gitRepository(repositoryPath: Path): Path {
        repositoryPath.createDirectories()
        git(repositoryPath, "init", "--quiet", "--initial-branch=main")
        git(repositoryPath, "commit", "--quiet", "--allow-empty", "--message=첫 커밋")
        return repositoryPath
    }

    private fun git(repositoryPath: Path, vararg arguments: String) {
        val process = ProcessBuilder(listOf("git") + arguments)
            .directory(repositoryPath.toFile())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        check(process.waitFor() == 0) { "git ${arguments.joinToString(" ")} 실패: $output" }
    }
}
