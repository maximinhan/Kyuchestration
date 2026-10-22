package com.kyuchestration.desktop.terminal.chat

import com.kyuchestration.desktop.terminal.PersonOpenedSessionTarget
import com.kyuchestration.desktop.terminal.SessionCommandAnswer
import com.kyuchestration.desktop.terminal.SessionCommandSource
import com.kyuchestration.desktop.terminal.SessionConversationChoice
import com.kyuchestration.desktop.terminal.SessionTarget
import java.nio.file.Path
import java.util.concurrent.Executors
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assumptions.abort

/**
 * 진짜 `claude` 가 오래 걸리는 MCP 도구를 기다리는 중에 끊고, 같은 대화가 다음 말에 정상으로 답하는
 * 것을 상태 홀더까지 통과시켜 확인한다(session-tree-design.md 7 절 1 단계의 완료 확인).
 *
 * **글을 쓰던 중의 중단과 다른 길이다.** 그쪽(RealClaudeChatConversationIntegrationTest)은
 * `aborted_streaming` 으로 끝나고, 이쪽은 대기 중인 도구 호출이 거절된 결과로 닫히며 `aborted_tools`
 * 로 끝난다(3.2). 어댑터가 하나만 알면 이 턴이 실패로 그려진다.
 *
 * **argv 를 엔진에게 묻지 않고 여기서 짓는다.** 엔진이 조립하는 메인 세션에는 오래 걸리는 도구를
 * 마음대로 부를 자리가 없다. 그래서 설계 부록 B 의 프로브 — `seconds` 만큼 기다리는 도구 하나뿐인
 * stdio MCP 서버 — 를 엔진의 챗 모드와 같은 stream-json 플래그로 붙인다. 이 시험이 재는 것은 엔진의
 * 조립이 아니라 **어댑터와 상태 홀더가 그 스트림을 어떻게 읽는가** 다.
 *
 * **부르면 토큰을 쓴다.** 다른 실 claude 검증과 같은 변수로 일부러 켜야 돈다. python3 도 필요하다.
 *
 * ```
 * KYU_CLAUDE_CHAT_INTEGRATION=1 ./gradlew test --tests '*RealClaudeToolWaitInterruptIntegrationTest*'
 * ```
 */
class RealClaudeToolWaitInterruptIntegrationTest {

    private val temporaryDirectory = createTempDirectory("kyu-real-tool-wait-interrupt-test")

