package com.kyuchestration.desktop.wsl

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 윈도우 화면이 코어를 띄울 배포판에 닿기까지의 자리(core-ui-split-design.md 6.17). */
sealed interface WslCorePlacePreparationState {

    data object LookingAround : WslCorePlacePreparationState

    /** `wsl.exe -l -v` 가 실패했다. WSL 이 켜져 있지 않거나 배포판이 하나도 없는 판에서 이렇게 온다. */
    data class ListingFailed(val wslMessage: String) : WslCorePlacePreparationState

    data object NoDistributions : WslCorePlacePreparationState

    /**
     * 사람이 배포판을 고른다.
     *
     * @param preselectedName 처음에 골라 둔 배포판 — 문제가 난 배포판, 아니면 기본 배포판, 아니면 첫 배포판.
     * @param problem 지난번에 고른(또는 방금 고른) 배포판으로 가지 못한 까닭.
     */
    data class Choosing(
        val distributions: WslDistributions,
        val preselectedName: String,
        val problem: ChosenDistributionProblem?,
    ) : WslCorePlacePreparationState

    data class CheckingCore(val distributionName: String) : WslCorePlacePreparationState

    /** 화면이 실은 묶음으로 이 배포판에 코어를 놓는 중이다. 사람이 할 일은 없다 — 다른 단추를 숨긴다. */
    data class InstallingCore(
        val distributionName: String,
        val step: WslCoreInstallationStep,
        val reportLines: List<String>,
    ) : WslCorePlacePreparationState

    /** 이 배포판의 코어를 띄운다. */
    data class Ready(val distributionName: String) : WslCorePlacePreparationState
}

sealed interface ChosenDistributionProblem {

    val distributionName: String

    data class CoreNotInstalled(override val distributionName: String) : ChosenDistributionProblem

    data class DistributionFailed(override val distributionName: String, val wslMessage: String) : ChosenDistributionProblem

    /** 묶음으로 깔다 한 걸음이 실패했다(종료 코드 · 화면 쪽 까닭). 손으로 까는 길과 다시 설치를 보인다. */
    data class CoreInstallFailed(override val distributionName: String, val failure: WslCoreInstallationOutcome.Failed) :
        ChosenDistributionProblem

    /** 리눅스 홈 경로에 빈칸이 있어 화면이 깔지 않았다(WslCoreInstallationOutcome.LinuxHomeUnusable). */
    data class CoreInstallRefusedForLinuxHome(override val distributionName: String, val linuxHome: String) : ChosenDistributionProblem
}

/**
 * 화면에 실린 WSL 코어 묶음의 판과 그것으로 까는 길(core-ui-split-design.md 6.17 의 4b 정정). 소스에서 띄운 화면에는 없다.
 *
 * @param versionLabel 화면 자신의 릴리스 판 — 묶음은 같은 빌드에서 나온다.
 */
class BundledWslCore(
    val versionLabel: String,
    val install: suspend (distributionName: String, onProgress: (WslCoreInstallationProgress) -> Unit) -> WslCoreInstallationOutcome,
)

/**
 * 윈도우 화면이 코어를 띄울 배포판을 정한다(core-ui-split-design.md 6.17).
 *
 * 지난번에 고른 배포판이 목록에 있고 거기 코어가 있으면 묻지 않는다. 그 밖에는 사람이 고른다. **배포판마다 코어가 있는지 미리 보지
 * 않는다** — `wsl.exe -d` 는 멈춘 배포판을 띄운다. 사람이 고르지 않은 배포판을 보려고 VM 을 깨우지 않는다. 고른 배포판만 본다.
 *
 * 기억하는 것은 코어에 닿을 수 있는 배포판뿐이다. 코어가 없는 배포판을 기억하면 다음에 뜰 때 같은 자리에서 또 멈춘다.
 *
 * 화면에 묶음이 실려 있으면([bundledCore]) 코어가 없거나 · 판을 모르거나 · 판이 다를 때 그 묶음으로 깐다(6.17 의 4b 정정). 묶음이 있으면
 * 판이 같은 코어만 띄운다. 묶음이 없으면(소스 실행) 코어가 없을 때 설치 명령을 안내하고, 판은 붙은 뒤 `welcome` 이 견준다(6.14).
 */
