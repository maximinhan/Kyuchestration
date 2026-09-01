package com.kyuchestration.desktop.dashboard

import com.kyuchestration.desktop.workdir.RepoState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CardSessionFactsTest {

    @Test
    fun `앱 세션은 git 상태를 가리지 않는다`() {
        val facts = mergeCardSessionFacts(RepoState.Dirty, hasRecordedConversation = false, appSessionHeld = true)

        // 엔진 눈에 세션이 없으므로 엔진은 언제나 git 상태를 답한다. 그래서 이 카드는 "돌고 있다" 와
        // "더럽다" 를 함께 보여줄 수 있다 — 값 하나가 두 축을 겸하던 시절에는 없던 자리다(설계 5.4.1).
        assertTrue(facts.appSessionHeld)
        assertEquals(RepoState.Dirty, facts.gitState)
    }

    @Test
    fun `세션이 없으면 엔진이 답한 git 상태를 그대로 쓴다`() {
        assertEquals(RepoState.Idle, sessionlessFactsFor(RepoState.Idle).gitState)
        assertEquals(RepoState.Ahead, sessionlessFactsFor(RepoState.Ahead).gitState)
    }

    @Test
    fun `모르는 상태도 git 상태 자리에 그대로 싣는다`() {
        // 낡은 kyu 가 PATH 에 남아 RUNNING 을 답하는 머신이 지나는 길이기도 하다. 계약은 값이
        // 하나 늘어도 판을 올리지 않으므로, 모르는 낱말에서 멈추는 대신 그대로 들고 있는다.
        val unrecognized = RepoState.Unrecognized("RUNNING")

        assertEquals(unrecognized, sessionlessFactsFor(unrecognized).gitState)
    }

    @Test
    fun `기록된 대화가 있으면 카드가 이어갈 대화를 말한다`() {
        val facts = mergeCardSessionFacts(
            RepoState.Idle,
            hasRecordedConversation = true,
            appSessionHeld = false,
        )

        // 앱을 껐다 켠 사용자가 워크디렉토리를 연 자리에서 보는 것이 이것이다. 표시가 없으면
        // 카드를 눌러 대화가 이어지고 나서야 그런 것이 있었음을 알게 된다(설계 문서 5.5.3).
        assertTrue(facts.conversationToResume)
    }

    @Test
    fun `기록이 없으면 이어갈 대화도 없다`() {
        val facts = mergeCardSessionFacts(
            RepoState.Idle,
            hasRecordedConversation = false,
            appSessionHeld = false,
        )

        assertFalse(facts.conversationToResume)
    }

    @Test
    fun `보유 중인 세션의 카드는 이어갈 대화를 말하지 않는다`() {
        val facts = mergeCardSessionFacts(
            RepoState.Dirty,
            hasRecordedConversation = true,
            appSessionHeld = true,
        )

        // 그 대화는 이어갈 것이 아니라 지금 쓰고 있는 것이다. 세션 칩 옆에 함께 뜨면
        // 사용자는 카드 하나에 대화가 둘 있는 것으로 읽는다.
        assertTrue(facts.appSessionHeld)
        assertFalse(facts.conversationToResume)
    }

    @Test
    fun `메인 세션 줄은 git 상태 없이 같은 규칙을 따른다`() {
        val idle = mergeCardSessionFacts(
            gitState = null,
            hasRecordedConversation = true,
            appSessionHeld = false,
        )
        val held = mergeCardSessionFacts(
            gitState = null,
            hasRecordedConversation = true,
            appSessionHeld = true,
        )

        // 워크디렉토리 최상위는 레포가 아니라 DIRTY 라는 말이 성립하지 않는다(설계 문서 5.4).
        // 그 밖의 규칙은 레포와 같아야 하고, 화면이 각자 조립하면 그 규칙이 한쪽에서만 바뀌는 날이 온다.
        assertTrue(idle.conversationToResume)
        assertNull(idle.gitState)
        assertFalse(held.conversationToResume)
    }

    private fun sessionlessFactsFor(gitState: RepoState) = mergeCardSessionFacts(
        gitState,
        hasRecordedConversation = false,
        appSessionHeld = false,
    )
}
