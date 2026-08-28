package com.kyuchestration.desktop.workdir.kyucli

import com.kyuchestration.desktop.kyu.realKyuCommandRunnerOrSkip
import com.kyuchestration.desktop.workdir.RepoState
import com.kyuchestration.desktop.workdir.WorkDirObservationFailure
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 진짜 kyu 바이너리와 진짜 git 저장소로 어댑터 전체를 한 번 통과시킨다.
 *
 * 가짜 실행기를 쓰는 시험은 "이런 문서가 오면 이렇게 읽는다" 까지만 말한다. 그 문서가 실제로
 * 그 모양으로 온다는 것은 엔진에게 직접 물어야만 알 수 있고, 계약이 조용히 어긋나는 자리도
 * 정확히 거기다.
 *
 * kyu 를 찾지 못하면 건너뛴다(realKyuCommandRunnerOrSkip).
 */
class KyuCliWorkDirObserverIntegrationTest {

    private val temporaryDirectory = createTempDirectory("kyu-integration-test")

    @AfterTest
    fun 임시_워크디렉토리를_지운다() {
        temporaryDirectory.toFile().deleteRecursively()
    }

    @Test
    fun `실제 kyu 가 관찰한 워크디렉토리를 카드로 그릴 수 있는 스냅샷으로 읽어낸다`() {
        val observer = KyuCliWorkDirObserver(realKyuCommandRunnerOrSkip())
        val workDirPath = temporaryDirectory.resolve("WorkDir-featureX").createDirectories()

        gitRepository(workDirPath.resolve("proj-a"))
        gitRepository(workDirPath.resolve("proj-b")).resolve("커밋하지-않은-파일.txt").writeText("아직 커밋 전")
        workDirPath.resolve(".coord").createDirectories().resolve("plan.md").writeText(PLAN_WITH_A_BLOCKED_TASK)

        val snapshot = observer.observe(workDirPath)

        assertEquals("WorkDir-featureX", snapshot.name)
        assertEquals(workDirPath.toString(), snapshot.absolutePath)
        assertEquals(listOf("proj-a", "proj-b"), snapshot.repos.map { it.name })
        assertEquals(emptyList(), snapshot.planWarnings)

        val projectA = snapshot.repos.first()
        // 세션을 띄우지 않았고 워킹 트리도 깨끗하다.
        assertEquals(RepoState.Idle, projectA.state)
        assertEquals("publish", projectA.task?.id)
        assertEquals("doing", projectA.task?.status)
        assertEquals(emptyList(), projectA.task?.blockedBy)
        assertEquals(0, projectA.doneTaskCount)
        assertEquals(1, projectA.totalTaskCount)

        val projectB = snapshot.repos.last()
        // 커밋하지 않은 파일 하나가 상태를 DIRTY 로 만든다.
        assertEquals(RepoState.Dirty, projectB.state)
        assertEquals("consume", projectB.task?.id)
        assertEquals(listOf("publish"), projectB.task?.blockedBy)
        assertEquals(1, projectB.doneTaskCount)
        assertEquals(2, projectB.totalTaskCount)

        assertEquals(0, snapshot.aliveSessionCount)
        assertTrue(!snapshot.mainSessionAlive)
    }

    @Test
    fun `워크디렉토리가 아닌 곳을 열면 kyu 가 남긴 이유를 그대로 받는다`() {
        val observer = KyuCliWorkDirObserver(realKyuCommandRunnerOrSkip())

        val failure = assertFailsWith<WorkDirObservationFailure.KyuExitedWithFailure> {
            observer.observe(temporaryDirectory.resolve("있지도-않은-워크디렉토리"))
        }

        assertEquals(1, failure.exitCode)
        assertTrue(failure.guidance.isNotBlank(), "kyu 가 stderr 로 남긴 이유가 안내 문구가 되어야 한다")
    }

    private fun gitRepository(repositoryPath: Path): Path {
        repositoryPath.createDirectories()
        git(repositoryPath, "init", "--quiet", "--initial-branch=main")
        // 커밋이 하나도 없는 저장소는 git 이 다르게 답한다. 실제 워크디렉토리는 클론한 것이라
        // 언제나 커밋이 있으므로, 그 조건을 맞춰 둔다.
        git(repositoryPath, "commit", "--quiet", "--allow-empty", "--message=첫 커밋")
        return repositoryPath
    }

    private fun git(repositoryPath: Path, vararg arguments: String) {
        // 신원은 명령마다 붙인다. 전역 git 설정이 없는 기계에서도 커밋이 되어야 하고, 시험이
        // 사용자의 전역 설정을 건드려서도 안 된다.
        val command = listOf("git", "-C", repositoryPath.toString(), "-c", "user.email=test@kyuchestration", "-c", "user.name=kyu test") + arguments
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        check(process.waitFor() == 0) { "git ${arguments.joinToString(" ")} 실패: $output" }
    }

    private companion object {
        val PLAN_WITH_A_BLOCKED_TASK = """
            ---
            tasks:
              - id: publish
                repo: proj-a
                title: 발행 로직 구현
                needs: []
                status: doing

              - id: consume
                repo: proj-b
                title: 컨슈머 구현
                needs: [publish]
                status: blocked

              - id: cleanup
                repo: proj-b
                title: 임시 코드 정리
                needs: []
                status: done
            ---

            ## 확정 인터페이스

            없음
        """.trimIndent()
    }
}
