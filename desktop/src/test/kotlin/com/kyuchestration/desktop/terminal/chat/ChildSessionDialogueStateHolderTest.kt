package com.kyuchestration.desktop.terminal.chat

import com.kyuchestration.desktop.terminal.SessionTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject

/**
 * 상위와 하위가 서로 말하는 길(session-tree-design.md 6.5 · 6.6 — 7 절 4b).
 *
 * 원칙 18 — **말은 상대가 지금 듣고 있는 통로로 간다.** 상대가 우리 도구 호출로 기다리고 있으면 그 호출의 답으로,
 * 그렇지 않으면 사용자 메시지로 간다. 진짜 `claude` 없이 그 갈림길만 본다 — 어느 통로로 갔는가는 대역 세션의
 * stdin 과 소켓 요청의 답으로 다 보인다.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ChildSessionDialogueStateHolderTest {

    @Test
    fun `send 는 하위의 stdin 에 상위의 말로 넣고 as_message 로 답한다`() = runTest {
        val bed = mainWithChildrenTestBed(childCount = 1)

        val asked = bed.mainChannel.askSessionTree(SessionTreeRequest.SendToSession("child-1", "포트는 5433 이다"))
        runCurrent()

        assertEquals(SessionTreeAnswer.DeliveredAsMessage, asked.answerSoFar())
        val sent = bed.child(1).sentMessages.last()
        assertEquals("[상위 세션] 포트는 5433 이다", sent.text)
        // 되돌아올 때 "상위로부터" 로 그릴 근거는 접두가 아니라 앱이 정한 uuid 와 함께 적어 둔 보낸 이다(6.12).
        assertEquals(UserMessageSender.ParentSession, sent.sender)
    }

    @Test
    fun `없는 하위에게 보내라고 하면 거절한다`() = runTest {
        val bed = mainWithChildrenTestBed(childCount = 0)

        val asked = bed.mainChannel.askSessionTree(SessionTreeRequest.SendToSession("child-9", "여보세요"))
        runCurrent()

        assertTrue("child-9" in assertIs<SessionTreeAnswer.Refused>(asked.answerSoFar()).reason)
    }

    @Test
    fun `끝난 하위에게 보내라고 하면 끝났다고 거절하고 stdin 에 쓰지 않는다`() = runTest {
        val bed = mainWithChildrenTestBed(childCount = 1)
        bed.mainChannel.askSessionTree(SessionTreeRequest.EndSession("child-1"))
        runCurrent()
        val sentBefore = bed.child(1).sentMessages.size

        val asked = bed.mainChannel.askSessionTree(SessionTreeRequest.SendToSession("child-1", "아직 있니"))
        runCurrent()

        val refused = assertIs<SessionTreeAnswer.Refused>(asked.answerSoFar())
        assertTrue("끝났습니다" in refused.reason, refused.reason)
        assertEquals(sentBefore, bed.child(1).sentMessages.size)
    }

    @Test
    fun `하위의 소켓으로 온 send 는 거절한다`() = runTest {
        // 하위끼리 말하는 길은 없다(6.3) — 하위의 kyu 서버에는 이 도구가 없지만, 소켓은 누가 보냈는지 고르지 않는다.
        val bed = mainWithChildrenTestBed(childCount = 2)

        val asked = bed.childChannel(1).askSessionTree(SessionTreeRequest.SendToSession("child-2", "몰래"))
        runCurrent()

        assertIs<SessionTreeAnswer.Refused>(asked.answerSoFar())
        assertEquals(listOf("[상위 세션] 일해"), bed.child(2).sentMessages.map { it.text })
    }

    @Test
    fun `사람이 하위 화면에서 친 말은 사람의 말로 간다`() = runTest {
        val bed = mainWithChildrenTestBed(childCount = 1)
        bed.holder.showChildSession("child-1")

        bed.holder.sendUserMessage("내가 직접 묻는다")
        runCurrent()

        assertEquals(UserMessageSender.Human, bed.child(1).sentMessages.last().sender)
        assertEquals(SessionTarget.Child("child-1"), bed.holder.state.value.target)
    }

    @Test
    fun `await 는 하위의 턴이 끝날 때까지 기다렸다가 그 결과를 담아 답한다`() = runTest {
        val bed = mainWithChildrenTestBed(childCount = 1)
        startMainTurn(bed)

        val asked = bed.mainChannel.askSessionTree(awaitRequest())
        runCurrent()
        assertNull(asked.answerSoFar(), "전할 일이 없는데 답했습니다")

        bed.child(1).finishTurnOnLastSentMessage(result = "포트를 5433 으로 고쳤다")
        runCurrent()

        val awaited = assertIs<SessionTreeAnswer.Awaited>(asked.answerSoFar())
        assertEquals(
            listOf<SessionTreeEvent>(
                SessionTreeEvent.TurnCompleted(
                    childId = "child-1",
                    result = "포트를 5433 으로 고쳤다",
                    isError = false,
                    triggeredBy = TurnTrigger.Parent,
                    costUsd = 0.25,
                    durationMillis = 1_200,
                ),
            ),
            awaited.events,
        )
        assertEquals(listOf("child-1"), awaited.stillRunning)
    }

    @Test
    fun `기다리기 전에 쌓인 사건은 다음 await 가 곧바로 다 받는다`() = runTest {
        // 상위가 깨어나 답을 쓰는 사이 다른 하위의 턴이 끝날 수 있다 — 잃지 않고 한 번에 담는다(6.6).
        val bed = mainWithChildrenTestBed(childCount = 2)
        startMainTurn(bed)
        bed.child(1).finishTurnOnLastSentMessage(result = "하나")
        bed.child(2).finishTurnOnLastSentMessage(result = "둘")
        runCurrent()

        val asked = bed.mainChannel.askSessionTree(awaitRequest())
        runCurrent()

        val awaited = assertIs<SessionTreeAnswer.Awaited>(asked.answerSoFar())
        assertEquals(listOf("하나", "둘"), awaited.events.map { assertIs<SessionTreeEvent.TurnCompleted>(it).result })
        assertEquals(listOf("child-1", "child-2"), awaited.stillRunning)
    }

    @Test
    fun `아무 일 없이 기다림 시간이 지나면 빈 사건과 살아 있는 하위로 답한다`() = runTest {
        val bed = mainWithChildrenTestBed(childCount = 1)
        startMainTurn(bed)

        val asked = bed.mainChannel.askSessionTree(awaitRequest(waitSeconds = 60))
        advanceTimeBy(59_000)
        runCurrent()
        assertNull(asked.answerSoFar())

        advanceTimeBy(1_001)
        runCurrent()

        assertEquals(SessionTreeAnswer.Awaited(events = emptyList(), stillRunning = listOf("child-1")), asked.answerSoFar())
    }

    @Test
    fun `ids 로 고른 하위의 사건만 깨우고 나머지는 다음 기다림에 남는다`() = runTest {
        val bed = mainWithChildrenTestBed(childCount = 2)
        startMainTurn(bed)
        val waitingForSecond = bed.mainChannel.askSessionTree(awaitRequest(childIds = listOf("child-2")))
        runCurrent()

        bed.child(1).finishTurnOnLastSentMessage(result = "하나")
        runCurrent()
        assertNull(waitingForSecond.answerSoFar(), "고르지 않은 하위의 사건에 깨어났습니다")

        bed.child(2).finishTurnOnLastSentMessage(result = "둘")
        runCurrent()
        val second = assertIs<SessionTreeAnswer.Awaited>(waitingForSecond.answerSoFar())
        assertEquals(listOf("둘"), second.events.map { assertIs<SessionTreeEvent.TurnCompleted>(it).result })
        assertEquals(listOf("child-2"), second.stillRunning)

        val next = bed.mainChannel.askSessionTree(awaitRequest())
        runCurrent()
        assertEquals(
            listOf("하나"),
            assertIs<SessionTreeAnswer.Awaited>(next.answerSoFar()).events.map { assertIs<SessionTreeEvent.TurnCompleted>(it).result },
        )
    }

    @Test
    fun `하위가 스스로 끝나면 session_ended 로 깨운다`() = runTest {
        val bed = mainWithChildrenTestBed(childCount = 1)
        startMainTurn(bed)
        val asked = bed.mainChannel.askSessionTree(awaitRequest())
        runCurrent()

        bed.child(1).emit(ChatSessionEvent.SessionEnded(exitCode = 137))
        runCurrent()

        assertEquals(
            SessionTreeAnswer.Awaited(
                events = listOf(SessionTreeEvent.SessionEnded("child-1", ChildEndReason.Crashed, exitCode = 137)),
                stillRunning = emptyList(),
            ),
            asked.answerSoFar(),
        )
    }

    @Test
    fun `상위가 끝낸 하위도 다음 기다림이 session_ended 로 받는다`() = runTest {
        // 6.13 — 상위의 다음 await_session 이 session_ended 를 받는다.
        val bed = mainWithChildrenTestBed(childCount = 1)
        startMainTurn(bed)
        bed.mainChannel.askSessionTree(SessionTreeRequest.EndSession("child-1"))
        runCurrent()

        val asked = bed.mainChannel.askSessionTree(awaitRequest())
        runCurrent()

        assertEquals(
            SessionTreeAnswer.Awaited(
                events = listOf(SessionTreeEvent.SessionEnded("child-1", ChildEndReason.EndedByParent, exitCode = null)),
                stillRunning = emptyList(),
            ),
            asked.answerSoFar(),
        )
    }

    @Test
    fun `기다릴 살아 있는 하위도 쌓인 사건도 없으면 곧바로 빈 사건으로 답한다`() = runTest {
        // 기다려도 올 것이 없다. waitSeconds 를 다 채우면 상위의 턴이 이유 없이 그만큼 묶인다.
        val bed = mainWithChildrenTestBed(childCount = 0)
        startMainTurn(bed)

        val asked = bed.mainChannel.askSessionTree(awaitRequest())
        runCurrent()

        assertEquals(SessionTreeAnswer.Awaited(events = emptyList(), stillRunning = emptyList()), asked.answerSoFar())
    }

    @Test
    fun `사람이 하위 화면에서 시킨 턴은 사람이 시킨 것으로 상위에게 간다`() = runTest {
        // 상위는 자기가 시키지 않은 턴의 결과를 받는다 — 그 사실을 알아야 결과를 제 일의 답으로 읽지 않는다(6.6).
        val bed = mainWithChildrenTestBed(childCount = 1)
        startMainTurn(bed)
        bed.child(1).finishTurnOnLastSentMessage(result = "첫 일")
        bed.holder.showChildSession("child-1")
        bed.holder.sendUserMessage("이것도 해 줘")
        runCurrent()
        bed.child(1).finishTurnOnLastSentMessage(result = "사람의 일", outcome = TurnOutcome.Failed)
        runCurrent()

        val asked = bed.mainChannel.askSessionTree(awaitRequest())
        runCurrent()

        val events = assertIs<SessionTreeAnswer.Awaited>(asked.answerSoFar()).events
            .map { assertIs<SessionTreeEvent.TurnCompleted>(it) }
        assertEquals(listOf(TurnTrigger.Parent, TurnTrigger.Human), events.map { it.triggeredBy })
        assertEquals(listOf(false, true), events.map { it.isError })
    }

    @Test
    fun `없는 하위를 기다리라고 하면 거절한다`() = runTest {
        val bed = mainWithChildrenTestBed(childCount = 1)

        val asked = bed.mainChannel.askSessionTree(awaitRequest(childIds = listOf("child-1", "child-9")))
        runCurrent()

        assertTrue("child-9" in assertIs<SessionTreeAnswer.Refused>(asked.answerSoFar()).reason)
    }

    @Test
    fun `하위의 소켓으로 온 await 는 거절한다`() = runTest {
        val bed = mainWithChildrenTestBed(childCount = 1)

        val asked = bed.childChannel(1).askSessionTree(awaitRequest())
        runCurrent()

        assertIs<SessionTreeAnswer.Refused>(asked.answerSoFar())
    }

    @Test
    fun `쉬는 상위에게는 하위의 턴 끝이 하위의 말로 상위의 stdin 에 간다`() = runTest {
        // 6.6 의 마지막 줄 — 상위는 하위를 띄우고 턴을 끝내도 된다. 하위가 끝나면 앱이 상위를 깨운다.
        val bed = mainWithChildrenTestBed(childCount = 1)

        bed.child(1).finishTurnOnLastSentMessage(result = "포트를 고쳤다")
        runCurrent()

        val told = bed.main.sentMessages.single()
        assertEquals("[하위 세션 child-1 · 턴 끝] 포트를 고쳤다", told.text)
        assertEquals(UserMessageSender.ChildSession("child-1"), told.sender)
    }

    @Test
    fun `stdin 으로 간 사건은 다음 기다림이 다시 받지 않는다`() = runTest {
        // 같은 사실이 두 길로 가면 상위는 같은 결과를 두 번 읽는다.
        val bed = mainWithChildrenTestBed(childCount = 1)
        bed.child(1).finishTurnOnLastSentMessage(result = "포트를 고쳤다")
        runCurrent()
        bed.main.echoLastSentMessage()
        runCurrent()

        val asked = bed.mainChannel.askSessionTree(awaitRequest())
        runCurrent()

        assertNull(asked.answerSoFar())
    }

    @Test
    fun `턴 중인데 기다리지 않는 상위에게는 사건이 쌓였다가 그 턴이 끝날 때 stdin 으로 간다`() = runTest {
        // 이 턴 안에서 await 를 부르면 그 답으로 받는다. 부르지 않고 턴을 끝내면 그때 사용자 메시지로 받는다 — 어느 쪽이든 한 번이다.
        val bed = mainWithChildrenTestBed(childCount = 1)
        startMainTurn(bed)
        val sentBefore = bed.main.sentMessages.size

        bed.child(1).finishTurnOnLastSentMessage(result = "포트를 고쳤다")
        runCurrent()
        assertEquals(sentBefore, bed.main.sentMessages.size, "턴 중인 상위의 stdin 에 썼습니다")

        bed.main.emit(turnFinishedWith("하위를 띄웠다"))
        runCurrent()

        assertEquals("[하위 세션 child-1 · 턴 끝] 포트를 고쳤다", bed.main.sentMessages.last().text)
    }

    @Test
    fun `쉬는 상위에게는 하위가 끝난 것도 그 이유와 함께 간다`() = runTest {
        val bed = mainWithChildrenTestBed(childCount = 1)

        bed.child(1).emit(ChatSessionEvent.SessionEnded(exitCode = 137))
        runCurrent()

        assertEquals("[하위 세션 child-1 · 끝남] 비정상으로 끝났습니다 (종료 코드 137)", bed.main.sentMessages.single().text)
    }

    @Test
    fun `사람이 시킨 턴의 끝과 실패한 턴의 끝은 접두가 그 사실을 말한다`() = runTest {
        val bed = mainWithChildrenTestBed(childCount = 1)
        bed.holder.showChildSession("child-1")
        bed.holder.sendUserMessage("이것도 해 줘")
        runCurrent()

        bed.child(1).finishTurnOnLastSentMessage(result = "못 했다", outcome = TurnOutcome.Failed)
        runCurrent()

        assertEquals("[하위 세션 child-1 · 턴 끝 · 실패 · 사람이 시킨 턴] 못 했다", bed.main.sentMessages.single().text)
    }

    @Test
    fun `사람이 기다리는 상위에게 말하면 user_spoke 로 곧바로 깨우고 그 말은 stdin 으로 간다`() = runTest {
        // 6.5 의 셋째 줄 — 사람의 말은 stdin 으로 가서 도구 결과 바로 뒤에 같은 턴으로 붙는다(3.1). 기다림이 그 말에도
        // 일찍 끝나야 사람이 하위를 기다리는 상위와 대화할 수 있다.
        val bed = mainWithChildrenTestBed(childCount = 1)
        startMainTurn(bed)
        val asked = bed.mainChannel.askSessionTree(awaitRequest())
        runCurrent()

        bed.holder.sendUserMessage("그만 기다리고 지금까지 상황을 말해")
        runCurrent()

        assertEquals(
            SessionTreeAnswer.Awaited(events = listOf(SessionTreeEvent.UserSpoke), stillRunning = listOf("child-1")),
            asked.answerSoFar(),
        )
        val said = bed.main.sentMessages.last()
        assertEquals("그만 기다리고 지금까지 상황을 말해", said.text)
        assertEquals(UserMessageSender.Human, said.sender)
    }

    @Test
    fun `user_spoke 로 깨울 때 쌓여 있던 그 기다림의 사건도 함께 담는다`() = runTest {
        val bed = mainWithChildrenTestBed(childCount = 2)
        startMainTurn(bed)
        val asked = bed.mainChannel.askSessionTree(awaitRequest(childIds = listOf("child-2")))
        runCurrent()
        bed.child(1).finishTurnOnLastSentMessage(result = "고르지 않은 하위의 일")
        runCurrent()

        bed.holder.sendUserMessage("어떻게 돼 가?")
        runCurrent()

        assertEquals(listOf<SessionTreeEvent>(SessionTreeEvent.UserSpoke), assertIs<SessionTreeAnswer.Awaited>(asked.answerSoFar()).events)
        // 고르지 않은 하위의 사건은 여전히 남아 다음 기다림이 받는다.
        val next = bed.mainChannel.askSessionTree(awaitRequest())
        runCurrent()
        assertEquals(
            listOf("고르지 않은 하위의 일"),
            assertIs<SessionTreeAnswer.Awaited>(next.answerSoFar()).events.map { assertIs<SessionTreeEvent.TurnCompleted>(it).result },
        )
    }

    @Test
    fun `기다리지 않는 상위에게 사람이 말한 것은 사건으로 남지 않는다`() = runTest {
        // 사람의 말은 stdin 으로 이미 갔다. 나중의 기다림이 그것으로 깨면 상위는 이미 읽은 말 때문에 일찍 깬다.
        val bed = mainWithChildrenTestBed(childCount = 1)
        startMainTurn(bed)

        bed.holder.sendUserMessage("참고로 테스트 DB 는 5433 이야")
        runCurrent()
        val asked = bed.mainChannel.askSessionTree(awaitRequest())
        runCurrent()

        assertNull(asked.answerSoFar())
    }

    @Test
    fun `사람이 하위에게 말하면 기다리는 상위는 깨지 않는다`() = runTest {
        val bed = mainWithChildrenTestBed(childCount = 1)
        startMainTurn(bed)
        val asked = bed.mainChannel.askSessionTree(awaitRequest())
        runCurrent()
        bed.holder.showChildSession("child-1")

        bed.holder.sendUserMessage("하위에게 직접")
        runCurrent()

        assertNull(asked.answerSoFar())
    }

    @Test
    fun `기다림의 연결이 끊기면 그 기다림만 지우고 하위는 그대로 둔다`() = runTest {
        // 사람이 "그만 기다려" 를 눌렀다고 하위의 일이 지워지면 안 된다(6.6 · 3.2). 하위를 끝내는 것은 end_session 의 일이다.
        val bed = mainWithChildrenTestBed(childCount = 1)
        startMainTurn(bed)
        val interrupted = bed.mainChannel.askSessionTree(awaitRequest())
        runCurrent()

        interrupted.closeConnection()
        runCurrent()
        bed.child(1).finishTurnOnLastSentMessage(result = "일은 계속됐다")
        runCurrent()

        assertNull(interrupted.answerSoFar(), "끊긴 기다림에 답했습니다")
        assertEquals(0, bed.child(1).endSessionCount)
        assertEquals(ChildLifecycle.Running, bed.holder.sessionTree.value.child("child-1")?.lifecycle)
        // 끊긴 기다림이 가져가지 못한 사건은 다음 기다림이 받는다.
        val next = bed.mainChannel.askSessionTree(awaitRequest())
        runCurrent()
        assertEquals(
            listOf("일은 계속됐다"),
            assertIs<SessionTreeAnswer.Awaited>(next.answerSoFar()).events.map { assertIs<SessionTreeEvent.TurnCompleted>(it).result },
        )
    }

    @Test
    fun `기다림이 끊긴 뒤 상위의 턴이 끝나면 쌓인 사건은 stdin 으로 간다`() = runTest {
        val bed = mainWithChildrenTestBed(childCount = 1)
        startMainTurn(bed)
        val interrupted = bed.mainChannel.askSessionTree(awaitRequest())
        runCurrent()
        interrupted.closeConnection()
        runCurrent()
        bed.child(1).finishTurnOnLastSentMessage(result = "일은 계속됐다")
        runCurrent()

        bed.main.emit(turnFinishedWith(null, TurnOutcome.InterruptedDuringToolWait))
        runCurrent()

        assertEquals("[하위 세션 child-1 · 턴 끝] 일은 계속됐다", bed.main.sentMessages.last().text)
    }

    @Test
    fun `하위의 승인 카드가 떠 있는 중에 연결이 끊기면 카드를 거두고 하위는 다시 모델을 기다린다`() = runTest {
        val bed = mainWithChildrenTestBed(childCount = 1)
        bed.child(1).echoLastSentMessage()
        val asked = bed.childChannel(1).ask("Bash", "toolu_child", buildJsonObject {})
        runCurrent()
        assertEquals(ChildWaitingFor.Approval, bed.holder.sessionTree.value.child("child-1")?.waitingFor)

        asked.closeConnection()
        runCurrent()

        bed.holder.showChildSession("child-1")
        val card = (bed.holder.state.value as ChatScreenState.ChatOnScreen).conversation.entries
            .filterIsInstance<ChatEntry.PermissionAsked>().single()
        assertEquals(PermissionAnswer.Withdrawn, card.answer)
        assertEquals(ChildWaitingFor.Model, bed.holder.sessionTree.value.child("child-1")?.waitingFor)
    }

    @Test
    fun `하위가 물으면 기다리던 상위가 그 물음을 담아 곧바로 깨어난다`() = runTest {
        // 완료 확인 ② — 상위가 await_session 중이었는데도 즉시 답한다. stdin 으로 보내면 기다림이 끝날 때까지 안 닿는다(3.1).
        val bed = mainWithChildrenTestBed(childCount = 1)
        startMainTurn(bed)
        val waiting = bed.mainChannel.askSessionTree(awaitRequest())
        runCurrent()

        val question = bed.childChannel(1).askSessionTree(SessionTreeRequest.AskParent("테스트 DB 포트는 5432? 5433?"))
        runCurrent()

        assertEquals(
            SessionTreeAnswer.Awaited(
                events = listOf(SessionTreeEvent.AskedParent("child-1", "테스트 DB 포트는 5432? 5433?", askId = "ask-1")),
                stillRunning = listOf("child-1"),
            ),
            waiting.answerSoFar(),
        )
        assertNull(question.answerSoFar(), "상위가 답하기 전에 하위의 물음이 풀렸습니다")
        assertEquals(ChildWaitingFor.Parent, bed.holder.sessionTree.value.child("child-1")?.waitingFor)
    }

    @Test
    fun `상위가 send 하면 하위가 묻는 중이었으므로 그 물음의 답이 된다`() = runTest {
        val bed = mainWithChildrenTestBed(childCount = 1)
        bed.child(1).echoLastSentMessage()
        startMainTurn(bed)
        val question = bed.childChannel(1).askSessionTree(SessionTreeRequest.AskParent("포트는?"))
        runCurrent()
        val sentToChildBefore = bed.child(1).sentMessages.size

        val sent = bed.mainChannel.askSessionTree(SessionTreeRequest.SendToSession("child-1", "5433 을 써"))
        runCurrent()

        assertEquals(SessionTreeAnswer.DeliveredAsAnswer(askId = "ask-1"), sent.answerSoFar())
        assertEquals(SessionTreeAnswer.ParentAnswered("5433 을 써"), question.answerSoFar())
        assertEquals(sentToChildBefore, bed.child(1).sentMessages.size, "답이 하위의 stdin 에도 갔습니다")
        assertEquals(ChildWaitingFor.Model, bed.holder.sessionTree.value.child("child-1")?.waitingFor)
    }

    @Test
    fun `물음에 답한 뒤의 send 는 다시 stdin 으로 간다`() = runTest {
        val bed = mainWithChildrenTestBed(childCount = 1)
        startMainTurn(bed)
        bed.childChannel(1).askSessionTree(SessionTreeRequest.AskParent("포트는?"))
        runCurrent()
        bed.mainChannel.askSessionTree(SessionTreeRequest.SendToSession("child-1", "5433"))
        runCurrent()

        val second = bed.mainChannel.askSessionTree(SessionTreeRequest.SendToSession("child-1", "그리고 README 도 고쳐"))
        runCurrent()

        assertEquals(SessionTreeAnswer.DeliveredAsMessage, second.answerSoFar())
        assertEquals("[상위 세션] 그리고 README 도 고쳐", bed.child(1).sentMessages.last().text)
    }

    @Test
    fun `쉬는 상위에게는 하위의 물음이 하위의 말로 stdin 에 간다`() = runTest {
        val bed = mainWithChildrenTestBed(childCount = 1)

        bed.childChannel(1).askSessionTree(SessionTreeRequest.AskParent("포트는 5432? 5433?"))
        runCurrent()

        val told = bed.main.sentMessages.single()
        assertEquals("[하위 세션 child-1 · 질문] 포트는 5432? 5433?", told.text)
        assertEquals(UserMessageSender.ChildSession("child-1"), told.sender)
    }

    @Test
    fun `턴 중인 상위가 나중에 기다리면 쌓인 물음을 곧바로 받는다`() = runTest {
        val bed = mainWithChildrenTestBed(childCount = 1)
        startMainTurn(bed)
        bed.childChannel(1).askSessionTree(SessionTreeRequest.AskParent("포트는?"))
        runCurrent()

        val waiting = bed.mainChannel.askSessionTree(awaitRequest())
        runCurrent()

        assertEquals(
            listOf<SessionTreeEvent>(SessionTreeEvent.AskedParent("child-1", "포트는?", askId = "ask-1")),
            assertIs<SessionTreeAnswer.Awaited>(waiting.answerSoFar()).events,
        )
    }

    @Test
    fun `물음의 연결이 끊기면 물음을 거두고 아직 전하지 못한 그 물음도 지운다`() = runTest {
        // 사람이 하위의 턴을 중단했다. 상위가 나중에 그 물음에 답하면 그 말은 아무도 기다리지 않는 답이 된다.
        val bed = mainWithChildrenTestBed(childCount = 1)
        bed.child(1).echoLastSentMessage()
        startMainTurn(bed)
        val question = bed.childChannel(1).askSessionTree(SessionTreeRequest.AskParent("포트는?"))
        runCurrent()

        question.closeConnection()
        runCurrent()

        assertEquals(ChildWaitingFor.Model, bed.holder.sessionTree.value.child("child-1")?.waitingFor)
        val waiting = bed.mainChannel.askSessionTree(awaitRequest())
        runCurrent()
        assertNull(waiting.answerSoFar(), "거둬진 물음이 상위에게 갔습니다")
        val sent = bed.mainChannel.askSessionTree(SessionTreeRequest.SendToSession("child-1", "5433"))
        runCurrent()
        assertEquals(SessionTreeAnswer.DeliveredAsMessage, sent.answerSoFar())
    }

    @Test
    fun `상위가 묻는 중인 하위를 끝내면 그 물음은 session_ended 로 답해진다`() = runTest {
        val bed = mainWithChildrenTestBed(childCount = 1)
        startMainTurn(bed)
        val question = bed.childChannel(1).askSessionTree(SessionTreeRequest.AskParent("포트는?"))
        runCurrent()

        bed.mainChannel.askSessionTree(SessionTreeRequest.EndSession("child-1"))
        runCurrent()

        assertEquals(SessionTreeAnswer.AskEnded(AskEndReason.SessionEnded), question.answerSoFar())
    }

    @Test
    fun `상위가 끝나면 하위의 물음은 parent_ended 로 답해진다`() = runTest {
        val bed = mainWithChildrenTestBed(childCount = 1)
        val question = bed.childChannel(1).askSessionTree(SessionTreeRequest.AskParent("포트는?"))
        runCurrent()

        bed.holder.endOnScreenSession()
        runCurrent()

        assertEquals(SessionTreeAnswer.AskEnded(AskEndReason.ParentEnded), question.answerSoFar())
    }

    @Test
    fun `하위가 묻는 중에 사람이 하위에게 친 말은 stdin 으로만 가고 물음은 그대로다`() = runTest {
        // 6.5 의 넷째 줄 — 물음은 상위에게 한 것이다. 사람이 그 답을 가로채면 상위는 답하지 않은 물음이 답해진 것을 모른다.
        val bed = mainWithChildrenTestBed(childCount = 1)
        val question = bed.childChannel(1).askSessionTree(SessionTreeRequest.AskParent("포트는?"))
        runCurrent()
        bed.holder.showChildSession("child-1")

        bed.holder.sendUserMessage("5433 이야")
        runCurrent()

        assertNull(question.answerSoFar())
        assertEquals("5433 이야", bed.child(1).sentMessages.last().text)
        assertEquals(ChildWaitingFor.Parent, bed.holder.sessionTree.value.child("child-1")?.waitingFor)
    }

    @Test
    fun `메인의 소켓으로 온 ask_parent 는 거절한다`() = runTest {
        // 메인에게는 물을 상위가 없다. 하위의 kyu 서버만 이 도구를 갖는다(6.3).
        val bed = mainWithChildrenTestBed(childCount = 0)

        val question = bed.mainChannel.askSessionTree(SessionTreeRequest.AskParent("누구에게?"))
        runCurrent()

        assertIs<SessionTreeAnswer.Refused>(question.answerSoFar())
    }

    @Test
    fun `list 는 하위의 마지막 턴 결과와 뜬 시각 끝난 시각을 답한다`() = runTest {
        val bed = mainWithChildrenTestBed(childCount = 2)
        startMainTurn(bed)
        bed.child(1).finishTurnOnLastSentMessage(result = "포트를 고쳤다")
        bed.mainChannel.askSessionTree(SessionTreeRequest.EndSession("child-2"))
        runCurrent()

        val listed = bed.mainChannel.askSessionTree(SessionTreeRequest.ListSessions)
        runCurrent()

        val (first, second) = assertIs<SessionTreeAnswer.Listed>(listed.answerSoFar()).children
        assertEquals(ChildTurnResult(result = "포트를 고쳤다", isError = false), first.lastTurnResult)
        assertEquals(TEST_CLOCK.instant(), first.startedAt)
        assertNull(first.endedAt)
        assertNull(second.lastTurnResult)
        assertEquals(TEST_CLOCK.instant(), second.endedAt)
    }

    @Test
    fun `상위의 턴이 끝나면 끊김이 아직 안 닿은 기다림을 닫고 그 뒤의 사건은 stdin 으로 간다`() = runTest {
        // 도구 호출은 턴을 넘기지 못한다. 턴이 끝났는데 기다림이 열려 있다면 그 연결은 이미 죽었고 EOF 만 아직 안 닿았다 —
        // 그 기다림에 사건을 담으면 아무도 읽지 않는 연결로 가서 영영 잃는다.
        val bed = mainWithChildrenTestBed(childCount = 1)
        startMainTurn(bed)
        val interrupted = bed.mainChannel.askSessionTree(awaitRequest())
        runCurrent()

        bed.main.emit(turnFinishedWith(null, TurnOutcome.InterruptedDuringToolWait))
        runCurrent()
        bed.child(1).finishTurnOnLastSentMessage(result = "끝난 뒤의 일")
        runCurrent()

        assertNull(interrupted.answerSoFar(), "턴이 끝난 기다림에 사건을 담았습니다")
        assertEquals("[하위 세션 child-1 · 턴 끝] 끝난 뒤의 일", bed.main.sentMessages.last().text)
    }

    private fun awaitRequest(childIds: List<String>? = null, waitSeconds: Int = 1800) =
        SessionTreeRequest.AwaitSessions(childIds = childIds, waitSeconds = waitSeconds)
}
