package com.kyuchestration.desktop.terminal.kyucli

import com.kyuchestration.desktop.kyu.realKyuCommandRunnerOrSkip
import com.kyuchestration.desktop.terminal.ChildSessionCommandAnswer
import com.kyuchestration.desktop.terminal.ChildSessionCommandRequest
import com.kyuchestration.desktop.terminal.TerminalSessionFailure
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 진짜 kyu 에게 하위 세션을 물어, 앱이 읽는 모양으로 답이 오는지 본다(3 단계 계약의 앱 쪽 끝).
 *
 * `claude` 는 부르지 않는다. 관문에 걸리는지 · 승인한 지문으로 다시 물으면 띄울 명령이 오는지는 엔진이
 * `claude` 없이 답한다. kyu 를 찾지 못하면 건너뛴다.
 */
class KyuCliChildSessionCommandSourceIntegrationTest {

    private val temporaryDirectory = createTempDirectory("kyu-child-command-test")

    @AfterTest
    fun 임시_워크디렉토리를_지운다() {
        temporaryDirectory.toFile().deleteRecursively()
    }

    @Test
    fun `걸리는 설정이 있는 폴더는 관문의 물음으로 답하고 승인한 지문으로 다시 물으면 띄울 명령이 온다`() {
        val source = KyuCliChildSessionCommandSource(realKyuCommandRunnerOrSkip())
        val workDirPath = temporaryDirectory.resolve("WorkDir-gate").createDirectories()
        val gatedFolder = workDirPath.resolve("proj-a").createDirectories()
        gatedFolder.resolve(".mcp.json").writeText("""{"mcpServers":{}}""")

        val firstAnswer = source.childSessionCommandFor(workDirPath, request("proj-a", null), SOCKET_PATH)

        val gate = assertIs<ChildSessionCommandAnswer.ApprovalRequired>(firstAnswer).gate
        assertEquals("proj-a", gate.relativeCwd)
        assertEquals(listOf(".mcp.json"), gate.executionConfigs.map { it.relativePath })
        assertEquals("""{"mcpServers":{}}""", gate.executionConfigs.single().content)

        val secondAnswer = source.childSessionCommandFor(workDirPath, request("proj-a", gate.fingerprint), SOCKET_PATH)

        val ready = assertIs<ChildSessionCommandAnswer.Ready>(secondAnswer)
        assertEquals("child-1", ready.assignedChildSession.childId)
        assertEquals("proj-a", ready.assignedChildSession.relativeCwd)
        assertEquals(gatedFolder.toRealPath(), ready.sessionCommandAnswer.workingDirectory.toRealPath())
        assertTrue(ready.sessionCommandAnswer.command.isNotEmpty())
    }

    @Test
    fun `이름 없이 물으면 엔진이 기록 뒤의 이름을 지어 답한다`() {
        val source = KyuCliChildSessionCommandSource(realKyuCommandRunnerOrSkip())
        val workDirPath = temporaryDirectory.resolve("WorkDir-naming").createDirectories()
        workDirPath.resolve("proj-a").createDirectories()
        workDirPath.resolve("proj-b").createDirectories()

        val first = source.childSessionCommandFor(workDirPath, request("proj-a", null), SOCKET_PATH)
        val second = source.childSessionCommandFor(
            workDirPath,
            request("proj-b", null).copy(liveChildRelativeCwds = listOf("proj-a")),
            SOCKET_PATH,
        )

        assertEquals("child-1", assertIs<ChildSessionCommandAnswer.Ready>(first).assignedChildSession.childId)
        assertEquals("child-2", assertIs<ChildSessionCommandAnswer.Ready>(second).assignedChildSession.childId)
    }

    @Test
    fun `살아 있는 하위의 자리에 또 띄우려 하면 엔진이 거절한다`() {
        val source = KyuCliChildSessionCommandSource(realKyuCommandRunnerOrSkip())
        val workDirPath = temporaryDirectory.resolve("WorkDir-live").createDirectories()
        workDirPath.resolve("proj-a").createDirectories()

        val failure = assertFailsWith<TerminalSessionFailure.SessionCommandRefused> {
            source.childSessionCommandFor(
                workDirPath,
                request("proj-a", null).copy(liveChildRelativeCwds = listOf("proj-a")),
                SOCKET_PATH,
            )
        }

        // 이 문구가 spawn 의 error 로 상위 모델에게 그대로 간다.
        assertTrue(failure.guidance.isNotBlank())
    }

    private fun request(cwd: String, approvedFingerprint: String?) = ChildSessionCommandRequest(
        requestedCwd = cwd,
        createDirectory = false,
        title = null,
        liveChildRelativeCwds = emptyList(),
        approvedFingerprint = approvedFingerprint,
    )

    private companion object {
        /** 엔진은 이 자리에 붙지 않는다 — 조립에 경로를 싣기만 한다. */
        val SOCKET_PATH: Path = Path.of("/tmp/kyu-child-test.sock")
    }
}
