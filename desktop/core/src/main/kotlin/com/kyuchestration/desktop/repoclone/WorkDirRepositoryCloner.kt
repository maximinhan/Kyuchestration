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
