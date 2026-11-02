package com.kyuchestration.desktop.terminal.chat

import com.kyuchestration.desktop.diagnostics.DiagnosticLog
import com.kyuchestration.desktop.terminal.ClaudeCodeConversationHistory
import com.kyuchestration.desktop.terminal.ClaudeCodeConversationHistorySource
import com.kyuchestration.desktop.terminal.ClaudeCodeConversationSource
import com.kyuchestration.desktop.terminal.SessionConversationChoice
import com.kyuchestration.desktop.terminal.SessionEntryPlan
import com.kyuchestration.desktop.terminal.SessionTarget
import com.kyuchestration.desktop.terminal.TerminalSessionFailure
import java.io.IOException
import java.util.concurrent.CountDownLatch
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * 지난 대화 읽기와 세션 띄우기가 나란히 도는 자리의 수명(app-owned-sessions-design.md 5.5.8).
 *
 * 읽기는 kyu 를 부르고 끝날 때까지 기다리는 블로킹 호출이라 취소되지 않는다. 그래서 가상 시계가 아니라 진짜 스레드에서,
 * 읽기를 걸쇠로 붙잡아 둔 채 잰다 — 읽기가 끝나지 않는 동안 세션 쪽 일이 그것을 기다리지 않는지가 요점이다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PastConversationReadingLifecycleTest {

    private val holderScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 붙잡힌 읽기를 놓는다. 시험이 끝나도 블로킹 스레드가 남지 않게. */
    private val readingReleased = CountDownLatch(1)

    @AfterTest
    fun cleanUp() {
        readingReleased.countDown()
        holderScope.cancel()
    }

    @Test
    fun `세션을 띄우지 못하면 지난 대화 읽기를 기다리지 않고 곧바로 실패를 보인다`(): Unit = runBlocking {
        val failingOpener = object : ChatSessionOpener {
            override suspend fun openChatSession(plan: SessionEntryPlan): OpenedChatSession =
                throw TerminalSessionFailure.ChatProcessFailedToStart(IOException("claude 를 찾지 못했습니다"))
        }
        val holder = holderWith(failingOpener)

        holder.enterSession(TEST_WORK_DIR_PATH, SessionTarget.Main, SessionConversationChoice.ContinueRecordedConversation)

        // 읽기는 아직 붙잡혀 있다. 그래도 진입 실패가 선다.
        withTimeout(STEP_TIMEOUT) {
            while (holder.sessionEntries.value[SessionTarget.Main] !is SessionEntryState.Failed) {
                delay(POLL_INTERVAL)
            }
        }
        assertEquals(1L, readingReleased.count, "실패가 읽기가 끝나기 전에 섰다")
    }

    @Test
    fun `지난 대화를 기다리는 사이 진입이 취소되면 막 띄운 세션을 끝낸다`(): Unit = runBlocking {
        // 고아가 된 claude 는 아무도 듣지 않는 파이프에 매달린 채 남는다(설계 8.1). 보유하지 못했으면 끝내야 한다.
        val opener = RecordingChatSessionOpener()
        val holder = holderWith(opener)

        holder.enterSession(TEST_WORK_DIR_PATH, SessionTarget.Main, SessionConversationChoice.ContinueRecordedConversation)
        withTimeout(STEP_TIMEOUT) {
            while (opener.openedSessions.isEmpty()) {
                delay(POLL_INTERVAL)
            }
        }
        holderScope.cancel()

        withTimeout(STEP_TIMEOUT) {
            while (opener.endedSessions.isEmpty()) {
                delay(POLL_INTERVAL)
            }
        }
        assertEquals(opener.openedSessions, opener.endedSessions)
        assertEquals(emptyList(), holder.heldSessions.value)
    }

    private fun holderWith(opener: ChatSessionOpener) = ChatSessionStateHolder(
        sessionCommandSource = RecordingSessionCommandSource().apply { resumedConversationId = RESUMED_ID },
        claudeCodeConversationSource = ClaudeCodeConversationSource { _, _ -> emptyList() },
        claudeCodeConversationHistorySource = ClaudeCodeConversationHistorySource { _, _, _ ->
            readingReleased.await()
            ClaudeCodeConversationHistory(pastTurns = emptyList(), omittedBeforeCount = 0)
        },
        chatSessionOpener = opener,
        childSessionCommandSource = RecordingChildSessionCommandSource(),
        claudeAuthToken = { null },
        coroutineScope = holderScope,
        diagnosticLog = DiagnosticLog.Discarding,
        baseEnvironment = emptyMap(),
        appSocketChannelOpener = {
            FakeAppSocketChannel(socketPath = TEST_APPROVAL_SOCKET_DIRECTORY.resolve("s.sock"), answeringScope = holderScope)
        },
        sessionEntryDispatcher = Dispatchers.IO,
        standardInputDispatcher = Dispatchers.IO.limitedParallelism(1),
    )

    private companion object {
        const val RESUMED_ID = "211f6974-88a8-4453-9248-a02b0d6febae"
        val STEP_TIMEOUT = 10.seconds
        val POLL_INTERVAL = 0.02.seconds
    }
}
