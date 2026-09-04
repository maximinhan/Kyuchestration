package com.kyuchestration.desktop.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

/**
 * 앱의 모든 화면이 그 안에서 그려지는 테마.
 *
 * `MaterialTheme { }` 를 인자 없이 부르던 자리를 대신한다. 인자가 없다는 것은 색도 타이포도
 * 셰이프도 Material 3 의 baseline 기본값이라는 뜻이었고, 그것이 앱이 "기본 테마 그대로" 로
 * 보이던 이유다(설계 1.4).
 *
 * @param darkTheme 이 합성을 어두운 스킴으로 그릴 것인가. 이 값을 정하는 것은 여기가 아니라
 *   창을 세우는 쪽이다 — 시스템 설정과 사용자가 고른 것을 합치는 판단이라 화면의 일이 아니다
 *   (ThemePreference).
 */
@Composable
fun KyuTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) KyuDarkColorScheme else KyuLightColorScheme,
        typography = KyuTypography,
        shapes = KyuShapes,
        content = content,
    )
}
