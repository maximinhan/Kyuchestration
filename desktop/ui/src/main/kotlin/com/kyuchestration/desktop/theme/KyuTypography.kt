package com.kyuchestration.desktop.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp

/**
 * 한글로 읽는 Material 3 타이포 스케일.
 *
 * **크기 스케일은 Material 3 것을 그대로 쓴다.** 우리가 고치는 것은 자간과 행간 둘뿐이고, 둘 다
 * 그 기본값이 라틴 문자를 기준으로 잡혀 있어서 한글에서 어긋나는 자리다.
 *
 * - **자간을 0 으로 둔다.** M3 의 본문 자간(0.25sp~0.5sp)은 붙어 보이기 쉬운 라틴 소문자를 떼어
 *   놓으려는 값이다. 한글 글자는 그 자체로 네모난 칸을 차지해서 이미 떨어져 있고, 여기에 자간을
 *   더하면 낱말이 아니라 글자 하나하나가 눈에 들어온다.
 * - **행간을 한 뼘 넓힌다.** 한글은 받침이 있는 줄과 없는 줄의 높이가 달라, 라틴 기준 행간에서는
 *   받침이 아랫줄에 닿는 것처럼 보인다.
 *
 * **폰트는 시스템 기본을 그대로 둔다(설계 10 절 열린 질문 9).** 담으면 리눅스와 맥이 같아지지만
 * 설치 패키지가 폰트 하나만큼 커지고 라이선스를 따져야 한다. 지금 이 앱을 쓰는 사람은 자기
 * 데스크톱의 한글 폰트를 이미 고른 사람이고, 목업이 확정되기 전에 그 선택을 덮을 근거가 없다.
 * 확정되면 이 파일 하나에서 [TextStyle.fontFamily] 를 채우는 것으로 끝난다.
 */
internal val KyuTypography: Typography = Typography().forKoreanReading()

private fun Typography.forKoreanReading(): Typography = Typography(
    displayLarge = displayLarge.forKoreanReading(),
    displayMedium = displayMedium.forKoreanReading(),
    displaySmall = displaySmall.forKoreanReading(),
    headlineLarge = headlineLarge.forKoreanReading(),
    headlineMedium = headlineMedium.forKoreanReading(),
    headlineSmall = headlineSmall.forKoreanReading(),
    titleLarge = titleLarge.forKoreanReading(),
    titleMedium = titleMedium.forKoreanReading(),
    titleSmall = titleSmall.forKoreanReading(),
    bodyLarge = bodyLarge.forKoreanReading(),
    bodyMedium = bodyMedium.forKoreanReading(),
    bodySmall = bodySmall.forKoreanReading(),
    labelLarge = labelLarge.forKoreanReading(),
    labelMedium = labelMedium.forKoreanReading(),
    labelSmall = labelSmall.forKoreanReading(),
)

private fun TextStyle.forKoreanReading(): TextStyle = copy(
    lineHeight = lineHeight * KOREAN_LINE_HEIGHT_RATIO,
    letterSpacing = 0.sp,
)

/**
 * 행간을 얼마나 넓히는가.
 *
 * 1.15 는 받침이 아랫줄 글자에 닿지 않을 만큼이면서 문단이 흩어지지 않는 선이다. 더 넓히면
 * 대시보드처럼 짧은 줄이 여럿 쌓이는 화면에서 줄과 줄 사이가 항목과 항목 사이처럼 읽힌다.
 */
private const val KOREAN_LINE_HEIGHT_RATIO = 1.15f
