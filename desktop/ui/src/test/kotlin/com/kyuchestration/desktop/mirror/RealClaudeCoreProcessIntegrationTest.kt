package com.kyuchestration.desktop.mirror

import com.kyuchestration.desktop.claudeauth.ClaudeAuthenticationState
import com.kyuchestration.desktop.core.TEST_SCREEN_VERSION_LABEL
import com.kyuchestration.desktop.core.coreProcessCommandForTests
import com.kyuchestration.desktop.core.isolatedCoreEnvironment
import com.kyuchestration.desktop.dashboard.WorkDirDashboardState
import com.kyuchestration.desktop.diagnostics.DiagnosticLog
import com.kyuchestration.desktop.engine.EngineInstallationState
import com.kyuchestration.desktop.terminal.SessionConversationChoice
import com.kyuchestration.desktop.terminal.SessionTarget
import com.kyuchestration.desktop.terminal.chat.ChatEntry
import com.kyuchestration.desktop.terminal.chat.ChildLifecycle
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assumptions.abort

/**
 * 코어를 진짜 자식 프로세스로 띄우고 그 stdio 에 붙은 거울로 실 claude 세션을 부린다(core-ui-split-design.md 7 절 2 단계 완료 확인).
 *
 * 메인 챗 · 관문 · 하위 세션이 프로세스 경계를 건너 돌고, 화면이 stdin 을 닫으면 코어가 claude 를 모두 끝내고 스스로 나간다 —
 * 코어 프로세스의 자손이 하나도 남지 않는다.
 *
 * ```
 * KYU_CLAUDE_CHAT_INTEGRATION=1 KYU_BINARY_PATH=<이 브랜치에서 빌드한 kyu> \
 *   ./gradlew :ui:test --tests '*RealClaudeCoreProcessIntegrationTest'
 * ```
 */
class RealClaudeCoreProcessIntegrationTest {

    private val scratchDirectory: Path = createTempDirectory("kyu-real-core-process")

    private val workDirPath: Path = scratchDirectory.resolve("workdir").createDirectories()

    private val readingScope = CoroutineScope(Dispatchers.Default)

    @AfterTest
    fun cleanUp() {
        readingScope.cancel()
        scratchDirectory.toFile().deleteRecursively()
    }

