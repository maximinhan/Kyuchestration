package com.kyuchestration.desktop.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KyuTypographyTest {

    @Test
    fun `모든 스타일의 자간이 0 이다`() {
        allStyles(KyuTypography).forEach { (name, style) ->
            assertEquals(0.sp, style.letterSpacing, "$name 의 자간이 0 이 아니다")
        }
    }

    @Test
    fun `모든 스타일의 행간이 Material 3 기본보다 넓다`() {
        val baseline = allStyles(Typography()).toMap()

        allStyles(KyuTypography).forEach { (name, style) ->
            val baselineLineHeight = baseline.getValue(name).lineHeight
            assertTrue(
                style.lineHeight.value > baselineLineHeight.value,
                "$name 의 행간이 넓어지지 않았다: ${style.lineHeight} (기본 $baselineLineHeight)",
            )
        }
    }

    @Test
    fun `글자 크기 스케일은 Material 3 그대로다`() {
        val baseline = allStyles(Typography()).toMap()

        allStyles(KyuTypography).forEach { (name, style) ->
            assertEquals(baseline.getValue(name).fontSize, style.fontSize, "$name 의 크기가 바뀌었다")
        }
    }

    private fun allStyles(typography: Typography): List<Pair<String, TextStyle>> = with(typography) {
        listOf(
            "displayLarge" to displayLarge,
            "displayMedium" to displayMedium,
            "displaySmall" to displaySmall,
            "headlineLarge" to headlineLarge,
            "headlineMedium" to headlineMedium,
            "headlineSmall" to headlineSmall,
            "titleLarge" to titleLarge,
            "titleMedium" to titleMedium,
            "titleSmall" to titleSmall,
            "bodyLarge" to bodyLarge,
            "bodyMedium" to bodyMedium,
            "bodySmall" to bodySmall,
            "labelLarge" to labelLarge,
            "labelMedium" to labelMedium,
            "labelSmall" to labelSmall,
        )
    }
}
