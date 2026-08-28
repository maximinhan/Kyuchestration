package com.kyuchestration.desktop.repoclone

import java.nio.file.Path

/**
 * 고른 레포를 워크디렉토리 안으로 받아 오는 자리.
 *
 * 앱이 git 을 부르지 않는다. 일회성 credential helper 로 토큰을 디스크에 남기지 않는 것,
 * 같은 이름이 이미 있으면 건너뛰는 것, 하나가 실패해도 나머지를 계속 시도하는 것 — 클론이
 * 실제로 하는 일은 전부 엔진 안에 있다(clone_machine.go). 앱이 그중 하나라도 흉내 내기
 * 시작하면 앱으로 받은 레포와 손으로 받은 레포가 다른 규칙 아래 놓인다.
 */
interface WorkDirRepositoryCloner {

    /**
     * [repositoryFullNames] 를 [workDirPath] 바로 아래에 받아 온다.
     *
     * 빈 목록으로 부르지 않는다 — 무엇을 받을지 정해지지 않은 클론은 이 앱에 없다. 고른 것이
     * 없으면 화면이 시작조차 시키지 않는다.
     *
     * @param repositoryFullNames owner/name 꼴. 카탈로그가 준 fullName 을 그대로 넘긴다.
     * @throws CloneStepFailure 클론을 시작하지도 못했을 때. 시작한 뒤의 실패는 예외가 아니라
     *   결과 안의 한 줄이다 — 세 개 중 하나가 실패한 것은 "클론이 실패했다" 가 아니다.
     */
    fun cloneInto(workDirPath: Path, profileName: String, repositoryFullNames: List<String>): CloneOutcome
}

/**
 * 클론 한 번의 결과 전부.
 *
 * @param mainSessionRestartNeeded 떠 있는 메인 세션이 방금 받아 온 레포를 보지 못하는지.
 *   세션이 실행할 명령은 세션을 만드는 순간 정해지고 그 뒤로 바뀌지 않는다 — 조용히 넘어가면
 *   사용자는 방금 받은 레포가 세션에서 왜 안 보이는지 알 수 없다.
 */
data class CloneOutcome(
    val results: List<RepositoryCloneResult>,
    val mainSessionRestartNeeded: Boolean,
)

/**
 * 레포 하나가 어떻게 끝났는지.
 *
 * @param repositoryFullName 시킬 때 넘긴 그대로. 앱이 자기가 보낸 줄과 이 결과를 맞추는 열쇠다.
 * @param message 왜 그렇게 끝났는지. 받아 온 레포에서는 빈 문자열이다.
 */
data class RepositoryCloneResult(
    val repositoryFullName: String,
    val status: CloneStatus,
    val message: String,
)

/**
 * 레포 하나의 끝. 화면에서 색과 기호가 갈리므로 낱말이 아니라 타입으로 좁힌다.
 *
 * 모르는 낱말에서 멈추지 않는 것은 RepoState 와 같은 이유다 — GUI 가 엔진보다 늦게 배포되는 일은
 * 흔하고, 그때 결과를 통째로 못 보여주는 것보다 낯선 낱말 하나를 그대로 보여주는 편이 낫다.
 */
sealed interface CloneStatus {

    /** kyu 문서에 실려 온 낱말. */
    val rawValue: String

    data object Cloned : CloneStatus {
        override val rawValue: String = "cloned"
    }

    /** 같은 이름의 디렉토리가 이미 있어 받지 않았다. 실패가 아니다. */
    data object Skipped : CloneStatus {
        override val rawValue: String = "skipped"
    }

    data object Failed : CloneStatus {
        override val rawValue: String = "failed"
    }

    data class Unrecognized(override val rawValue: String) : CloneStatus

    companion object {
        fun ofRawValue(rawValue: String): CloneStatus = when (rawValue) {
            Cloned.rawValue -> Cloned
            Skipped.rawValue -> Skipped
            Failed.rawValue -> Failed
            else -> Unrecognized(rawValue)
        }
    }
}
