package com.kyuchestration.desktop.dashboard

import com.kyuchestration.desktop.workdir.RepoState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CardSessionFactsTest {

    @Test
    fun `엔진의 RUNNING 은 CLI 세션이다`() {
        val facts = sessionlessFactsFor(RepoState.Running)

        // 앱은 이 세션에 붙지도 죽이지도 못한다. 앱 세션과 같은 칩으로 그리면 사용자는 카드를
        // 눌러 세션 두 개를 만들고 나서 그것을 알게 된다(설계 문서 5.4.2).
        assertTrue(facts.cliSessionRunning)
        assertFalse(facts.appSessionHeld)
    }

    @Test
    fun `CLI 세션이 떠 있으면 git 상태는 실려 오지 않는다`() {
        val facts = sessionlessFactsFor(RepoState.Running)

        // RUNNING 은 상태가 아니라 "세션이 떠 있어 git 상태를 답하지 못했다" 는 뜻이다.
        assertNull(facts.gitState)
    }

    @Test
    fun `앱 세션은 git 상태를 가리지 않는다`() {
        val facts = mergeRepoCardSessionFacts(RepoState.Dirty, hasRecordedConversation = false, appSessionHeld = true)

        // 엔진 눈에 앱 세션이 없으므로 엔진은 git 상태를 답한다. 그래서 이 카드는 "돌고 있다" 와
        // "더럽다" 를 함께 보여줄 수 있다 — 계약을 바꾸지 않고 정보가 느는 자리다(설계 5.4.1).
        assertTrue(facts.appSessionHeld)
        assertEquals(RepoState.Dirty, facts.gitState)
        assertFalse(facts.cliSessionRunning)
    }

    @Test
    fun `앱 세션과 CLI 세션은 한 레포에서 함께 있을 수 있다`() {
        val facts = mergeRepoCardSessionFacts(RepoState.Running, hasRecordedConversation = false, appSessionHeld = true)

        // 막지 않고 알린다(설계 5.4.2). 같은 레포에 claude 를 둘 띄우는 것은 사용자가 실제로
        // 원할 수 있는 일이고, 앱이 막으면 그 카드에서 할 수 있는 일이 아예 없어진다.
        assertTrue(facts.appSessionHeld)
        assertTrue(facts.cliSessionRunning)
    }

    @Test
    fun `세션이 없으면 엔진이 답한 git 상태를 그대로 쓴다`() {
        assertEquals(RepoState.Idle, sessionlessFactsFor(RepoState.Idle).gitState)
        assertEquals(RepoState.Ahead, sessionlessFactsFor(RepoState.Ahead).gitState)
    }

    @Test
    fun `모르는 상태도 git 상태 자리에 그대로 싣는다`() {
        val unrecognized = RepoState.Unrecognized("BEHIND")

        val facts = sessionlessFactsFor(unrecognized)

        // 계약은 값이 하나 늘어도 판을 올리지 않는다. 모르는 낱말에서 멈추는 대신 그대로 들고
        // 있는 것이 관찰 쪽의 자세이고, 합치는 자리도 같아야 한다.
        assertEquals(unrecognized, facts.gitState)
        assertFalse(facts.cliSessionRunning)
    }

    private fun sessionlessFactsFor(engineReportedState: RepoState) = mergeRepoCardSessionFacts(
        engineReportedState,
        hasRecordedConversation = false,
        appSessionHeld = false,
    )

    @Test
    fun `기록된 대화가 있으면 카드가 이어갈 대화를 말한다`() {
        val facts = mergeRepoCardSessionFacts(
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
        val facts = mergeRepoCardSessionFacts(
            RepoState.Idle,
            hasRecordedConversation = false,
            appSessionHeld = false,
        )

        assertFalse(facts.conversationToResume)
    }

    @Test
    fun `보유 중인 세션의 카드는 이어갈 대화를 말하지 않는다`() {
        val facts = mergeRepoCardSessionFacts(
            RepoState.Dirty,
            hasRecordedConversation = true,
            appSessionHeld = true,
        )

        // 그 대화는 이어갈 것이 아니라 지금 쓰고 있는 것이다. 앱 세션 칩 옆에 함께 뜨면
        // 사용자는 카드 하나에 대화가 둘 있는 것으로 읽는다.
        assertTrue(facts.appSessionHeld)
        assertFalse(facts.conversationToResume)
    }

    @Test
    fun `CLI 세션이 떠 있어도 앱이 이어갈 대화는 그대로 있다`() {
        val facts = mergeRepoCardSessionFacts(
            RepoState.Running,
            hasRecordedConversation = true,
            appSessionHeld = false,
        )

        // 두 세션은 서로 다른 대화를 갖는다 — kyu start 는 --session-id 를 붙이지 않는다
        // (설계 문서 5.4.2 의 마지막 줄). CLI 세션이 떠 있다고 앱의 대화가 사라지지 않는다.
        assertTrue(facts.cliSessionRunning)
        assertTrue(facts.conversationToResume)
    }

    @Test
    fun `메인 세션도 같은 규칙으로 이어갈 대화를 말한다`() {
        val idle = mergeMainCardSessionFacts(
            mainSessionAlive = false,
            hasRecordedConversation = true,
            appSessionHeld = false,
        )
        val held = mergeMainCardSessionFacts(
            mainSessionAlive = false,
            hasRecordedConversation = true,
            appSessionHeld = true,
        )

        // 메인 세션은 레포가 아니라 git 상태가 없다(설계 문서 5.4). 그 밖의 규칙은 레포와 같아야
        // 하고, 화면이 각자 조립하면 그 규칙이 한쪽에서만 바뀌는 날이 온다.
        assertTrue(idle.conversationToResume)
        assertNull(idle.gitState)
        assertFalse(held.conversationToResume)
    }

    @Test
    fun `메인 세션의 RUNNING 은 CLI 세션이다`() {
        val facts = mergeMainCardSessionFacts(
            mainSessionAlive = true,
            hasRecordedConversation = false,
            appSessionHeld = false,
        )

        assertTrue(facts.cliSessionRunning)
    }
}
