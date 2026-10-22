package com.kyuchestration.desktop.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KyuTonalPaletteTest {

    @Test
    fun `계단의 양끝은 검정과 흰색이다`() {
        val palette = KyuTonalPalette.fromSeed(TEAL_SEED)

        assertEquals(Color.Black, palette.tone(0))
        assertEquals(Color.White, palette.tone(100))
    }

    @Test
    fun `톤 번호가 커질수록 밝아진다`() {
        val palette = KyuTonalPalette.fromSeed(TEAL_SEED)

        val luminances = listOf(10, 20, 30, 40, 50, 60, 70, 80, 90).map { palette.tone(it).luminance() }

        assertEquals(luminances.sorted(), luminances)
    }

    @Test
    fun `계열이 달라도 같은 톤은 같은 밝기다`() {
        val palettes = listOf(TEAL_SEED, AMBER_SEED, BLUE_SEED).map(KyuTonalPalette::fromSeed)

        listOf(10, 20, 40, 60, 80, 90).forEach { tone ->
            val luminances = palettes.map { it.tone(tone).luminance() }
            val spread = luminances.max() - luminances.min()

            assertTrue(spread < LUMINANCE_TOLERANCE, "톤 $tone 의 계열별 밝기가 벌어졌다: $luminances")
        }
    }

    @Test
    fun `톤 40 은 어느 계열에서도 흰 글자를 받는다`() {
        // 이 앱이 색을 HSL 명도가 아니라 휘도로 맞추는 이유. 명도로 뽑으면 틸의 40 톤 위에서
        // 흰 글자가 2.6:1 까지 떨어진다.
        listOf(TEAL_SEED, AMBER_SEED, BLUE_SEED).forEach { seed ->
            val tone40 = KyuTonalPalette.fromSeed(seed).tone(40)
            val contrast = (1f + WCAG_OFFSET) / (tone40.luminance() + WCAG_OFFSET)

            assertTrue(contrast >= MIN_CONTRAST_RATIO, "%s 의 톤 40 대비: %.2f : 1".format(seed, contrast))
        }
    }

    @Test
    fun `씨앗의 밝기는 버리고 색상과 채도만 가져온다`() {
        // 같은 색상·채도를 가진 어두운 씨앗과 밝은 씨앗. 계단은 같아야 한다.
        val darkSeed = Color(0xFF0F766E)
        val lightSeed = KyuTonalPalette.fromSeed(darkSeed).tone(85)

        val fromDark = KyuTonalPalette.fromSeed(darkSeed)
        val fromLight = KyuTonalPalette.fromSeed(lightSeed)

        assertTrue(
            abs(fromDark.hue - fromLight.hue) < HUE_TOLERANCE_DEGREES,
            "색상이 달라졌다: ${fromDark.hue} 대 ${fromLight.hue}",
        )
        assertTrue(
            abs(fromDark.chroma - fromLight.chroma) < CHROMA_TOLERANCE,
            "채도가 달라졌다: ${fromDark.chroma} 대 ${fromLight.chroma}",
        )
    }

    @Test
    fun `회색 씨앗은 채도 없는 계단이 된다`() {
        val palette = KyuTonalPalette.fromSeed(Color(0xFF6B6B6B))

        assertEquals(0f, palette.chroma)
        // 채도가 없으므로 어느 톤이든 세 성분이 같다.
        val midTone = palette.tone(50)
        assertEquals(midTone.red, midTone.green)
        assertEquals(midTone.green, midTone.blue)
    }

    @Test
    fun `채도를 낮춘 계열은 같은 색상을 유지한다`() {
        val palette = KyuTonalPalette.fromSeed(TEAL_SEED)

        val neutral = palette.withChromaRatio(0.06f)

        assertEquals(palette.hue, neutral.hue)
        assertTrue(neutral.chroma < palette.chroma, "채도가 낮아지지 않았다")
    }

    @Test
    fun `색상을 돌린 계열은 색상환을 벗어나지 않는다`() {
        val palette = KyuTonalPalette(hue = 330f, chroma = 0.5f)

        val rotated = palette.withHueRotated(60f)

        assertEquals(30f, rotated.hue)
        assertEquals(palette.chroma, rotated.chroma)
    }

    private companion object {
        /** 목업이 라이트 강조로 고른 틸. */
        val TEAL_SEED = Color(0xFF0F766E)

        /** 목업이 다크 강조로 고른 앰버. 밝은 계열이라 톤 맞추기가 가장 어긋나기 쉬운 자리다. */
        val AMBER_SEED = Color(0xFFF5B544)

        /** 앱이 이미 쓰던 AHEAD 파랑. 어두운 계열 쪽의 대조군이다. */
        val BLUE_SEED = Color(0xFF1565C0)

        const val HUE_TOLERANCE_DEGREES = 1f
        const val CHROMA_TOLERANCE = 0.02f

        /** 흰색 기준 휘도 1 에서 이만큼 벌어지면 눈에 밝기 차이로 보인다. */
        const val LUMINANCE_TOLERANCE = 0.01f

        const val MIN_CONTRAST_RATIO = 4.5f
        const val WCAG_OFFSET = 0.05f
    }
}
