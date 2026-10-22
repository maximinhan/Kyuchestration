package com.kyuchestration.desktop.theme

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThemePreferenceTest {

    @Test
    fun `고른 것이 다크면 시스템이 무엇을 답하든 다크다`() {
        assertTrue(resolveDarkTheme(ThemePreference.AlwaysDark, systemReportsDark = false))
        assertTrue(resolveDarkTheme(ThemePreference.AlwaysDark, systemReportsDark = true))
    }

    @Test
    fun `고른 것이 라이트면 시스템이 무엇을 답하든 라이트다`() {
        assertFalse(resolveDarkTheme(ThemePreference.AlwaysLight, systemReportsDark = false))
        assertFalse(resolveDarkTheme(ThemePreference.AlwaysLight, systemReportsDark = true))
    }

    @Test
    fun `시스템 따르기는 시스템이 답한 것을 그대로 쓴다`() {
        assertTrue(resolveDarkTheme(ThemePreference.FollowSystem, systemReportsDark = true))
        assertFalse(resolveDarkTheme(ThemePreference.FollowSystem, systemReportsDark = false))
    }

    @Test
    fun `답하지 않는 플랫폼에서 시스템 따르기는 라이트가 된다`() {
        // 리눅스에서 실제로 그렇다 — skiko 가 "모름" 을 답하고 그것이 밝음으로 접힌다.
        // 화면이 그 사실을 먼저 말해야 하는 근거이므로 검사로도 적어 둔다.
        assertFalse(resolveDarkTheme(ThemePreference.FollowSystem, systemReportsDark = false))
    }

    @Test
    fun `기본값은 목업이 확정한 다크다`() {
        // enum 의 첫 갈래가 곧 기본값이라는 규칙을 쓰지 않는다. 기본값을 고르는 자리가
        // 화면(Main.kt)이므로, 여기서 지키는 것은 "다크가 첫 갈래로 서 있다" 하나다 —
        // 고르는 목록에서 사용자가 가장 먼저 보는 것도 이 순서다.
        assertEquals(ThemePreference.AlwaysDark, ThemePreference.entries.first())
    }
}
