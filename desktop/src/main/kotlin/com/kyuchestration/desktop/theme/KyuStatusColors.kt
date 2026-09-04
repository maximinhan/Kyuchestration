package com.kyuchestration.desktop.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * 뜻이 정해져 있는 색들.
 *
 * Material 3 의 색 역할은 "이것이 강조인가 표면인가" 를 말하지 "이것이 DIRTY 인가 세션인가" 를
 * 말하지 않는다. 그래서 상태 칩과 배너가 쓰는 색은 스킴에 자리가 없고, 그동안 그리는 쪽 파일마다
 * `Color(0xFF2E7D32)` 로 흩어져 있었다 — 같은 초록이 세 파일에 세 번 적혀 있었고, 다크 화면에서
 * 그것들이 어떻게 보여야 하는지는 아무도 정한 적이 없었다.
 *
 * **뜻이 다르면 이름이 다르다.** [repoAhead] 와 [notice] 는 지금 같은 파랑이지만 하나는 git 이
 * 본 것이고 하나는 앱이 하는 안내다. 값이 같다고 이름을 합치면, 목업이 둘을 가르기로 한 날
 * 어느 자리가 어느 뜻이었는지 알 수 없다.
 *
 * 라이트 값은 이 전환 전에 화면이 쓰던 것 그대로다 — [caution] 하나만 빼고. 테마를 넣는 것과
 * 뜻을 다시 고르는 것은 다른 일이고, 색의 뜻을 바꾸는 것은 목업이 확정될 때 할 일이다.
 */
@Immutable
data class KyuStatusColors(
    /** 앱이 이 세션을 지금 보유하고 있다. */
    val session: Color,
    /** 누르면 앞서 하던 대화가 이어진다. */
    val resumableConversation: Color,
    /** git 이 커밋하지 않은 변경을 봤다. */
    val repoDirty: Color,
    /** git 이 밀지 않은 커밋을 봤다. */
    val repoAhead: Color,
    /** 아무 일도 없거나, 우리가 모르는 낱말이거나. 없는 뜻을 색으로 지어내지 않는 자리다. */
    val neutral: Color,
    /**
     * 막지는 않지만 알고 지나가야 하는 것 — 평문 저장 · 비공개 레포 · 이어가기 실패.
     *
     * **이 값만 예전 것(`#B26A00`)에서 옮겼다.** 이 색은 배너 머리말과 안내 줄에 **글자로**
     * 쓰이는데, 밝은 표면 위에서 대비가 4.03:1 이라 본문 글자의 선(4.5:1)에 못 미쳤다. 같은
     * 색상을 유지한 채 한 톤 어둡게 옮겨 5.08:1 로 맞췄다 — 경고의 뜻도, 앰버라는 계열도 그대로다.
     */
    val caution: Color,
    /** 하려던 일이 되지 않았다. */
    val failure: Color,
    /** 하려던 일이 됐다. */
    val success: Color,
    /** 앱이 사용자에게 거는 말. 잘못된 것이 아니라 알려 주는 것이다. */
    val notice: Color,
) {
    companion object {

        val Light = KyuStatusColors(
            session = Color(0xFF2E7D32),
            resumableConversation = Color(0xFF00695C),
            repoDirty = Color(0xFFE65100),
            repoAhead = Color(0xFF1565C0),
            neutral = Color(0xFF6B6B6B),
            caution = Color(0xFF9B5C00),
            failure = Color(0xFFB3261E),
            success = Color(0xFF2E7D32),
            notice = Color(0xFF1565C0),
        )

        /**
         * 어두운 바탕 위의 같은 뜻들. **목업이 준 넷은 그대로, 나머지 다섯은 라이트에서 뽑는다.**
         *
         * 손으로 아홉 색을 다 고르지 않는 이유는 뜻은 색상이 지고 밝기는 바탕이 정하기 때문이다 —
         * 두 벌을 처음부터 끝까지 따로 적어 두면 언젠가 한쪽만 고쳐진다. 목업이 정한 넷은
         * 사람이 실제로 견주어 고른 값이라 뽑은 것으로 대신하지 않는다.
         */
        val Dark = KyuStatusColors(
            session = Color(0xFF5FCF72),
            resumableConversation = Color(0xFF6FD3C8),
            repoDirty = Color(0xFFFF9E5C),
            repoAhead = Light.repoAhead.forDarkSurface(),
            neutral = Light.neutral.forDarkSurface(),
            caution = Light.caution.forDarkSurface(),
            failure = Color(0xFFFF7B72),
            // 클론이 끝났다는 표시. 세션이 돈다는 표시와 같은 초록을 쓰던 자리라 함께 옮긴다.
            success = Color(0xFF5FCF72),
            notice = Light.notice.forDarkSurface(),
        )
    }
}

private fun Color.forDarkSurface(): Color = KyuTonalPalette.fromSeed(this).tone(DARK_SURFACE_TONE)

/**
 * 어두운 바탕 위의 상태 색이 서는 톤.
 *
 * 목업이 준 넷이 놓인 자리(67~78)의 가운데쯤이다. 뽑아 쓰는 다섯이 목업의 넷과 같은 무게로
 * 보여야, 한 줄에 나란히 놓였을 때 어느 쪽이 더 급한 말인지가 색의 밝기에서 잘못 읽히지 않는다.
 */
private const val DARK_SURFACE_TONE = 74
