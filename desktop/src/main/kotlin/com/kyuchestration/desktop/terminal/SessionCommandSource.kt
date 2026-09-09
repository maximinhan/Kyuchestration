package com.kyuchestration.desktop.terminal

import java.nio.file.Path

/**
 * 세션 하나가 무엇을 띄울지 엔진에게 묻는 자리.
 *
 * 세션을 여는 어댑터와 갈라 둔다. 이 포트 뒤에 있는 것은 끝까지 기다리는 자식 프로세스 하나와
 * JSON 문서 하나이고, 여는 쪽에 있는 것은 대화가 사는 동안 계속 도는 프로세스와 그 파이프 셋이다.
 * 한 클래스에 두면 "무엇을 띄우는가" 를 시험할 때마다 `claude` 가 떠야 하고, 반대로 여는 동작을
 * 시험할 때마다 진짜 kyu 가 있어야 한다.
 *
 * fun interface 라 여는 쪽의 시험은 람다 하나로 답을 정해 세울 수 있다.
 */
fun interface SessionCommandSource {

    /**
     * @param conversationChoice 이 세션이 쓸 대화를 어느 쪽으로 정할지. 묻는 것이 곧 기록하는
     *   것이라(설계 문서 5.5.3) 이 값이 워크디렉토리의 대화 기록을 바꾼다 — 화면을 그리려고
     *   부를 수 있는 자리가 아니다.
     * @param approvalSocketPath 앱이 이 세션의 승인 물음을 받으려고 열어 둔 소켓의 자리.
     *   null 이면 이 세션에는 승인 브리지가 없다.
     *
     *   **기본값을 두지 않는다.** `fun interface` 의 추상 메서드는 기본값을 가질 수 없고, 그
     *   제약이 여기서는 오히려 맞다 — 브리지를 줄지 말지는 부르는 자리마다 정해야 하는 것이고,
     *   기본값을 두면 그 자리를 지나친 챗 세션이 승인 카드 없이 조용히 열린다.
     *
     *   **경로를 앱이 정해 엔진에게 준다**(chat-ui-design.md 5.2). 소켓을 여는 것은 앱이고,
     *   그것을 `claude` 의 어느 플래그로 옮길지는 엔진만 안다(원칙 11).
     * @throws TerminalSessionFailure 묻지 못했거나, kyu 가 답하기를 거절했거나, 답을 읽지 못했을 때.
     */
    fun sessionCommandFor(
        workDirPath: Path,
        target: SessionTarget,
        conversationChoice: SessionConversationChoice,
        approvalSocketPath: Path?,
    ): SessionCommandAnswer
}