    @Test
    fun `프로세스 경계를 건너 메인 챗 · 관문 · 하위 세션이 돌고 화면이 떠나면 코어가 claude 를 모두 끝내고 나간다`(): Unit = runBlocking {
        val kyuExecutable = requireRealClaudeAndKyuOrSkip()
        workDirPath.resolve("gated").createDirectories().resolve(".mcp.json").writeText("""{"mcpServers":{}}""")
        val coreProcess = CoreChildProcess.start(coreProcessCommandForTests(), DiagnosticLog.Discarding) {
            isolatedCoreEnvironment(scratchDirectory, kyuExecutable)
        }
        val mirror = withTimeout(STEP_TIMEOUT) {
            assertIs<CoreConnection.Connected>(
                coreProcess.connectMirror(readingScope, TEST_SCREEN_VERSION_LABEL, leaveTimeout = LEAVE_TIMEOUT),
            ).mirror
        }

        waitUntil("엔진과 claude 자격 증명이 서기를") {
            mirror.engineInstallationState.value == EngineInstallationState.EngineReady &&
                mirror.claudeAuthenticationState.value == ClaudeAuthenticationState.CredentialsReady
        }
        mirror.openWorkDir(workDirPath.toString())
        waitUntil("워크디렉토리가 열리기를") { mirror.workDirDashboardState.value is WorkDirDashboardState.WorkDirObserved }

        // 메인 챗 — 거울이 보낸 진입과 말이 코어 프로세스의 claude 에 닿고, 그 답이 틀로 돌아온다.
        mirror.enterSession(SessionTarget.Main, SessionConversationChoice.StartNewConversation)
        waitUntil("메인이 서기를") { SessionTarget.Main in mirror.heldChatSessionTargets.value }
        mirror.sendUserMessage(
            SessionTarget.Main,
            "mcp__kyu__spawn_session 도구를 한 번 불러 cwd 'gated' 에 prompt '숫자 1 만 답해' 로 하위 세션을 띄워라. " +
                "그 결과와 상관없이 이어서 한 번 더 불러 cwd 'proj-a', createDirectory true, prompt '숫자 2 만 답해' 로 띄워라. " +
                "띄운 뒤에는 기다리지 말고 '띄웠다' 라고만 답해.",
        )

        // 관문 — 물음은 코어에 남고 화면은 그 id 로 답한다(원칙 22).
        waitUntil("관문 물음이 서기를", TURN_TIMEOUT) { mirror.childSessionGateQuestions.value.isNotEmpty() }
        val question = mirror.childSessionGateQuestions.value.single()
        assertEquals("gated", question.gate.relativeCwd)
        mirror.answerGate(question.gateQuestionId, approved = false)

        // 하위 세션 — 관문에 걸리지 않은 쪽이 코어 프로세스 안에 서고, 거울의 트리에 그 하위가 선다.
        waitUntil("메인의 턴이 끝나기를", TURN_TIMEOUT) {
            mirror.chatConversations.value[SessionTarget.Main]?.entries.orEmpty().any { it is ChatEntry.TurnEnded }
        }
        val children = mirror.sessionTree.value.children
        assertEquals(listOf("proj-a"), children.map { it.relativeCwd })
        assertEquals(ChildLifecycle.Running, children.single().lifecycle)

        // 화면이 떠난다 — 코어의 자손(claude · kyu mcp serve)을 떠나기 전에 세어 두고, 떠난 뒤 하나도 남지 않았는지 본다.
        val coreDescendants = ProcessHandle.of(coreProcess.pid).orElseThrow().descendants().toList()
        println("떠나기 전 코어의 자손 ${coreDescendants.size} 개: ${coreDescendants.map { it.info().command().orElse("?") }}")
        assertEquals(true, coreDescendants.isNotEmpty(), "세션이 떠 있는데 코어의 자손이 없다")

        val exit = coreProcess.leave(mirror, timeout = LEAVE_TIMEOUT)

        assertEquals(CoreChildProcess.Exit.EndedByItself(exitCode = 0), exit)
        assertEquals(emptyList(), coreDescendants.filter { it.isAlive }.map { it.pid() }, "코어가 나간 뒤에도 자손이 남았다")
        assertEquals(null, mirror.coreConnectionFailure.value)
    }

    private suspend fun waitUntil(what: String, timeout: kotlin.time.Duration = STEP_TIMEOUT, condition: () -> Boolean) {
        withTimeout(timeout) {
            while (!condition()) {
                delay(POLL_INTERVAL)
            }
        }
        println("확인: $what")
    }

    private fun requireRealClaudeAndKyuOrSkip(): Path {
        if (System.getenv(OPT_IN_VARIABLE) != "1") {
            abort<Unit>("$OPT_IN_VARIABLE=1 이 아니라 건너뜁니다 — 이 검증은 진짜 claude 를 부릅니다")
        }
        val kyuExecutable = System.getenv(KYU_BINARY_VARIABLE)?.takeIf { it.isNotBlank() }?.let(Path::of)
            ?: abort<Path>("$KYU_BINARY_VARIABLE 가 없어 건너뜁니다 — 코어가 부를 엔진을 이 판의 소스에서 빌드한 것으로 고정한다")
        if (!Files.isExecutable(kyuExecutable)) {
            abort<Unit>("$kyuExecutable 를 실행할 수 없어 건너뜁니다")
        }
        return kyuExecutable
    }

    private companion object {
        const val OPT_IN_VARIABLE = "KYU_CLAUDE_CHAT_INTEGRATION"
        const val KYU_BINARY_VARIABLE = "KYU_BINARY_PATH"
        val STEP_TIMEOUT = 60.seconds
        val TURN_TIMEOUT = 5.minutes
        val LEAVE_TIMEOUT = 60.seconds
        val POLL_INTERVAL = 0.2.seconds
    }
}
