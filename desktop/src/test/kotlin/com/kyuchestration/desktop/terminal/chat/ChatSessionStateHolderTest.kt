package com.kyuchestration.desktop.terminal.chat

import com.kyuchestration.desktop.diagnostics.DiagnosticLog
import com.kyuchestration.desktop.diagnostics.DiagnosticLogEntry
import com.kyuchestration.desktop.diagnostics.RecordingDiagnosticLog
import com.kyuchestration.desktop.terminal.SessionCommandAnswer
import com.kyuchestration.desktop.terminal.SessionCommandSource
import com.kyuchestration.desktop.terminal.SessionConversationChoice
import com.kyuchestration.desktop.terminal.SessionEntryPlan
import com.kyuchestration.desktop.terminal.SessionTarget
import com.kyuchestration.desktop.terminal.TerminalSessionFailure
import java.io.IOException
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * 챗 세션을 열고 보유하고 그 이벤트를 대화로 옮기는 자리(chat-ui-design.md 5.5).
 *
 * 진짜 `claude` 도 진짜 kyu 도 부르지 않는다. 여기서 갈리는 것은 "이벤트가 어느 대화로 가는가"
 * 와 "세션을 언제 다시 띄우는가" 이고, 그 답에 프로세스가 필요하지 않다 — 이벤트가 대화로
 * 쌓이는 규칙 자체는 ChatConversationFoldingTest 가 진짜 줄로 확인한다.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ChatSessionStateHolderTest {

    @Test
    fun `아무 챗도 열지 않은 채로 시작한다`() = runTest {
        val holder = stateHolder(RecordingChatSessionOpener())

        assertEquals(ChatScreenState.NoChatOpen, holder.state.value)
        assertEquals(emptyList(), holder.heldSessions.value.map { it.target })
    }

    @Test
    fun `진입을 요청하면 세션이 뜰 때까지 진행 중으로 둔다`() = runTest {
        val holder = stateHolder(RecordingChatSessionOpener())

        holder.enterSession(WORK_DIR_PATH, SessionTarget.Main, SessionConversationChoice.ContinueRecordedConversation)

        val running = assertIs<ChatScreenState.ChatSessionEntryRunning>(holder.state.value)
        assertEquals(SessionTarget.Main, running.target)
    }

    @Test
    fun `엔진이 답한 것을 그대로 띄우고 보유한다`() = runTest {
        val opener = RecordingChatSessionOpener()
        val commandSource = RecordingSessionCommandSource()
        val holder = stateHolder(opener, commandSource)

        holder.enterSessionContinuingConversation(SessionTarget.Repo("proj-a"))
        runCurrent()

        // 앱이 argv 를 짓지 않는다(설계 원칙 11). 이 자리가 하는 일은 엔진의 답을 실행할 계획으로
        // 옮기는 것 하나다.
        assertEquals(listOf<SessionTarget>(SessionTarget.Repo("proj-a")), commandSource.askedTargets)
        assertEquals(ENGINE_ANSWERED_COMMAND, opener.openedPlans.single().command)
        assertEquals(listOf(SessionTarget.Repo("proj-a")), holder.heldSessions.value.map { it.target })
        assertIs<ChatScreenState.ChatOnScreen>(holder.state.value)
    }

    @Test
    fun `받은 이벤트가 화면의 대화로 쌓인다`() = runTest {
        val opener = RecordingChatSessionOpener()
        val holder = stateHolder(opener)
        holder.enterSessionContinuingConversation(SessionTarget.Main)
        runCurrent()

        opener.lastSession.emit(ChatSessionEvent.UserMessageEchoed("1 더하기 1은?"))
        opener.lastSession.emit(ChatSessionEvent.AssistantTextArrived("2", parentToolUseId = null))
        runCurrent()

        val conversation = assertIs<ChatScreenState.ChatOnScreen>(holder.state.value).conversation
        assertEquals(
            listOf<ChatEntry>(ChatEntry.UserSaid("1 더하기 1은?"), ChatEntry.AssistantSaid("2")),
            conversation.entries,
        )
    }

    @Test
    fun `다른 세션으로 옮겨도 앞 대화는 그대로 남는다`() = runTest {
        val opener = RecordingChatSessionOpener()
        val holder = stateHolder(opener)
        holder.enterSessionContinuingConversation(SessionTarget.Main)
        runCurrent()
        val mainSession = opener.lastSession
        mainSession.emit(ChatSessionEvent.UserMessageEchoed("메인에게 한 말"))
        runCurrent()

        holder.enterSessionContinuingConversation(SessionTarget.Repo("proj-a"))
        runCurrent()
        holder.enterSessionContinuingConversation(SessionTarget.Main)
        runCurrent()

        // 옮길 때마다 앞 세션을 끝내면 사용자는 한 번 잘못 눌러 하던 작업을 잃는다. 다시 띄우지도
        // 않는다 — 다시 띄우면 그 자리에 새 프로세스가 서고 전사가 빈다.
        assertEquals(2, opener.openedPlans.size)
        assertEquals(0, mainSession.endSessionCount)
        val conversation = assertIs<ChatScreenState.ChatOnScreen>(holder.state.value).conversation
        assertEquals(listOf<ChatEntry>(ChatEntry.UserSaid("메인에게 한 말")), conversation.entries)
    }

    @Test
    fun `화면에 없는 세션의 이벤트도 계속 받는다`() = runTest {
        // 받기를 멈추면 그 세션의 파이프가 차고, 차는 순간 그 안의 claude 가 쓰기에서 멎는다.
        val opener = RecordingChatSessionOpener()
        val holder = stateHolder(opener)
        holder.enterSessionContinuingConversation(SessionTarget.Main)
        runCurrent()
        val mainSession = opener.lastSession

        holder.enterSessionContinuingConversation(SessionTarget.Repo("proj-a"))
        runCurrent()
        mainSession.emit(ChatSessionEvent.AssistantTextArrived("보고 있지 않을 때 온 답", parentToolUseId = null))
        runCurrent()

        holder.enterSessionContinuingConversation(SessionTarget.Main)
        runCurrent()
        val conversation = assertIs<ChatScreenState.ChatOnScreen>(holder.state.value).conversation
        assertEquals(listOf<ChatEntry>(ChatEntry.AssistantSaid("보고 있지 않을 때 온 답")), conversation.entries)
    }

    @Test
    fun `말을 보내면 세션에 실리고 답을 기다린다`() = runTest {
        val opener = RecordingChatSessionOpener()
        val holder = stateHolder(opener)
        holder.enterSessionContinuingConversation(SessionTarget.Main)
        runCurrent()

        holder.sendUserMessage("README 를 읽어줘")
        runCurrent()

        assertEquals(listOf("README 를 읽어줘"), opener.lastSession.sentMessages)
        // 보낸 말을 앱이 전사에 직접 넣지 않는다 — 되돌아온 것으로만 그린다(3.14).
        val conversation = assertIs<ChatScreenState.ChatOnScreen>(holder.state.value).conversation
        assertEquals(emptyList(), conversation.entries)
        assertEquals(listOf("README 를 읽어줘"), conversation.pendingUserMessages)
        // 보낸 것은 아직 시작한 턴이 아니다. 시작은 이 말이 되돌아올 때다.
        assertEquals(TurnState.Idle, conversation.turnState)
    }

    @Test
    fun `보내지 못하면 대기 중에도 남지 않는다`() = runTest {
        // stdin 이 닫혔다는 것은 이 세션이 끝났다는 뜻이라, 되돌아올 말도 없다.
        val opener = RecordingChatSessionOpener()
        val holder = stateHolder(opener)
        holder.enterSessionContinuingConversation(SessionTarget.Main)
        runCurrent()
        opener.lastSession.failWrites()

        holder.sendUserMessage("이미 끝난 세션에 거는 말")
        runCurrent()

        val conversation = onScreenConversation(holder)
        assertEquals(emptyList(), conversation.pendingUserMessages)
        assertEquals(TurnState.Idle, conversation.turnState)
        assertIs<ChatEntry.EngineNotice>(conversation.entries.single())
    }

    @Test
    fun `턴이 끝나면 기다림도 끝난다`() = runTest {
        val opener = RecordingChatSessionOpener()
        val holder = stateHolder(opener)
        holder.enterSessionContinuingConversation(SessionTarget.Main)
        runCurrent()
        holder.sendUserMessage("긴 답을 줘")
        runCurrent()

        // 되돌아온 말이 result 보다 먼저 온다(실측: 2.4 초 · 80 초). 그 순서를 지키지 않으면
        // 이 검증은 앱에 없는 상태를 만들어 놓고 그 결과를 본다.
        opener.lastSession.emit(ChatSessionEvent.UserMessageEchoed("긴 답을 줘"))
        opener.lastSession.emit(finishedTurn())
        runCurrent()

        val conversation = assertIs<ChatScreenState.ChatOnScreen>(holder.state.value).conversation
        assertEquals(TurnState.Idle, conversation.turnState)
        assertEquals(0.25, conversation.totalCostUsd)
    }

    @Test
    fun `턴이 도는 중에 보낸 말은 대기 중으로 선다`() = runTest {
        // 되돌아온 말이 그 턴이 시작할 때에야 오므로(실측: 78 초 뒤), 앱이 보낸 것을 들고 있지
        // 않으면 사용자가 친 말이 그동안 화면 어디에도 없다.
        val opener = RecordingChatSessionOpener()
        val holder = stateHolder(opener)
        holder.enterSessionContinuingConversation(SessionTarget.Main)
        runCurrent()

        holder.sendUserMessage("긴 답을 줘")
        holder.sendUserMessage("사과의 색은?")
        runCurrent()

        assertEquals(listOf("긴 답을 줘", "사과의 색은?"), opener.lastSession.sentMessages)
        assertEquals(listOf("긴 답을 줘", "사과의 색은?"), onScreenConversation(holder).pendingUserMessages)
        assertEquals(emptyList(), onScreenConversation(holder).entries)
    }

    @Test
    fun `되돌아온 말은 대기 중에서 빠지고 말풍선이 된다`() = runTest {
        val opener = RecordingChatSessionOpener()
        val holder = stateHolder(opener)
        holder.enterSessionContinuingConversation(SessionTarget.Main)
        runCurrent()
        holder.sendUserMessage("긴 답을 줘")
        holder.sendUserMessage("사과의 색은?")
        runCurrent()

        opener.lastSession.emit(ChatSessionEvent.UserMessageEchoed("긴 답을 줘"))
        runCurrent()

        val conversation = onScreenConversation(holder)
        assertEquals(listOf("사과의 색은?"), conversation.pendingUserMessages)
        assertEquals(listOf<ChatEntry>(ChatEntry.UserSaid("긴 답을 줘")), conversation.entries)
    }

    @Test
    fun `되돌아온 말이 그 턴의 시작이다`() = runTest {
        val opener = RecordingChatSessionOpener()
        val holder = stateHolder(opener)
        holder.enterSessionContinuingConversation(SessionTarget.Main)
        runCurrent()
        holder.sendUserMessage("긴 답을 줘")
        holder.sendUserMessage("사과의 색은?")
        runCurrent()

        opener.lastSession.emit(ChatSessionEvent.UserMessageEchoed("긴 답을 줘"))
        runCurrent()
        assertEquals(TurnState.Running, onScreenConversation(holder).turnState)

        // 첫 턴이 끝났다. 둘째 말은 아직 되돌아오지 않았으므로 그 턴은 시작하지 않았다 —
        // 기다리는 중이라는 사실은 대기 목록이 말하고, 도는 턴은 없다.
        opener.lastSession.emit(finishedTurn())
        runCurrent()
        val betweenTurns = onScreenConversation(holder)
        assertEquals(TurnState.Idle, betweenTurns.turnState)
        assertEquals(listOf("사과의 색은?"), betweenTurns.pendingUserMessages)

        opener.lastSession.emit(ChatSessionEvent.UserMessageEchoed("사과의 색은?"))
        runCurrent()
        assertEquals(TurnState.Running, onScreenConversation(holder).turnState)
    }

    @Test
    fun `큐에 든 말만 있을 때는 끊을 턴이 없다`() = runTest {
        // 시작하지 않은 턴에 대고 제어 요청을 보내면 그 말이 시작하자마자 첫 낱말에서 끊긴다.
        val opener = RecordingChatSessionOpener()
        val holder = stateHolder(opener)
        holder.enterSessionContinuingConversation(SessionTarget.Main)
        runCurrent()
        holder.sendUserMessage("긴 답을 줘")
        holder.sendUserMessage("큐에 드는 말")
        runCurrent()
        opener.lastSession.emit(ChatSessionEvent.UserMessageEchoed("긴 답을 줘"))
        opener.lastSession.emit(finishedTurn())
        runCurrent()

        holder.interruptOnScreenTurn()
        runCurrent()

        assertEquals(0, opener.lastSession.interruptCount)
        assertEquals(TurnState.Idle, onScreenConversation(holder).turnState)
    }

    @Test
    fun `도는 턴을 끊으면 그 요청이 세션에 실린다`() = runTest {
        val opener = RecordingChatSessionOpener()
        val holder = stateHolder(opener)
        holder.enterSessionContinuingConversation(SessionTarget.Main)
        runCurrent()
        holder.sendUserMessage("아주 긴 답을 줘")
        opener.lastSession.emit(ChatSessionEvent.UserMessageEchoed("아주 긴 답을 줘"))
        runCurrent()

        holder.interruptOnScreenTurn()
        runCurrent()

        assertEquals(1, opener.lastSession.interruptCount)
        // 아직 끊긴 것이 아니라 끊어 달라고 말한 것이다. 여기서 곧바로 Idle 로 두면 화면은 턴이
        // 끝났다고 말하는데 글자는 계속 흐른다 — 끝났다는 사실은 result 가 말한다(3.8).
        assertEquals(TurnState.Interrupting, onScreenConversation(holder).turnState)
    }

    @Test
    fun `끊긴 턴이 배지로 남고 입력이 다시 열린다`() = runTest {
        val opener = RecordingChatSessionOpener()
        val holder = stateHolder(opener)
        holder.enterSessionContinuingConversation(SessionTarget.Main)
        runCurrent()
        holder.sendUserMessage("아주 긴 답을 줘")
        opener.lastSession.emit(ChatSessionEvent.UserMessageEchoed("아주 긴 답을 줘"))
        runCurrent()
        holder.interruptOnScreenTurn()
        runCurrent()

        opener.lastSession.emit(finishedTurn(outcome = TurnOutcome.Interrupted))
        runCurrent()

        val conversation = onScreenConversation(holder)
        assertEquals(TurnState.Idle, conversation.turnState)
        assertEquals(
            TurnOutcome.Interrupted,
            conversation.entries.filterIsInstance<ChatEntry.TurnEnded>().single().outcome,
        )
    }

    @Test
    fun `턴이 돌지 않을 때는 끊어 달라고 말하지 않는다`() = runTest {
        // 도는 턴이 없는데 제어 요청을 보내면 claude 는 다음 턴의 첫 낱말을 끊는다.
        val opener = RecordingChatSessionOpener()
        val holder = stateHolder(opener)
        holder.enterSessionContinuingConversation(SessionTarget.Main)
        runCurrent()

        holder.interruptOnScreenTurn()
        runCurrent()

        assertEquals(0, opener.lastSession.interruptCount)
        assertEquals(TurnState.Idle, onScreenConversation(holder).turnState)
    }

    @Test
    fun `이미 끊어 달라고 말한 턴에 두 번 말하지 않는다`() = runTest {
        val opener = RecordingChatSessionOpener()
        val holder = stateHolder(opener)
        holder.enterSessionContinuingConversation(SessionTarget.Main)
        runCurrent()
        holder.sendUserMessage("아주 긴 답을 줘")
        opener.lastSession.emit(ChatSessionEvent.UserMessageEchoed("아주 긴 답을 줘"))
        runCurrent()

        holder.interruptOnScreenTurn()
        holder.interruptOnScreenTurn()
        runCurrent()

        assertEquals(1, opener.lastSession.interruptCount)
    }

    @Test
    fun `띄우지 못하면 그 이유를 들고 있고 기록에 남긴다`() = runTest {
        val opener = RecordingChatSessionOpener()
        val commandSource = RecordingSessionCommandSource().apply {
            respondWith { throw TerminalSessionFailure.KyuExecutableNotFound() }
        }
        val diagnosticLog = RecordingDiagnosticLog()
        val holder = stateHolder(opener, commandSource, diagnosticLog)

        holder.enterSessionContinuingConversation(SessionTarget.Repo("proj-a"))
        runCurrent()

        val failed = assertIs<ChatScreenState.ChatSessionEntryFailed>(holder.state.value)
        assertIs<TerminalSessionFailure.KyuExecutableNotFound>(failed.failure)
        assertEquals(emptyList(), holder.heldSessions.value.map { it.target })
        assertIs<DiagnosticLogEntry.SessionEntryFailed>(diagnosticLog.recordedEntries.single())
    }

    @Test
    fun `세션이 스스로 끝나면 보유에서 빠지고 전사는 화면에 남는다`() = runTest {
        val opener = RecordingChatSessionOpener()
        val holder = stateHolder(opener)
        holder.enterSessionContinuingConversation(SessionTarget.Main)
        runCurrent()
        opener.lastSession.emit(ChatSessionEvent.AssistantTextArrived("마지막 답", parentToolUseId = null))

        opener.lastSession.emit(ChatSessionEvent.SessionEnded(exitCode = 0))
        runCurrent()

        assertEquals(emptyList(), holder.heldSessions.value.map { it.target })
        val conversation = assertIs<ChatScreenState.ChatOnScreen>(holder.state.value).conversation
        assertEquals(listOf<ChatEntry>(ChatEntry.AssistantSaid("마지막 답")), conversation.entries)
        assertEquals(0, conversation.endedExitCode)
    }

    @Test
    fun `이어가기로 연 세션이 실패한 코드로 끝나면 그 사실이 기록과 화면에 남는다`() = runTest {
        val opener = RecordingChatSessionOpener()
        val commandSource = RecordingSessionCommandSource().apply { resumedConversationId = RESUMED_ID }
        val diagnosticLog = RecordingDiagnosticLog()
        val holder = stateHolder(opener, commandSource, diagnosticLog)
        holder.enterSessionContinuingConversation(SessionTarget.Main)
        runCurrent()

        // 전사가 사라진 대화를 이어가려 하면 claude 는 그 사실을 stderr 에 적고 코드 1 로 끝난다(3.9).
        opener.lastSession.emit(ChatSessionEvent.EngineSpoke("No conversation found with session ID: $RESUMED_ID"))
        opener.lastSession.emit(ChatSessionEvent.SessionEnded(exitCode = 1))
        runCurrent()

        val conversation = assertIs<ChatScreenState.ChatOnScreen>(holder.state.value).conversation
        assertTrue(conversation.resumeFailureSuspected, "PTY 시절에는 사용자의 /exit 과 구분할 수 없던 자리다")
        assertIs<ChatEntry.EngineNotice>(conversation.entries.single())
        val recorded = diagnosticLog.recordedEntries.filterIsInstance<DiagnosticLogEntry.SessionEndedUnexpectedly>()
        assertTrue(recorded.single().resumeFailureSuspected)
    }

    @Test
    fun `세션을 끝내면 프로세스가 끝나고 화면이 빈다`() = runTest {
        val opener = RecordingChatSessionOpener()
        val diagnosticLog = RecordingDiagnosticLog()
        val holder = stateHolder(opener, diagnosticLog = diagnosticLog)
        holder.enterSessionContinuingConversation(SessionTarget.Main)
        runCurrent()

        holder.endOnScreenSession()
        runCurrent()

        assertEquals(1, opener.lastSession.endSessionCount)
        assertEquals(ChatScreenState.NoChatOpen, holder.state.value)
        assertEquals(emptyList(), holder.heldSessions.value.map { it.target })
        // 사용자가 시킨 일이라 진단할 것이 없다. 이것까지 남기면 기록이 정상적인 사용으로 찬다.
        assertEquals(
            listOf<DiagnosticLogEntry>(DiagnosticLogEntry.SessionStarted("main", resumingConversation = false)),
            diagnosticLog.recordedEntries,
        )
    }

    @Test
    fun `끝낸 세션을 다시 열면 새 대화로 시작한다`() = runTest {
        val opener = RecordingChatSessionOpener()
        val holder = stateHolder(opener)
        holder.enterSessionContinuingConversation(SessionTarget.Main)
        runCurrent()
        opener.lastSession.emit(ChatSessionEvent.AssistantTextArrived("앞선 답", parentToolUseId = null))
        opener.lastSession.emit(ChatSessionEvent.SessionEnded(exitCode = 0))
        runCurrent()

        holder.enterSessionContinuingConversation(SessionTarget.Main)
        runCurrent()

        // 새 프로세스가 섰으므로 화면도 그 프로세스가 말한 것부터 보여야 한다. 앞 전사를 이어
        // 붙이면 이번 실행에서 온 적 없는 말이 새 대화 위에 얹힌다.
        val conversation = assertIs<ChatScreenState.ChatOnScreen>(holder.state.value).conversation
        assertEquals(emptyList(), conversation.entries)
        assertEquals(2, opener.openedPlans.size)
    }

    @Test
    fun `워크디렉토리를 바꾸면 보유한 챗이 전부 끝난다`() = runTest {
        val opener = RecordingChatSessionOpener()
        val holder = stateHolder(opener)
        holder.enterSessionContinuingConversation(SessionTarget.Main)
        runCurrent()
        val mainSession = opener.lastSession
        holder.enterSessionContinuingConversation(SessionTarget.Repo("proj-a"))
        runCurrent()

        holder.endAllSessions()
        runCurrent()

        // 놓아 주면 앱 어디에도 보이지 않는 claude 가 남는다(설계 8 절 1 번).
        assertEquals(1, mainSession.endSessionCount)
        assertEquals(1, opener.lastSession.endSessionCount)
        assertEquals(ChatScreenState.NoChatOpen, holder.state.value)
    }

    private fun ChatSessionStateHolder.enterSessionContinuingConversation(target: SessionTarget) =
        enterSession(WORK_DIR_PATH, target, SessionConversationChoice.ContinueRecordedConversation)

    private fun TestScope.stateHolder(
        opener: ChatSessionOpener,
        sessionCommandSource: SessionCommandSource = RecordingSessionCommandSource(),
        diagnosticLog: DiagnosticLog = DiagnosticLog.Discarding,
    ) = ChatSessionStateHolder(
        sessionCommandSource = sessionCommandSource,
        chatSessionOpener = opener,
        coroutineScope = backgroundScope,
        diagnosticLog = diagnosticLog,
        baseEnvironment = emptyMap(),
        // 앱에서는 엔진에게 묻고 프로세스를 띄우는 동안 화면이 멎지 않도록 IO 로 나간다.
        sessionEntryDispatcher = StandardTestDispatcher(testScheduler),
        // stdin 쓰기도 시험의 시계 위에 올린다. 앱에서는 한 번에 하나씩 도는 자리이고, 여기서도
        // 한 줄씩 차례로 도는 것은 같다 — 다른 것은 그 차례를 시험이 진행시킨다는 것뿐이다.
        standardInputDispatcher = StandardTestDispatcher(testScheduler),
    )

    private fun onScreenConversation(holder: ChatSessionStateHolder): ChatConversation =
        assertIs<ChatScreenState.ChatOnScreen>(holder.state.value).conversation

    private fun finishedTurn(
        costUsd: Double = 0.25,
        outcome: TurnOutcome = TurnOutcome.Completed,
    ) = ChatSessionEvent.TurnFinished(
        outcome = outcome,
        costUsd = costUsd,
        usage = TurnUsage(inputTokens = 3, outputTokens = 41, cacheReadInputTokens = 0, cacheCreationInputTokens = 0),
        permissionDenials = emptyList(),
        durationMillis = 1_200,
    )

    private class RecordingSessionCommandSource : SessionCommandSource {

        val askedTargets = mutableListOf<SessionTarget>()

        /** 다음에 열 세션이 이어갈 대화. 진짜 어댑터에서는 엔진의 답이 이 값을 정한다. */
        var resumedConversationId: String? = null

        private var respond: () -> SessionCommandAnswer = {
            SessionCommandAnswer(
                command = ENGINE_ANSWERED_COMMAND,
                workingDirectory = WORK_DIR_PATH,
                environmentToAdd = emptyMap(),
                resumedConversationId = resumedConversationId,
            )
        }

        fun respondWith(respond: () -> SessionCommandAnswer) {
            this.respond = respond
        }

        override fun sessionCommandFor(
            workDirPath: Path,
            target: SessionTarget,
            conversationChoice: SessionConversationChoice,
        ): SessionCommandAnswer {
            askedTargets.add(target)
            return respond()
        }
    }

    private class RecordingChatSessionOpener : ChatSessionOpener {

        val openedPlans = mutableListOf<SessionEntryPlan>()
        private val openedSessions = mutableListOf<FakeChatSession>()

        val lastSession: FakeChatSession get() = openedSessions.last()

        override suspend fun openChatSession(plan: SessionEntryPlan): OpenedChatSession {
            openedPlans.add(plan)
            return FakeChatSession().also(openedSessions::add)
        }
    }

    /**
     * `claude` 자리에 놓는 세션 하나. 무엇이 언제 오는지를 시험이 정한다.
     *
     * 한계 없는 채널인 것이 진짜 어댑터와 같다(ProcessChatSession) — 받는 쪽이 아직 모으기
     * 시작하지 않았어도 [emit] 한 것이 잃히지 않는다.
     */
    private class FakeChatSession : OpenedChatSession {

        private val incoming = Channel<ChatSessionEvent>(Channel.UNLIMITED)

        val sentMessages = mutableListOf<String>()
        private var writesFail = false
        var endSessionCount = 0
            private set
        var interruptCount = 0
            private set

        override val events: Flow<ChatSessionEvent> = incoming.receiveAsFlow()

        fun emit(event: ChatSessionEvent) {
            incoming.trySend(event)
        }

        /** 프로세스가 이미 끝나 stdin 이 닫힌 자리를 만든다. */
        fun failWrites() {
            writesFail = true
        }

        override suspend fun sendUserMessage(text: String) {
            if (writesFail) {
                throw IOException("Stream closed")
            }
            sentMessages.add(text)
        }

        override suspend fun interruptCurrentTurn() {
            interruptCount++
        }

        override suspend fun endSession() {
            endSessionCount++
            incoming.close()
        }
    }

    private companion object {
        val WORK_DIR_PATH: Path = Path.of("/home/me/work/WorkDir-featureX")

        /** 엔진이 답한 argv. 앱이 이 목록을 짓지 않는다는 것이 이 상수의 뜻이다. */
        val ENGINE_ANSWERED_COMMAND = listOf("claude", "-p", "--input-format", "stream-json")

        const val RESUMED_ID = "211f6974-88a8-4453-9248-a02b0d6febae"
    }
}
