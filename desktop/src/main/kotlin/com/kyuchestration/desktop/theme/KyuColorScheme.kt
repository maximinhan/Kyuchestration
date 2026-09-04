package com.kyuchestration.desktop.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * 라이트 화면의 강조색. **목업이 확정되면 바꿀 것은 이 한 줄이다.**
 *
 * 아래의 스킴 두 벌은 전부 이 씨앗과 다크 쪽 씨앗에서 나온다 — 색을 손으로 적어 둔 자리가
 * 없으므로, 강조를 바꾸는 일이 파생된 색 스물몇 개를 함께 맞춰 주는 일이 되지 않는다.
 */
private val LIGHT_ACCENT_SEED = Color(0xFF0F766E)

/**
 * 다크 화면의 강조색. 라이트와 다른 계열인 것이 의도다.
 *
 * 같은 틸을 어두운 바탕에 그대로 쓰면 강조가 배경에 가라앉는다. 목업이 고른 앰버는 어두운
 * 바탕에서 스스로 빛나는 계열이라, 두 스킴이 같은 씨앗을 나눠 쓰는 대신 각자의 씨앗을 갖는다.
 */
private val DARK_ACCENT_SEED = Color(0xFFF5B544)

/**
 * 실패를 말하는 붉은 계열의 씨앗.
 *
 * 강조에서 파생하지 않는다. 강조가 바뀌어도 실패는 붉어야 하고, 강조가 마침 붉은 계열이면
 * 파생된 error 역할이 강조와 구분되지 않는다. 값은 앱이 이미 쓰던 실패 색 그대로다
 * (KyuStatusColors 의 failure 와 같은 씨앗).
 */
private val ERROR_SEED = Color(0xFFB3261E)

internal val KyuLightColorScheme: ColorScheme = lightColorSchemeFrom(LIGHT_ACCENT_SEED)

internal val KyuDarkColorScheme: ColorScheme = darkColorSchemeFrom(DARK_ACCENT_SEED)

/**
 * 씨앗 하나에서 라이트 스킴 한 벌.
 *
 * 톤 번호는 Material 3 이 정한 것을 그대로 쓴다(라이트: 강조 40, 그 위의 글자 100, 컨테이너 90).
 * 여기서 우리가 정하는 것은 색이 아니라 **어느 팔레트를 쓰는가** 뿐이고, 그래서 이 함수는
 * 목업이 바뀌어도 그대로 남는다.
 */
private fun lightColorSchemeFrom(accentSeed: Color): ColorScheme {
    val (accent, secondary, tertiary, neutral, neutralVariant, error) = paletteFamilyFrom(accentSeed)

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
        // 챗의 깊이가 놓일 층. 배경 · 말풍선 · 도구 카드 · 카드 안 코드가 이 다섯 칸에 대응한다
        // (설계 6.1). 지금은 카드와 배너가 아래 두 칸만 쓰지만, 층 자체는 여기서 이미 갈라 둔다.
        surfaceContainerLowest = neutral.tone(100),
        surfaceContainerLow = neutral.tone(96),
        surfaceContainer = neutral.tone(94),
        surfaceContainerHigh = neutral.tone(92),
        surfaceContainerHighest = neutral.tone(90),
    )
}

/**
 * 다크 스킴은 톤 번호가 뒤집힌다 — 강조가 80, 그 위의 글자가 20, 컨테이너가 30.
 *
 * 표면 층은 뒤집는 것으로 끝나지 않는다. 라이트는 100 에서 90 으로 다섯 칸이 촘촘한데,
 * 다크는 4 에서 22 로 더 멀리 벌린다. 어두운 쪽에서는 톤 차이가 눈에 덜 띄어서, 같은 간격으로
 * 두면 카드가 배경에서 떠오르지 않는다.
 */
