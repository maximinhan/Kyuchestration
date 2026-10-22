package com.kyuchestration.desktop.terminal

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 앱이 열 세션 하나.
 *
 * 문자열 하나로 두지 않는다. 메인 세션은 이름이 아니라 **인자가 없는 것**으로 가리킨다
 * (`kyu session-command` 는 인자 없는 실행을 메인 세션으로 읽는다). 그 차이를 문자열 비교로
 * 다루면 부르는 쪽마다 다시 틀린다 — 라벨은 화면에 쓰는 이름이지 명령의 인자가 아니다.
 */
@Serializable
sealed interface SessionTarget {

    /** 화면에 그대로 쓰는 이름. kyu 가 사람에게 쓰는 이름과 같다. */
    val label: String

    @Serializable
    @SerialName("repo")
    data class Repo(val repoName: String) : PersonOpenedSessionTarget {
        override val label: String get() = repoName
    }

    /**
     * 워크디렉토리 최상위의 조율용 세션.
     *
     * main 은 kyu 가 예약한 이름이다(list.go 의 mainRowLabel).
     */
    @Serializable
    @SerialName("main")
    data object Main : PersonOpenedSessionTarget {
        override val label: String = "main"
    }

    /**
     * 메인이 `spawn_session` 으로 띄운 하위 세션(session-tree-design.md 6.11).
     *
     * **사람이 여는 세션이 아니다.** 패널의 줄을 눌러 들어가는 길이 없고, 엔진에게 묻는 물음도 다르다
     * (`--child`). 그래서 [PersonOpenedSessionTarget] 에 들지 않는다 — 들면 "레포 이름 자리에 무엇을
     * 넣는가" 를 묻는 자리마다 답할 수 없는 갈래가 하나 생긴다.
     *
     * @param childId 엔진이 지은 이름(`child-1` …). 엔진이 기록을 보고 상위 하나 안에서 다시 쓰지 않게 짓는다(6.2).
     */
    @Serializable
    @SerialName("child")
    data class Child(val childId: String) : SessionTarget {
        override val label: String get() = childId
    }
}

/**
 * 사람이 패널에서 골라 여는 세션 — 메인과 레포.
 *
 * 엔진에게 "이 라벨의 세션은 무엇을 띄우는가" 를 묻고(SessionCommandSource), 기록된 대화를 이어가거나
 * 새로 시작할 수 있는 것이 이 둘이다. 하위 세션은 상위가 띄우고 언제나 새 대화라 이 물음 밖에 있다.
 */
@Serializable
sealed interface PersonOpenedSessionTarget : SessionTarget
