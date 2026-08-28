package com.kyuchestration.desktop.terminal

import com.jediterm.terminal.TtyConnector
import java.nio.file.Path

/**
 * 앱 안의 터미널을 워크디렉토리의 세션 하나에 붙이는 자리.
 *
 * 돌려주는 것이 JediTerm 의 타입이다. 한 겹 더 감싸지 않는 이유는 화면이 그것 말고 다른 것으로는
 * 터미널을 그릴 수 없어서다 — 감싸 봐야 그 겹이 하는 일은 위임뿐이고, 라이브러리를 갈아 끼울 때
 * 어차피 화면도 함께 바뀐다.
 *
 * fun interface 라 상태 홀더의 시험은 람다 하나로 대신 세울 수 있다.
 */
fun interface SessionTerminalAttacher {

    /**
     * 대상 세션을 띄우고(이미 떠 있으면 그대로 두고) 그 세션에 붙은 통로를 돌려준다.
     *
     * @throws TerminalSessionFailure 붙지 못했을 때.
     */
    fun attachTo(workDirPath: Path, target: SessionTarget): TtyConnector
}
