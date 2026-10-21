package com.kyuchestration.desktop.terminal.chat

import com.kyuchestration.desktop.terminal.PersonOpenedSessionTarget
import com.kyuchestration.desktop.terminal.SessionCommandAnswer
import com.kyuchestration.desktop.terminal.SessionConversationChoice
import com.kyuchestration.desktop.terminal.SessionTarget
import com.kyuchestration.desktop.terminal.TerminalSessionFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject

/**
 * 메인이 앱 소켓으로 하위 세션을 띄우고 거두고 묻는 자리(session-tree-design.md 6.2 · 6.11).
 *
 * 진짜 `claude` 도 진짜 kyu 도 부르지 않는다. 여기서 갈리는 것은 "어느 소켓의 요청을 받는가 · 엔진에게 무엇을
 * 묻는가 · 트리에 무엇이 서는가 · 상위에게 무엇을 답하는가" 이고, 그 답에 프로세스가 필요하지 않다.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ChildSessionStateHolderTest {

    @Test
    fun `메인이 spawn 하면 엔진에게 하위로 묻고 띄운 뒤 그 사실을 답한다`() = runTest {
        val tree = sessionTreeTestBed()

        val asked = tree.mainChannel().askSessionTree(spawn("proj-a 테스트를 고쳐 줘", cwd = "proj-a"))
        runCurrent()

        assertEquals(
            SessionTreeAnswer.Spawned(
                childId = "child-1",
                relativeCwd = "proj-a",
                title = "proj-a",
                conversationId = "conversation-child-1",
                notice = null,
            ),
            asked.answerSoFar(),
        )
        assertEquals("proj-a", tree.childSource.askedRequests.single().requestedCwd)
        // 하위에게도 자기 소켓이 따로 있다 — 연결 자체가 곧 어느 세션인지다(6.4).
        assertEquals(tree.channels[1].socketPath, tree.childSource.askedAppSocketPaths.single())
        assertEquals(listOf<SessionTarget>(SessionTarget.Main, SessionTarget.Child("child-1")), tree.holder.heldSessions.value.map { it.target })
        assertEquals(ChildLifecycle.Running, tree.holder.sessionTree.value.child("child-1")?.lifecycle)
    }

    @Test
    fun `하위를 띄워도 화면은 사람이 보던 세션에 그대로 있다`() = runTest {
        val tree = sessionTreeTestBed()

        tree.mainChannel().askSessionTree(spawn("일해", cwd = "proj-a"))
        runCurrent()

        assertEquals(SessionTarget.Main, tree.holder.state.value.target)
    }

    @Test
    fun `첫 프롬프트를 상위 세션의 말로 하위에게 넣는다`() = runTest {
        // 하위의 규약 문구가 '[상위 세션]' 으로 시작하는 말을 상위의 것으로 읽는다(session_command_child.go).
        val tree = sessionTreeTestBed()

        tree.mainChannel().askSessionTree(spawn("proj-a 테스트를 고쳐 줘", cwd = "proj-a"))
        runCurrent()

        assertEquals(listOf("[상위 세션] proj-a 테스트를 고쳐 줘"), tree.childSession(1).sentMessages.map { it.text })
        assertEquals(ChildWaitingFor.Model, tree.holder.sessionTree.value.child("child-1")?.waitingFor)
    }

    @Test
    fun `하위의 이름은 엔진이 지어 답한 것을 그대로 쓴다`() = runTest {
        // 이름을 짓는 길이 둘이면 어긋날 수 있다. 엔진은 기록을 보고 짓고(conversations.go 의 nextChildSessionID),
        // 앱은 그 답을 트리 · 화면 · 상위의 답에 그대로 싣는다.
        val tree = sessionTreeTestBed()
        tree.childSource.respondWith { tree.childSource.readyAnswerFor(it, childId = "child-7") }

        val asked = tree.mainChannel().askSessionTree(spawn("일해", cwd = "proj-a"))
        runCurrent()

        assertEquals("child-7", assertIs<SessionTreeAnswer.Spawned>(asked.answerSoFar()).childId)
        assertEquals(listOf("child-7"), tree.holder.sessionTree.value.children.map { it.childId })
        assertEquals(listOf<SessionTarget>(SessionTarget.Main, SessionTarget.Child("child-7")), tree.holder.heldSessions.value.map { it.target })
        assertEquals(listOf("[상위 세션] 일해"), tree.childSession(1).sentMessages.map { it.text })
    }

    @Test
    fun `엔진이 하위를 열며 남긴 경고가 그 하위의 챗에 선다`() = runTest {
        val tree = sessionTreeTestBed()
        tree.childSource.engineWarnings = listOf("하위 기록 하나를 버렸습니다")

        tree.mainChannel().askSessionTree(spawn("일해", cwd = "proj-a"))
        runCurrent()
        tree.holder.showChildSession("child-1")

        val conversation = assertIs<ChatScreenState.ChatOnScreen>(tree.holder.state.value).conversation
        assertEquals(listOf<ChatEntry>(ChatEntry.EngineNotice("하위 기록 하나를 버렸습니다")), conversation.entries)
    }

    @Test
    fun `두 번째 spawn 은 살아 있는 하위의 자리를 엔진에게 함께 준다`() = runTest {
        val tree = sessionTreeTestBed()

        tree.mainChannel().askSessionTree(spawn("하나", cwd = "proj-a"))
        runCurrent()
        val second = tree.mainChannel().askSessionTree(spawn("둘", cwd = "proj-b"))
        runCurrent()

        assertEquals("child-2", assertIs<SessionTreeAnswer.Spawned>(second.answerSoFar()).childId)
        assertEquals(listOf("proj-a"), tree.childSource.askedRequests.last().liveChildRelativeCwds)
    }

    @Test
    fun `메인이 아닌 세션의 소켓으로 온 spawn 은 거절하고 엔진에게 묻지도 않는다`() = runTest {
        // 하위가 하위를 낳는 길을 소켓에서도 막는다(6.3) — 레포 세션과 하위 세션의 소켓이 여기 든다.
        val tree = sessionTreeTestBed(firstSession = SessionTarget.Repo("proj-a"))

        val asked = tree.mainChannel().askSessionTree(spawn("몰래 띄워", cwd = "proj-b"))
        runCurrent()

        assertIs<SessionTreeAnswer.Refused>(asked.answerSoFar())
        assertTrue(tree.childSource.askedRequests.isEmpty())
    }

    @Test
    fun `하위의 소켓으로 온 spawn 도 거절한다`() = runTest {
        val tree = sessionTreeTestBed()
        tree.mainChannel().askSessionTree(spawn("일해", cwd = "proj-a"))
        runCurrent()

        val asked = tree.channels[1].askSessionTree(spawn("손자를 띄워", cwd = "proj-b"))
        runCurrent()

        assertIs<SessionTreeAnswer.Refused>(asked.answerSoFar())
        assertEquals(1, tree.childSource.askedRequests.size)
    }

    @Test
    fun `엔진이 거절하면 그 이유로 거절하고 하위의 소켓을 닫는다`() = runTest {
        val tree = sessionTreeTestBed()
        tree.childSource.respondWith {
            throw TerminalSessionFailure.SessionCommandRefused(
                targetLabel = "새 하위",
                exitCode = 1,
                arguments = emptyList(),
                standardError = "워크디렉토리 밖입니다: ../elsewhere",
            )
        }

        val asked = tree.mainChannel().askSessionTree(spawn("일해", cwd = "../elsewhere"))
        runCurrent()

        val refused = assertIs<SessionTreeAnswer.Refused>(asked.answerSoFar())
        assertTrue("워크디렉토리 밖입니다" in refused.reason, refused.reason)
        assertEquals(1, tree.channels[1].closeCount)
        assertEquals(emptyList(), tree.holder.sessionTree.value.children)
    }

    @Test
    fun `사람이 연 레포 세션과 같은 자리면 띄우되 그 사실을 답에 싣는다`() = runTest {
        // 막으면 레포 탭을 열어둔 것만으로 상위의 일이 막힌다. 알리면 상위가 사람에게 물을 수 있다(6.8).
        val tree = sessionTreeTestBed()
        tree.commandSource.respondWith {
            SessionCommandAnswer(
                command = TEST_ENGINE_ANSWERED_COMMAND,
                workingDirectory = TEST_WORK_DIR_PATH.resolve("proj-a"),
                environmentToAdd = emptyMap(),
                resumedConversationId = null,
                engineWarnings = emptyList(),
            )
        }
        tree.holder.enterSession(TEST_WORK_DIR_PATH, SessionTarget.Repo("proj-a"), SessionConversationChoice.ContinueRecordedConversation)
        runCurrent()

        val asked = tree.mainChannel().askSessionTree(spawn("일해", cwd = "proj-a"))
        runCurrent()

        val spawned = assertIs<SessionTreeAnswer.Spawned>(asked.answerSoFar())
        assertTrue(spawned.notice.orEmpty().contains("proj-a"), "알림이 없습니다: $spawned")
    }

    @Test
    fun `list 는 하위 전부를 답한다`() = runTest {
        val tree = sessionTreeTestBed()
        tree.mainChannel().askSessionTree(spawn("하나", cwd = "proj-a"))
        runCurrent()

        val asked = tree.mainChannel().askSessionTree(SessionTreeRequest.ListSessions)
        runCurrent()

        assertEquals(listOf("child-1"), assertIs<SessionTreeAnswer.Listed>(asked.answerSoFar()).children.map { it.childId })
    }

    @Test
    fun `end 는 하위를 끝내고 트리에 상위가 끝냈다고 남긴다`() = runTest {
        val tree = sessionTreeTestBed()
        tree.mainChannel().askSessionTree(spawn("하나", cwd = "proj-a"))
        runCurrent()

        val asked = tree.mainChannel().askSessionTree(SessionTreeRequest.EndSession("child-1"))
        runCurrent()

        assertEquals(SessionTreeAnswer.Ended("child-1"), asked.answerSoFar())
        assertEquals(1, tree.childSession(1).endSessionCount)
        assertEquals(1, tree.channels[1].closeCount)
        assertEquals(
            ChildEndReason.EndedByParent,
            assertIs<ChildLifecycle.Ended>(tree.holder.sessionTree.value.child("child-1")?.lifecycle).reason,
        )
        assertEquals(listOf<SessionTarget>(SessionTarget.Main), tree.holder.heldSessions.value.map { it.target })
    }

    @Test
    fun `끝난 하위의 자리는 다음 spawn 에 살아 있는 자리로 가지 않는다`() = runTest {
        val tree = sessionTreeTestBed()
        tree.mainChannel().askSessionTree(spawn("하나", cwd = "proj-a"))
        runCurrent()
        tree.mainChannel().askSessionTree(SessionTreeRequest.EndSession("child-1"))
        runCurrent()

        tree.mainChannel().askSessionTree(spawn("다시", cwd = "proj-a"))
        runCurrent()

        assertEquals(emptyList(), tree.childSource.askedRequests.last().liveChildRelativeCwds)
    }

    @Test
    fun `없는 하위를 끝내라고 하면 거절한다`() = runTest {
        val tree = sessionTreeTestBed()

        val asked = tree.mainChannel().askSessionTree(SessionTreeRequest.EndSession("child-9"))
        runCurrent()

        assertTrue("child-9" in assertIs<SessionTreeAnswer.Refused>(asked.answerSoFar()).reason)
    }

    @Test
    fun `하위가 스스로 끝나면 트리에 그 이유와 종료 코드가 남는다`() = runTest {
        val tree = sessionTreeTestBed()
        tree.mainChannel().askSessionTree(spawn("하나", cwd = "proj-a"))
        tree.mainChannel().askSessionTree(spawn("둘", cwd = "proj-b"))
        runCurrent()

        tree.childSession(1).emit(ChatSessionEvent.SessionEnded(exitCode = 0))
        tree.childSession(2).emit(ChatSessionEvent.SessionEnded(exitCode = 137))
        runCurrent()

        assertEquals(ChildLifecycle.Ended(ChildEndReason.Exited, 0), tree.holder.sessionTree.value.child("child-1")?.lifecycle)
        assertEquals(ChildLifecycle.Ended(ChildEndReason.Crashed, 137), tree.holder.sessionTree.value.child("child-2")?.lifecycle)
        // 하위가 끝나도 상위는 그대로다(원칙 17).
        assertEquals(listOf<SessionTarget>(SessionTarget.Main), tree.holder.heldSessions.value.map { it.target })
    }

    @Test
    fun `하위의 승인 물음이 걸려 있으면 트리가 승인을 기다린다고 말한다`() = runTest {
        val tree = sessionTreeTestBed()
        tree.mainChannel().askSessionTree(spawn("하나", cwd = "proj-a"))
        runCurrent()

        tree.channels[1].ask("Write", "toolu_child", buildJsonObject {})
        runCurrent()

        assertEquals(ChildWaitingFor.Approval, tree.holder.sessionTree.value.child("child-1")?.waitingFor)
    }

    private fun spawn(prompt: String, cwd: String?) =
        SessionTreeRequest.SpawnSession(prompt = prompt, requestedCwd = cwd, createDirectory = false, title = null)

    /** 메인(또는 고른 세션) 하나를 띄워 둔 홀더와 그 대역들. */
    private fun TestScope.sessionTreeTestBed(
        firstSession: PersonOpenedSessionTarget = SessionTarget.Main,
    ): SessionTreeTestBed {
        val opener = RecordingChatSessionOpener()
        val commandSource = RecordingSessionCommandSource()
        val childSource = RecordingChildSessionCommandSource()
        val channels = mutableListOf<FakeAppSocketChannel>()
        val holder = stateHolder(
            opener = opener,
            sessionCommandSource = commandSource,
            permissionChannels = channels,
            childSessionCommandSource = childSource,
        )
        holder.enterSession(TEST_WORK_DIR_PATH, firstSession, SessionConversationChoice.ContinueRecordedConversation)
        runCurrent()
        return SessionTreeTestBed(holder, opener, commandSource, childSource, channels)
    }

    private class SessionTreeTestBed(
        val holder: ChatSessionStateHolder,
        val opener: RecordingChatSessionOpener,
        val commandSource: RecordingSessionCommandSource,
        val childSource: RecordingChildSessionCommandSource,
        /** 연 차례대로 — 맨 앞이 처음 띄운 세션(보통 메인)의 소켓이다. */
        val channels: List<FakeAppSocketChannel>,
    ) {
        fun mainChannel(): FakeAppSocketChannel = channels.first()

        /** n 번째로 띄운 하위의 세션(1 부터). 맨 앞은 처음 띄운 세션이다. */
        fun childSession(ordinal: Int): FakeChatSession = opener.openedSessions[ordinal]
    }
}
