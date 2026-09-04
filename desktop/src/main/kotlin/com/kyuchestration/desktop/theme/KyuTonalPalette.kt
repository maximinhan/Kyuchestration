package com.kyuchestration.desktop.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.math.absoluteValue
import kotlin.math.max
import kotlin.math.min

/**
 * 색 하나에서 명도만 갈아 만든 계단.
 *
 * Material 3 의 색 역할(primary · surfaceContainerHigh · outline …)은 전부 "어느 팔레트의 몇 번
 * 톤인가" 로 정의된다. 그래서 스킴을 손으로 스물몇 개 적는 대신, 씨앗 하나를 이 계단으로 펴고
 * 역할마다 톤 번호를 고른다 — **강조색을 바꾸는 일이 상수 한 줄을 바꾸는 일로 남는다.**
 *
 * **HCT 가 아니라 HSL 로 편다.** 진짜 Material 3 은 인지 균등한 HCT 색공간에서 톤을 뽑는데,
 * 그 변환을 얻으려면 material-color-utilities 를 의존으로 들여야 한다. 지금 필요한 것은
 * "톤 번호가 커질수록 밝아지고 색상은 그대로인 계단" 하나뿐이고 HSL 이 그것을 준다. 대가는
 * 노랑 계열의 40 톤과 파랑 계열의 40 톤이 눈에는 같은 밝기로 보이지 않는다는 것 — 목업이
 * 확정되어 색을 실제로 견주게 될 때 이 한 파일을 갈아 끼우면 되는 자리다.
 *
 * @param hue 0 이상 360 미만의 색상각.
 * @param chroma 0f~1f 의 채도. 중립 계열은 이것을 거의 0 으로 낮춘 같은 색상이다.
 */
internal data class KyuTonalPalette(val hue: Float, val chroma: Float) {

    /**
     * @param tone 0(검정) ~ 100(흰색). Material 3 이 색 역할을 적을 때 쓰는 그 번호다.
     */
    fun tone(tone: Int): Color {
        require(tone in 0..100) { "톤은 0 에서 100 사이여야 한다 — 받은 값: $tone" }
        return hslToColor(hue = hue, saturation = chroma, lightness = tone / 100f)
    }

    /** 같은 색상의 덜 짙은 계열. 보조색과 중립 계열이 여기서 갈린다. */
    fun withChromaRatio(ratio: Float): KyuTonalPalette = copy(chroma = chroma * ratio)

    /** 색상만 돌린 계열. Material 3 이 tertiary 를 만드는 방식이다. */
    fun withHueRotated(degrees: Float): KyuTonalPalette =
        copy(hue = (hue + degrees).mod(360f))

    companion object {
        /**
         * 이 색과 같은 색상·채도를 가진 계단.
         *
         * 씨앗의 밝기는 버린다. 씨앗이 어두운 색이든 밝은 색이든 계단은 검정부터 흰색까지
         * 이어져야 하고, 씨앗이 정하는 것은 "어느 색인가" 지 "얼마나 밝은가" 가 아니다.
         */
        fun fromSeed(seed: Color): KyuTonalPalette {
            val red = seed.red
            val green = seed.green
            val blue = seed.blue
            val highest = max(red, max(green, blue))
            val lowest = min(red, min(green, blue))
            val span = highest - lowest

            if (span == 0f) {
                // 회색에는 색상이 없다. 0 도(빨강)를 넣어 두어도 채도가 0 이라 결과는 회색 계단이다.
                return KyuTonalPalette(hue = 0f, chroma = 0f)
            }

            val hue = when (highest) {
                red -> ((green - blue) / span).mod(6f)
                green -> (blue - red) / span + 2f
                else -> (red - green) / span + 4f
            } * 60f

            val lightness = (highest + lowest) / 2f
            val saturation = span / (1f - abs(2f * lightness - 1f))

            return KyuTonalPalette(hue = hue.mod(360f), chroma = saturation.coerceIn(0f, 1f))
        }
    }
}

/**
 * HSL 을 RGB 로. 색상환을 60 도씩 여섯 조각으로 나눠 그 안에서 선형으로 잇는 표준 변환이다.
 */
private fun hslToColor(hue: Float, saturation: Float, lightness: Float): Color {
    val chroma = (1f - (2f * lightness - 1f).absoluteValue) * saturation
    val sector = hue / 60f
    val secondary = chroma * (1f - (sector.mod(2f) - 1f).absoluteValue)
    val (red, green, blue) = when (sector.toInt()) {
        0 -> Triple(chroma, secondary, 0f)
        1 -> Triple(secondary, chroma, 0f)
        2 -> Triple(0f, chroma, secondary)
        3 -> Triple(0f, secondary, chroma)
        4 -> Triple(secondary, 0f, chroma)
        else -> Triple(chroma, 0f, secondary)
    }
    val lift = lightness - chroma / 2f
    return Color(
        red = (red + lift).coerceIn(0f, 1f),
        green = (green + lift).coerceIn(0f, 1f),
        blue = (blue + lift).coerceIn(0f, 1f),
    )
}
