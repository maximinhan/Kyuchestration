package com.kyuchestration.desktop.terminal.chat

import com.kyuchestration.desktop.kyu.realKyuCommandRunnerOrSkip
import com.kyuchestration.desktop.platform.childProcessEnvironment
import com.kyuchestration.desktop.terminal.SessionConversationChoice
import com.kyuchestration.desktop.terminal.SessionEntryPlan
import com.kyuchestration.desktop.terminal.SessionMode
import com.kyuchestration.desktop.terminal.SessionTarget
import com.kyuchestration.desktop.terminal.kyucli.KyuCliSessionCommandSource
import com.kyuchestration.desktop.terminal.planSessionEntry
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assumptions.abort

/**
 * 진짜 `claude` 를 한 번 띄워, 프로세스 하나가 두 턴을 사는 것을 확인한다(chat-ui-design.md 9 절 2 단계).
 *
 * **이 시험만 답할 수 있는 물음이 있다.** 스텁으로 세운 검증은 "이런 줄이 오면 이렇게 읽는다"
 * 까지만 말한다. 엔진이 답한 argv 로 실제 `claude` 가 뜨는지, 그 프로세스가 첫 답 뒤에도 살아
 * 다음 말을 받는지, 대화가 이어지는지는 진짜를 띄워야 갈린다 — 이 설계의 뼈대가 그 성질이다(3.1).
 *
 * **부르면 토큰을 쓴다.** 그래서 다른 통합 검증(kyu 만 부르는 것들)과 달리 환경 변수로 일부러
 * 켜야 돈다. 키체인 왕복 검증이 같은 자세다(`KYU_KEYCHAIN_INTEGRATION`).
 *
 * ```
 * KYU_CLAUDE_CHAT_INTEGRATION=1 KYU_BINARY_PATH=<이 브랜치에서 빌드한 kyu> \
 *   ./gradlew test --tests '*RealClaudeChatSessionIntegrationTest*'
 * ```
 *
 * 판정은 마커로 한다. 첫 턴에서만 알려준 낱말을 둘째 턴이 답하면, 그 사이에 프로세스도 대화도
 * 살아 있었다는 뜻이다 — 모델이 무엇을 말했는지가 아니라 **그 낱말이 어디서 왔는지**가 근거다.
 */
class RealClaudeChatSessionIntegrationTest {

    private val temporaryDirectory = createTempDirectory("kyu-real-chat-test")

    @AfterTest
    fun 임시_워크디렉토리를_지운다() {
        temporaryDirectory.toFile().deleteRecursively()
    }

    @Test
    fun `한 프로세스가 두 턴을 살고 대화가 이어진다`(): Unit = runBlocking {
        val plan = chatSessionPlanOrSkip()
        val session = ProcessChatSessionOpener().openChatSession(plan)
        val received = mutableListOf<ChatSessionEvent>()
        collectInto(received, session)

        try {
            session.sendUserMessage("이 대화의 암호는 $CONVERSATION_PASSPHRASE 다. 알겠다고만 답해.")
            waitForFinishedTurns(received, turnCount = 1)

            session.sendUserMessage("이 대화의 암호가 뭐였지? 암호만 답해.")
            waitForFinishedTurns(received, turnCount = 2)
        } finally {
            session.endSession()
        }

        // 세션이 무엇인지 스스로 말한다. 이 값이 비어 있으면 3 단계의 화면이 어느 대화를 그리는지
        // 알 수 없다.
        val described = received.filterIsInstance<ChatSessionEvent.SessionDescribed>()
        assertTrue(described.isNotEmpty(), "system/init 이 오지 않았습니다: ${received.take(5)}")
        assertTrue(described.first().conversationId.isNotBlank(), "대화 ID 가 비어 있습니다")

        // 첫 턴에서만 알려준 낱말이다. 프로세스를 턴마다 새로 띄웠다면 둘째 턴은 이것을 모른다.
        val answersAfterFirstTurn = received
            .dropWhile { it !is ChatSessionEvent.TurnFinished }
            .filterIsInstance<ChatSessionEvent.AssistantTextArrived>()
            .joinToString("\n") { it.text }
        assertTrue(
            CONVERSATION_PASSPHRASE in answersAfterFirstTurn,
            "둘째 턴이 첫 턴의 암호를 기억하지 못했습니다. 받은 답: $answersAfterFirstTurn",
        )

        // 글자 조각이 실제로 온다 — --include-partial-messages 가 argv 에 실려 있다는 증거다(3.3).
        assertTrue(
            received.any { it is ChatSessionEvent.AssistantTextStreaming },
            "부분 스트리밍이 한 조각도 오지 않았습니다",
        )

        assertEquals(
            ChatSessionEvent.SessionEnded::class,
            received.last()::class,
            "끝났다는 사실이 마지막 이벤트여야 한다: ${received.takeLast(3)}",
        )
    }

