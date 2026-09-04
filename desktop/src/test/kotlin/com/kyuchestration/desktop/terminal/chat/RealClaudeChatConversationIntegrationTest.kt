package com.kyuchestration.desktop.terminal.chat

import com.kyuchestration.desktop.kyu.realKyuCommandRunnerOrSkip
import com.kyuchestration.desktop.terminal.SessionConversationChoice
import com.kyuchestration.desktop.terminal.SessionMode
import com.kyuchestration.desktop.terminal.SessionTarget
import com.kyuchestration.desktop.terminal.kyucli.KyuCliSessionCommandSource
import java.util.concurrent.Executors
import kotlin.io.path.createTempDirectory
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
import org.junit.jupiter.api.Assumptions.abort

/**
 * 진짜 `claude` 를 띄워, 두 턴의 말이 상태 홀더의 전사에 쌓이는 것을 확인한다
 * (chat-ui-design.md 9 절 3 단계의 완료 확인).
 *
 * **어댑터 검증(RealClaudeChatSessionIntegrationTest)이 답하지 못하는 물음이 여기 있다.** 그쪽은
 * 이벤트가 오는 것까지 본다. 이쪽은 그 이벤트가 화면에 그려질 대화가 되는지를 본다 — 특히
 * **턴마다 오는 `system/init` 이 앞 턴의 전사를 지우지 않는지**는 진짜 두 턴을 돌려야 갈린다.
 * 녹화 자료로도 같은 것을 보지만(ChatConversationFoldingTest), 그 자료가 진짜와 어긋나는 날
 * 이 검증만 그것을 알아챈다.
 *
 * **부르면 토큰을 쓴다.** 그래서 환경 변수로 일부러 켜야 돈다 — 어댑터 검증과 같은 규율이다.
 *
 * ```
 * KYU_CLAUDE_CHAT_INTEGRATION=1 KYU_BINARY_PATH=<이 브랜치에서 빌드한 kyu> \
 *   ./gradlew test --tests '*RealClaudeChatConversationIntegrationTest*'
 * ```
 */
class RealClaudeChatConversationIntegrationTest {

    private val temporaryDirectory = createTempDirectory("kyu-real-chat-conversation-test")

    /**
     * 앱에서 이 홀더에 닿는 스코프는 화면을 그리는 단일 스레드다(Main.kt). 여기서도 스레드 하나로
     * 두는 것은 흉내가 아니라 같은 전제를 지키는 것이다 — 여럿으로 두면 이 검증이 앱에 없는
     * 경합을 만들어 놓고 그 결과를 본다.
     */
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
    fun `두 턴의 말풍선이 쌓이고 비용이 붙는다`(): Unit = runBlocking {
        val holder = stateHolderOrSkip()

        holder.enterSession(temporaryDirectory, SessionTarget.Main, SessionConversationChoice.ContinueRecordedConversation)
        waitUntilChatIsOnScreen(holder)

        holder.sendUserMessage("이 대화의 암호는 $CONVERSATION_PASSPHRASE 다. 알겠다고만 답해.")
        waitForFinishedTurns(holder, turnCount = 1)

        holder.sendUserMessage("이 대화의 암호가 뭐였지? 암호만 답해.")
        waitForFinishedTurns(holder, turnCount = 2)

        val conversation = onScreenConversation(holder)
        holder.endOnScreenSession()

        // 사용자의 말 둘이 그대로 남아 있다. 턴마다 오는 system/init 이 전사를 비웠다면 여기서
        // 첫 말이 사라진다 — 이 앱이 가장 쉽게 저지를 수 있었던 결함이다(3.1).
        assertEquals(
            listOf(
                "이 대화의 암호는 $CONVERSATION_PASSPHRASE 다. 알겠다고만 답해.",
                "이 대화의 암호가 뭐였지? 암호만 답해.",
            ),
            conversation.entries.filterIsInstance<ChatEntry.UserSaid>().map { it.text },
        )

        // 첫 턴에서만 알려준 낱말이다. 프로세스를 턴마다 새로 띄웠다면 둘째 턴은 이것을 모른다.
        val answersAfterFirstTurn = conversation.entries
            .dropWhile { it !is ChatEntry.TurnEnded }
            .filterIsInstance<ChatEntry.AssistantSaid>()
            .joinToString("\n") { it.text }
        assertTrue(
            CONVERSATION_PASSPHRASE in answersAfterFirstTurn,
            "둘째 턴이 첫 턴의 암호를 기억하지 못했습니다. 받은 답: $answersAfterFirstTurn",
        )

        val turnBadges = conversation.entries.filterIsInstance<ChatEntry.TurnEnded>()
        assertEquals(2, turnBadges.size)
        assertTrue(turnBadges.all { it.outcome == TurnOutcome.Completed }, "턴이 잘 끝나야 한다: $turnBadges")
        assertTrue(conversation.totalCostUsd > 0, "비용 배지가 그릴 값이 없습니다")
        assertTrue(turnBadges.all { it.durationMillis > 0 }, "소요 시간이 배지에 실리지 않았습니다")

        // 입력창의 잠금이 풀려 있어야 다음 말을 걸 수 있다.
        assertEquals(TurnState.Idle, conversation.turnState)
    }

