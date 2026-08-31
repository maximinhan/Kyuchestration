package com.kyuchestration.desktop.terminal

import java.nio.file.Path

/**
 * 워크디렉토리의 세션 하나를 앱 안에서 새로 띄우는 자리.
 *
 * "붙는다"(attach)가 아니라 "띄운다"(open)다. 앱은 더 이상 남이 만든 세션에 손님으로 들어가지
 * 않는다 — 자기 PTY 에서 `claude` 를 직접 띄우고 그 프로세스를 자기가 보유한다(설계 문서 5.3).
 * 그래서 이 자리가 돌려주는 통로를 닫는 것이 곧 그 세션을 끝내는 일이다.
 *
 * 돌려주는 것 안의 통로가 JediTerm 의 타입이다. 한 겹 더 감싸지 않는 이유는 화면이 그것 말고
 * 다른 것으로는 터미널을 그릴 수 없어서다 — 감싸 봐야 그 겹이 하는 일은 위임뿐이고, 라이브러리를
 * 갈아 끼울 때 어차피 화면도 함께 바뀐다.
 *
 * fun interface 라 상태 홀더의 시험은 람다 하나로 대신 세울 수 있다.
 */
fun interface SessionTerminalOpener {

    /**
     * 그 세션을 띄우고, 앱이 보유할 것을 돌려준다.
     *
     * @param conversationChoice 이 세션이 쓸 대화를 어느 쪽으로 정할지. 여기를 지나 엔진에게
     *   닿고, 엔진이 그 뜻대로 명령을 조립한다.
     * @throws TerminalSessionFailure 띄우지 못했을 때.
     */
    fun openSessionIn(
        workDirPath: Path,
        target: SessionTarget,
        conversationChoice: SessionConversationChoice,
    ): OpenedSessionTerminal
}
