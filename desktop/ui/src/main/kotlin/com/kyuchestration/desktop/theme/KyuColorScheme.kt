package com.kyuchestration.desktop.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * 다크 화면의 강조색. **목업이 확정한 앰버이고, 바꿀 것은 이 한 줄이다.**
 *
 * 아래 다크 스킴의 강조 계열 전부가 이것에서 나온다 — 강조를 바꾸는 일이 파생된 색 여럿을
 * 함께 맞춰 주는 일이 되지 않는다.
 */
private val DARK_ACCENT_SEED = Color(0xFFF5B544)

/**
 * 다크 화면의 바탕 계열 씨앗. 목업이 준 가장 어두운 배경이다.
 *
 * **강조에서 뽑지 않는다.** 목업의 바탕은 초록빛이 도는 회색이고 강조는 따뜻한 앰버라, 한
 * 씨앗에서 둘을 함께 뽑으면 바탕이 갈색으로 물든다. 그래서 다크는 씨앗 둘 — 강조 하나와
 * 바탕 하나 — 을 갖는다.
 *
 * **목업이 준 나머지 중립색이 전부 이 한 계단 위에 있다.** 배경 셋(#131615 · #161A19 · #1A1F1D),
 * 테두리 둘(#262C2A · #2C3331), 보조 글자 둘(#8A938E · #A4ACA8), 본문(#E6E9E7)이 이 씨앗의
 * 톤 7 · 9 · 11 · 17 · 21 · 60 · 70 · 92 에 놓인다. 그것을 KyuColorSchemeTest 가 확인한다 —
 * 목업을 손으로 옮겨 적는 대신 계단 하나로 재현하는 근거다.
 */
private val DARK_NEUTRAL_SEED = Color(0xFF131615)

/**
 * 라이트 화면의 강조색.
 *
 * **라이트는 지금 기본이 아니다.** 앱은 다크로 뜨고(ThemePreference), 라이트는 설정에서 고를 수
 * 있는 쪽이다. 목업이 확정한 것은 다크 쪽이라 이 계열은 아직 그 짝을 기다린다 — 그래도 스킴을
 * 세워 두는 것은, 고를 수 있다고 말해 놓고 골랐을 때 기본 보라색 Material 이 뜨는 자리를
 * 만들지 않기 위해서다.
 */
private val LIGHT_ACCENT_SEED = Color(0xFF0F766E)

/**
 * 실패를 말하는 붉은 계열의 씨앗.
 *
 * 강조에서 파생하지 않는다. 강조가 바뀌어도 실패는 붉어야 하고, 강조가 마침 붉은 계열이면
 * 파생된 error 역할이 강조와 구분되지 않는다. 값은 앱이 이미 쓰던 실패 색 그대로다.
 */
private val ERROR_SEED = Color(0xFFB3261E)

internal val KyuLightColorScheme: ColorScheme = lightColorSchemeFrom(LIGHT_ACCENT_SEED)

internal val KyuDarkColorScheme: ColorScheme = darkColorSchemeFrom(DARK_ACCENT_SEED, DARK_NEUTRAL_SEED)

/**
 * 다크 스킴 한 벌.
 *
 * 톤 번호가 이 함수의 전부다. 목업이 준 색들이 어느 톤인지를 재어(6.9 · 8.8 · 11.2 · 17.4 ·
 * 20.5 · 60.1 · 69.6 · 92.1) 가장 가까운 정수로 적었고, 목업이 주지 않은 역할은 Material 3 이
 * 다크에 정해 둔 번호를 그대로 쓴다.
 *
 * **표면 층을 다섯으로 벌린다.** 목업은 배경을 셋 줬는데 Material 3 의 층은 다섯이다. 준 셋을
 * 가운데 셋(7 · 9 · 11)에 놓고 위아래로 한 칸씩(5 · 14) 더 뽑았다 — 같은 계단 위라 목업이
 * 정한 간격이 그대로 이어진다.
 */