    /**
     * 9 절 4 단계의 완료 확인 — **긴 답 도중 끊으면 턴만 끊기고 다음 말을 걸면 답한다**(3.8).
     *
     * 홀더까지 통과시켜야 답이 나오는 물음이다. 어댑터만 보면 `control_request` 가 나갔는지까지
     * 알 수 있지만, 화면이 실제로 기다리는 것은 그다음이다 — 잠금이 풀리고, 배지가 중단으로
     * 서고, 같은 프로세스가 다음 말을 받는가.
     */
    @Test
    fun `긴 답을 끊어도 같은 대화가 다음 말에 답한다`(): Unit = runBlocking {
        val holder = stateHolderOrSkip()

        holder.enterSession(temporaryDirectory, SessionTarget.Main, SessionConversationChoice.ContinueRecordedConversation)
        waitUntilChatIsOnScreen(holder)

        holder.sendUserMessage("$INTERRUPTED_TOPIC 에 대해 아주 길게, 열 문단으로 설명해라.")
        // 글자가 흐르기 시작한 뒤에 끊는다. 그 전에 보내면 아직 시작하지 않은 턴에 대고 말하는
        // 것이라, 이 검증이 무엇을 쟀는지가 흐려진다.
        waitUntilTheAnswerIsFlowing(holder)

        holder.interruptOnScreenTurn()
        waitForFinishedTurns(holder, turnCount = 1)

        val afterInterrupt = onScreenConversation(holder)
        assertEquals(
            TurnOutcome.Interrupted,
            afterInterrupt.entries.filterIsInstance<ChatEntry.TurnEnded>().single().outcome,
        )
        // 잘린 문장이 화면에 남으면 다음 턴의 답 위에 계속 떠 있는다(5.3.2).
        assertEquals(null, afterInterrupt.streamingText)
        assertEquals(TurnState.Idle, afterInterrupt.turnState)
        assertTrue(afterInterrupt.alive, "중단이 프로세스를 죽였습니다 — 그러면 이것은 세션 끝내기 버튼이다")

        holder.sendUserMessage("방금 무슨 주제를 쓰고 있었지? 그 낱말만 답해.")
        waitForFinishedTurns(holder, turnCount = 2)

        val conversation = onScreenConversation(holder)
        holder.endOnScreenSession()

        val answerAfterInterrupt = conversation.entries
            .dropWhile { it !is ChatEntry.TurnEnded }
            .filterIsInstance<ChatEntry.AssistantSaid>()
            .joinToString("\n") { it.text }
        assertTrue(
            INTERRUPTED_TOPIC in answerAfterInterrupt,
            "끊긴 대화가 앞 맥락을 잃었습니다. 받은 답: $answerAfterInterrupt",
        )
    }

    private fun stateHolderOrSkip(): ChatSessionStateHolder {
        if (System.getenv(OPT_IN_VARIABLE) != "1") {
            abort<Unit>("$OPT_IN_VARIABLE=1 이 아니라 건너뜁니다 — 이 검증은 진짜 claude 를 부릅니다")
        }
        requireClaudeIsInstalled()

        return ChatSessionStateHolder(
            sessionCommandSource = KyuCliSessionCommandSource(realKyuCommandRunnerOrSkip(), SessionMode.Chat),
            chatSessionOpener = ProcessChatSessionOpener(),
            coroutineScope = holderScope,
        )
    }

    private fun requireClaudeIsInstalled() {
        val installed = runCatching {
            ProcessBuilder(listOf("claude", "--version")).redirectErrorStream(true).start().waitFor() == 0
        }.getOrDefault(false)

        if (!installed) {
            abort<Unit>("claude 를 부르지 못해 건너뜁니다")
        }
    }

    private suspend fun waitUntilChatIsOnScreen(holder: ChatSessionStateHolder) =
        withTimeout(TURN_TIMEOUT_MILLIS) {
            while (holder.state.value !is ChatScreenState.ChatOnScreen) {
                delay(POLL_INTERVAL_MILLIS)
            }
        }

    /** 모델의 글자가 실제로 흐르기 시작할 때까지. 조각이든 완성본이든 하나라도 오면 그 턴은 살아 있다. */
    private suspend fun waitUntilTheAnswerIsFlowing(holder: ChatSessionStateHolder) =
        withTimeout(TURN_TIMEOUT_MILLIS) {
            while (onScreenConversation(holder).let { conversation ->
                    conversation.streamingText == null &&
                        conversation.entries.none { it is ChatEntry.AssistantSaid }
                }
            ) {
                delay(POLL_INTERVAL_MILLIS)
            }
        }

    private suspend fun waitForFinishedTurns(holder: ChatSessionStateHolder, turnCount: Int) =
        withTimeout(TURN_TIMEOUT_MILLIS) {
            while (onScreenConversation(holder).entries.filterIsInstance<ChatEntry.TurnEnded>().size < turnCount) {
                delay(POLL_INTERVAL_MILLIS)
            }
        }

    private fun onScreenConversation(holder: ChatSessionStateHolder): ChatConversation =
        assertIs<ChatScreenState.ChatOnScreen>(holder.state.value).conversation

    private companion object {

        /** 이 검증을 일부러 켜는 자리. 어댑터 검증과 같은 이름을 쓴다 — 켜는 이유가 같다. */
        const val OPT_IN_VARIABLE = "KYU_CLAUDE_CHAT_INTEGRATION"

        const val CONVERSATION_PASSPHRASE = "KYUCHAT-2026"

        /** 끊긴 턴 뒤에도 대화가 살아 있는지를 가르는 낱말. 앞 턴에서만 나온 것이라야 근거가 된다. */
        const val INTERRUPTED_TOPIC = "헥사고날"

        /**
         * 한 턴을 기다리는 시간.
         *
         * 넉넉하다. 이 턴에는 모델 왕복만이 아니라 메인 세션에 등록된 오케스트레이션 MCP 서버의
         * 기동까지 들어 있다(엔진이 답한 argv 그대로다).
         */
        const val TURN_TIMEOUT_MILLIS = 180_000L

        const val POLL_INTERVAL_MILLIS = 200L
    }
}
