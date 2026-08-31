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
        val facts = mergeRepoCardSessionFacts(RepoState.Running, appSessionHeld = false)

        // 앱은 이 세션에 붙지도 죽이지도 못한다. 앱 세션과 같은 칩으로 그리면 사용자는 카드를
        // 눌러 세션 두 개를 만들고 나서 그것을 알게 된다(설계 문서 5.4.2).
        assertTrue(facts.cliSessionRunning)
        assertFalse(facts.appSessionHeld)
    }

    @Test
    fun `CLI 세션이 떠 있으면 git 상태는 실려 오지 않는다`() {
        val facts = mergeRepoCardSessionFacts(RepoState.Running, appSessionHeld = false)

        // RUNNING 은 상태가 아니라 "세션이 떠 있어 git 상태를 답하지 못했다" 는 뜻이다.
        assertNull(facts.gitState)
    }

    @Test
    fun `앱 세션은 git 상태를 가리지 않는다`() {
        val facts = mergeRepoCardSessionFacts(RepoState.Dirty, appSessionHeld = true)

        // 엔진 눈에 앱 세션이 없으므로 엔진은 git 상태를 답한다. 그래서 이 카드는 "돌고 있다" 와
        // "더럽다" 를 함께 보여줄 수 있다 — 계약을 바꾸지 않고 정보가 느는 자리다(설계 5.4.1).
        assertTrue(facts.appSessionHeld)
        assertEquals(RepoState.Dirty, facts.gitState)
        assertFalse(facts.cliSessionRunning)
    }

    @Test
    fun `앱 세션과 CLI 세션은 한 레포에서 함께 있을 수 있다`() {
        val facts = mergeRepoCardSessionFacts(RepoState.Running, appSessionHeld = true)

        // 막지 않고 알린다(설계 5.4.2). 같은 레포에 claude 를 둘 띄우는 것은 사용자가 실제로
        // 원할 수 있는 일이고, 앱이 막으면 그 카드에서 할 수 있는 일이 아예 없어진다.
        assertTrue(facts.appSessionHeld)
        assertTrue(facts.cliSessionRunning)
    }

    @Test
    fun `세션이 없으면 엔진이 답한 git 상태를 그대로 쓴다`() {
        assertEquals(RepoState.Idle, mergeRepoCardSessionFacts(RepoState.Idle, appSessionHeld = false).gitState)
        assertEquals(RepoState.Ahead, mergeRepoCardSessionFacts(RepoState.Ahead, appSessionHeld = false).gitState)
    }

    @Test
    fun `모르는 상태도 git 상태 자리에 그대로 싣는다`() {
        val unrecognized = RepoState.Unrecognized("BEHIND")

        val facts = mergeRepoCardSessionFacts(unrecognized, appSessionHeld = false)

        // 계약은 값이 하나 늘어도 판을 올리지 않는다. 모르는 낱말에서 멈추는 대신 그대로 들고
        // 있는 것이 관찰 쪽의 자세이고, 합치는 자리도 같아야 한다.
        assertEquals(unrecognized, facts.gitState)
        assertFalse(facts.cliSessionRunning)
    }
}
