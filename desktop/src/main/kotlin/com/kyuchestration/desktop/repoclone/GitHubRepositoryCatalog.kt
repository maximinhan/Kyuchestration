package com.kyuchestration.desktop.repoclone

import java.time.Instant

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

/**
 * 레포를 가진 GitHub 계정 하나.
 *
 * @param login `kyu repos list --owner` 와 `kyu clone --repo` 의 앞자리에 그대로 들어가는 값.
 */
data class RepositoryOwner(val login: String, val kind: OwnerKind)

/**
 * 계정의 종류. 화면에서 개인과 조직을 갈라 보여주는 데 쓴다.
 *
 * 참·거짓이 아니라 낱말로 둔다 — 계약이 그렇게 정한 이유와 같다(repos_json.go). 소유자의 종류가
 * 셋이 되는 날(GitHub 은 Enterprise 계정을 따로 센다) 참·거짓은 늘릴 수 없다.
 */
sealed interface OwnerKind {

    /** kyu 문서에 실려 온 코드. */
    val rawValue: String

    data object Personal : OwnerKind {
        override val rawValue: String = "user"
    }

    data object Organization : OwnerKind {
        override val rawValue: String = "org"
    }

    data class Unrecognized(override val rawValue: String) : OwnerKind

    companion object {
        fun ofRawValue(rawValue: String): OwnerKind = when (rawValue) {
            Personal.rawValue -> Personal
            Organization.rawValue -> Organization
            else -> Unrecognized(rawValue)
        }
    }
}

/**
 * GitHub 에 있는 레포 하나. 아직 이 머신에 없다.
 *
 * @param name 워크디렉토리에 만들어질 디렉토리 이름. 이미 그 이름이 있는지 대조하는 값이다.
 * @param fullName owner/name. 클론을 시킬 때 그대로 넘기는 값이라 앱이 두 값을 이어 붙이지 않는다.
 * @param lastUpdatedAt GitHub 이 이 레포를 마지막으로 갱신한 시각. 받지 못했으면 null 이다 —
 *   없는 것과 1970 년은 다른 사실이라 영 값으로 대신하지 않는다.
 */
data class RemoteRepository(
    val name: String,
    val fullName: String,
    val isPrivate: Boolean,
    val lastUpdatedAt: Instant?,
)
