package com.kyuchestration.desktop.repoclone.kyucli

import com.kyuchestration.desktop.kyu.realKyuCommandRunnerOrSkip
import com.kyuchestration.desktop.repoclone.CloneStepFailure
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 진짜 kyu 바이너리로 토큰 프로필 어댑터를 통과시킨다.
 *
 * 가짜 실행기를 쓰는 시험은 "이런 문서가 오면 이렇게 읽는다" 까지만 말한다. 그 문서가 실제로 그
 * 모양으로 온다는 것과, 토큰이 정말 stdin 으로 건네진다는 것은 엔진에게 직접 물어야만 안다.
 *
 * 진짜 토큰을 만들지 않는다. 이 시험이 확인하는 것은 거절당하는 길이고, 그 길에서는 아무것도
 * 저장되지 않는 것이 계약이다(auth_add.go) — 그 계약 자체가 여기서 확인하는 것이기도 하다.
 *
 * kyu 를 찾지 못하면 건너뛴다(realKyuCommandRunnerOrSkip).
 */
class KyuCliTokenProfileRegistryIntegrationTest {

    @Test
    fun `등록된 프로필이 하나도 없어도 실패가 아니라 빈 목록이다`() {
        val registry = KyuCliTokenProfileRegistry(realKyuCommandRunnerOrSkip())

        // 이 머신에 무엇이 등록돼 있든 목록은 성립한다. 등록된 것이 없는 자리에서 사람용 출력은
        // 등록 안내를 내지만(auth.go), 기계용 문서는 같은 사실을 빈 배열로 답한다.
        val profiles = registry.listProfiles()

        assertTrue(profiles.all { it.name.isNotBlank() }, "이름 없는 프로필이 실려 오면 안 된다: $profiles")
    }

    @Test
    fun `거절당한 토큰은 프로필로 남지 않는다`() {
        val registry = KyuCliTokenProfileRegistry(realKyuCommandRunnerOrSkip())

        val failure = assertFailsWith<CloneStepFailure.KyuExitedWithFailure> {
            registry.registerProfile(REJECTED_PROFILE_NAME, "ghp_이것은진짜토큰이아니다")
        }

        assertTrue(failure.guidance.isNotBlank(), "kyu 가 stderr 로 남긴 이유가 안내 문구가 되어야 한다")
        // 등록이 일어나지 않았다는 것이 이 길의 계약이다. 반쯤 저장된 프로필이 남으면 사용자는
        // 다음 실행에서 고를 수 있는 것처럼 보이는 죽은 프로필을 보게 된다.
        assertTrue(
            registry.listProfiles().none { it.name == REJECTED_PROFILE_NAME },
            "거절당한 등록이 프로필 목록에 남았다",
        )
    }

    private companion object {
        /**
         * 이 시험만 쓰는 이름. 사람이 쓸 법한 이름("개인")을 쓰면, 만에 하나 저장되는 날
         * 사용자의 진짜 프로필을 덮어쓴다.
         */
        const val REJECTED_PROFILE_NAME = "kyu-desktop-통합시험-거절되는-토큰"
    }
}
