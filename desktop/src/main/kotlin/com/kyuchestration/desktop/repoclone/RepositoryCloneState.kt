package com.kyuchestration.desktop.repoclone

import java.nio.file.Path

/**
 * 레포를 골라 받아 오는 화면이 지금 보여줄 것.
 *
 * 네 걸음(프로필 · 계정 · 레포 · 클론)마다 "도는 중" 과 "실패" 를 따로 두지 않는다. 그렇게 두면
 * 갈래가 열둘이 되는데, 화면이 하는 일은 셋뿐이다 — 스피너를 돌리거나, 고르게 하거나, 실패
 * 이유를 보여주거나. 그래서 "무엇을 시켰는가"(CloneStep)와 "그 일이 어디까지 왔는가"(이 타입)를
 * 갈라 두었다.
 */
sealed interface RepositoryCloneState {

    /** 이 화면이 열려 있지 않다. */
    data object Closed : RepositoryCloneState

    /** 엔진에게 시킨 일이 돌고 있다. 무엇을 시켰는지가 기다리는 동안의 말이 된다. */
    data class StepRunning(val step: CloneStep) : RepositoryCloneState

    /**
     * 시킨 일이 성립하지 않았다.
     *
     * 되풀이할 수 있는 걸음만 여기 온다. 토큰 등록은 이 자리에 오지 못하는데, 되풀이하려면
     * 토큰을 들고 있어야 하기 때문이다 — 그쪽은 폼으로 돌아가 다시 붙여넣게 한다.
     */
    data class StepFailed(val step: CloneStep.Retryable, val failure: CloneStepFailure) : RepositoryCloneState

    /** 1 단계 — 어느 토큰으로 GitHub 에 붙을지 고른다. */
    data class ChoosingTokenProfile(val profiles: List<TokenProfile>) : RepositoryCloneState

    /**
     * 1 단계의 다른 길 — 새 토큰을 등록한다.
     *
     * @param profilesToReturnTo 돌아갈 목록. 등록된 것이 없어 곧바로 이 폼으로 왔으면 비어 있다.
     *   다시 물어보지 않고 들고 오는 이유는, 폼을 그만두는 것이 GitHub 왕복을 시킬 일은 아니기
     *   때문이다.
     * @param rejectionReason 지난 시도가 거절당한 이유. 처음 열렸으면 null 이다. 토큰은 여기
     *   담기지 않는다 — 담으면 화면이 들고 있는 동안 계속 남는다.
     */
    data class RegisteringTokenProfile(
        val profilesToReturnTo: List<TokenProfile>,
        val rejectionReason: String?,
    ) : RepositoryCloneState

    /**
     * 등록이 끝났다. 어느 계정의 토큰인지 확인받고 넘어간다.
     *
     * 확인 한 걸음을 두는 이유는 계정이다. 회사 토큰을 개인 프로필로 등록하는 것은 GitHub 이
     * 확인해준 login 을 사용자가 보는 그 자리에서만 막힌다 — 조용히 다음 화면으로 넘어가면
     * 엉뚱한 계정의 레포 목록을 보고 나서야 알아차린다.
     */
    data class TokenProfileRegistered(val profile: RegisteredTokenProfile) : RepositoryCloneState

    /** 2 단계 — 누구의 레포를 볼지 고른다. 고를 것이 하나뿐이면 이 화면은 뜨지 않는다. */
    data class ChoosingOwner(
        val profileName: String,
        val owners: List<RepositoryOwner>,
    ) : RepositoryCloneState

    /**
     * 3 단계 — 받아 올 레포를 고른다.
     *
     * @param alreadyClonedRepositoryNames 이 워크디렉토리에 이미 있는 레포 이름. 목록에서 지우지
     *   않고 고를 수만 없게 한다 — 지우면 사용자는 "그 레포가 GitHub 에 없다" 와 "이미 받아 뒀다"
     *   를 구분하지 못한다.
     * @param searchText 검색 칸에 적힌 것. 거르는 일은 화면이 아니라 여기서 한다 — 무엇을 걸러
     *   보여주는가는 시험할 수 있어야 하는 판단이다.
     */
    data class ChoosingRepositories(
        val profileName: String,
        val ownerLogin: String,
        val repositories: List<RemoteRepository>,
        val alreadyClonedRepositoryNames: Set<String>,
        val searchText: String,
        val selectedRepositoryFullNames: Set<String>,
    ) : RepositoryCloneState {

        /** 검색어에 걸린 것만. 검색어가 비어 있으면 전부다. */
        val matchedRepositories: List<RemoteRepository> = searchText.trim().let { keyword ->
            if (keyword.isEmpty()) {
                repositories
            } else {
                repositories.filter { it.name.contains(keyword, ignoreCase = true) }
            }
        }

        fun isAlreadyCloned(repository: RemoteRepository): Boolean =
            repository.name in alreadyClonedRepositoryNames
    }

    /**
     * 4 단계 — 시킨 클론이 끝났다.
     *
     * 화면을 닫는 것은 사용자의 몫이다. 결과를 보여주자마자 닫으면 무엇이 건너뛰어졌고 무엇이
     * 실패했는지 읽을 틈이 없다. 새 레포가 카드로 뜨는 것은 대시보드의 다음 갱신이 알아서 한다.
     */
    data class CloneFinished(val outcome: CloneOutcome) : RepositoryCloneState
}

/**
 * 엔진에게 시키는 한 걸음.
 *
 * 무엇을 시켰는지가 기다리는 동안의 말이 되고, 실패한 뒤에는 "다시 시도" 가 되풀이할 대상이 된다.
 */
sealed interface CloneStep {

    /**
     * 실패해도 같은 재료로 다시 시킬 수 있는 걸음.
     *
     * 토큰 등록만 이 갈래 밖에 있다. 되풀이하려면 토큰을 들고 있어야 하는데, 앱이 토큰을 쥐고
     * 있는 시간은 건네는 그 한 번으로 끝나야 한다 — 그 결정을 주석이 아니라 타입으로 적어 두면,
     * 뒷날 누가 "다시 시도" 에 등록을 얹으려 해도 컴파일이 되지 않는다.
     */
    sealed interface Retryable : CloneStep

    data object LoadTokenProfiles : Retryable

    /** @param profileName 이름만 들고 있다. 토큰은 이 값 어디에도 없다. */
    data class RegisterTokenProfile(val profileName: String) : CloneStep

    data class LoadOwners(val profileName: String) : Retryable

    data class LoadRepositories(val profileName: String, val ownerLogin: String) : Retryable

    data class CloneRepositories(
        val workDirPath: Path,
        val profileName: String,
        val repositoryFullNames: List<String>,
    ) : Retryable
}
