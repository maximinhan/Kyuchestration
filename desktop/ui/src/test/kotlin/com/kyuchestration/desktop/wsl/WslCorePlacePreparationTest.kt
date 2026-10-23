package com.kyuchestration.desktop.wsl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

/**
 * 윈도우 화면이 코어를 띄울 배포판에 닿기까지(core-ui-split-design.md 6.17).
 */
class WslCorePlacePreparationTest {

    private val ubuntuAndDebian = WslDistributions(names = listOf("Ubuntu-24.04", "Debian"), defaultName = "Ubuntu-24.04")

    private val remembered = mutableListOf<String>()

    private val checkedDistributions = mutableListOf<String>()

    private fun preparation(
        listing: WslDistributionListing = WslDistributionListing.Listed(ubuntuAndDebian),
        rememberedDistribution: String? = null,
        coreCheck: (String) -> WslCoreCheck = { WslCoreCheck.Installed },
    ) = WslCorePlacePreparation(
        listDistributions = { listing },
        checkCore = { distributionName -> checkedDistributions += distributionName; coreCheck(distributionName) },
        rememberedDistribution = { rememberedDistribution },
        rememberDistribution = { remembered += it },
    )

    @Test
    fun `지난번에 고른 배포판에 코어가 있으면 묻지 않고 그 배포판으로 간다`() = runTest {
        val preparation = preparation(rememberedDistribution = "Debian")

        preparation.lookAround()

        assertEquals(WslCorePlacePreparationState.Ready("Debian"), preparation.state.value)
        assertEquals(listOf("Debian"), checkedDistributions)
    }

    /**
     * 고른 적이 없으면 기본 배포판이라도 묻는다. 배포판마다 코어가 있는지 미리 보지 않는다 — `wsl.exe -d` 는 멈춘 배포판을 띄운다.
     * 사람이 고르지 않은 배포판을 띄우는 것은 그 사람의 머신에서 VM 하나를 깨우는 일이다.
     */
    @Test
    fun `고른 적이 없으면 기본 배포판을 앞세워 고르게 하고 아무 배포판도 띄워 보지 않는다`() = runTest {
        val preparation = preparation()

        preparation.lookAround()

        assertEquals(
            WslCorePlacePreparationState.Choosing(ubuntuAndDebian, preselectedName = "Ubuntu-24.04", problem = null),
            preparation.state.value,
        )
        assertEquals(emptyList(), checkedDistributions)
    }

    @Test
    fun `지난번에 고른 배포판이 목록에 없으면 다시 고르게 한다`() = runTest {
        val preparation = preparation(rememberedDistribution = "Ubuntu-20.04")

        preparation.lookAround()

        assertEquals(
            WslCorePlacePreparationState.Choosing(ubuntuAndDebian, preselectedName = "Ubuntu-24.04", problem = null),
            preparation.state.value,
        )
    }

    @Test
    fun `지난번에 고른 배포판에 코어가 없으면 그 배포판을 앞세워 이유와 함께 고르게 한다`() = runTest {
        val preparation = preparation(rememberedDistribution = "Debian", coreCheck = { WslCoreCheck.NotInstalled })

        preparation.lookAround()

        assertEquals(
            WslCorePlacePreparationState.Choosing(
                ubuntuAndDebian,
                preselectedName = "Debian",
                problem = ChosenDistributionProblem.CoreNotInstalled("Debian"),
            ),
            preparation.state.value,
        )
    }

    @Test
    fun `고른 배포판에 코어가 있으면 그것을 기억하고 그 배포판으로 간다`() = runTest {
        val preparation = preparation()
        preparation.lookAround()

        preparation.choose("Debian")

        assertEquals(WslCorePlacePreparationState.Ready("Debian"), preparation.state.value)
        assertEquals(listOf("Debian"), remembered)
    }

    /** 기억하는 것은 코어에 닿을 수 있는 배포판뿐이다. 코어가 없는 배포판을 기억하면 다음에 뜰 때 같은 자리에서 또 멈춘다. */
    @Test
    fun `고른 배포판을 띄우지 못하면 wsl 의 말을 보이고 기억하지 않는다`() = runTest {
        val preparation = preparation(coreCheck = { WslCoreCheck.DistributionFailed("제공된 이름의 배포가 없습니다.") })
        preparation.lookAround()

        preparation.choose("Debian")

        assertEquals(
            WslCorePlacePreparationState.Choosing(
                ubuntuAndDebian,
                preselectedName = "Debian",
                problem = ChosenDistributionProblem.DistributionFailed("Debian", "제공된 이름의 배포가 없습니다."),
            ),
            preparation.state.value,
        )
        assertEquals(emptyList(), remembered)
    }

    @Test
    fun `배포판이 하나도 없으면 설치 안내로 간다`() = runTest {
        val preparation = preparation(listing = WslDistributionListing.Listed(WslDistributions(emptyList(), defaultName = null)))

        preparation.lookAround()

        assertEquals(WslCorePlacePreparationState.NoDistributions, preparation.state.value)
    }

    @Test
    fun `목록을 받지 못하면 wsl 의 말을 그대로 보인다`() = runTest {
        val preparation = preparation(listing = WslDistributionListing.Failed("Linux용 Windows 하위 시스템에 설치된 배포가 없습니다."))

        preparation.lookAround()

        assertEquals(
            WslCorePlacePreparationState.ListingFailed("Linux용 Windows 하위 시스템에 설치된 배포가 없습니다."),
            preparation.state.value,
        )
    }

    @Test
    fun `기본 배포판이 걸러졌으면 목록의 첫 배포판을 앞세운다`() = runTest {
        val withoutDefault = WslDistributions(names = listOf("Debian", "Ubuntu-22.04"), defaultName = null)
        val preparation = preparation(listing = WslDistributionListing.Listed(withoutDefault))

        preparation.lookAround()

        assertEquals(
            WslCorePlacePreparationState.Choosing(withoutDefault, preselectedName = "Debian", problem = null),
            preparation.state.value,
        )
    }
}