private fun darkColorSchemeFrom(accentSeed: Color, neutralSeed: Color): ColorScheme {
    val family = paletteFamilyFrom(accentSeed, KyuTonalPalette.fromSeed(neutralSeed))
    val (accent, secondary, tertiary, neutral, error) = family

    return darkColorScheme(
        // 목업의 앰버(#F5B544)가 놓인 톤. Material 3 의 80 과 두 걸음 차이지만, 목업이 고른
        // 색을 그대로 내는 쪽을 골랐다 — 이 값이 화면에서 가장 눈에 띄는 한 색이다.
        primary = accent.tone(78),
        // 목업의 #1A1200. Material 3 의 20 보다 훨씬 어둡다 — 밝은 앰버 위의 글자라 그만큼
        // 떨어뜨려야 읽힌다.
        onPrimary = accent.tone(6),
        primaryContainer = accent.tone(30),
        onPrimaryContainer = accent.tone(92),
        inversePrimary = accent.tone(40),
        secondary = secondary.tone(78),
        onSecondary = secondary.tone(6),
        secondaryContainer = secondary.tone(28),
        onSecondaryContainer = secondary.tone(92),
        tertiary = tertiary.tone(78),
        onTertiary = tertiary.tone(6),
        tertiaryContainer = tertiary.tone(28),
        onTertiaryContainer = tertiary.tone(92),
        error = error.tone(70),
        onError = error.tone(10),
        errorContainer = error.tone(28),
        onErrorContainer = error.tone(92),
        // 목업의 가장 어두운 배경. 대화 자리가 이 색이다.
        background = neutral.tone(7),
        onBackground = neutral.tone(92),
        surface = neutral.tone(7),
        // 목업의 본문색 #E6E9E7.
        onSurface = neutral.tone(92),
        surfaceVariant = neutral.tone(21),
        // 목업의 보조 글자 #A4ACA8.
        onSurfaceVariant = neutral.tone(70),
        surfaceTint = accent.tone(78),
        inverseSurface = neutral.tone(90),
        inverseOnSurface = neutral.tone(15),
        // 목업의 테두리 #2C3331. 나누는 선과 카드 테두리가 이것 하나를 함께 쓴다.
        outlineVariant = neutral.tone(21),
        // 목업에 없는 역할. Material 3 에서 outline 은 입력 칸과 외곽선 버튼의 테두리라
        // 나누는 선보다 훨씬 잘 보여야 한다.
        outline = neutral.tone(50),
        scrim = neutral.tone(0),
        surfaceBright = neutral.tone(21),
        surfaceDim = neutral.tone(5),
        surfaceContainerLowest = neutral.tone(5),
        surfaceContainerLow = neutral.tone(7),
        // 레일과 세션 패널이 이 색이다 — 목업의 #161A19.
        surfaceContainer = neutral.tone(9),
        // 목업의 #1A1F1D.
        surfaceContainerHigh = neutral.tone(11),
        surfaceContainerHighest = neutral.tone(14),
    )
}

/**
 * 라이트 스킴 한 벌.
 *
 * 톤 번호는 Material 3 이 정한 것을 그대로 쓴다(강조 40, 그 위의 글자 100, 컨테이너 90).
 * 목업이 아직 라이트를 확정하지 않았으므로 여기서 우리가 정하는 것은 색이 아니라 **어느
 * 팔레트를 쓰는가** 뿐이다.
 */