private fun darkColorSchemeFrom(accentSeed: Color): ColorScheme {
    val (accent, secondary, tertiary, neutral, neutralVariant, error) = paletteFamilyFrom(accentSeed)

    return darkColorScheme(
        primary = accent.tone(80),
        onPrimary = accent.tone(20),
        primaryContainer = accent.tone(30),
        onPrimaryContainer = accent.tone(90),
        inversePrimary = accent.tone(40),
        secondary = secondary.tone(80),
        onSecondary = secondary.tone(20),
        secondaryContainer = secondary.tone(30),
        onSecondaryContainer = secondary.tone(90),
        tertiary = tertiary.tone(80),
        onTertiary = tertiary.tone(20),
        tertiaryContainer = tertiary.tone(30),
        onTertiaryContainer = tertiary.tone(90),
        error = error.tone(80),
        onError = error.tone(20),
        errorContainer = error.tone(30),
        onErrorContainer = error.tone(90),
        background = neutral.tone(6),
        onBackground = neutral.tone(90),
        surface = neutral.tone(6),
        onSurface = neutral.tone(90),
        surfaceVariant = neutralVariant.tone(30),
        onSurfaceVariant = neutralVariant.tone(80),
        surfaceTint = accent.tone(80),
        inverseSurface = neutral.tone(90),
        inverseOnSurface = neutral.tone(20),
        outline = neutralVariant.tone(60),
        outlineVariant = neutralVariant.tone(30),
        scrim = neutral.tone(0),
        surfaceBright = neutral.tone(24),
        surfaceDim = neutral.tone(6),
        surfaceContainerLowest = neutral.tone(4),
        surfaceContainerLow = neutral.tone(10),
        surfaceContainer = neutral.tone(12),
        surfaceContainerHigh = neutral.tone(17),
        surfaceContainerHighest = neutral.tone(22),
    )
}

/**
 * 한 스킴이 쓰는 계단 여섯. 라이트와 다크가 같은 규칙으로 자기 씨앗에서 이것을 만든다.
 *
 * 구조 분해로 받으려고 data class 로 둔다 — 스킴 함수 두 곳이 여섯 이름을 그대로 쓴다.
 */
private data class KyuPaletteFamily(
    val accent: KyuTonalPalette,
    val secondary: KyuTonalPalette,
    val tertiary: KyuTonalPalette,
    val neutral: KyuTonalPalette,
    val neutralVariant: KyuTonalPalette,
    val error: KyuTonalPalette,
)

private fun paletteFamilyFrom(accentSeed: Color): KyuPaletteFamily {
    val accent = KyuTonalPalette.fromSeed(accentSeed)
    return KyuPaletteFamily(
        accent = accent,
        secondary = accent.withChromaRatio(SECONDARY_CHROMA_RATIO),
        tertiary = accent.withHueRotated(TERTIARY_HUE_ROTATION_DEGREES).withChromaRatio(TERTIARY_CHROMA_RATIO),
        neutral = accent.withChromaRatio(NEUTRAL_CHROMA_RATIO),
        neutralVariant = accent.withChromaRatio(NEUTRAL_VARIANT_CHROMA_RATIO),
        error = KyuTonalPalette.fromSeed(ERROR_SEED),
    )
}

/** 보조색은 같은 색상을 옅게 쓴 것이다. 강조와 겨루지 않아야 보조다. */
private const val SECONDARY_CHROMA_RATIO = 0.35f

/** Material 3 이 tertiary 를 만들 때 색상환을 돌리는 각도. */
private const val TERTIARY_HUE_ROTATION_DEGREES = 60f

private const val TERTIARY_CHROMA_RATIO = 0.55f

/**
 * 표면 계열에 남기는 강조의 흔적.
 *
 * 0 으로 두면 완전한 회색이 되는데, 그러면 앱의 배경이 어느 강조색을 써도 똑같아진다. 아주
 * 옅게 물들여 두면 배경까지 한 벌로 읽힌다 — Material 3 이 중립 팔레트에 채도를 조금 남기는 이유다.
 */
private const val NEUTRAL_CHROMA_RATIO = 0.05f

/** 테두리와 보조 글자가 쓰는 계열. 배경보다 한 걸음 더 물들어 있어야 면과 선이 갈린다. */
private const val NEUTRAL_VARIANT_CHROMA_RATIO = 0.14f
