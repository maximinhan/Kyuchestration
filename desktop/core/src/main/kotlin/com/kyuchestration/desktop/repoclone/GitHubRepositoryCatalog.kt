package com.kyuchestration.desktop.repoclone


/**
 * 이 토큰으로 GitHub 에서 볼 수 있는 것을 묻는 자리. 아무것도 바꾸지 않는다.
 *
 * 받아 오는 포트(WorkDirRepositoryCloner)와 갈라 둔다. 이쪽은 사용자가 화면을 넘길 때마다
 * 일어나는 읽기이고 저쪽은 디스크에 쓰는 일이다 — 한 인터페이스에 묶으면 "몇 번 물어도 같은
 * 것" 과 "한 번만 시켜야 하는 것" 이 같은 이름 아래에 놓인다.
 */
interface GitHubRepositoryCatalog {

    /**
     * [profileName] 토큰으로 레포를 볼 수 있는 계정 전부. 개인 계정이 늘 첫 자리다.
     *
     * 조직을 볼 권한이 없는 토큰(fine-grained 403)이면 개인 계정만 실려 온다 — 실패가 아니다.
     * 그 사정을 앱이 갈라 볼 방법은 없고, 갈라 봐도 할 수 있는 일은 같다(개인 레포를 보여준다).
     *
     * @throws CloneStepFailure 계정을 물어보지 못했을 때.
     */
    fun listOwners(profileName: String): List<RepositoryOwner>

    /**
     * [ownerLogin] 계정의 레포 전부. github.com 의 repositories 탭과 같은 최근 갱신 순이다.
     *
     * @throws CloneStepFailure 레포를 물어보지 못했을 때.
     */
    fun listRepositories(profileName: String, ownerLogin: String): List<RemoteRepository>
}