private fun lightColorSchemeFrom(accentSeed: Color): ColorScheme {
    val accentPalette = KyuTonalPalette.fromSeed(accentSeed)
    val family = paletteFamilyFrom(accentSeed, accentPalette.withChromaRatio(LIGHT_NEUTRAL_CHROMA_RATIO))
    val (accent, secondary, tertiary, neutral, error) = family
    val neutralVariant = accentPalette.withChromaRatio(LIGHT_NEUTRAL_VARIANT_CHROMA_RATIO)

    return lightColorScheme(
        primary = accent.tone(40),
        onPrimary = accent.tone(100),
        primaryContainer = accent.tone(90),
        onPrimaryContainer = accent.tone(10),
        inversePrimary = accent.tone(80),
        secondary = secondary.tone(40),
        onSecondary = secondary.tone(100),
        secondaryContainer = secondary.tone(90),
        onSecondaryContainer = secondary.tone(10),
        tertiary = tertiary.tone(40),
        onTertiary = tertiary.tone(100),
        tertiaryContainer = tertiary.tone(90),
        onTertiaryContainer = tertiary.tone(10),
        error = error.tone(40),
        onError = error.tone(100),
        errorContainer = error.tone(90),
        onErrorContainer = error.tone(10),
        background = neutral.tone(98),
        onBackground = neutral.tone(10),
        surface = neutral.tone(98),
        onSurface = neutral.tone(10),
        surfaceVariant = neutralVariant.tone(90),
        onSurfaceVariant = neutralVariant.tone(30),
        surfaceTint = accent.tone(40),
        inverseSurface = neutral.tone(20),
        inverseOnSurface = neutral.tone(95),
        outline = neutralVariant.tone(50),
        outlineVariant = neutralVariant.tone(80),
        scrim = neutral.tone(0),
        surfaceBright = neutral.tone(98),
        surfaceDim = neutral.tone(87),
        surfaceContainerLowest = neutral.tone(100),
        surfaceContainerLow = neutral.tone(96),
        surfaceContainer = neutral.tone(94),
        surfaceContainerHigh = neutral.tone(92),
        surfaceContainerHighest = neutral.tone(90),
    )
}

/**
 * 한 스킴이 쓰는 계단 다섯. 라이트와 다크가 같은 규칙으로 만든다.
 *
 * 구조 분해로 받으려고 data class 로 둔다 — 스킴 함수 두 곳이 다섯 이름을 그대로 쓴다.
 */
private data class KyuPaletteFamily(
    val accent: KyuTonalPalette,
    val secondary: KyuTonalPalette,
    val tertiary: KyuTonalPalette,
    val neutral: KyuTonalPalette,
    val error: KyuTonalPalette,
)

/**
 * @param neutral 바탕 계열. 다크는 목업이 준 바탕에서, 라이트는 강조를 옅게 해서 온다 —
 *   그 차이가 두 스킴의 유일한 구조 차이라 인자로 받는다.
 */
private fun paletteFamilyFrom(accentSeed: Color, neutral: KyuTonalPalette): KyuPaletteFamily {
    val accent = KyuTonalPalette.fromSeed(accentSeed)
    return KyuPaletteFamily(
        accent = accent,
        secondary = accent.withChromaRatio(SECONDARY_CHROMA_RATIO),
        tertiary = accent.withHueRotated(TERTIARY_HUE_ROTATION_DEGREES).withChromaRatio(TERTIARY_CHROMA_RATIO),
        neutral = neutral,
        error = KyuTonalPalette.fromSeed(ERROR_SEED),
    )
}

/** 보조색은 같은 색상을 옅게 쓴 것이다. 강조와 겨루지 않아야 보조다. */
private const val SECONDARY_CHROMA_RATIO = 0.35f

/** Material 3 이 tertiary 를 만들 때 색상환을 돌리는 각도. */
private const val TERTIARY_HUE_ROTATION_DEGREES = 60f

private const val TERTIARY_CHROMA_RATIO = 0.55f

/**
 * 라이트의 바탕에 남기는 강조의 흔적.
 *
 * 0 으로 두면 완전한 회색이 되는데, 그러면 앱의 배경이 어느 강조색을 써도 똑같아진다. 아주
 * 옅게 물들여 두면 배경까지 한 벌로 읽힌다 — 다크가 목업의 초록빛 회색을 쓰는 것과 같은 뜻이다.
 */
private const val LIGHT_NEUTRAL_CHROMA_RATIO = 0.05f

/** 라이트의 테두리와 보조 글자. 배경보다 한 걸음 더 물들어 있어야 면과 선이 갈린다. */
private const val LIGHT_NEUTRAL_VARIANT_CHROMA_RATIO = 0.14f