    /**
     * 엔진에게 물어 챗 세션의 계획을 받는다. 부를 것이 없으면 이 검증을 건너뛴다.
     *
     * **argv 를 여기서 짓지 않는다.** 지으면 이 시험이 확인하는 것이 "우리가 적은 플래그로 claude 가
     * 도는가" 가 되어, 정작 확인해야 할 것(엔진이 답한 것으로 도는가)을 비껴간다(설계 원칙 11).
     */
    private fun chatSessionPlanOrSkip(): SessionEntryPlan {
        if (System.getenv(OPT_IN_VARIABLE) != "1") {
            abort<Unit>("$OPT_IN_VARIABLE=1 이 아니라 건너뜁니다 — 이 검증은 진짜 claude 를 부릅니다")
        }
        requireClaudeIsInstalled()

        val answer = KyuCliSessionCommandSource(realKyuCommandRunnerOrSkip(), SessionMode.Chat)
            .sessionCommandFor(temporaryDirectory, SessionTarget.Main, SessionConversationChoice.ContinueRecordedConversation)

        return planSessionEntry(
            sessionCommandAnswer = answer,
            baseEnvironment = childProcessEnvironment(),
            workDirPath = temporaryDirectory,
            target = SessionTarget.Main,
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

    /**
     * 이벤트를 받는 대로 쌓는다.
     *
     * 같은 코루틴 문맥에서 돈다. 다른 스레드로 빼면 이 목록을 두 스레드가 만지게 되고,
     * 그때부터 이 시험의 실패는 어댑터의 결함인지 시험의 결함인지 갈리지 않는다.
     */
    private fun CoroutineScope.collectInto(received: MutableList<ChatSessionEvent>, session: OpenedChatSession) {
        launch { session.events.collect { received += it } }
    }

    /** 그만큼의 턴이 끝날 때까지 기다린다. `delay` 라 기다리는 동안 받는 쪽이 계속 돈다. */
    private suspend fun waitForFinishedTurns(received: List<ChatSessionEvent>, turnCount: Int) =
        withTimeout(TURN_TIMEOUT_MILLIS) {
            while (received.count { it is ChatSessionEvent.TurnFinished } < turnCount) {
                delay(POLL_INTERVAL_MILLIS)
            }
        }

    private companion object {

        /** 이 검증을 일부러 켜는 자리. 켜지 않으면 CI 에서도 사람 손에서도 조용히 넘어간다. */
        const val OPT_IN_VARIABLE = "KYU_CLAUDE_CHAT_INTEGRATION"

        /** 첫 턴에서만 알려주는 낱말. 둘째 턴이 이것을 답하면 대화가 이어진 것이다. */
        const val CONVERSATION_PASSPHRASE = "KYUCHAT-2026"

        /**
         * 한 턴을 기다리는 시간.
         *
         * 넉넉하다. 이 턴에는 모델 왕복만이 아니라 메인 세션에 등록된 오케스트레이션 MCP 서버의
         * 기동까지 들어 있고(엔진이 답한 argv 그대로다), 그 시간은 기계마다 다르다.
         */
        const val TURN_TIMEOUT_MILLIS = 180_000L

        const val POLL_INTERVAL_MILLIS = 200L
    }
}
