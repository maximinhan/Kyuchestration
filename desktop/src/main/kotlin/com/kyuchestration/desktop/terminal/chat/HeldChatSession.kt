package com.kyuchestration.desktop.terminal.chat

import com.kyuchestration.desktop.terminal.SessionTarget

/**
 * 앱이 지금 보유 중인 챗 세션 하나 — 그 대상과, 그것에 말을 걸 통로.
 *
 * 터미널의 `HeldSession` 과 같은 자리이고 쥐는 것만 다르다(통로가 `TtyConnector` 에서
 * [OpenedChatSession] 으로). 엔진에게 물어서 얻는 사실이 아니라는 것도 같다 — 세션을 띄우는
 * 것이 앱뿐이라 엔진에게는 셀 세션이 없다(설계 5.4).
 *
 * **대화는 여기 없다.** 전사는 세션보다 오래 산다 — 세션이 끝난 뒤에도 사용자가 왜 끝났는지
 * 읽을 수 있어야 해서, 그것을 들고 있는 자리는 상태 홀더의 대화 맵이다.
 *
 * @param resumedConversationId 이 세션이 이어가는 대화의 ID. 새 대화면 null. 세션이 실패한
 *   코드로 끝났을 때 그것을 이어가기 실패로 읽는 근거다(5.5.4).
 */
data class HeldChatSession(
    val target: SessionTarget,
    val session: OpenedChatSession,
    val resumedConversationId: String?,
)
