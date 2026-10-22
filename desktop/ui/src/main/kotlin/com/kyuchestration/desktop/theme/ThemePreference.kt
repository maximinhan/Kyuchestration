package com.kyuchestration.desktop.theme

/**
 * 사용자가 테마에 대해 고른 것.
 *
 * **고른 것과 그려질 것은 다르다.** "시스템 따르기" 를 고른 사람에게 화면이 어두울지 밝을지는
 * 이 값만으로 정해지지 않는다 — 그 합침이 [resolveDarkTheme] 이고, 갈래를 셋으로 둔 것은 그
 * 차이를 타입에 남기기 위해서다. 불리언 하나로 두면 "지금 밝은 것은 사용자가 그렇게 고른
 * 것인가, 시스템이 그렇다고 답한 것인가" 를 화면이 되물을 수 없다.
 *
 * **고른 것을 파일에 남기지 않는다.** 앱을 다시 띄우면 기본값으로 돌아온다. 남기려면 설정
 * 파일의 자리 · 형식 · 읽기 실패 처리라는 하위 구조가 통째로 생기는데, 지금 앱이 남길 설정은
 * 이 하나뿐이다(원칙 4 — 두 번째 사용처가 생길 때 만든다). 기본값이 목업이 확정한 다크라
 * 대부분의 실행에서 고칠 것이 없기도 하다. 사용자가 매번 고르는 것을 실제로 불편해할 때
 * 그때 연다.
 */
enum class ThemePreference {

    /**
     * 목업이 확정한 어두운 화면. 앱의 기본값이다.
     */
    AlwaysDark,

    /**
     * 밝은 화면. 목업이 아직 확정하지 않은 쪽이다.
     */
    AlwaysLight,

    /**
     * 이 머신의 시스템 설정을 따른다.
     *
     * **답하지 않는 플랫폼이 있다.** 데스크톱에서 시스템 테마를 읽는 것은 skiko 의 네이티브
     * 구현이고, 리눅스에서는 그것이 "모름" 을 답한다(WSL2 에서 실제로 재어 확인했다). 모름은
     * 밝음으로 접히므로 리눅스에서 이 갈래를 고르면 라이트가 뜬다 — 화면이 그 사실을 먼저
     * 말한다(SessionNavigationRail 의 설정).
     */
    FollowSystem,
}

/**
 * 고른 것과 시스템이 답한 것을 합쳐 이 화면을 어둡게 그릴지 정한다.
 *
 * @param systemReportsDark 시스템이 어둡다고 답했는가. 답하지 않는 플랫폼에서는 언제나 false 다 —
 *   "밝다" 와 "모른다" 가 여기서 합쳐진다. 그 둘을 가르는 길이 표준 API 에 없어서 그렇고,
 *   그래서 [ThemePreference.FollowSystem] 의 설명이 그 사실을 적어 둔다.
 */
fun resolveDarkTheme(preference: ThemePreference, systemReportsDark: Boolean): Boolean =
    when (preference) {
        ThemePreference.AlwaysDark -> true
        ThemePreference.AlwaysLight -> false
        ThemePreference.FollowSystem -> systemReportsDark
    }
