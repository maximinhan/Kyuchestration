package com.kyuchestration.desktop.terminal

import java.nio.file.Path

/**
 * 세션 하나가 무엇을 띄울지 엔진에게 묻는 자리.
 *
 * PTY 를 여는 어댑터와 갈라 둔다. 이 포트 뒤에 있는 것은 보통의 자식 프로세스 하나와 JSON 문서
 * 하나이고, PTY 쪽에 있는 것은 커널이 만든 진짜 터미널 장치다. 한 클래스에 두면 "무엇을 띄우는가"
 * 를 시험할 때마다 PTY 가 열려야 하고, 반대로 PTY 의 동작을 시험할 때마다 진짜 kyu 가 있어야 한다.
 *
 * fun interface 라 PTY 어댑터의 시험은 람다 하나로 답을 정해 세울 수 있다.
 */
fun interface SessionCommandSource {

    /**
     * @param conversationChoice 이 세션이 쓸 대화를 어느 쪽으로 정할지. 묻는 것이 곧 기록하는
     *   것이라(설계 문서 5.5.3) 이 값이 워크디렉토리의 대화 기록을 바꾼다 — 화면을 그리려고
     *   부를 수 있는 자리가 아니다.
     * @throws TerminalSessionFailure 묻지 못했거나, kyu 가 답하기를 거절했거나, 답을 읽지 못했을 때.
     */
    fun sessionCommandFor(
        workDirPath: Path,
        target: SessionTarget,
        conversationChoice: SessionConversationChoice,
    ): SessionCommandAnswer
}
