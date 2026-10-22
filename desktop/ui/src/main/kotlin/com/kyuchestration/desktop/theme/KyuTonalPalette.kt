package com.kyuchestration.desktop.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.abs
import kotlin.math.absoluteValue
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * 색 하나에서 밝기만 갈아 만든 계단.
 *
 * Material 3 의 색 역할(primary · surfaceContainerHigh · outline …)은 전부 "어느 팔레트의 몇 번
 * 톤인가" 로 정의된다. 그래서 스킴을 손으로 스물몇 개 적는 대신, 씨앗 하나를 이 계단으로 펴고
 * 역할마다 톤 번호를 고른다 — **강조색을 바꾸는 일이 상수 한 줄을 바꾸는 일로 남는다.**
 *
 * **색상·채도는 HSL 에서 가져오고, 톤 번호는 휘도로 맞춘다.** 톤 번호를 HSL 의 명도로 그냥
 * 읽으면 계열마다 같은 번호가 다른 밝기가 된다 — 틸의 명도 40% 는 파랑의 명도 40% 보다 훨씬
 * 밝아서, 그 위에 흰 글자를 놓으면 대비가 2.6:1 까지 떨어진다(실제로 그렇게 만들어 검사에
 * 걸렸다). Material 3 의 톤 번호는 명도가 아니라 **CIE 명도 L\* **이고, L\* 는 휘도로 옮길 수
 * 있다. 그래서 톤 번호가 요구하는 휘도를 먼저 구하고, 그 휘도를 내는 명도를 이분 탐색으로 찾는다.
 * 계열이 무엇이든 톤 40 은 흰 글자를 받을 만큼 어둡다.
 *
 * **진짜 HCT 는 아니다.** HCT 는 채도까지 인지 균등하게 다루고 색역을 벗어나는 조합을 옮겨
 * 주는데, 그것을 얻으려면 material-color-utilities 를 의존으로 들여야 한다. 여기서 필요한 것은
 * 대비가 무너지지 않는 계단이고 휘도를 맞추는 것으로 그 선은 지켜진다 — 남는 차이는 같은 톤에서
 * 계열마다 선명함이 조금씩 다르다는 것뿐이다.
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
        return when (tone) {
            // 계단의 양끝은 어느 계열에서도 같은 색이다. 이분 탐색에 맡기면 부동소수점 오차만큼
            // 검정도 흰색도 아닌 값이 나온다.
            0 -> Color.Black
            100 -> Color.White
            else -> lightnessWithLuminance(luminanceForTone(tone)).let { hslToColor(hue, chroma, it) }
        }
    }

    /** 같은 색상의 덜 짙은 계열. 보조색과 중립 계열이 여기서 갈린다. */
    fun withChromaRatio(ratio: Float): KyuTonalPalette = copy(chroma = chroma * ratio)

    /** 색상만 돌린 계열. Material 3 이 tertiary 를 만드는 방식이다. */
    fun withHueRotated(degrees: Float): KyuTonalPalette =
        copy(hue = (hue + degrees).mod(360f))

    /**
     * 이 색상·채도에서 목표 휘도를 내는 명도.
     *
     * 명도를 올리면 세 성분이 모두 오르므로 휘도는 명도에 대해 단조 증가한다 — 이분 탐색이
     * 성립하는 근거다.
     */
    private fun lightnessWithLuminance(targetLuminance: Float): Float {
        var low = 0f
        var high = 1f
        repeat(TONE_SEARCH_STEPS) {
            val middle = (low + high) / 2f
            if (hslToColor(hue, chroma, middle).luminance() < targetLuminance) {
                low = middle
            } else {
                high = middle
            }
        }
        return (low + high) / 2f
    }

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
 * 톤 번호가 요구하는 상대 휘도.
 *
 * CIE 가 정한 L\* → Y 변환 그대로다. 톤 번호가 곧 L\* 이므로 이 함수 하나로 "톤 50 은 흰색의
 * 18% 밝기" 같은 인지 기준이 들어온다 — 산술 평균(0.5)이 아니라는 것이 요점이다.
 */
private fun luminanceForTone(tone: Int): Float {
    val lStar = tone.toFloat()
    return if (lStar > CIE_LINEAR_SEGMENT_LIMIT) {
        ((lStar + 16f) / 116f).pow(3)
    } else {
        lStar / CIE_LINEAR_SEGMENT_SLOPE
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

/**
 * 이분 탐색을 몇 번 접을 것인가.
 *
 * 24 번이면 명도의 폭이 1/16777216 까지 좁혀진다 — 8 비트 채널이 구분할 수 있는 것보다 잘다.
 */
private const val TONE_SEARCH_STEPS = 24

/** CIE 명도가 세제곱이 아니라 직선을 쓰는 구간의 끝(L\* = 8). */
private const val CIE_LINEAR_SEGMENT_LIMIT = 8f

private const val CIE_LINEAR_SEGMENT_SLOPE = 903.2963f