    /** 앱에서 이 홀더에 닿는 스코프는 화면을 그리는 단일 스레드다(Main.kt). 같은 전제를 지킨다. */
    private val holderThread = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "chat-state-holder-test")
    }
    private val holderScope = CoroutineScope(holderThread.asCoroutineDispatcher())

    @AfterTest
    fun 남은_세션과_임시_디렉토리를_치운다() = runBlocking {
        holderScope.cancel()
        holderThread.shutdownNow()
        temporaryDirectory.toFile().deleteRecursively()
        Unit
    }

    @Test
    fun `도구를 기다리던 중에 끊어도 같은 대화가 다음 말에 답한다`(): Unit = runBlocking {
        val holder = stateHolderOrSkip()

        holder.enterSession(temporaryDirectory, SessionTarget.Main, SessionConversationChoice.ContinueRecordedConversation)
        waitUntil(holder) { true }

        holder.sendUserMessage(
            SessionTarget.Main,
            "Call the mcp__probe__slow tool once with label \"$PROBE_LABEL\" and seconds $PROBE_SECONDS. " +
                "After it returns, reply with exactly: TURN1-DONE",
        )
        waitUntil(holder) { conversation -> conversation.slowCallWaiting() }
        // 호출이 스트림에 실린 순간과 서버가 그것을 받는 순간 사이가 짧게나마 있다. 서버에 닿기 전에
        // 끊으면 이 시험이 재는 것이 "도구 대기 중" 이 아니게 된다.
        delay(TOOL_CALL_SETTLE_MILLIS)

        holder.interruptTurn(SessionTarget.Main)
        waitUntil(holder) { conversation -> conversation.finishedTurns().size == 1 }

        val afterInterrupt = onScreenConversation(holder)
        assertEquals(listOf(TurnOutcome.InterruptedDuringToolWait), afterInterrupt.finishedTurns())
        assertEquals(TurnState.Idle, afterInterrupt.turnState)
        assertTrue(afterInterrupt.alive, "중단이 프로세스를 죽였습니다")
        assertEquals(
            true,
            afterInterrupt.entries.filterIsInstance<ChatEntry.ToolCall>().single { it.toolName == SLOW_TOOL }.answer?.failed,
            "끊긴 도구 호출은 거절된 결과로 닫혀야 한다(3.2)",
        )

        holder.sendUserMessage(SessionTarget.Main, "Did the slow tool finish? Reply with exactly: NOT-FINISHED or FINISHED")
        waitUntil(holder) { conversation -> conversation.finishedTurns().size == 2 }

        val conversation = onScreenConversation(holder)
        holder.endSession(SessionTarget.Main)

        assertEquals(
            listOf(TurnOutcome.InterruptedDuringToolWait, TurnOutcome.Completed),
            conversation.finishedTurns(),
        )
        val answerAfterInterrupt = conversation.entries
            .dropWhile { it !is ChatEntry.TurnEnded }
            .filterIsInstance<ChatEntry.AssistantSaid>()
            .joinToString("\n") { it.text }
        assertTrue("FINISHED" in answerAfterInterrupt, "끊긴 뒤의 말에 답하지 않았습니다. 받은 답: $answerAfterInterrupt")

        // 앱이 정한 uuid 가 진짜 claude 를 한 바퀴 돌아와 두 말을 모두 놓았다(session-tree 3.5 (다)).
        // 하나라도 남으면 화면 아래에 영영 "대기 중" 이 붙는다.
        assertEquals(emptyList(), conversation.pendingUserMessages)
    }

    private fun stateHolderOrSkip(): ChatSessionStateHolder {
        if (System.getenv(OPT_IN_VARIABLE) != "1") {
            abort<Unit>("$OPT_IN_VARIABLE=1 이 아니라 건너뜁니다 — 이 검증은 진짜 claude 를 부릅니다")
        }
        requireCommandRuns(listOf("claude", "--version"))
        requireCommandRuns(listOf("python3", "--version"))

        val probeServer = temporaryDirectory.resolve("slow_probe_mcp_server.py")
            .apply { writeText(SLOW_PROBE_MCP_SERVER) }

        return ChatSessionStateHolder(
            sessionCommandSource = ProbeSessionCommandSource(probeServer),
            chatSessionOpener = ProcessChatSessionOpener(),
            // 이 시험의 메인은 프로브 서버만 달고 떠 세션 트리 도구가 없다 — 하위를 띄우는 길에 들면 그것이 결함이다.
            childSessionCommandSource = { _, request, _ -> error("이 시험은 하위를 띄우지 않습니다: ${request.requestedCwd}") },
            claudeAuthToken = { null },
            coroutineScope = holderScope,
        )
    }

    private fun requireCommandRuns(command: List<String>) {
        val runs = runCatching {
            ProcessBuilder(command).redirectErrorStream(true).start().waitFor() == 0
        }.getOrDefault(false)

        if (!runs) {
            abort<Unit>("${command.first()} 를 부르지 못해 건너뜁니다")
        }
    }

    private suspend fun waitUntil(holder: ChatSessionStateHolder, condition: (ChatConversation) -> Boolean) =
        withTimeout(TURN_TIMEOUT_MILLIS) {
            while (true) {
                val onScreen = holder.state.value
                if (onScreen is ChatScreenState.ChatOnScreen && condition(onScreen.conversation)) {
                    break
                }
                delay(POLL_INTERVAL_MILLIS)
            }
        }

    private fun onScreenConversation(holder: ChatSessionStateHolder): ChatConversation =
        assertIs<ChatScreenState.ChatOnScreen>(holder.state.value).conversation

    private fun ChatConversation.slowCallWaiting(): Boolean = turnState == TurnState.Running &&
        entries.any { it is ChatEntry.ToolCall && it.toolName == SLOW_TOOL && it.answer == null }

    private fun ChatConversation.finishedTurns(): List<TurnOutcome> =
        entries.filterIsInstance<ChatEntry.TurnEnded>().map { it.outcome }

    /**
     * 프로브 서버 하나만 붙인 `claude` 를 답한다(설계 부록 B 의 구동기와 같은 플래그).
     *
     * `--setting-sources ""` · `--strict-mcp-config` 는 시험을 돌리는 사람의 전역 설정과 다른 MCP
     * 서버가 섞이지 않게 한다. 앱 소켓은 쓰지 않는다 — 프로브 도구는 `--allowedTools` 로 허용했다.
     */
    private class ProbeSessionCommandSource(private val probeServer: Path) : SessionCommandSource {

        override fun sessionCommandFor(
            workDirPath: Path,
            target: PersonOpenedSessionTarget,
            conversationChoice: SessionConversationChoice,
            appSocketPath: Path?,
        ): SessionCommandAnswer {
            val mcpConfig = buildJsonObject {
                putJsonObject("mcpServers") {
                    putJsonObject("probe") {
                        put("type", "stdio")
                        put("command", "python3")
                        putJsonArray("args") { add(probeServer.toString()) }
                    }
                }
            }

            return SessionCommandAnswer(
                command = listOf(
                    "claude", "-p",
                    "--input-format", "stream-json",
                    "--output-format", "stream-json",
                    "--verbose",
                    "--include-partial-messages",
                    "--replay-user-messages",
                    "--setting-sources", "",
                    "--strict-mcp-config",
                    "--mcp-config", mcpConfig.toString(),
                    "--allowedTools", SLOW_TOOL,
                    "--model", "sonnet",
                ),
                workingDirectory = workDirPath,
                environmentToAdd = emptyMap(),
                resumedConversationId = null,
                engineWarnings = emptyList(),
            )
        }
    }

    private companion object {

        const val OPT_IN_VARIABLE = "KYU_CLAUDE_CHAT_INTEGRATION"

        const val SLOW_TOOL = "mcp__probe__slow"

        const val PROBE_LABEL = "M"

        /** 끊기 전에 끝나지 않을 만큼 길게. 끝나 버리면 이 시험은 중단이 아니라 정상 턴을 잰다. */
        const val PROBE_SECONDS = 120

        const val TOOL_CALL_SETTLE_MILLIS = 3_000L

        const val TURN_TIMEOUT_MILLIS = 180_000L

        const val POLL_INTERVAL_MILLIS = 200L

        /**
         * 설계 부록 B 의 프로브 서버를 이 시험에 필요한 만큼 줄인 것 — 줄 단위 JSON-RPC, 도구 하나.
         *
         * 호출마다 스레드 하나에서 기다리고, `notifications/cancelled` 를 받으면 답하지 않고 접는다.
         * 모르는 메서드(`server/discover` 따위)에는 빈 결과로 답한다 — 2.1.304 가 `initialize` 전에
         * 그것을 보내는데, 빈 결과로도 붙는 것을 실측이 확인했다(부록 A).
         */
        val SLOW_PROBE_MCP_SERVER = """
            import sys, json, threading, time
            lock = threading.Lock()
            cancelled = set()
            def send(obj):
                with lock:
                    sys.stdout.write(json.dumps(obj) + "\n"); sys.stdout.flush()
            TOOLS = [{"name": "slow", "description": "Waits the given seconds then returns the label.",
              "inputSchema": {"type": "object", "properties": {"label": {"type": "string"}, "seconds": {"type": "number"}},
                              "required": ["label", "seconds"]}}]
            def run_slow(rid, args):
                end = time.time() + float(args.get("seconds", 1))
                while time.time() < end:
                    if rid in cancelled:
                        return
                    time.sleep(0.2)
                send({"jsonrpc": "2.0", "id": rid, "result": {"content": [{"type": "text", "text": "slow done: " + str(args.get("label"))}]}})
            for line in sys.stdin:
                try:
                    msg = json.loads(line)
                except Exception:
                    continue
                method = msg.get("method"); rid = msg.get("id")
                if method == "initialize":
                    send({"jsonrpc": "2.0", "id": rid, "result": {"protocolVersion": msg["params"].get("protocolVersion", "2025-06-18"),
                        "capabilities": {"tools": {}}, "serverInfo": {"name": "probe", "version": "0"}}})
                elif method == "tools/list":
                    send({"jsonrpc": "2.0", "id": rid, "result": {"tools": TOOLS}})
                elif method == "tools/call":
                    threading.Thread(target=run_slow, args=(rid, msg["params"].get("arguments", {})), daemon=True).start()
                elif method == "notifications/cancelled":
                    cancelled.add(msg["params"].get("requestId"))
                elif rid is not None and method:
                    send({"jsonrpc": "2.0", "id": rid, "result": {}})
        """.trimIndent()
    }
}
