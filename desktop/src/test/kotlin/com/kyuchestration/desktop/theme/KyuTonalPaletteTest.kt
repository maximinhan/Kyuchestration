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

    /** 설계 6.1 이 라이트 강조로 고른 틸. */
    private val TEAL_SEED = Color(0xFF0F766E)

    private companion object {
        const val HUE_TOLERANCE_DEGREES = 1f
        const val CHROMA_TOLERANCE = 0.02f
    }
}
