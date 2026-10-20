package com.kyuchestration.desktop.terminal.chat

import com.kyuchestration.desktop.terminal.SessionTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

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
}
