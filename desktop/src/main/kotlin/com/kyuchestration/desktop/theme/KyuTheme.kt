package com.kyuchestration.desktop.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

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
    CompositionLocalProvider(
        LocalKyuStatusColors provides if (darkTheme) KyuStatusColors.Dark else KyuStatusColors.Light,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) KyuDarkColorScheme else KyuLightColorScheme,
            typography = KyuTypography,
            shapes = KyuShapes,
            content = content,
        )
    }
}

/**
 * 테마가 들고 있지만 Material 3 의 스킴에는 자리가 없는 것들.
 *
 * `MaterialTheme.colorScheme` 옆에 `KyuTheme.statusColors` 로 서게 두는 것이 Compose 의 관례다 —
 * 그리는 쪽은 어느 쪽이 표준 역할이고 어느 쪽이 우리 뜻인지를 이름에서 바로 읽는다.
 */
object KyuTheme {
    val statusColors: KyuStatusColors
        @Composable
        @ReadOnlyComposable
        get() = LocalKyuStatusColors.current
}

/**
 * 테마 밖에서 그려지는 것이 없으므로 기본값은 라이트다.
 *
 * `staticCompositionLocalOf` 인 것이 뜻이다 — 이 값이 바뀌는 것은 테마를 통째로 갈아 끼울 때뿐이라,
 * 값을 읽는 자리마다 구독을 걸어 두면 얻는 것 없이 합성만 무거워진다.
 */
private val LocalKyuStatusColors = staticCompositionLocalOf { KyuStatusColors.Light }
