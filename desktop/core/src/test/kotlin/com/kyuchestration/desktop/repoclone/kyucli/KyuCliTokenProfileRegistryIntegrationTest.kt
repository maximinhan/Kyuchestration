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

    /**
     * 등록된 적 없는 이름을 지우려 하면 엔진이 거절한다.
     *
     * **성공하는 길은 여기서 재지 않는다.** 재려면 진짜 토큰을 먼저 등록해야 하는데, 이 시험은
     * 아무나 돌리는 것이고 진짜 토큰은 만들지도 두지도 않는다. 그래서 확인하는 것은 이 어댑터가
     * 실제로 확인해야 하는 하나다 — `auth remove` 를 부르는 방식이 엔진이 받는 모양과 맞는가.
     *
     * 그 하나가 가짜 실행기로는 확인되지 않는 이유가 --json 이다. 이 흐름의 다른 명령은 모두
     * 그 옵션을 받지만 remove 만 받지 않고(auth.go 는 인자를 정확히 하나만 받는다), 붙였다면
     * 여기서 나는 거절이 "없는 프로필" 이 아니라 "인자 개수가 틀렸다" 가 된다.
     */
    @Test
    fun `등록된 적 없는 프로필을 지우려 하면 엔진이 거절한다`() {
        val registry = KyuCliTokenProfileRegistry(realKyuCommandRunnerOrSkip())

        val failure = assertFailsWith<CloneStepFailure.KyuExitedWithFailure> {
            registry.removeProfile(NEVER_REGISTERED_PROFILE_NAME)
        }

        assertTrue(failure.guidance.isNotBlank(), "kyu 가 stderr 로 남긴 이유가 안내 문구가 되어야 한다")
        // 인자 개수를 잘못 넘겼다면 엔진은 사용법을 되돌려준다. 그 말이 섞여 오면 이 어댑터가
        // 부르는 모양이 엔진이 받는 모양과 어긋난 것이다.
        assertTrue(
            "지울 프로필 이름 하나가 필요합니다" !in failure.guidance,
            "auth remove 에 인자를 잘못 넘겼다: ${failure.guidance}",
        )
    }

    private companion object {
        /**
         * 이 시험만 쓰는 이름. 사람이 쓸 법한 이름("개인")을 쓰면, 만에 하나 저장되는 날
         * 사용자의 진짜 프로필을 덮어쓴다.
         */
        const val REJECTED_PROFILE_NAME = "kyu-desktop-통합시험-거절되는-토큰"

        /**
         * 지우기 시험이 겨누는 이름.
         *
         * 위의 것과 갈라 둔다. 같은 이름을 쓰면 이 시험이 앞선 시험의 뒤처리에 기대게 되고,
         * 시험 순서가 바뀌는 날 이유 없이 빨개진다.
         */
        const val NEVER_REGISTERED_PROFILE_NAME = "kyu-desktop-통합시험-등록된-적-없는-프로필"
    }
}
