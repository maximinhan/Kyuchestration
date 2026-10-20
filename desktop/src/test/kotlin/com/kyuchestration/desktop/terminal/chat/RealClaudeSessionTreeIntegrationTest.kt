package com.kyuchestration.desktop.terminal.chat

import com.kyuchestration.desktop.kyu.realKyuCommandRunnerOrSkip
import com.kyuchestration.desktop.terminal.APP_SESSION_MARKER_ENV_NAME
import com.kyuchestration.desktop.terminal.SessionConversationChoice
import com.kyuchestration.desktop.terminal.SessionTarget
import com.kyuchestration.desktop.terminal.coordrecord.CoordConversationRecordChildSessionIds
import com.kyuchestration.desktop.terminal.kyucli.KyuCliChildSessionCommandSource
import com.kyuchestration.desktop.terminal.kyucli.KyuCliSessionCommandSource
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Executors
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assumptions.abort

/**
 * 진짜 `claude` 메인에게 하위를 띄우게 하고 앱이 그것을 쥐고 거두는지 본다(session-tree-design.md 7 절 4 단계 완료
 * 확인 ① ④ ⑤ — 4a 몫).
 *
 * | 확인 | 이 시험이 보는 것 |
 * |---|---|
 * | ① 하위 둘이 동시에 돈다 | 메인의 한 턴 안에서 띄운 두 하위의 `claude` 가 이 JVM 의 자식으로 함께 살아 있고, 각자 첫 턴을 끝낸다. 시각은 각 프로세스의 시작 시각(`ProcessHandle.info`)이다 |
 * | ④ 메인을 닫으면 하위가 사라진다 | 메인을 끝낸 뒤 `KYU_APP_SESSION` 표식이 이 워크디렉토리인 프로세스가 `/proc` 에 하나도 없다 — `ps` 와 같은 자리를 본다 |
 * | ⑤ 걸리는 설정이 있는 폴더 | 메인이 `.mcp.json` 이 있는 폴더에 띄우려 하면 관문 물음이 서고, 거절하면 하위가 서지 않는다 |
 *
 * **부르면 토큰을 쓴다.** 다른 챗 통합 검증과 같은 규율로 일부러 켜야 돈다.
 *
 * ```
 * KYU_CLAUDE_CHAT_INTEGRATION=1 KYU_BINARY_PATH=<이 브랜치에서 빌드한 kyu> \
 *   ./gradlew test --tests '*RealClaudeSessionTreeIntegrationTest*'
 * ```
 *
 * **메인의 승인 카드는 이 시험이 허용으로 답한다.** `spawn_session` 이 사람의 승인을 거치는지는 이 머신의 설정이
 * 정하고, 이 시험이 재려는 것은 그 뒤다.
 */
class RealClaudeSessionTreeIntegrationTest {

    /** 표식(`KYU_APP_SESSION=<워크디렉토리 이름>-<라벨>`)이 이 시험만의 것이 되도록 이름이 무작위인 자리. */
    private val workDirPath: Path = createTempDirectory("kyu-real-tree")

