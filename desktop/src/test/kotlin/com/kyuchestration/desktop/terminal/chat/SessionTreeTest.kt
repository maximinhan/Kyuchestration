package com.kyuchestration.desktop.terminal.chat

import com.kyuchestration.desktop.terminal.SessionTarget
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 메인 아래 하위 세션들의 사실(session-tree-design.md 6.11).
 *
 * 프로세스도 소켓도 부르지 않는다. 여기서 갈리는 것은 "누가 살아 있는가 · 하위가 지금 무엇을
 * 기다리는가" 이고, 그 답은 값만으로 정해진다.
 */
class SessionTreeTest {

    @Test
    fun `살아 있는 하위만 자리를 차지한다`() {
        val tree = SessionTree(
            children = listOf(
                childNode("child-1", relativeCwd = "proj-a"),
                childNode("child-2", relativeCwd = "proj-b").copy(
                    lifecycle = ChildLifecycle.Ended(ChildEndReason.Exited, exitCode = 0),
                ),
            ),
        )

        // --live-child-cwd 로 엔진에게 넘기는 값이다. 끝난 하위의 자리까지 넘기면 그 폴더에 다시 띄울 수 없다.
        assertEquals(listOf("proj-a"), tree.liveChildRelativeCwds)
    }

    @Test
    fun `같은 이름의 하위를 고치면 자리를 바꾸지 않고 바뀐다`() {
        val tree = SessionTree(children = listOf(childNode("child-1"), childNode("child-2")))

        val changed = tree.withChild(childNode("child-1").copy(waitingFor = ChildWaitingFor.Model))

        assertEquals(listOf("child-1", "child-2"), changed.children.map { it.childId })
        assertEquals(ChildWaitingFor.Model, changed.child("child-1")?.waitingFor)
    }

    @Test
    fun `없는 이름이면 끝에 선다`() {
        val tree = SessionTree(children = listOf(childNode("child-1")))

        val changed = tree.withChild(childNode("child-2"))

        assertEquals(listOf("child-1", "child-2"), changed.children.map { it.childId })
    }

    @Test
    fun `하위의 대상은 그 이름의 Child 다`() {
        assertEquals(SessionTarget.Child("child-1"), childNode("child-1").target)
        assertEquals("child-1", SessionTarget.Child("child-1").label)
    }

    @Test
    fun `승인 물음이 걸려 있으면 승인을 기다린다`() {
        // 턴이 도는 중이어도 승인이 앞선다 — 그 턴은 사람의 답 없이 나아가지 못한다.
        val conversation = conversationOf("child-1").copy(turnState = TurnState.Running)

        assertEquals(ChildWaitingFor.Approval, childWaitingFor(conversation, approvalPending = true, parentQuestionPending = false))
    }

    @Test
    fun `턴이 돌거나 보낸 말이 남아 있으면 모델을 기다린다`() {
        assertEquals(
            ChildWaitingFor.Model,
            childWaitingFor(
                conversationOf("child-1").copy(turnState = TurnState.Running),
                approvalPending = false,
                parentQuestionPending = false,
            ),
        )
        // 첫 프롬프트를 보낸 직후가 이 자리다 — 되돌아오기 전에도 하위는 이미 일을 받았다.
        assertEquals(
            ChildWaitingFor.Model,
            childWaitingFor(
                conversationOf("child-1").copy(pendingUserMessages = listOf(OutgoingUserMessage("첫 프롬프트"))),
                approvalPending = false,
                parentQuestionPending = false,
            ),
        )
    }

    @Test
    fun `아무 일도 없으면 쉬는 중이다`() {
        assertEquals(
            ChildWaitingFor.Nothing,
            childWaitingFor(conversationOf("child-1"), approvalPending = false, parentQuestionPending = false),
        )
    }

    @Test
    fun `상위에게 묻는 중이면 턴이 돌고 있어도 상위를 기다린다`() {
        // 그 턴은 상위의 답 없이 나아가지 못한다. 모델을 기다린다고 답하면 상위는 자기가 답할 차례인 것을 모른다.
        val conversation = conversationOf("child-1").copy(turnState = TurnState.Running)

        assertEquals(
            ChildWaitingFor.Parent,
            childWaitingFor(conversation, approvalPending = false, parentQuestionPending = true),
        )
    }

    private fun conversationOf(childId: String) = ChatConversation(target = SessionTarget.Child(childId))

    private fun childNode(childId: String, relativeCwd: String = childId) = ChildSessionNode(
        childId = childId,
        title = childId,
        relativeCwd = relativeCwd,
        workingDirectory = WORK_DIR_PATH.resolve(relativeCwd),
        conversationId = "conversation-$childId",
        startedAt = Instant.parse("2026-10-20T10:01:12Z"),
    )

    private companion object {
        val WORK_DIR_PATH: Path = Path.of("/home/me/work/WorkDir-featureX")
    }
}
