package com.kyuchestration.desktop.terminal.chat

import com.kyuchestration.desktop.diagnostics.DiagnosticLogEntry
import com.kyuchestration.desktop.diagnostics.RecordingDiagnosticLog
import com.kyuchestration.desktop.terminal.PersonOpenedSessionTarget
import com.kyuchestration.desktop.terminal.SessionConversationChoice
import com.kyuchestration.desktop.terminal.SessionTarget
import com.kyuchestration.desktop.terminal.TerminalSessionFailure
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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

        assertEquals(emptyMap(), holder.conversations.value)
        assertEquals(emptyMap(), holder.sessionEntries.value)
        assertEquals(emptyList(), holder.heldSessions.value.map { it.target })
    }

    @Test
    fun `진입을 요청하면 세션이 뜰 때까지 진행 중으로 둔다`() = runTest {
        val holder = stateHolder(RecordingChatSessionOpener())

        holder.enterSession(WORK_DIR_PATH, SessionTarget.Main, SessionConversationChoice.ContinueRecordedConversation)

        assertEquals(mapOf<PersonOpenedSessionTarget, SessionEntryState>(SessionTarget.Main to SessionEntryState.Running), holder.sessionEntries.value)
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
        assertNotNull(holder.conversations.value[SessionTarget.Repo("proj-a")])
        // 보유하면 진입 줄은 빠진다 — 대화가 그 자리를 잇는다.
        assertEquals(emptyMap(), holder.sessionEntries.value)
    }

    @Test
    fun `엔진이 세션을 열며 남긴 경고가 그 세션의 챗 맨 앞에 선다`() = runTest {
        // 버려진 대화 기록 같은 말이다. 엔진은 세션을 열어 주되 그 사실을 stderr 로 남긴다 — 버리면 사람은 앞 대화가
        // 왜 이어지지 않았는지 모른다.
        for (target in listOf(SessionTarget.Main, SessionTarget.Repo("proj-a"))) {
            val opener = RecordingChatSessionOpener()
            val commandSource = RecordingSessionCommandSource()
            commandSource.engineWarnings = listOf("대화 기록을 읽지 못해 버렸습니다", "새 대화로 엽니다")
            val holder = stateHolder(opener, commandSource)

            holder.enterSessionContinuingConversation(target)
            runCurrent()

            val conversation = conversationOf(holder, target)
            assertEquals(
                listOf<ChatEntry>(
                    ChatEntry.EngineNotice("대화 기록을 읽지 못해 버렸습니다"),
                    ChatEntry.EngineNotice("새 대화로 엽니다"),
                ),
                conversation.entries,
                "$target",
            )
        }
    }

    @Test
    fun `받은 이벤트가 화면의 대화로 쌓인다`() = runTest {
        val opener = RecordingChatSessionOpener()
        val holder = stateHolder(opener)
        holder.enterSessionContinuingConversation(SessionTarget.Main)
        runCurrent()

        opener.lastSession.emit(ChatSessionEvent.UserMessageEchoed("1 더하기 1은?", parentToolUseId = null, messageUuid = null))
        opener.lastSession.emit(ChatSessionEvent.AssistantTextArrived("2", parentToolUseId = null))
        runCurrent()

        val conversation = conversationOf(holder, SessionTarget.Main)
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
        mainSession.emit(ChatSessionEvent.UserMessageEchoed("메인에게 한 말", parentToolUseId = null, messageUuid = null))
        runCurrent()

        holder.enterSessionContinuingConversation(SessionTarget.Repo("proj-a"))
        runCurrent()
        holder.enterSessionContinuingConversation(SessionTarget.Main)
        runCurrent()

        // 옮길 때마다 앞 세션을 끝내면 사용자는 한 번 잘못 눌러 하던 작업을 잃는다. 다시 띄우지도
        // 않는다 — 다시 띄우면 그 자리에 새 프로세스가 서고 전사가 빈다.
        assertEquals(2, opener.openedPlans.size)
        assertEquals(0, mainSession.endSessionCount)
        val conversation = conversationOf(holder, SessionTarget.Main)
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
        val conversation = conversationOf(holder, SessionTarget.Main)
        assertEquals(listOf<ChatEntry>(ChatEntry.AssistantSaid("보고 있지 않을 때 온 답")), conversation.entries)
    }

    @Test
    fun `말을 보내면 세션에 실리고 답을 기다린다`() = runTest {
        val opener = RecordingChatSessionOpener()
        val holder = stateHolder(opener)
        holder.enterSessionContinuingConversation(SessionTarget.Main)
        runCurrent()

        holder.sendUserMessage(SessionTarget.Main, "README 를 읽어줘")
        runCurrent()

        assertEquals(listOf("README 를 읽어줘"), opener.lastSession.sentMessages.map { it.text })
        // 보낸 말을 앱이 전사에 직접 넣지 않는다 — 되돌아온 것으로만 그린다(3.14).
        val conversation = conversationOf(holder, SessionTarget.Main)
        assertEquals(emptyList(), conversation.entries)
        assertEquals(opener.lastSession.sentMessages, conversation.pendingUserMessages)
        // 보낸 것은 아직 시작한 턴이 아니다. 시작은 이 말이 되돌아올 때다.
        assertEquals(TurnState.Idle, conversation.turnState)
    }

    @Test
    fun `말은 화면이 무엇을 보든 명시한 세션으로 간다`() = runTest {
        // 화면이 다른 프로세스로 갈라지면 코어는 화면이 무엇을 보는지 모른다(core-ui-split 6.8). 명령이 대상을 싣는다.
        val opener = RecordingChatSessionOpener()
        val holder = stateHolder(opener)
        holder.enterSessionContinuingConversation(SessionTarget.Main)
        runCurrent()
        val mainSession = opener.lastSession
        holder.enterSessionContinuingConversation(SessionTarget.Repo("proj-a"))
        runCurrent()

        holder.sendUserMessage(SessionTarget.Main, "메인에게 하는 말")
        runCurrent()

        assertEquals(listOf("메인에게 하는 말"), mainSession.sentMessages.map { it.text })
        assertEquals(emptyList(), opener.lastSession.sentMessages)
    }

    @Test
    fun `승인 카드의 답도 명시한 세션의 물음으로 간다`() = runTest {
        val channels = mutableListOf<FakeAppSocketChannel>()
        val holder = stateHolder(RecordingChatSessionOpener(), permissionChannels = channels)
        holder.enterSessionContinuingConversation(SessionTarget.Main)
        runCurrent()
        val asked = channels.single().ask("Write", "toolu_01", writeInput("/tmp/probe.txt"))
        runCurrent()
        holder.enterSessionContinuingConversation(SessionTarget.Repo("proj-a"))
        runCurrent()

        holder.answerPermission(SessionTarget.Repo("proj-a"), "toolu_01", PermissionCardChoice.Deny("다른 세션의 답"))
        holder.answerPermission(SessionTarget.Main, "toolu_01", PermissionCardChoice.AllowOnce(asked.request.input))
        runCurrent()

        // 같은 toolUseId 라도 다른 세션의 답은 이 물음에 닿지 않는다.
        assertEquals(PermissionDecision.Allow(asked.request.input), asked.decisionSoFar())
    }

    @Test
    fun `보내지 못하면 대기 중에도 남지 않는다`() = runTest {
        // stdin 이 닫혔다는 것은 이 세션이 끝났다는 뜻이라, 되돌아올 말도 없다.
        val opener = RecordingChatSessionOpener()
        val holder = stateHolder(opener)
        holder.enterSessionContinuingConversation(SessionTarget.Main)
        runCurrent()
        opener.lastSession.failWrites()

        holder.sendUserMessage(SessionTarget.Main, "이미 끝난 세션에 거는 말")
        runCurrent()

        val conversation = conversationOf(holder, SessionTarget.Main)
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
        holder.sendUserMessage(SessionTarget.Main, "긴 답을 줘")
        runCurrent()

        // 되돌아온 말이 result 보다 먼저 온다(실측: 2.4 초 · 80 초). 그 순서를 지키지 않으면
        // 이 검증은 앱에 없는 상태를 만들어 놓고 그 결과를 본다.
        opener.lastSession.emit(echoOfPending(holder, "긴 답을 줘"))
        opener.lastSession.emit(finishedTurn())
        runCurrent()

        val conversation = conversationOf(holder, SessionTarget.Main)
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

        holder.sendUserMessage(SessionTarget.Main, "긴 답을 줘")
        holder.sendUserMessage(SessionTarget.Main, "사과의 색은?")
        runCurrent()

        assertEquals(listOf("긴 답을 줘", "사과의 색은?"), opener.lastSession.sentMessages.map { it.text })
        assertEquals(opener.lastSession.sentMessages, conversationOf(holder, SessionTarget.Main).pendingUserMessages)
        assertEquals(emptyList(), conversationOf(holder, SessionTarget.Main).entries)
    }

    @Test
    fun `되돌아온 말은 대기 중에서 빠지고 말풍선이 된다`() = runTest {
        val opener = RecordingChatSessionOpener()
        val holder = stateHolder(opener)
        holder.enterSessionContinuingConversation(SessionTarget.Main)
        runCurrent()
        holder.sendUserMessage(SessionTarget.Main, "긴 답을 줘")
        holder.sendUserMessage(SessionTarget.Main, "사과의 색은?")
        runCurrent()

        opener.lastSession.emit(echoOfPending(holder, "긴 답을 줘"))
        runCurrent()

        val conversation = conversationOf(holder, SessionTarget.Main)
        assertEquals(listOf("사과의 색은?"), conversation.pendingUserMessages.map { it.text })
        assertEquals(listOf<ChatEntry>(ChatEntry.UserSaid("긴 답을 줘")), conversation.entries)
    }

    @Test
    fun `같은 말을 두 번 보내도 되돌아온 쪽만 대기 중에서 빠진다`() = runTest {
        // 텍스트로 잇던 때는 둘 중 어느 것이 돌아왔는지 가를 수 없었다. 앱이 정한 uuid 가 그대로
        // 돌아오므로(session-tree 3.5 (다)) 이제는 가른다.
        val opener = RecordingChatSessionOpener()
        val holder = stateHolder(opener)
        holder.enterSessionContinuingConversation(SessionTarget.Main)
        runCurrent()
        holder.sendUserMessage(SessionTarget.Main, "응")
        holder.sendUserMessage(SessionTarget.Main, "응")
        runCurrent()
        val (first, second) = opener.lastSession.sentMessages

        opener.lastSession.emit(ChatSessionEvent.UserMessageEchoed("응", parentToolUseId = null, messageUuid = second.uuid))
        runCurrent()

        assertEquals(listOf(first), conversationOf(holder, SessionTarget.Main).pendingUserMessages)
    }

    @Test
    fun `앱이 보내지 않은 같은 문구는 기다리는 말을 놓지 않는다`() = runTest {
        // 중단 안내처럼 claude 가 스스로 지은 user 줄은 앱의 uuid 와 맞지 않는다. 문구가 우연히
        // 같아도 기다리던 말은 아직 돌아오지 않았다.
        val opener = RecordingChatSessionOpener()
        val holder = stateHolder(opener)
        holder.enterSessionContinuingConversation(SessionTarget.Main)
        runCurrent()
        holder.sendUserMessage(SessionTarget.Main, "[Request interrupted by user]")
        runCurrent()

        opener.lastSession.emit(
            ChatSessionEvent.UserMessageEchoed(
                "[Request interrupted by user]",
                parentToolUseId = null,
                messageUuid = "b5c0c63f-0e28-4112-8c1b-c55684e91d9c",
            ),
        )
        runCurrent()

        assertEquals(opener.lastSession.sentMessages, conversationOf(holder, SessionTarget.Main).pendingUserMessages)
    }

    @Test
    fun `되돌아온 말이 그 턴의 시작이다`() = runTest {
        val opener = RecordingChatSessionOpener()
        val holder = stateHolder(opener)
        holder.enterSessionContinuingConversation(SessionTarget.Main)
        runCurrent()
        holder.sendUserMessage(SessionTarget.Main, "긴 답을 줘")
        holder.sendUserMessage(SessionTarget.Main, "사과의 색은?")
        runCurrent()

        opener.lastSession.emit(echoOfPending(holder, "긴 답을 줘"))
        runCurrent()
        assertEquals(TurnState.Running, conversationOf(holder, SessionTarget.Main).turnState)

        // 첫 턴이 끝났다. 둘째 말은 아직 되돌아오지 않았으므로 그 턴은 시작하지 않았다 —
        // 기다리는 중이라는 사실은 대기 목록이 말하고, 도는 턴은 없다.
        opener.lastSession.emit(finishedTurn())
        runCurrent()
        val betweenTurns = conversationOf(holder, SessionTarget.Main)
        assertEquals(TurnState.Idle, betweenTurns.turnState)
        assertEquals(listOf("사과의 색은?"), betweenTurns.pendingUserMessages.map { it.text })

        opener.lastSession.emit(echoOfPending(holder, "사과의 색은?"))
        runCurrent()
        assertEquals(TurnState.Running, conversationOf(holder, SessionTarget.Main).turnState)
    }

    @Test
    fun `큐에 든 말만 있을 때는 끊을 턴이 없다`() = runTest {
        // 시작하지 않은 턴에 대고 제어 요청을 보내면 그 말이 시작하자마자 첫 낱말에서 끊긴다.
        val opener = RecordingChatSessionOpener()
        val holder = stateHolder(opener)
        holder.enterSessionContinuingConversation(SessionTarget.Main)
        runCurrent()
        holder.sendUserMessage(SessionTarget.Main, "긴 답을 줘")
        holder.sendUserMessage(SessionTarget.Main, "큐에 드는 말")
        runCurrent()
        opener.lastSession.emit(echoOfPending(holder, "긴 답을 줘"))
        opener.lastSession.emit(finishedTurn())
        runCurrent()

        holder.interruptTurn(SessionTarget.Main)
        runCurrent()

        assertEquals(0, opener.lastSession.interruptCount)
        assertEquals(TurnState.Idle, conversationOf(holder, SessionTarget.Main).turnState)
    }

    @Test
    fun `도는 턴을 끊으면 그 요청이 세션에 실린다`() = runTest {
        val opener = RecordingChatSessionOpener()
        val holder = stateHolder(opener)
        holder.enterSessionContinuingConversation(SessionTarget.Main)
        runCurrent()
        holder.sendUserMessage(SessionTarget.Main, "아주 긴 답을 줘")
        opener.lastSession.emit(echoOfPending(holder, "아주 긴 답을 줘"))
        runCurrent()

        holder.interruptTurn(SessionTarget.Main)
        runCurrent()

        assertEquals(1, opener.lastSession.interruptCount)
        // 아직 끊긴 것이 아니라 끊어 달라고 말한 것이다. 여기서 곧바로 Idle 로 두면 화면은 턴이
        // 끝났다고 말하는데 글자는 계속 흐른다 — 끝났다는 사실은 result 가 말한다(3.8).
        assertEquals(TurnState.Interrupting, conversationOf(holder, SessionTarget.Main).turnState)
    }

    @Test
    fun `끊긴 턴이 배지로 남고 입력이 다시 열린다`() = runTest {
        val opener = RecordingChatSessionOpener()
        val holder = stateHolder(opener)
        holder.enterSessionContinuingConversation(SessionTarget.Main)
        runCurrent()
        holder.sendUserMessage(SessionTarget.Main, "아주 긴 답을 줘")
        opener.lastSession.emit(echoOfPending(holder, "아주 긴 답을 줘"))
        runCurrent()
        holder.interruptTurn(SessionTarget.Main)
        runCurrent()

        opener.lastSession.emit(finishedTurn(outcome = TurnOutcome.Interrupted))
        runCurrent()

        val conversation = conversationOf(holder, SessionTarget.Main)
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

        holder.interruptTurn(SessionTarget.Main)
        runCurrent()

        assertEquals(0, opener.lastSession.interruptCount)
        assertEquals(TurnState.Idle, conversationOf(holder, SessionTarget.Main).turnState)
    }

    @Test
    fun `이미 끊어 달라고 말한 턴에 두 번 말하지 않는다`() = runTest {
        val opener = RecordingChatSessionOpener()
        val holder = stateHolder(opener)
        holder.enterSessionContinuingConversation(SessionTarget.Main)
        runCurrent()
        holder.sendUserMessage(SessionTarget.Main, "아주 긴 답을 줘")
        opener.lastSession.emit(echoOfPending(holder, "아주 긴 답을 줘"))
        runCurrent()

        holder.interruptTurn(SessionTarget.Main)
        holder.interruptTurn(SessionTarget.Main)
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

        val failed = assertIs<SessionEntryState.Failed>(holder.sessionEntries.value[SessionTarget.Repo("proj-a")])
        assertEquals("TerminalSessionFailure.KyuExecutableNotFound", failed.failure.kind)
        assertEquals(emptyList(), holder.heldSessions.value.map { it.target })
        assertIs<DiagnosticLogEntry.SessionEntryFailed>(diagnosticLog.recordedEntries.single())
    }

    @Test
    fun `보유 세션의 대상은 보유 목록이 바뀌는 그 자리에서 함께 바뀐다`() = runTest {
        // 화면이 읽는 것은 대상뿐이다(core-ui-split 6.2 (가)). 따로 파생하면 한 바퀴 늦고, 그 사이 화면은 이미 끝난
        // 세션을 보유 중으로 그린다.
        val opener = RecordingChatSessionOpener()
        val holder = stateHolder(opener)
        holder.enterSessionContinuingConversation(SessionTarget.Main)
        runCurrent()
        assertEquals(setOf<SessionTarget>(SessionTarget.Main), holder.heldSessionTargets.value)

        opener.lastSession.emit(ChatSessionEvent.SessionEnded(exitCode = 0))
        runCurrent()

        assertEquals(emptySet<SessionTarget>(), holder.heldSessionTargets.value)
        assertEquals(holder.heldSessions.value.map { it.target }.toSet(), holder.heldSessionTargets.value)
    }

    @Test
    fun `띄우는 사이 다른 세션을 열어도 앞 진입은 끝까지 가서 보유된다`() = runTest {
        // 어느 세션을 보는지는 화면의 것이라(core-ui-split 6.8), 이 자리는 사람이 다른 데로 갔다는 것을 모른다.
        // 띄운 세션은 버리지 않고 보유한다 — 사람이 그 줄을 다시 누르면 이미 떠 있다.
        val opener = RecordingChatSessionOpener()
        val holder = stateHolder(opener)

        holder.enterSessionContinuingConversation(SessionTarget.Main)
        holder.enterSessionContinuingConversation(SessionTarget.Repo("proj-a"))
        runCurrent()

        assertEquals(
            setOf<SessionTarget>(SessionTarget.Main, SessionTarget.Repo("proj-a")),
            holder.heldSessions.value.map { it.target }.toSet(),
        )
        assertEquals(0, opener.openedSessions.sumOf { it.endSessionCount })
    }

    @Test
    fun `띄우는 중인 세션을 다시 열라고 해도 두 번 띄우지 않는다`() = runTest {
        val opener = RecordingChatSessionOpener()
        val holder = stateHolder(opener)

        holder.enterSessionContinuingConversation(SessionTarget.Main)
        holder.enterSessionContinuingConversation(SessionTarget.Main)
        runCurrent()

        assertEquals(1, opener.openedPlans.size)
    }

    @Test
    fun `띄우는 중에 그 세션을 끝내면 뜨는 순간 끝낸다`() = runTest {
        // 끝낸 세션의 claude 가 뒤늦게 떠서 아무도 읽지 않은 채 사는 것을 막는다.
        val opener = RecordingChatSessionOpener()
        val holder = stateHolder(opener)
        holder.enterSessionContinuingConversation(SessionTarget.Main)

        holder.endSession(SessionTarget.Main)
        runCurrent()

        assertEquals(1, opener.lastSession.endSessionCount)
        assertEquals(emptyList(), holder.heldSessions.value)
        assertEquals(emptyMap(), holder.sessionEntries.value)
        assertEquals(emptyMap(), holder.conversations.value)
    }

    @Test
    fun `띄우지 못한 진입을 닫으면 그 실패만 빠진다`() = runTest {
        // 실패는 그 세션의 진입 상태다(core-ui-split 6.8 (가)). 닫는 길이 없으면 사람이 다른 데로 간 뒤에도 영영 남는다.
        val commandSource = RecordingSessionCommandSource().apply {
            respondWith { throw TerminalSessionFailure.KyuExecutableNotFound() }
        }
        val holder = stateHolder(RecordingChatSessionOpener(), commandSource)
        holder.enterSessionContinuingConversation(SessionTarget.Repo("proj-a"))
        runCurrent()
        holder.enterSessionContinuingConversation(SessionTarget.Main)

        holder.dismissSessionEntryFailure(SessionTarget.Repo("proj-a"))
        holder.dismissSessionEntryFailure(SessionTarget.Main)

        // 띄우는 중인 진입은 실패가 아니라 닫히지 않는다 — 닫으면 뜬 세션이 보유되는 순간을 화면이 모른다.
        assertEquals(mapOf<PersonOpenedSessionTarget, SessionEntryState>(SessionTarget.Main to SessionEntryState.Running), holder.sessionEntries.value)
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
        val conversation = conversationOf(holder, SessionTarget.Main)
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

        val conversation = conversationOf(holder, SessionTarget.Main)
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

        holder.endSession(SessionTarget.Main)
        runCurrent()

        assertEquals(1, opener.lastSession.endSessionCount)
        assertEquals(emptyMap(), holder.conversations.value)
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
        val conversation = conversationOf(holder, SessionTarget.Main)
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
        assertEquals(emptyMap(), holder.conversations.value)
    }

    @Test
    fun `엔진에게 물을 때 앱이 연 승인 소켓의 자리를 함께 준다`() = runTest {
        // 소켓을 여는 것은 앱이고(결정을 내리는 쪽이므로), 그 자리를 claude 의 어느 플래그로
        // 옮길지는 엔진만 안다(설계 5.2 · 원칙 11).
        val commandSource = RecordingSessionCommandSource()
        val holder = stateHolder(RecordingChatSessionOpener(), commandSource)

        holder.enterSessionContinuingConversation(SessionTarget.Main)
        runCurrent()

        val askedSocketPath = commandSource.askedApprovalSocketPaths.single()
        assertEquals(TEST_APPROVAL_SOCKET_DIRECTORY, askedSocketPath?.parent)
        // 워크디렉토리 이름이 아무리 길어도 소켓은 짧은 런타임 자리에 열린다(실측 3.7 의 107 바이트).
        assertTrue(
            askedSocketPath.toString().toByteArray().size <= 107,
            "소켓 경로가 107 바이트를 넘습니다: $askedSocketPath",
        )
    }

    @Test
    fun `올라온 승인 물음이 전사에 카드로 선다`() = runTest {
        val channels = mutableListOf<FakeAppSocketChannel>()
        val holder = stateHolder(RecordingChatSessionOpener(), permissionChannels = channels)
        holder.enterSessionContinuingConversation(SessionTarget.Main)
        runCurrent()

        channels.single().ask("Write", "toolu_01", writeInput("/tmp/probe.txt"))
        runCurrent()

        val card = assertIs<ChatEntry.PermissionAsked>(conversationOf(holder, SessionTarget.Main).entries.single())
        assertEquals("Write", card.toolName)
        assertEquals("toolu_01", card.toolUseId)
        assertEquals(null, card.answer)
    }

    @Test
    fun `허용을 누르면 그 결정이 통로로 가고 카드에 남는다`() = runTest {
        val channels = mutableListOf<FakeAppSocketChannel>()
        val holder = stateHolder(RecordingChatSessionOpener(), permissionChannels = channels)
        holder.enterSessionContinuingConversation(SessionTarget.Main)
        runCurrent()
        val asked = channels.single().ask("Write", "toolu_01", writeInput("/tmp/probe.txt"))
        runCurrent()

        holder.answerPermission(SessionTarget.Main, "toolu_01", PermissionCardChoice.AllowOnce(asked.request.input))
        runCurrent()

        assertEquals(PermissionDecision.Allow(asked.request.input), asked.decisionSoFar())
        val card = assertIs<ChatEntry.PermissionAsked>(conversationOf(holder, SessionTarget.Main).entries.single())
        // 고치지 않았으므로 고친 인자는 없다 — 원본과 같은 값을 담아 두면 화면이 둘을 가르지 못한다.
        assertEquals(PermissionAnswer.Allowed(editedInput = null), card.answer)
    }

    @Test
    fun `인자를 고쳐서 허용하면 고친 것이 통로로 가고 카드가 그 사실을 남긴다`() = runTest {
        // 고친 대로 실제로 실행된다(실측 3.5) — 그래서 이 자리의 편집은 시늉이 아니다.
        val channels = mutableListOf<FakeAppSocketChannel>()
        val holder = stateHolder(RecordingChatSessionOpener(), permissionChannels = channels)
        holder.enterSessionContinuingConversation(SessionTarget.Main)
        runCurrent()
        val asked = channels.single().ask("Write", "toolu_01", writeInput("/tmp/probe.txt"))
        runCurrent()

        val editedInput = writeInput("/tmp/somewhere-else.txt")
        holder.answerPermission(SessionTarget.Main, "toolu_01", PermissionCardChoice.AllowOnce(editedInput))
        runCurrent()

        assertEquals(PermissionDecision.Allow(editedInput), asked.decisionSoFar())
        val card = assertIs<ChatEntry.PermissionAsked>(conversationOf(holder, SessionTarget.Main).entries.single())
        assertEquals(PermissionAnswer.Allowed(editedInput = editedInput), card.answer)
    }

    @Test
    fun `거부하면 그 이유가 그대로 모델에게 갈 결정이 된다`() = runTest {
        val channels = mutableListOf<FakeAppSocketChannel>()
        val holder = stateHolder(RecordingChatSessionOpener(), permissionChannels = channels)
        holder.enterSessionContinuingConversation(SessionTarget.Main)
        runCurrent()
        val asked = channels.single().ask("Write", "toolu_01", writeInput("/tmp/probe.txt"))
        runCurrent()

        holder.answerPermission(SessionTarget.Main, "toolu_01", PermissionCardChoice.Deny("이 파일은 손대지 마세요"))
        runCurrent()

        assertEquals(PermissionDecision.Deny("이 파일은 손대지 마세요"), asked.decisionSoFar())
        val card = assertIs<ChatEntry.PermissionAsked>(conversationOf(holder, SessionTarget.Main).entries.single())
        assertEquals(PermissionAnswer.Denied("이 파일은 손대지 마세요"), card.answer)
    }

    @Test
    fun `이 세션에서 계속 허용하면 같은 도구의 다음 물음은 묻지 않고 통과한다`() = runTest {
        // 규칙은 앱의 메모리에만 산다(설계 10 절 열린 질문 4 의 v1 결정). 그래도 통과한 것은
        // 카드로 남는다 — 규칙이 무엇을 허용했는지 볼 길이 없으면 그 규칙은 확인할 수 없다.
        val channels = mutableListOf<FakeAppSocketChannel>()
        val holder = stateHolder(RecordingChatSessionOpener(), permissionChannels = channels)
        holder.enterSessionContinuingConversation(SessionTarget.Main)
        runCurrent()
        val firstAsked = channels.single().ask("Write", "toolu_01", writeInput("/tmp/one.txt"))
        runCurrent()
        holder.answerPermission(SessionTarget.Main, "toolu_01", PermissionCardChoice.AllowForThisSession(firstAsked.request.input))
        runCurrent()

        val secondAsked = channels.single().ask("Write", "toolu_02", writeInput("/tmp/two.txt"))
        runCurrent()

        assertEquals(PermissionDecision.Allow(secondAsked.request.input), secondAsked.decisionSoFar())
        val cards = conversationOf(holder, SessionTarget.Main).entries.filterIsInstance<ChatEntry.PermissionAsked>()
        assertEquals(PermissionAnswer.AllowedForThisSession, cards.first().answer)
        // 사람이 누른 것과 규칙이 통과시킨 것을 가른다. 같은 모양으로 그리면 사용자는 자기가
        // 보지 않은 승인을 자기가 한 것으로 읽는다.
        assertEquals(PermissionAnswer.AllowedByThisSessionRule, cards.last().answer)
    }

    @Test
    fun `다른 도구는 세션 규칙을 타지 않는다`() = runTest {
        val channels = mutableListOf<FakeAppSocketChannel>()
        val holder = stateHolder(RecordingChatSessionOpener(), permissionChannels = channels)
        holder.enterSessionContinuingConversation(SessionTarget.Main)
        runCurrent()
        val writeAsked = channels.single().ask("Write", "toolu_01", writeInput("/tmp/one.txt"))
        runCurrent()
        holder.answerPermission(SessionTarget.Main, "toolu_01", PermissionCardChoice.AllowForThisSession(writeAsked.request.input))
        runCurrent()

        channels.single().ask("Bash", "toolu_02", writeInput("/tmp/two.txt"))
        runCurrent()

        val cards = conversationOf(holder, SessionTarget.Main).entries.filterIsInstance<ChatEntry.PermissionAsked>()
        assertEquals(null, cards.last().answer, "Write 를 허용한 규칙이 Bash 까지 통과시켰습니다")
    }

    @Test
    fun `세션을 끝내면 승인 통로도 닫고 그 세션의 규칙도 버린다`() = runTest {
        val channels = mutableListOf<FakeAppSocketChannel>()
        val holder = stateHolder(RecordingChatSessionOpener(), permissionChannels = channels)
        holder.enterSessionContinuingConversation(SessionTarget.Main)
        runCurrent()
        val asked = channels.single().ask("Write", "toolu_01", writeInput("/tmp/one.txt"))
        runCurrent()
        holder.answerPermission(SessionTarget.Main, "toolu_01", PermissionCardChoice.AllowForThisSession(asked.request.input))
        runCurrent()

        holder.endSession(SessionTarget.Main)
        runCurrent()

        assertEquals(1, channels.single().closeCount)

        // 같은 세션을 다시 열면 규칙은 없다 — 세션 수명이 그 규칙의 수명이다.
        holder.enterSessionContinuingConversation(SessionTarget.Main)
        runCurrent()
        channels.last().ask("Write", "toolu_02", writeInput("/tmp/two.txt"))
        runCurrent()

        val card = assertIs<ChatEntry.PermissionAsked>(conversationOf(holder, SessionTarget.Main).entries.single())
        assertEquals(null, card.answer, "앞 세션의 규칙이 새 세션까지 살아남았습니다")
    }

    @Test
    fun `세션이 스스로 끝나면 답하지 못한 카드가 거절로 닫힌다`() = runTest {
        // 소켓이 닫히면 kyu mcp ask 는 EOF 를 거절로 읽는다(mcp_ask.go). 화면에만 버튼이 살아
        // 있으면 사용자는 누를 수 있는 척하는 카드를 보게 된다.
        val opener = RecordingChatSessionOpener()
        val channels = mutableListOf<FakeAppSocketChannel>()
        val holder = stateHolder(opener, permissionChannels = channels)
        holder.enterSessionContinuingConversation(SessionTarget.Main)
        runCurrent()
        channels.single().ask("Write", "toolu_01", writeInput("/tmp/one.txt"))
        runCurrent()

        opener.lastSession.emit(ChatSessionEvent.SessionEnded(exitCode = 0))
        runCurrent()

        val card = assertIs<ChatEntry.PermissionAsked>(conversationOf(holder, SessionTarget.Main).entries.single())
        assertIs<PermissionAnswer.Denied>(card.answer)
    }

    private fun ChatSessionStateHolder.enterSessionContinuingConversation(target: PersonOpenedSessionTarget) =
        enterSession(WORK_DIR_PATH, target, SessionConversationChoice.ContinueRecordedConversation)

    /** Write 도구의 인자 하나. 무엇을 쓰는지가 카드에서 읽혀야 하므로 경로를 담는다. */
    private fun writeInput(filePath: String): JsonObject = buildJsonObject {
        put("file_path", filePath)
        put("content", "WRITTEN\n")
    }

    private fun conversationOf(holder: ChatSessionStateHolder, target: SessionTarget): ChatConversation =
        assertNotNull(holder.conversations.value[target], "$target 의 대화가 없습니다")

    /**
     * 기다리는 말 하나가 되돌아온 모습 — `claude` 가 앱이 정한 uuid 를 그대로 실어 돌려준다
     * (session-tree 3.5 (다)). uuid 는 홀더가 지었으므로 시험이 지어내지 않고 대기 목록에서 읽는다.
     */
    private fun echoOfPending(holder: ChatSessionStateHolder, text: String) = ChatSessionEvent.UserMessageEchoed(
        text = text,
        parentToolUseId = null,
        messageUuid = conversationOf(holder, SessionTarget.Main).pendingUserMessages.first { it.text == text }.uuid,
    )

    private fun finishedTurn(
        costUsd: Double = 0.25,
        outcome: TurnOutcome = TurnOutcome.Completed,
    ) = ChatSessionEvent.TurnFinished(
        outcome = outcome,
        costUsd = costUsd,
        usage = TurnUsage(inputTokens = 3, outputTokens = 41, cacheReadInputTokens = 0, cacheCreationInputTokens = 0),
        permissionDenials = emptyList(),
        durationMillis = 1_200,
        resultText = "끝",
    )

    private companion object {
        val WORK_DIR_PATH: Path = TEST_WORK_DIR_PATH

        val ENGINE_ANSWERED_COMMAND = TEST_ENGINE_ANSWERED_COMMAND

        const val RESUMED_ID = "211f6974-88a8-4453-9248-a02b0d6febae"
    }
}