    /** 앱에서 홀더에 닿는 스코프는 화면을 그리는 단일 스레드다. 같은 전제를 지킨다. */
    private val holderThread = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "session-tree-test") }
    private val holderScope = CoroutineScope(holderThread.asCoroutineDispatcher())

    @AfterTest
    fun 남은_세션과_임시_디렉토리를_치운다() = runBlocking {
        holderScope.cancel()
        holderThread.shutdownNow()
        workDirPath.toFile().deleteRecursively()
        Unit
    }

    @Test
    fun `메인이 띄운 하위 둘이 함께 돌고 메인을 닫으면 하위 프로세스가 모두 사라진다`(): Unit = runBlocking {
        val holder = stateHolderOrSkip()
        openMain(holder)

        onHolder {
            holder.sendUserMessage(
                "mcp__kyu__spawn_session 도구를 두 번 불러 하위 세션 둘을 띄워라. " +
                    "첫째는 cwd 'proj-a', createDirectory true, prompt '숫자 1 만 답해'. " +
                    "둘째는 cwd 'proj-b', createDirectory true, prompt '숫자 2 만 답해'. " +
                    "띄운 뒤에는 기다리지 말고 '띄웠다' 라고만 답해.",
            )
        }

        waitUntil("하위 둘이 트리에 서기를", holder) { holder.sessionTree.value.children.size >= 2 }

        // ① 둘이 함께 살아 있다 — 각자의 프로세스에서.
        val childProcesses = listOf("child-1", "child-2").map { childId ->
            directChildProcessMarked(sessionMarker(childId))
                ?: error("$childId 의 claude 를 이 JVM 의 자식에서 찾지 못했습니다")
        }
        assertTrue(childProcesses.all { it.isAlive }, "하위 둘이 함께 살아 있지 않습니다")
        val startInstants = childProcesses.map { it.info().startInstant().orElseThrow() }
        println("하위 프로세스 시작 시각: child-1=${startInstants[0]} child-2=${startInstants[1]} " +
            "(차이 ${Duration.between(startInstants[0], startInstants[1]).abs().toMillis()} ms)")

        // 둘 다 첫 턴을 끝낸다 — 메인이 기다리지 않아도 각자 돈다.
        waitUntil("하위 둘이 첫 턴을 끝내기를", holder) {
            holder.sessionTree.value.children.all { it.waitingFor == ChildWaitingFor.Nothing }
        }
        assertTrue(childProcesses.all { it.isAlive }, "첫 턴을 끝낸 하위가 살아 있지 않습니다 — 하위는 상위가 끝낼 때까지 산다")

        // ④ 메인을 닫는다.
        onHolder { holder.endOnScreenSession() }

        withTimeout(PROCESS_GONE_TIMEOUT_MILLIS) {
            while (processesMarkedForThisWorkDir().isNotEmpty()) {
                delay(POLL_INTERVAL_MILLIS)
            }
        }
        assertEquals(emptyList(), processesMarkedForThisWorkDir().map { it.pid() })
        assertTrue(childProcesses.none { it.isAlive })
    }

    @Test
    fun `걸리는 설정이 있는 폴더에 띄우려 하면 관문 물음이 서고 거절하면 하위가 서지 않는다`(): Unit = runBlocking {
        val holder = stateHolderOrSkip()
        workDirPath.resolve("gated").createDirectories().resolve(".mcp.json").writeText("""{"mcpServers":{}}""")
        openMain(holder)

        onHolder {
            holder.sendUserMessage(
                "mcp__kyu__spawn_session 도구를 한 번 불러 cwd 'gated' 에 prompt '숫자 1 만 답해' 로 하위 세션을 띄워라. " +
                    "결과가 무엇이든 그 결과를 한 줄로만 답해.",
            )
        }

        waitUntil("관문 물음이 서기를", holder) { holder.gateQuestions.value.isNotEmpty() }
        val question = holder.gateQuestions.value.single()
        assertEquals("gated", question.gate.relativeCwd)
        assertEquals(listOf(".mcp.json"), question.gate.executionConfigs.map { it.relativePath })

        onHolder { holder.answerGateQuestion(question.childId, approved = false) }

        waitUntil("메인의 턴이 끝나기를", holder) {
            onScreenConversation(holder).entries.any { it is ChatEntry.TurnEnded }
        }
        assertEquals(emptyList(), holder.sessionTree.value.children)
        onHolder { holder.endOnScreenSession() }
    }

    private suspend fun openMain(holder: ChatSessionStateHolder) {
        onHolder {
            holder.enterSession(workDirPath, SessionTarget.Main, SessionConversationChoice.StartNewConversation)
        }
        withTimeout(TURN_TIMEOUT_MILLIS) {
            while (holder.state.value !is ChatScreenState.ChatOnScreen) {
                delay(POLL_INTERVAL_MILLIS)
            }
        }
    }

    /**
     * 조건이 설 때까지 기다린다. 그 사이 메인에 걸린 승인 카드는 허용으로 답한다 — 사람이 앉아 있는 자리다.
     */
    private suspend fun waitUntil(what: String, holder: ChatSessionStateHolder, condition: () -> Boolean) {
        try {
            withTimeout(TURN_TIMEOUT_MILLIS) {
                while (!condition()) {
                    allowPendingPermissionCardsOnScreen(holder)
                    delay(POLL_INTERVAL_MILLIS)
                }
            }
        } catch (timeout: TimeoutCancellationException) {
            throw AssertionError("$what 기다렸지만 오지 않았습니다. 메인의 전사: ${onScreenConversation(holder).entries}", timeout)
        }
    }

    private suspend fun allowPendingPermissionCardsOnScreen(holder: ChatSessionStateHolder) {
        val pending = (holder.state.value as? ChatScreenState.ChatOnScreen)?.conversation?.entries
            ?.filterIsInstance<ChatEntry.PermissionAsked>()
            ?.filter { it.answer == null }
            .orEmpty()
        pending.forEach { card ->
            onHolder { holder.answerOnScreenPermission(card.toolUseId, PermissionCardChoice.AllowOnce(card.input)) }
        }
    }

    private suspend fun onHolder(action: suspend () -> Unit) = withContext(holderScope.coroutineContext) { action() }

    private fun onScreenConversation(holder: ChatSessionStateHolder): ChatConversation =
        assertIs<ChatScreenState.ChatOnScreen>(holder.state.value).conversation

    private fun sessionMarker(label: String) = "$APP_SESSION_MARKER_ENV_NAME=${workDirPath.fileName}-$label"

    /** 이 JVM 이 직접 띄운 자식 중 그 표식을 단 것 — 앱이 쥔 `claude` 그 자체다. */
    private fun directChildProcessMarked(marker: String): ProcessHandle? =
        ProcessHandle.current().children().filter { marker in environmentOf(it) }.findFirst().orElse(null)

    /** 이 워크디렉토리의 표식을 단 프로세스 전부 — 자식의 자식(kyu mcp serve 등)까지. `ps eww` 가 보는 자리다. */
    private fun processesMarkedForThisWorkDir(): List<ProcessHandle> {
        val markerPrefix = "$APP_SESSION_MARKER_ENV_NAME=${workDirPath.fileName}-"
        return ProcessHandle.allProcesses()
            .filter { process -> environmentOf(process).split('\u0000').any { it.startsWith(markerPrefix) } }
            .filter { it.isAlive }
            .toList()
    }

    /** `/proc/<pid>/environ`. 읽을 수 없는 프로세스(남의 것 · 이미 사라진 것)는 빈 문자열이다. */
    private fun environmentOf(process: ProcessHandle): String =
        runCatching { String(Files.readAllBytes(Path.of("/proc", process.pid().toString(), "environ"))) }.getOrDefault("")

    private fun stateHolderOrSkip(): ChatSessionStateHolder {
        if (System.getenv(OPT_IN_VARIABLE) != "1") {
            abort<Unit>("$OPT_IN_VARIABLE=1 이 아니라 건너뜁니다 — 이 검증은 진짜 claude 를 부릅니다")
        }
        if (!Files.isDirectory(Path.of("/proc/self"))) {
            abort<Unit>("/proc 이 없어 프로세스를 셀 수 없어 건너뜁니다")
        }
        val claudeInstalled = runCatching {
            ProcessBuilder(listOf("claude", "--version")).redirectErrorStream(true).start().waitFor() == 0
        }.getOrDefault(false)
        if (!claudeInstalled) {
            abort<Unit>("claude 를 부르지 못해 건너뜁니다")
        }

        val kyuCommandRunner = realKyuCommandRunnerOrSkip()
        return ChatSessionStateHolder(
            sessionCommandSource = KyuCliSessionCommandSource(kyuCommandRunner),
            chatSessionOpener = ProcessChatSessionOpener(),
            childSessionCommandSource = KyuCliChildSessionCommandSource(kyuCommandRunner),
            recordedChildSessionIds = CoordConversationRecordChildSessionIds(),
            claudeAuthToken = { null },
            coroutineScope = holderScope,
        )
    }

    private companion object {
        const val OPT_IN_VARIABLE = "KYU_CLAUDE_CHAT_INTEGRATION"

        /** 메인의 한 턴 — 도구 둘과 하위 기동까지 들어 있다. */
        const val TURN_TIMEOUT_MILLIS = 300_000L

        /** stdin 을 닫고 5 초 안에 안 끝나면 죽이므로(ProcessChatSession), 그 두 번에 여유를 둔다. */
        const val PROCESS_GONE_TIMEOUT_MILLIS = 20_000L

        const val POLL_INTERVAL_MILLIS = 300L
    }
}
