package com.kyuchestration.desktop.terminal.chat

import com.kyuchestration.desktop.kyu.realKyuCommandRunnerOrSkip
import com.kyuchestration.desktop.terminal.APP_SESSION_MARKER_ENV_NAME
import com.kyuchestration.desktop.terminal.SessionConversationChoice
import com.kyuchestration.desktop.terminal.SessionTarget
import com.kyuchestration.desktop.terminal.kyucli.KyuCliChildSessionCommandSource
import com.kyuchestration.desktop.terminal.kyucli.KyuCliSessionCommandSource
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assumptions.abort

/**
 * 진짜 `claude` 메인에게 하위를 띄우게 하고 앱이 그것을 쥐고 거두고, 둘 사이의 말을 나르는지 본다(session-tree-design.md
 * 7 절 4 단계 완료 확인 — ① ④ ⑤ 는 4a, ② ③ 과 ① 의 기다림은 4b 몫).
 *
 * | 확인 | 이 시험이 보는 것 |
 * |---|---|
 * | ① 하위 둘이 동시에 돈다 | 메인의 한 턴 안에서 띄운 두 하위의 `claude` 가 이 JVM 의 자식으로 함께 살아 있고, 각자 첫 턴을 끝낸다. 시각은 각 프로세스의 시작 시각(`ProcessHandle.info`)이다 |
 * | ① 둘의 결과를 기다림으로 받는다 | 메인이 `await_session` 으로 두 하위의 `turn_completed` 를 다 받는다. 몇 번의 기다림이 걸렸는지 적는다 |
 * | ② 기다리는 상위에게 하위가 묻는다 | 메인이 `await_session` 중에 하위가 `ask_parent` 로 묻고, 그 기다림이 `asked_parent` 를 담아 곧바로 끝나며, 메인의 `send_to_session` 이 하위의 물음의 답이 된다 |
 * | ③ 기다리는 상위에게 사람이 말한다 | 메인의 두 번째 `await_session`(900 초)이 도는 중에 사람이 메인에게 말하면 그 기다림이 `user_spoke` 로 곧바로 끝난다 |
 * | ④ 메인을 닫으면 하위가 사라진다 | 메인을 끝낸 뒤 `KYU_APP_SESSION` 표식이 이 워크디렉토리인 프로세스가 `/proc` 에 하나도 없다 — `ps` 와 같은 자리를 본다 |
 * | ⑤ 걸리는 설정이 있는 폴더 | 메인이 `.mcp.json` 이 있는 폴더에 띄우려 하면 관문 물음이 서고, 거절하면 하위가 서지 않는다 |
 * | 하위의 이름은 엔진이 짓는다 | 기록에 앞 실행의 하위(child-1 · child-2)가 있으면 엔진이 child-3 을 짓고, 앱의 트리 · 하위 챗 · 프로세스 표식 · 메인이 받은 답이 모두 그 이름이다 |
 *
 * "곧바로" 는 스트림이 준 시각으로 잰다 — 기다림 도구 카드의 호출·결과 시각(ChatEntry.ToolCall · ToolCallAnswer)이다.
 * 기다림의 시간 상한(600 · 900 초)보다 훨씬 짧으면 사건이 깨운 것이다.
 *
 * **부르면 토큰을 쓴다.** 다른 챗 통합 검증과 같은 규율로 일부러 켜야 돈다.
 *
 * ```
 * KYU_CLAUDE_CHAT_INTEGRATION=1 KYU_BINARY_PATH=<이 브랜치에서 빌드한 kyu> \
 *   ./gradlew test --tests '*RealClaudeSessionTreeIntegrationTest*'
 * ```
 *
 * **승인 카드는 이 시험이 허용으로 답한다** — 메인의 것도 하위의 것도. `spawn_session` · `ask_parent` 가 사람의 승인을
 * 거치는지는 이 머신의 설정이 정하고, 이 시험이 재려는 것은 그 뒤다.
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
    fun `기록에 앞 하위가 있으면 엔진이 그 뒤 이름을 짓고 트리와 화면이 그 이름을 쓴다`(): Unit = runBlocking {
        // 앱은 이름을 묻지 않는다(--child 에 id 없음). 앱이 따로 셌다면 메모리의 수가 0 이라 child-1 을 골랐을 자리다.
        val holder = stateHolderOrSkip()
        workDirPath.resolve(".coord").createDirectories().resolve("conversations.json").writeText(
            """
            {
              "schemaVersion": 1,
              "conversations": {},
              "delegations": {},
              "childSessions": {
                "main": {
                  "child-1": {"conversationId": "11111111-1111-4111-8111-111111111111", "cwd": "proj-old", "title": "proj-old",
                              "startedAt": "2026-10-20T10:01:12Z", "endedAt": "2026-10-20T10:20:03Z"},
                  "child-2": {"conversationId": "22222222-2222-4222-8222-222222222222", "cwd": "proj-old", "title": "proj-old",
                              "startedAt": "2026-10-20T10:01:14Z", "endedAt": "2026-10-20T10:20:03Z"}
                }
              }
            }
            """.trimIndent(),
        )
        openMain(holder)

        onHolder {
            holder.sendUserMessage(
                "mcp__kyu__spawn_session 도구를 한 번 불러 cwd 'proj-new', createDirectory true, prompt '숫자 1 만 답해' 로 " +
                    "하위 세션을 띄워라. 띄운 뒤에는 기다리지 말고 그 도구 결과의 id 값만 한 줄로 답해.",
            )
        }

        waitUntil("하위가 트리에 서기를", holder) { holder.sessionTree.value.children.isNotEmpty() }
        assertEquals(listOf("child-3"), holder.sessionTree.value.children.map { it.childId })
        assertEquals(SessionTarget.Child("child-3"), conversationOn(holder, SessionTarget.Child("child-3")).target)
        assertTrue(directChildProcessMarked(sessionMarker("child-3"))?.isAlive == true, "child-3 표식을 단 claude 가 없습니다")

        waitUntil("메인의 턴이 끝나기를", holder) {
            onScreenConversation(holder).entries.any { it is ChatEntry.TurnEnded }
        }
        val spawnAnswer = conversationOn(holder, SessionTarget.Main).entries.toolCallsNamed(SPAWN_SESSION_TOOL_NAME).single()
            .answer?.modelVisibleText.orEmpty()
        println("spawn_session 의 답: $spawnAnswer")
        assertTrue("child-3" in spawnAnswer, "메인이 받은 spawn 답에 엔진이 지은 이름이 없습니다: $spawnAnswer")
        onHolder { holder.endOnScreenSession() }
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

        onHolder { holder.answerGateQuestion(question, approved = false) }

        waitUntil("메인의 턴이 끝나기를", holder) {
            onScreenConversation(holder).entries.any { it is ChatEntry.TurnEnded }
        }
        assertEquals(emptyList(), holder.sessionTree.value.children)
        onHolder { holder.endOnScreenSession() }
    }

    @Test
    fun `메인이 기다리는 중에 하위가 물으면 그 기다림이 곧바로 끝나고 메인의 send 가 그 물음의 답이 된다`(): Unit = runBlocking {
        // 완료 확인 ② — 2 절의 교착이 일어나지 않는다.
        val holder = stateHolderOrSkip()
        openMain(holder)

        onHolder {
            holder.sendUserMessage(
                "다음 순서를 정확히 지켜라. " +
                    "1) mcp__kyu__spawn_session 을 cwd 'proj-ask', createDirectory true 로 불러 하위를 띄워라. 그 prompt 는 다음 문장 그대로다: " +
                    "'다른 일을 하기 전에 먼저 mcp__kyu__ask_parent 도구로 \"테스트 DB 포트는 5432 와 5433 중 어느 쪽인가요?\" 라고 물어라. " +
                    "답을 받으면 그 포트 번호 숫자만 답하고 끝내라.' " +
                    "2) 띄운 뒤 곧바로 mcp__kyu__await_session 을 waitSeconds 600 으로 불러라. " +
                    "3) 하위가 물었다는 사건(asked_parent)을 받으면 mcp__kyu__send_to_session 으로 그 하위에게 '5433' 이라고만 답하라. " +
                    "4) 다시 mcp__kyu__await_session 을 불러 그 하위의 턴이 끝나기를 기다려라. " +
                    "5) 끝나면 하위가 답한 것을 한 줄로만 답하라.",
            )
        }

        waitUntil("하위가 물음의 답을 받고 턴을 끝내기를", holder) {
            holder.sessionTree.value.child("child-1")?.lastTurnResult != null
        }

        val mainEntries = conversationOn(holder, SessionTarget.Main).entries
        val wokenByQuestion = mainEntries.toolCallsNamed(AWAIT_SESSION_TOOL_NAME)
            .firstOrNull { it.answer?.modelVisibleText.orEmpty().contains("asked_parent") }
            ?: throw AssertionError("asked_parent 를 담은 기다림이 없습니다. 메인의 전사: $mainEntries")
        val childEntries = conversationOn(holder, SessionTarget.Child("child-1")).entries
        val question = childEntries.toolCallsNamed(ASK_PARENT_TOOL_NAME).single()

        val askedAt = question.requestedAt ?: error("물음의 호출 시각이 스트림에 없습니다")
        val awaitStartedAt = wokenByQuestion.requestedAt ?: error("기다림의 호출 시각이 스트림에 없습니다")
        val awaitEndedAt = wokenByQuestion.answer?.answeredAt ?: error("기다림의 결과 시각이 스트림에 없습니다")
        println(
            "② 기다림 시작 $awaitStartedAt · 하위가 물은 때 $askedAt · 기다림이 끝난 때 $awaitEndedAt " +
                "(물음 → 깨어남 ${Duration.between(askedAt, awaitEndedAt).toMillis()} ms, 기다린 시간 " +
                "${Duration.between(awaitStartedAt, awaitEndedAt).toMillis()} ms)",
        )
        assertTrue(awaitStartedAt.isBefore(askedAt), "메인이 기다리기 전에 하위가 물었습니다 — 이 시험이 재려는 자리가 아닙니다")
        assertTrue(
            Duration.between(askedAt, awaitEndedAt) < WOKEN_PROMPTLY,
            "하위가 물은 뒤 기다림이 곧바로 끝나지 않았습니다 (${Duration.between(askedAt, awaitEndedAt)})",
        )
        // 메인의 send 가 하위의 물음의 답이 됐다 — 하위의 stdin 으로 가지 않았다.
        assertTrue(question.answer?.modelVisibleText.orEmpty().contains("5433"), "하위의 물음이 받은 것: ${question.answer}")
        assertTrue(
            mainEntries.toolCallsNamed(SEND_TO_SESSION_TOOL_NAME).any { it.answer?.modelVisibleText.orEmpty().contains("as_answer") },
            "send_to_session 이 as_answer 로 가지 않았습니다. 메인의 전사: $mainEntries",
        )
        assertTrue(holder.sessionTree.value.child("child-1")?.lastTurnResult?.result.orEmpty().contains("5433"))

        onHolder { holder.endOnScreenSession() }
    }

    @Test
    fun `메인이 기다리는 중에 사람이 메인에게 말하면 그 기다림이 user_spoke 로 곧바로 끝난다`(): Unit = runBlocking {
        // 완료 확인 ③ — 사람이 하위를 기다리는 상위와 대화할 수 있다.
        val holder = stateHolderOrSkip()
        openMain(holder)

        onHolder {
            holder.sendUserMessage(
                "다음 순서를 정확히 지켜라. " +
                    "1) mcp__kyu__spawn_session 을 cwd 'proj-idle', createDirectory true, prompt '숫자 1 만 답해' 로 불러라. " +
                    "2) mcp__kyu__await_session 으로 그 하위의 턴이 끝나기를 기다려라. " +
                    "3) 결과를 받으면 mcp__kyu__await_session 을 waitSeconds 900 으로 한 번 더 불러 기다려라. " +
                    "4) 그 두 번째 기다림이 끝나면, 받은 사건의 type 과 사람이 한 말을 한 줄로 답하라.",
            )
        }

        waitUntil("메인이 두 번째 기다림에 들어가기를", holder) {
            val awaits = conversationOn(holder, SessionTarget.Main).entries.toolCallsNamed(AWAIT_SESSION_TOOL_NAME)
            awaits.size >= 2 && awaits.last().answer == null
        }
        // 기다림이 앱에 닿을 틈을 준다. 도구 카드는 claude 가 호출을 낸 때 서고, 앱 소켓의 줄은 그 뒤에 온다.
        delay(SECOND_AWAIT_SETTLE_MILLIS)

        val spokeAt = Instant.now()
        onHolder { holder.sendUserMessage("나는 사람이다. 기다림을 멈추고 USER-SPOKE-SEEN 이라고 답해.") }

        waitUntil("두 번째 기다림이 끝나기를", holder) {
            conversationOn(holder, SessionTarget.Main).entries.toolCallsNamed(AWAIT_SESSION_TOOL_NAME).last().answer != null
        }
        val secondAwait = conversationOn(holder, SessionTarget.Main).entries.toolCallsNamed(AWAIT_SESSION_TOOL_NAME).last()
        val wokenAt = secondAwait.answer?.answeredAt ?: error("기다림의 결과 시각이 스트림에 없습니다")
        println("③ 사람이 말한 때 $spokeAt · 기다림이 끝난 때 $wokenAt (${Duration.between(spokeAt, wokenAt).toMillis()} ms)")

        assertTrue(secondAwait.answer?.modelVisibleText.orEmpty().contains("user_spoke"), "두 번째 기다림의 결과: ${secondAwait.answer}")
        assertTrue(Duration.between(spokeAt, wokenAt) < WOKEN_PROMPTLY, "사람이 말한 뒤 기다림이 곧바로 끝나지 않았습니다")

        waitUntil("메인이 사람의 말에 답하기를", holder) {
            conversationOn(holder, SessionTarget.Main).entries.filterIsInstance<ChatEntry.AssistantSaid>()
                .any { it.text.contains("USER-SPOKE-SEEN") }
        }
        onHolder { holder.endOnScreenSession() }
    }

    @Test
    fun `메인이 띄운 하위 둘의 결과를 기다림으로 받는다`(): Unit = runBlocking {
        // 완료 확인 ① 의 4b 몫 — 둘을 한 호출이 기다린다(3.3 · 6.6).
        val holder = stateHolderOrSkip()
        openMain(holder)

        onHolder {
            holder.sendUserMessage(
                "mcp__kyu__spawn_session 도구를 두 번 불러 하위 세션 둘을 띄워라. " +
                    "첫째는 cwd 'proj-a', createDirectory true, prompt '숫자 1 만 답해'. " +
                    "둘째는 cwd 'proj-b', createDirectory true, prompt '숫자 2 만 답해'. " +
                    "그다음 mcp__kyu__await_session 을 ids 없이 불러라. 두 하위의 turn_completed 를 모두 받을 때까지 필요하면 " +
                    "다시 불러라. 다 받으면 'A=<첫째의 결과> B=<둘째의 결과>' 한 줄로만 답해.",
            )
        }

        waitUntil("메인이 두 결과를 답하기를", holder) {
            conversationOn(holder, SessionTarget.Main).entries.filterIsInstance<ChatEntry.AssistantSaid>()
                .any { it.text.contains("A=") && it.text.contains("B=") }
        }

        val awaitAnswers = conversationOn(holder, SessionTarget.Main).entries.toolCallsNamed(AWAIT_SESSION_TOOL_NAME)
            .mapNotNull { it.answer?.modelVisibleText }
            // 거절은 문장 하나로 온다(relaySessionTreeRequest). 사건을 담은 답만 JSON 이다.
            .filter { it.trimStart().startsWith("{") }
        println("① 기다림 ${awaitAnswers.size} 번: $awaitAnswers")
        val completedChildIds = awaitAnswers.flatMap(::turnCompletedChildIds).toSet()
        assertEquals(setOf("child-1", "child-2"), completedChildIds)

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
     * 조건이 설 때까지 기다린다. 그 사이 걸린 승인 카드는 허용으로 답한다 — 사람이 앉아 있는 자리다. 하위의 카드도 답한다:
     * 그 하위의 챗을 잠깐 세우고 답한 뒤 메인으로 돌아온다.
     *
     * 조건은 홀더의 스레드에서 읽는다. 화면을 잠깐 하위로 옮기는 동안 시험 스레드가 메인의 전사를 읽으면 엉뚱한 전사를 본다.
     */
    private suspend fun waitUntil(what: String, holder: ChatSessionStateHolder, condition: suspend () -> Boolean) {
        try {
            withTimeout(TURN_TIMEOUT_MILLIS) {
                while (!condition()) {
                    allowPendingPermissionCards(holder)
                    delay(POLL_INTERVAL_MILLIS)
                }
            }
        } catch (timeout: TimeoutCancellationException) {
            throw AssertionError(
                "$what 기다렸지만 오지 않았습니다. 메인의 전사: ${conversationOn(holder, SessionTarget.Main).entries}",
                timeout,
            )
        }
    }

    private suspend fun allowPendingPermissionCards(holder: ChatSessionStateHolder) = onHolder {
        val childrenAwaitingApproval = holder.sessionTree.value.children
            .filter { it.lifecycle == ChildLifecycle.Running && it.waitingFor == ChildWaitingFor.Approval }
            .map { it.target }
        (listOf(SessionTarget.Main) + childrenAwaitingApproval).forEach { target ->
            showOnHolderThread(holder, target)
            val pending = (holder.state.value as? ChatScreenState.ChatOnScreen)?.conversation?.entries
                ?.filterIsInstance<ChatEntry.PermissionAsked>()
                ?.filter { it.answer == null }
                .orEmpty()
            pending.forEach { card -> holder.answerOnScreenPermission(card.toolUseId, PermissionCardChoice.AllowOnce(card.input)) }
        }
        showOnHolderThread(holder, SessionTarget.Main)
    }

    /** 그 세션의 전사. 화면을 잠깐 그 세션으로 옮겨 읽고 메인으로 돌아온다 — 홀더가 전사를 내놓는 자리가 화면 하나다. */
    private suspend fun conversationOn(holder: ChatSessionStateHolder, target: SessionTarget): ChatConversation =
        withContext(holderScope.coroutineContext) {
            showOnHolderThread(holder, target)
            val conversation = onScreenConversation(holder)
            showOnHolderThread(holder, SessionTarget.Main)
            conversation
        }

    /** 홀더의 스레드에서만 부른다. 메인은 이미 보유 중이라 enterSession 이 다시 띄우지 않고 화면만 옮긴다. */
    private fun showOnHolderThread(holder: ChatSessionStateHolder, target: SessionTarget) {
        when (target) {
            is SessionTarget.Child -> holder.showChildSession(target.childId)
            SessionTarget.Main -> holder.enterSession(workDirPath, SessionTarget.Main, SessionConversationChoice.StartNewConversation)
            else -> error("이 시험은 메인과 하위만 본다: $target")
        }
    }

    /** 기다림의 결과(앱이 답한 줄을 엔진이 들여쓰기만 한 JSON)에서 turn_completed 사건의 하위 이름들을 읽는다. */
    private fun turnCompletedChildIds(awaitAnswerText: String): List<String> =
        (Json.parseToJsonElement(awaitAnswerText).jsonObject["events"]?.jsonArray.orEmpty())
            .map { it.jsonObject }
            .filter { it["type"]?.jsonPrimitive?.content == "turn_completed" }
            .mapNotNull { it["id"]?.jsonPrimitive?.content }

    private fun List<ChatEntry>.toolCallsNamed(toolName: String): List<ChatEntry.ToolCall> =
        filterIsInstance<ChatEntry.ToolCall>().filter { it.toolName == toolName }

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

        /** 두 번째 기다림의 카드가 선 뒤 그 줄이 앱 소켓에 닿기까지의 여유. */
        const val SECOND_AWAIT_SETTLE_MILLIS = 3_000L

        /**
         * "곧바로" 의 선. 기다림의 상한(600 · 900 초)보다 두 자릿수 짧다 — 이 안에 끝났으면 시간이 아니라 사건이 깨운 것이다.
         * 그 사이에 든 것은 엔진의 왕복과 claude 가 도구 결과를 스트림에 싣는 시간이다.
         */
        val WOKEN_PROMPTLY: Duration = Duration.ofSeconds(30)

        const val SPAWN_SESSION_TOOL_NAME = "mcp__kyu__spawn_session"
        const val AWAIT_SESSION_TOOL_NAME = "mcp__kyu__await_session"
        const val SEND_TO_SESSION_TOOL_NAME = "mcp__kyu__send_to_session"
        const val ASK_PARENT_TOOL_NAME = "mcp__kyu__ask_parent"

    }
}
