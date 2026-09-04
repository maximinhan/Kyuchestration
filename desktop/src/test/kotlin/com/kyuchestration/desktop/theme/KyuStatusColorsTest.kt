package com.kyuchestration.desktop.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KyuStatusColorsTest {

    @Test
    fun `라이트 값은 이 전환 전에 화면이 쓰던 것 그대로다`() {
        // 테마를 넣는 것과 색의 뜻을 다시 고르는 것은 다른 일이다. 이 검사가 있어야 "테마를
        // 넣었더니 라이트 화면의 상태 칩이 슬며시 달라졌다" 가 일어나지 않는다.
        with(KyuStatusColors.Light) {
            assertEquals(Color(0xFF2E7D32), session)
            assertEquals(Color(0xFF00695C), resumableConversation)
            assertEquals(Color(0xFFE65100), repoDirty)
            assertEquals(Color(0xFF1565C0), repoAhead)
            assertEquals(Color(0xFF6B6B6B), neutral)
            assertEquals(Color(0xFFB3261E), failure)
            assertEquals(Color(0xFF2E7D32), success)
            assertEquals(Color(0xFF1565C0), notice)
        }
    }

    @Test
    fun `옮긴 것은 caution 하나뿐이고 그 이유는 대비다`() {
        // 예전 값(#B26A00)은 밝은 표면 위에서 4.03:1 이라 본문 글자의 선에 못 미쳤다. 이 색은
        // 배너 머리말로 글자를 그리는 자리라 그 선을 지켜야 한다.
        assertEquals(Color(0xFF9B5C00), KyuStatusColors.Light.caution)

        val previousCaution = Color(0xFFB26A00)
        assertTrue(
            contrastRatio(previousCaution, KyuLightColorScheme.surface) < MIN_TEXT_CONTRAST_RATIO,
            "옮긴 근거가 사라졌다 — 예전 값이 이미 본문 대비를 넘는다",
        )
        assertTrue(
            abs(KyuTonalPalette.fromSeed(previousCaution).hue - KyuTonalPalette.fromSeed(KyuStatusColors.Light.caution).hue) <
                HUE_TOLERANCE_DEGREES,
            "색상까지 바뀌었다 — 옮긴 것은 밝기여야 한다",
        )
    }

    @Test
    fun `다크 값은 뜻을 유지한 채 밝아진다`() {
        lightAndDarkPairs().forEach { (name, pair) ->
            val (light, dark) = pair

            assertTrue(dark.luminance() > light.luminance(), "$name 이 어두운 바탕에서 밝아지지 않았다")

            // 색상까지 옮겨 가면 뜻이 바뀐다 — 초록이던 것이 다크에서 청록으로 읽히면 사용자는
            // 그것을 다른 표시로 센다. 목업이 고른 넷은 사람 손을 거쳐 조금씩 돌아가 있으므로
            // "같은 계열" 이라고 부를 만한 폭까지 허용한다.
            val lightHue = KyuTonalPalette.fromSeed(light).hue
            val darkHue = KyuTonalPalette.fromSeed(dark).hue
            val hueGap = abs(lightHue - darkHue).let { minOf(it, 360f - it) }
            assertTrue(hueGap < HUE_TOLERANCE_DEGREES, "$name 의 색상이 계열을 벗어났다: $lightHue → $darkHue")
        }
    }

    @Test
    fun `목업이 정한 넷은 그대로 실린다`() {
        with(KyuStatusColors.Dark) {
            assertEquals(Color(0xFF5FCF72), session)
            assertEquals(Color(0xFF6FD3C8), resumableConversation)
            assertEquals(Color(0xFFFF9E5C), repoDirty)
            assertEquals(Color(0xFFFF7B72), failure)
        }
    }

    @Test
    fun `모든 상태 색은 점으로 그려도 자기 바탕에서 보인다`() {
        lightAndDarkPairs().forEach { (name, pair) ->
            val lightContrast = contrastRatio(pair.first, KyuLightColorScheme.surface)
            val darkContrast = contrastRatio(pair.second, KyuDarkColorScheme.surface)

            assertTrue(lightContrast >= MIN_GRAPHIC_CONTRAST_RATIO, "$name 라이트: %.2f : 1".format(lightContrast))
            assertTrue(darkContrast >= MIN_GRAPHIC_CONTRAST_RATIO, "$name 다크: %.2f : 1".format(darkContrast))
        }
    }

    @Test
    fun `글자로 쓰이는 상태 색은 본문 대비를 지킨다`() {
        colorsDrawnAsText().forEach { (name, pair) ->
            val lightContrast = contrastRatio(pair.first, KyuLightColorScheme.surface)
            val darkContrast = contrastRatio(pair.second, KyuDarkColorScheme.surface)

            assertTrue(lightContrast >= MIN_TEXT_CONTRAST_RATIO, "$name 라이트: %.2f : 1".format(lightContrast))
            assertTrue(darkContrast >= MIN_TEXT_CONTRAST_RATIO, "$name 다크: %.2f : 1".format(darkContrast))
        }
    }

    /**
     * 지금 화면이 이 색으로 **글자를 그리는** 것들.
     *
     * 배너 머리말(session · caution · failure · notice)과 클론 결과 줄(success · neutral)이다.
     * 나머지는 칩의 점과 옅은 바탕에만 쓰이므로 그림의 선(3:1)을 지키면 된다.
     *
     * 새로 어떤 색으로 글자를 그리게 되면 여기에 함께 적는다. 목록이 아니라 실제 쓰임을
     * 세는 길이 없어서, 이 줄이 그 쓰임을 적어 두는 자리다.
     */
    private fun colorsDrawnAsText(): List<Pair<String, Pair<Color, Color>>> =
        lightAndDarkPairs().filter { (name, _) -> name in DRAWN_AS_TEXT }

    private fun lightAndDarkPairs(): List<Pair<String, Pair<Color, Color>>> = listOf(
        "session" to (KyuStatusColors.Light.session to KyuStatusColors.Dark.session),
        "resumableConversation" to
            (KyuStatusColors.Light.resumableConversation to KyuStatusColors.Dark.resumableConversation),
        "repoDirty" to (KyuStatusColors.Light.repoDirty to KyuStatusColors.Dark.repoDirty),
        "repoAhead" to (KyuStatusColors.Light.repoAhead to KyuStatusColors.Dark.repoAhead),
        "neutral" to (KyuStatusColors.Light.neutral to KyuStatusColors.Dark.neutral),
        "caution" to (KyuStatusColors.Light.caution to KyuStatusColors.Dark.caution),
        "failure" to (KyuStatusColors.Light.failure to KyuStatusColors.Dark.failure),
        "success" to (KyuStatusColors.Light.success to KyuStatusColors.Dark.success),
        "notice" to (KyuStatusColors.Light.notice to KyuStatusColors.Dark.notice),
    )

    private fun contrastRatio(one: Color, other: Color): Float {
        val brighter = maxOf(one.luminance(), other.luminance())
        val darker = minOf(one.luminance(), other.luminance())
        return (brighter + WCAG_OFFSET) / (darker + WCAG_OFFSET)
    }

    private companion object {
        val DRAWN_AS_TEXT = setOf("session", "caution", "failure", "notice", "success", "neutral")

        /** WCAG 가 본문 글자에 요구하는 선. */
        const val MIN_TEXT_CONTRAST_RATIO = 4.5f

        /** WCAG 가 글자가 아닌 그림 요소(칩의 점 · 테두리)에 요구하는 선. */
        const val MIN_GRAPHIC_CONTRAST_RATIO = 3f

        const val WCAG_OFFSET = 0.05f

        /**
         * 같은 계열이라고 부를 수 있는 폭.
         *
         * 색상환에서 15 도는 초록과 청록 사이가 벌어지기 시작하는 자리보다 좁다. 목업이 고른
         * 네 색이 라이트 짝에서 최대 이만큼 돌아가 있다.
         */
        const val HUE_TOLERANCE_DEGREES = 15f
    }
}