class WslCorePlacePreparation(
    private val listDistributions: suspend () -> WslDistributionListing,
    private val checkCore: suspend (distributionName: String) -> WslCoreCheck,
    private val readInstalledCoreVersion: suspend (distributionName: String) -> InstalledWslCoreVersion,
    private val bundledCore: BundledWslCore?,
    private val rememberedDistribution: () -> String?,
    private val rememberDistribution: (distributionName: String) -> Unit,
) {

    private val mutableState = MutableStateFlow<WslCorePlacePreparationState>(WslCorePlacePreparationState.LookingAround)

    val state: StateFlow<WslCorePlacePreparationState> = mutableState.asStateFlow()

    /** 처음부터 본다 — 화면이 뜰 때, 그리고 사람이 배포판을 설치한 뒤 "다시 보기" 를 눌렀을 때. */
    suspend fun lookAround() {
        mutableState.value = WslCorePlacePreparationState.LookingAround
        val distributions = when (val listing = listDistributions()) {
            is WslDistributionListing.Failed -> {
                mutableState.value = WslCorePlacePreparationState.ListingFailed(listing.wslMessage)
                return
            }
            is WslDistributionListing.Listed -> listing.distributions
        }
        if (distributions.names.isEmpty()) {
            mutableState.value = WslCorePlacePreparationState.NoDistributions
            return
        }
        val remembered = rememberedDistribution()?.takeIf { it in distributions.names }
        if (remembered == null) {
            mutableState.value = WslCorePlacePreparationState.Choosing(
                distributions = distributions,
                preselectedName = distributions.defaultName ?: distributions.names.first(),
                problem = null,
            )
            return
        }
        goIfCoreIsThere(distributions, remembered)
    }

    /** 사람이 고른 배포판으로 간다. 코어가 없으면 그 까닭과 함께 다시 고르게 한다. */
    suspend fun choose(distributionName: String) {
        val distributions = (state.value as? WslCorePlacePreparationState.Choosing)?.distributions ?: return
        if (goIfCoreIsThere(distributions, distributionName)) {
            rememberDistribution(distributionName)
        }
    }

    private suspend fun goIfCoreIsThere(distributions: WslDistributions, distributionName: String): Boolean {
        mutableState.value = WslCorePlacePreparationState.CheckingCore(distributionName)
        val problem = when (val check = checkCore(distributionName)) {
            WslCoreCheck.Installed -> problemWithInstalledCore(distributionName)
            WslCoreCheck.NotInstalled -> when (val bundledCore = bundledCore) {
                null -> ChosenDistributionProblem.CoreNotInstalled(distributionName)
                else -> installBundledCore(bundledCore, distributionName)
            }
            is WslCoreCheck.DistributionFailed -> ChosenDistributionProblem.DistributionFailed(distributionName, check.wslMessage)
        }
        if (problem == null) {
            mutableState.value = WslCorePlacePreparationState.Ready(distributionName)
            return true
        }
        mutableState.value = WslCorePlacePreparationState.Choosing(distributions, preselectedName = distributionName, problem = problem)
        return false
    }

    /** 깔린 코어를 그대로 띄워도 되면 null. 묶음이 있으면 판이 같을 때만 그렇고, 아니면 묶음으로 갈아 끼운다. */
    private suspend fun problemWithInstalledCore(distributionName: String): ChosenDistributionProblem? {
        val bundledCore = bundledCore ?: return null
        return when (val installed = readInstalledCoreVersion(distributionName)) {
            is InstalledWslCoreVersion.Known -> if (installed.versionLabel == bundledCore.versionLabel) {
                null
            } else {
                installBundledCore(bundledCore, distributionName)
            }
            InstalledWslCoreVersion.Unknown -> installBundledCore(bundledCore, distributionName)
            is InstalledWslCoreVersion.DistributionFailed -> ChosenDistributionProblem.DistributionFailed(distributionName, installed.wslMessage)
        }
    }

    /** 깔았으면 null. */
    private suspend fun installBundledCore(bundledCore: BundledWslCore, distributionName: String): ChosenDistributionProblem? {
        mutableState.value = WslCorePlacePreparationState.InstallingCore(distributionName, WslCoreInstallationStep.FindingLinuxHome, emptyList())
        val outcome = bundledCore.install(distributionName) { progress ->
            mutableState.value = WslCorePlacePreparationState.InstallingCore(distributionName, progress.step, progress.reportLines)
        }
        return when (outcome) {
            WslCoreInstallationOutcome.Installed -> null
            is WslCoreInstallationOutcome.Failed ->
                ChosenDistributionProblem.CoreInstallFailed(distributionName, outcome)
            is WslCoreInstallationOutcome.LinuxHomeUnusable ->
                ChosenDistributionProblem.CoreInstallRefusedForLinuxHome(distributionName, outcome.linuxHome)
        }
    }
}
