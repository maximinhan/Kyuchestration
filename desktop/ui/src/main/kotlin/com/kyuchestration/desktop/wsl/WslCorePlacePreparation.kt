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

    /** 이 배포판의 코어를 띄운다. */
    data class Ready(val distributionName: String) : WslCorePlacePreparationState
}

sealed interface ChosenDistributionProblem {

    val distributionName: String

    data class CoreNotInstalled(override val distributionName: String) : ChosenDistributionProblem

    data class DistributionFailed(override val distributionName: String, val wslMessage: String) : ChosenDistributionProblem
}

/**
 * 윈도우 화면이 코어를 띄울 배포판을 정한다(core-ui-split-design.md 6.17).
 *
 * 지난번에 고른 배포판이 목록에 있고 거기 코어가 있으면 묻지 않는다. 그 밖에는 사람이 고른다. **배포판마다 코어가 있는지 미리 보지
 * 않는다** — `wsl.exe -d` 는 멈춘 배포판을 띄운다. 사람이 고르지 않은 배포판을 보려고 VM 을 깨우지 않는다. 고른 배포판만 본다.
 *
 * 기억하는 것은 코어에 닿을 수 있는 배포판뿐이다. 코어가 없는 배포판을 기억하면 다음에 뜰 때 같은 자리에서 또 멈춘다.
 */
class WslCorePlacePreparation(
    private val listDistributions: suspend () -> WslDistributionListing,
    private val checkCore: suspend (distributionName: String) -> WslCoreCheck,
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
            WslCoreCheck.Installed -> {
                mutableState.value = WslCorePlacePreparationState.Ready(distributionName)
                return true
            }
            WslCoreCheck.NotInstalled -> ChosenDistributionProblem.CoreNotInstalled(distributionName)
            is WslCoreCheck.DistributionFailed -> ChosenDistributionProblem.DistributionFailed(distributionName, check.wslMessage)
        }
        mutableState.value = WslCorePlacePreparationState.Choosing(distributions, preselectedName = distributionName, problem = problem)
        return false
    }
}
