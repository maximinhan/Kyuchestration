package com.kyuchestration.desktop.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * 앱이 쓰는 모서리 반경.
 *
 * 값 자체는 Material 3 기본과 거의 같다. 이 파일이 있는 이유는 값이 아니라 **자리**다 — 칩의
 * 6dp 와 배너의 8dp 가 그리는 쪽 파일에 직접 적혀 있었고, 그러면 새로 그리는 사람이 옆 파일을
 * 열어 숫자를 베낀다. 여기 모아 두면 "칩은 extraSmall, 배너는 small, 카드는 medium" 이 규칙이 된다.
 *
 * 대화가 들어오는 3 단계에서 말풍선의 반경이 여기 더해진다(설계 6.1 — 대화의 것과 기계의 것을
 * 모양으로 가른다). 지금은 그릴 말풍선이 없으므로 두지 않는다.
 */
internal val KyuShapes: Shapes = Shapes(
    /** 상태 칩. */
    extraSmall = RoundedCornerShape(6.dp),
    /** 목록 위에 얹히는 배너와 목록 안의 행. */
    small = RoundedCornerShape(8.dp),
    /** 카드. */
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    /** 대화상자. */
    extraLarge = RoundedCornerShape(28.dp),
)
