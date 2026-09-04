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
 * **말풍선과 카드가 다른 반경을 쓴다**(설계 6.1 — 대화의 것과 기계의 것을 모양으로 가른다).
 * 사용자 말풍선은 large 이고 도구 카드는 medium 이다. 여기에 `bubble` 같은 자리를 새로 만들지
 * 않은 것은, Material 의 셋이 이미 그 차이를 낼 만큼 벌어져 있어서다 — 이름이 하나 늘면 그리는
 * 쪽이 "말풍선은 어느 쪽인가" 를 매번 다시 물어야 한다.
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
