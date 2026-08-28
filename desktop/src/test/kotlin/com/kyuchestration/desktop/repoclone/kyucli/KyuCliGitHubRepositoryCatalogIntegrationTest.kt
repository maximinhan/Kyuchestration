package com.kyuchestration.desktop.repoclone.kyucli

import com.kyuchestration.desktop.kyu.realKyuCommandRunnerOrSkip
import com.kyuchestration.desktop.repoclone.CloneStepFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 진짜 kyu 바이너리로 레포 조회 어댑터의 실패 길을 통과시킨다.
 *
 * 성공하는 길은 여기서 지나지 않는다 — 그쪽은 진짜 토큰과 네트워크가 있어야 하고, 그 둘은
 * 시험이 만들어서는 안 되는 것이다. 대신 앱이 실제로 자주 만나는 실패(고른 프로필이 이 머신에
 * 없다)를 진짜 엔진에게 물어, 어댑터가 그 말을 사람에게 그대로 옮기는지 본다.
 *
 * kyu 를 찾지 못하면 건너뛴다(realKyuCommandRunnerOrSkip).
 */
class KyuCliGitHubRepositoryCatalogIntegrationTest {

    @Test
    fun `없는 프로필로 계정을 물으면 kyu 가 남긴 이유가 안내 문구가 된다`() {
        val catalog = KyuCliGitHubRepositoryCatalog(realKyuCommandRunnerOrSkip())

        val failure = assertFailsWith<CloneStepFailure.KyuExitedWithFailure> {
            catalog.listOwners("kyu-desktop-통합시험-없는-프로필")
        }

        assertEquals(1, failure.exitCode)
        // GitHub 에 붙기 전에 걸리는 실패라 네트워크가 없어도 같은 답이 온다.
        assertTrue(
            "kyu-desktop-통합시험-없는-프로필" in failure.guidance,
            "어느 이름이 없다는 것인지가 안내에 남아야 한다: ${failure.guidance}",
        )
    }
}
