package com.kyuchestration.desktop.terminal

/**
 * 이어가려던 대화를 열지 못한 것으로 보이는가.
 *
 * 화면(ChatConversation)과 진단 기록(ChatSessionStateHolder)이 함께 쓴다. 둘이 같은 판단을 각자
 * 적어 두면 한쪽만 고쳐지는 날이 오고, 그날 화면과 기록은 같은 세션을 두고 다른 말을 한다 —
 * 받은 기록으로 화면에서 본 것을 확인하려던 사람이 가장 먼저 걸리는 자리다.
 *
 * **단정하지 않는 이름인 것이 뜻이다.** 대화가 열린 뒤 `claude` 가 다른 이유로 죽어도 같은 모양이
 * 된다. 그래서 화면은 이 자리에서 대신 새 대화를 열어 주지 않고, 왜 끝났는지는 남아 있는 마지막
 * 줄이 말하게 두고(전사가 없다는 `claude` 의 한 줄이 거기 있다), 새로 시작할지는 사용자가
 * 정한다(app-owned-sessions-design.md 5.5.4).
 *
 * 종료 코드를 모르면 말하지 않는다. 예외로 끝나 코드가 없는 자리까지 실패로 세면 화면이 일어나지
 * 않은 일을 알리게 된다.
 */
internal fun resumeFailureSuspected(resumedConversationId: String?, exitCode: Int?): Boolean =
    resumedConversationId != null && exitCode != null && exitCode != 0
