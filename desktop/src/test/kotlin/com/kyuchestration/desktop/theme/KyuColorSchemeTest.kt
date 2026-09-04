package com.kyuchestration.desktop.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 스킴이 "쓸 수 있는가" 를 지킨다.
 *
 * 색이 예뻐 보이는가는 사람이 화면에서 판정할 일이라 여기서 재지 않는다. 여기서 재는 것은
 * 눈으로는 잘 놓치는 것 — 어느 역할과 그 위의 글자가 서로 안 보이게 되지 않는가, 표면 층의
 * 순서가 뒤집히지 않았는가다. 씨앗을 바꾸면 스물몇 색이 한꺼번에 움직이므로, 그때 무너지는
 * 자리를 사람 눈 대신 이 검사가 먼저 잡는다.
 */
class KyuColorSchemeTest {

    @Test
    fun `라이트 스킴의 글자와 바탕이 서로 읽힌다`() {
        with(KyuLightColorScheme) {
            assertReadable(onPrimary, primary, "onPrimary / primary")
            assertReadable(onPrimaryContainer, primaryContainer, "onPrimaryContainer / primaryContainer")
            assertReadable(onSecondary, secondary, "onSecondary / secondary")
            assertReadable(onTertiary, tertiary, "onTertiary / tertiary")
            assertReadable(onError, error, "onError / error")
            assertReadable(onSurface, surface, "onSurface / surface")
            assertReadable(onSurfaceVariant, surfaceVariant, "onSurfaceVariant / surfaceVariant")
            assertReadable(onBackground, background, "onBackground / background")
        }
    }

    @Test
    fun `다크 스킴의 글자와 바탕이 서로 읽힌다`() {
        with(KyuDarkColorScheme) {
            assertReadable(onPrimary, primary, "onPrimary / primary")
            assertReadable(onPrimaryContainer, primaryContainer, "onPrimaryContainer / primaryContainer")
            assertReadable(onSecondary, secondary, "onSecondary / secondary")
            assertReadable(onTertiary, tertiary, "onTertiary / tertiary")
            assertReadable(onError, error, "onError / error")
            assertReadable(onSurface, surface, "onSurface / surface")
            assertReadable(onSurfaceVariant, surfaceVariant, "onSurfaceVariant / surfaceVariant")
            assertReadable(onBackground, background, "onBackground / background")
        }
    }

    @Test
    fun `라이트의 표면 층은 위로 갈수록 어두워진다`() {
        with(KyuLightColorScheme) {
            val layers = listOf(
                surfaceContainerLowest,
                surfaceContainerLow,
                surfaceContainer,
                surfaceContainerHigh,
                surfaceContainerHighest,
            ).map { it.luminance() }

            assertTrue(layers == layers.sortedDescending(), "표면 층의 순서가 뒤집혔다: $layers")
        }
    }

    @Test
    fun `다크의 표면 층은 위로 갈수록 밝아진다`() {
        with(KyuDarkColorScheme) {
            val layers = listOf(
                surfaceContainerLowest,
                surfaceContainerLow,
                surfaceContainer,
                surfaceContainerHigh,
                surfaceContainerHighest,
            ).map { it.luminance() }

            assertTrue(layers == layers.sorted(), "표면 층의 순서가 뒤집혔다: $layers")
        }
    }

    @Test
    fun `다크의 바탕이 라이트의 바탕보다 어둡다`() {
        assertTrue(
            KyuDarkColorScheme.surface.luminance() < KyuLightColorScheme.surface.luminance(),
            "다크 스킴의 표면이 라이트보다 밝다",
        )
    }

    @Test
    fun `표면 층 다섯 칸이 서로 다른 색이다`() {
        // 톤 번호를 잘못 적어 두 칸이 같아지면 카드가 배경에서 떠오르지 않는다. 눈으로는
        // "왠지 밋밋하다" 로만 보이는 종류의 실수다.
        listOf(KyuLightColorScheme, KyuDarkColorScheme).forEach { scheme ->
            val layers = with(scheme) {
                listOf(
                    surfaceContainerLowest,
                    surfaceContainerLow,
                    surfaceContainer,
                    surfaceContainerHigh,
                    surfaceContainerHighest,
                )
            }

            assertTrue(layers.toSet().size == layers.size, "겹치는 표면 층이 있다: $layers")
        }
    }

    @Test
    fun `실패는 강조가 무엇이든 붉다`() {
        // error 를 강조에서 파생하지 않는 이유를 지킨다. 강조를 바꾸는 것은 상수 한 줄이고,
        // 그때 error 까지 따라 움직이면 강조를 초록으로 고른 날 "잘못됐다" 가 초록으로 뜬다.
        listOf(KyuLightColorScheme, KyuDarkColorScheme).forEach { scheme ->
            val errorHue = KyuTonalPalette.fromSeed(scheme.error).hue
            val distanceFromRed = minOf(errorHue, 360f - errorHue)

            assertTrue(distanceFromRed < RED_BAND_DEGREES, "실패 색이 붉은 띠를 벗어났다: $errorHue 도")
        }
    }

    private fun assertReadable(foreground: Color, background: Color, role: String) {
        val ratio = contrastRatio(foreground, background)
        assertTrue(ratio >= MIN_CONTRAST_RATIO, "$role 의 대비가 부족하다: %.2f : 1".format(ratio))
    }

    /**
     * WCAG 의 대비비. 두 색의 상대 휘도로 정해진다.
     */
    private fun contrastRatio(one: Color, other: Color): Float {
        val brighter = maxOf(one.luminance(), other.luminance())
        val darker = minOf(one.luminance(), other.luminance())
        return (brighter + WCAG_OFFSET) / (darker + WCAG_OFFSET)
    }

    private companion object {
        /**
         * WCAG AA 가 본문 글자에 요구하는 선.
         *
         * 큰 글자만 놓는 역할(onPrimary 등)에는 3:1 이면 되지만, 이 앱은 그 색들 위에도 작은
         * 글자를 놓으므로 한 선으로 4.5 를 지킨다.
         */
        const val MIN_CONTRAST_RATIO = 4.5f
        const val WCAG_OFFSET = 0.05f

        /** 0 도(빨강)에서 이만큼 안쪽이면 사람이 "붉다" 고 읽는다. */
        const val RED_BAND_DEGREES = 25f
    }
}
