package com.kyuchestration.desktop.terminal.chat

import com.kyuchestration.desktop.terminal.ChildSessionCommandAnswer
import com.kyuchestration.desktop.terminal.ChildSessionGate
import com.kyuchestration.desktop.terminal.SessionConversationChoice
import com.kyuchestration.desktop.terminal.SessionTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * 수명은 위에서 아래로만 흐른다(session-tree-design.md 원칙 17 · 6.13).
 *
 * 상위가 끝나면 하위가 끝나고, 하위가 끝나도 상위는 그대로다. 앱이 닫힐 때는 하위가 먼저다 — 상위가 먼저
 * 끝나면 하위의 물음이 답 없이 끊긴다.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ChildSessionLifecycleStateHolderTest {

    @Test
    fun `사람이 메인을 끝내면 살아 있는 하위가 먼저 끝나고 트리가 빈다`() = runTest {
        val bed = mainWithChildrenTestBed(childCount = 2)

        bed.holder.endSession(SessionTarget.Main)
        runCurrent()

        assertEquals(setOf(bed.child(1), bed.child(2)), bed.opener.endedSessions.take(2).toSet())
        assertEquals(bed.main, bed.opener.endedSessions.last())
        assertEquals(emptyList(), bed.holder.heldSessions.value)
        // 트리는 그 메인의 것이다. 메인의 전사를 놓는 자리에서 하위들도 함께 놓는다.
        assertEquals(emptyList(), bed.holder.sessionTree.value.children)
    }

    @Test
    fun `메인이 스스로 끝나면 하위가 상위가 끝나서 끝났다고 남는다`() = runTest {
        val bed = mainWithChildrenTestBed(childCount = 2)

        bed.main.emit(ChatSessionEvent.SessionEnded(exitCode = 1))
        runCurrent()

        assertEquals(1, bed.child(1).endSessionCount)
        assertEquals(1, bed.child(2).endSessionCount)
        assertEquals(
            listOf(ChildEndReason.ParentEnded, ChildEndReason.ParentEnded),
            bed.holder.sessionTree.value.children.map { assertIs<ChildLifecycle.Ended>(it.lifecycle).reason },
        )
        assertEquals(emptyList(), bed.holder.heldSessions.value)
    }

    @Test
    fun `앱이 닫히면 하위를 먼저 끝내고 메인과 다른 세션을 나중에 끝낸다`() = runTest {
        val bed = mainWithChildrenTestBed(childCount = 2)

        bed.holder.endAllSessions()
        runCurrent()

        assertEquals(setOf(bed.child(1), bed.child(2)), bed.opener.endedSessions.take(2).toSet())
        assertEquals(bed.main, bed.opener.endedSessions.last())
        assertEquals(emptyList(), bed.holder.sessionTree.value.children)
    }

    @Test
    fun `하위가 끝나도 메인은 그대로다`() = runTest {
        val bed = mainWithChildrenTestBed(childCount = 1)

        bed.child(1).emit(ChatSessionEvent.SessionEnded(exitCode = 0))
        runCurrent()

        assertEquals(0, bed.main.endSessionCount)
        assertEquals(listOf<SessionTarget>(SessionTarget.Main), bed.holder.heldSessions.value.map { it.target })
    }

    @Test
    fun `사람이 하위 화면에서 끝내면 그 하위만 끝나고 화면은 메인으로 돌아간다`() = runTest {
        val bed = mainWithChildrenTestBed(childCount = 2)
        bed.child(1).emit(ChatSessionEvent.AssistantTextArrived("하위의 답", parentToolUseId = null))
        runCurrent()
        bed.holder.showChildSession("child-1")

        bed.holder.endSession(SessionTarget.Child("child-1"))
        runCurrent()

        assertEquals(1, bed.child(1).endSessionCount)
        assertEquals(0, bed.child(2).endSessionCount)
        assertEquals(0, bed.main.endSessionCount)
        assertEquals(
            ChildEndReason.EndedByHuman,
            assertIs<ChildLifecycle.Ended>(bed.holder.sessionTree.value.child("child-1")?.lifecycle).reason,
        )
        assertEquals(SessionTarget.Main, bed.holder.state.value.target)

        // 끝난 하위의 전사는 남는다 — 하위는 패널의 줄을 눌러 다시 띄우는 길이 없어 그것이 유일한 기록이다.
        bed.holder.showChildSession("child-1")
        val conversation = assertIs<ChatScreenState.ChatOnScreen>(bed.holder.state.value).conversation
        assertEquals(ChatEntry.AssistantSaid("하위의 답"), conversation.entries.last())
    }

    @Test
    fun `하위를 고르면 그 하위의 대화가 화면에 선다`() = runTest {
        val bed = mainWithChildrenTestBed(childCount = 1)

        bed.holder.showChildSession("child-1")

        assertEquals(SessionTarget.Child("child-1"), bed.holder.state.value.target)
    }

    @Test
    fun `관문을 기다리는 중에 메인이 끝나면 물음을 거두고 spawn 은 거절된다`() = runTest {
        val bed = mainWithChildrenTestBed(childCount = 0)
        bed.childSource.respondWith {
            ChildSessionCommandAnswer.ApprovalRequired(ChildSessionGate("proj-x", "sha256:x", emptyList()))
        }
        val asked = bed.mainChannel.askSessionTree(spawnRequest("proj-x"))
        runCurrent()

        bed.holder.endSession(SessionTarget.Main)
        runCurrent()

        assertEquals(emptyList(), bed.holder.gateQuestions.value)
        assertIs<SessionTreeAnswer.Refused>(asked.answerSoFar())
    }

    @Test
    fun `메인을 새로 열면 앞 메인의 하위들은 트리에서 사라진다`() = runTest {
        val bed = mainWithChildrenTestBed(childCount = 1)
        bed.main.emit(ChatSessionEvent.SessionEnded(exitCode = 1))
        runCurrent()

        bed.holder.enterSession(TEST_WORK_DIR_PATH, SessionTarget.Main, SessionConversationChoice.ContinueRecordedConversation)
        runCurrent()

        assertEquals(emptyList(), bed.holder.sessionTree.value.children)
    }
}
