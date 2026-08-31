package com.kyuchestration.desktop.terminal

/**
 * 앱 안의 터미널이 들어갈 세션 하나.
 *
 * 문자열 하나로 두지 않는다. kyu 는 메인 세션과 레포 세션을 같은 자리(레포 이름 자리)에서
 * 가리키지만 명령마다 문법이 어긋난다 — `kyu session-command` 와 `kyu start` 는 인자 없는 실행을
 * 메인 세션으로 읽고, `kyu attach main` 은 이름으로 가리킨다. 그 차이를 문자열 비교로 다루면
 * 부르는 쪽마다 다시 틀린다. 라벨은 화면에 쓰는 이름이지 명령의 인자가 아니다.
 */
sealed interface SessionTarget {

    /** 화면에 그대로 쓰는 이름. kyu 가 사람에게 쓰는 이름과 같다. */
    val label: String

    data class Repo(val repoName: String) : SessionTarget {
        override val label: String get() = repoName
    }

    /**
     * 워크디렉토리 최상위의 조율용 세션.
     *
     * main 은 kyu 가 예약한 이름이다(session_target.go 의 sessionNameForLabel).
     */
    data object Main : SessionTarget {
        override val label: String = "main"
    }
}
