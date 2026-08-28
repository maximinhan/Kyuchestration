package com.kyuchestration.desktop.repoclone

import java.nio.file.Path
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 레포를 골라 받아 오는 걸음을 쥐고 있는 자리. Compose 를 모른다.
 *
 * 이 화면의 판단은 전부 시간과 순서에 걸려 있다 — 프로필이 없으면 등록으로, 계정이 하나뿐이면
 * 묻지 않고, 이미 받아 둔 레포는 고를 수 없고, 실패하면 그 걸음만 다시. 그 판단들을 Composable
 * 안에 두면 검사하려고 창을 띄워야 하고, 진짜 GitHub 왕복 없이는 한 줄도 확인할 수 없다.
 */
class RepositoryCloneStateHolder(
    private val tokenProfileRegistry: TokenProfileRegistry,
    private val gitHubRepositoryCatalog: GitHubRepositoryCatalog,
    private val workDirRepositoryCloner: WorkDirRepositoryCloner,
    private val coroutineScope: CoroutineScope,
    // kyu 를 프로세스로 띄우고 GitHub 왕복을 기다리는 일이라 화면을 그리는 스레드에서 하면 창이 멎는다.
    private val cloneStepDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val mutableState = MutableStateFlow<RepositoryCloneState>(RepositoryCloneState.Closed)

    val state: StateFlow<RepositoryCloneState> = mutableState.asStateFlow()

    private var runningStep: Job? = null

    /**
     * 레포를 받을 자리. 화면에 뜨지 않으므로 상태가 아니라 이 홀더가 여는 순간 받아 두는 재료다.
     */
    private var openedWorkDirPath: Path? = null

    /**
     * 열 때 본 워크디렉토리의 레포 이름.
     *
     * 대시보드가 3 초마다 보는 것과 달리 여는 순간의 한 장으로 굳힌다. 고르는 도중에 목록이
     * 바뀌면 방금 체크한 레포가 이유 없이 고를 수 없게 되고, 그 사이에 새로 생긴 레포는
     * kyu clone 이 "이미 있음" 으로 건너뛰므로 잘못 고른 대가도 없다.
     */
    private var alreadyClonedRepositoryNames: Set<String> = emptySet()

    /**
     * 화면을 열고 첫 걸음(등록된 토큰 프로필 묻기)을 시작한다.
     */
    fun open(workDirPath: Path, alreadyClonedRepositoryNames: Set<String>) {
        openedWorkDirPath = workDirPath
        this.alreadyClonedRepositoryNames = alreadyClonedRepositoryNames
        runCloneStep(CloneStep.LoadTokenProfiles)
    }

    /**
     * 화면을 닫는다.
     *
     * 돌고 있던 걸음은 놓아 버린다. 이미 시작된 kyu 프로세스가 멈추지는 않는데, 그래도 되는
     * 이유는 클론이 끝까지 가더라도 남는 것이 워크디렉토리 안의 레포뿐이기 때문이다 — 그것은
     * 대시보드의 다음 갱신에서 카드로 뜬다.
     */
    fun close() {
        runningStep?.cancel()
        runningStep = null
        openedWorkDirPath = null
        alreadyClonedRepositoryNames = emptySet()
        mutableState.value = RepositoryCloneState.Closed
    }

    fun chooseTokenProfile(profileName: String) {
        runCloneStep(CloneStep.LoadOwners(profileName))
    }

    /** 프로필 목록에서 "새 토큰 등록" 을 골랐다. */
    fun startRegisteringTokenProfile() {
        val profilesToReturnTo =
            (mutableState.value as? RepositoryCloneState.ChoosingTokenProfile)?.profiles.orEmpty()
        mutableState.value = RepositoryCloneState.RegisteringTokenProfile(profilesToReturnTo, rejectionReason = null)
    }

    /** 등록을 그만두고 프로필 목록으로 돌아간다. */
    fun stopRegisteringTokenProfile() {
        val form = mutableState.value as? RepositoryCloneState.RegisteringTokenProfile ?: return
        mutableState.value = RepositoryCloneState.ChoosingTokenProfile(form.profilesToReturnTo)
    }

    /**
     * 적어 넣은 이름과 토큰으로 프로필을 등록한다.
     *
     * 이 한 걸음만 다른 걸음들과 다르게 흐른다. 거절당해도 "다시 시도" 로 되돌리지 않고 폼으로
     * 보내는데, 되풀이하려면 토큰을 어딘가에 들고 있어야 하기 때문이다 — 앱이 토큰을 쥐고 있는
     * 시간은 이 호출 하나로 끝난다.
     */
    fun registerTokenProfile(profileName: String, token: String) {
        val form = mutableState.value as? RepositoryCloneState.RegisteringTokenProfile ?: return

        val trimmedProfileName = profileName.trim()
        if (trimmedProfileName.isEmpty()) {
            mutableState.value = form.copy(rejectionReason = "이 토큰에 붙일 이름을 적어 주세요.")
            return
        }
        // 빈 토큰은 kyu 도 거절하지만(auth_add.go) 여기서 먼저 막는다. 프로세스를 띄워 받아 올
        // 답이 정해져 있고, 그 왕복만큼 사용자는 이유 없이 기다린다.
        val trimmedToken = token.trim()
        if (trimmedToken.isEmpty()) {
            mutableState.value = form.copy(rejectionReason = "토큰을 붙여넣어 주세요.")
            return
        }

        runningStep?.cancel()
        mutableState.value = RepositoryCloneState.StepRunning(CloneStep.RegisterTokenProfile(trimmedProfileName))

        runningStep = coroutineScope.launch {
            val registered = try {
                withContext(cloneStepDispatcher) {
                    tokenProfileRegistry.registerProfile(trimmedProfileName, trimmedToken)
                }
            } catch (failure: CloneStepFailure) {
                // kyu 는 거절당한 토큰을 저장하지 않고, 저장하지 않았다는 사실까지 stderr 에 적는다.
                // 그 말을 그대로 폼에 올리면 사용자는 다시 붙여넣기만 하면 된다.
                mutableState.value = form.copy(rejectionReason = failure.guidance)
                return@launch
            }

            mutableState.value = RepositoryCloneState.TokenProfileRegistered(registered)
        }
    }

    /** 등록된 계정을 확인했다. 그 프로필로 다음 걸음을 밟는다. */
    fun continueWithRegisteredTokenProfile() {
        val registered = mutableState.value as? RepositoryCloneState.TokenProfileRegistered ?: return
        runCloneStep(CloneStep.LoadOwners(registered.profile.profileName))
    }

    fun chooseOwner(ownerLogin: String) {
        val choosing = mutableState.value as? RepositoryCloneState.ChoosingOwner ?: return
        runCloneStep(CloneStep.LoadRepositories(choosing.profileName, ownerLogin))
    }

    fun changeRepositorySearchText(searchText: String) {
        val choosing = mutableState.value as? RepositoryCloneState.ChoosingRepositories ?: return
        // 고른 것은 그대로 둔다. 검색어를 바꾸면 화면에서 사라지는 레포가 생기는데, 그때 선택까지
        // 거두면 두 낱말로 나눠 고른 사용자가 마지막 낱말에 걸린 것만 받게 된다.
        mutableState.value = choosing.copy(searchText = searchText)
    }

    /**
     * 레포 하나를 고르거나 고른 것을 무른다.
     *
     * 이미 워크디렉토리에 있는 레포는 골라지지 않는다. 화면에서도 막지만 여기서 한 번 더 막는
     * 이유는, 무엇을 보낼 수 있는가가 화면의 모양이 아니라 이 홀더의 판단이어야 하기 때문이다.
     */
    fun toggleRepositorySelection(repositoryFullName: String) {
        val choosing = mutableState.value as? RepositoryCloneState.ChoosingRepositories ?: return

        val repository = choosing.repositories.firstOrNull { it.fullName == repositoryFullName } ?: return
        if (choosing.isAlreadyCloned(repository)) {
            return
        }

        val selected = choosing.selectedRepositoryFullNames
        mutableState.value = choosing.copy(
            selectedRepositoryFullNames = if (repositoryFullName in selected) {
                selected - repositoryFullName
            } else {
                selected + repositoryFullName
            },
        )
    }

    fun cloneSelectedRepositories() {
        val choosing = mutableState.value as? RepositoryCloneState.ChoosingRepositories ?: return
        val workDirPath = openedWorkDirPath ?: return
        // 무엇을 받을지 정해지지 않은 클론은 이 앱에 없다. kyu 도 거절하지만, 그 거절을 화면에
        // 띄우는 것은 사용자가 하지도 않은 일에 대한 오류다.
        if (choosing.selectedRepositoryFullNames.isEmpty()) {
            return
        }

        runCloneStep(
            CloneStep.CloneRepositories(
                workDirPath = workDirPath,
                profileName = choosing.profileName,
                repositoryFullNames = choosing.selectedRepositoryFullNames.toList(),
            ),
        )
    }

    /** 실패한 걸음을 그 재료 그대로 다시 시킨다. */
    fun retryFailedStep() {
        val failed = mutableState.value as? RepositoryCloneState.StepFailed ?: return
        runCloneStep(failed.step)
    }

    /**
     * 걸음 하나를 시키고, 그 걸음이 넘겨주는 다음 걸음까지 이어서 시킨다.
     *
     * 이어 붙이는 고리가 여기 있는 이유는 자동 통과다 — 계정이 하나뿐일 때 레포 목록으로 곧장
     * 가야 하는데, 그 이음매를 걸음 안에서 코루틴을 새로 띄워 만들면 자기 자신을 취소하게 된다.
     */
    private fun runCloneStep(firstStep: CloneStep.Retryable) {
        runningStep?.cancel()

        // 누른 그 자리에서 진행 표시를 세운다. 코루틴 안에서만 세우면 표시가 한 박자 늦게 뜨고,
        // 그 사이에 사용자는 누른 것이 먹지 않았다고 여겨 한 번 더 누른다.
        mutableState.value = RepositoryCloneState.StepRunning(firstStep)

        runningStep = coroutineScope.launch {
            var step = firstStep
            while (true) {
                // 첫 걸음은 위에서 이미 세웠다. 여기서 다시 세우는 것은 이어지는 걸음들 때문이다 —
                // 같은 값을 넣는 것은 StateFlow 가 알아서 흘려보낸다.
                mutableState.value = RepositoryCloneState.StepRunning(step)

                val outcome = try {
                    withContext(cloneStepDispatcher) { performCloneStep(step) }
                } catch (failure: CloneStepFailure) {
                    mutableState.value = RepositoryCloneState.StepFailed(step, failure)
                    return@launch
                }

                when (outcome) {
                    is CloneStepOutcome.Settled -> {
                        mutableState.value = outcome.state
                        return@launch
                    }

                    is CloneStepOutcome.Continues -> step = outcome.nextStep
                }
            }
        }
    }

    private fun performCloneStep(step: CloneStep.Retryable): CloneStepOutcome = when (step) {
        is CloneStep.LoadTokenProfiles -> {
            val profiles = tokenProfileRegistry.listProfiles()
            CloneStepOutcome.Settled(
                // 등록된 것이 없으면 고를 것이 없는 목록을 보여주지 않고 곧바로 등록으로 간다
                // (kyu clone 의 대화형 흐름이 하는 것과 같은 선택이다).
                if (profiles.isEmpty()) {
                    RepositoryCloneState.RegisteringTokenProfile(profilesToReturnTo = emptyList(), rejectionReason = null)
                } else {
                    RepositoryCloneState.ChoosingTokenProfile(profiles)
                },
            )
        }

        is CloneStep.LoadOwners -> {
            val owners = gitHubRepositoryCatalog.listOwners(step.profileName)
            val onlyOwner = owners.singleOrNull()
            if (onlyOwner == null) {
                CloneStepOutcome.Settled(RepositoryCloneState.ChoosingOwner(step.profileName, owners))
            } else {
                // 고를 것이 하나뿐이면 묻지 않는다. 조직에 속하지 않은 계정에서는 이 화면이
                // 늘 한 줄짜리 목록인데, 그 한 줄을 누르게 하는 것은 걸음이 아니라 걸림돌이다.
                CloneStepOutcome.Continues(CloneStep.LoadRepositories(step.profileName, onlyOwner.login))
            }
        }

        is CloneStep.LoadRepositories -> CloneStepOutcome.Settled(
            RepositoryCloneState.ChoosingRepositories(
                profileName = step.profileName,
                ownerLogin = step.ownerLogin,
                repositories = gitHubRepositoryCatalog.listRepositories(step.profileName, step.ownerLogin),
                alreadyClonedRepositoryNames = alreadyClonedRepositoryNames,
                searchText = "",
                selectedRepositoryFullNames = emptySet(),
            ),
        )

        is CloneStep.CloneRepositories -> CloneStepOutcome.Settled(
            RepositoryCloneState.CloneFinished(
                workDirRepositoryCloner.cloneInto(step.workDirPath, step.profileName, step.repositoryFullNames),
            ),
        )
    }
}

/**
 * 걸음 하나가 끝나고 난 뒤.
 *
 * 화면이 멈춰 설 자리이거나, 사람에게 물을 것이 없어 곧바로 이어지는 다음 걸음이다.
 */
private sealed interface CloneStepOutcome {

    data class Settled(val state: RepositoryCloneState) : CloneStepOutcome

    data class Continues(val nextStep: CloneStep.Retryable) : CloneStepOutcome
}
