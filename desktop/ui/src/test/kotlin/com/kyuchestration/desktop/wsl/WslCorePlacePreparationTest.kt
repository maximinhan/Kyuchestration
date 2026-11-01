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

    private val versionReadDistributions = mutableListOf<String>()

    private val installedDistributions = mutableListOf<String>()

    private fun preparation(
        listing: WslDistributionListing = WslDistributionListing.Listed(ubuntuAndDebian),
        rememberedDistribution: String? = null,
        coreCheck: (String) -> WslCoreCheck = { WslCoreCheck.Installed },
        installedVersion: InstalledWslCoreVersion = InstalledWslCoreVersion.Known("v0.16.0"),
        bundledCore: BundledWslCore? = null,
    ) = WslCorePlacePreparation(
        listDistributions = { listing },
        checkCore = { distributionName -> checkedDistributions += distributionName; coreCheck(distributionName) },
        readInstalledCoreVersion = { distributionName -> versionReadDistributions += distributionName; installedVersion },
        bundledCore = bundledCore,
        rememberedDistribution = { rememberedDistribution },
        rememberDistribution = { remembered += it },
    )

    private fun bundle(
        versionLabel: String = "v0.16.0",
        install: (distributionName: String, onProgress: (WslCoreInstallationProgress) -> Unit) -> WslCoreInstallationOutcome = { _, _ ->
            WslCoreInstallationOutcome.Installed
        },
    ) = BundledWslCore(versionLabel) { distributionName, onProgress ->
        installedDistributions += distributionName
        install(distributionName, onProgress)
    }

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

    /** 소스에서 띄운 화면 — 깔린 코어의 판을 견줄 묶음이 없으니 읽지도 않는다. 판은 붙은 뒤 welcome 이 견준다(6.14). */
    @Test
    fun `묶음이 없으면 코어가 있을 때 판을 읽지 않고 그 배포판으로 간다`() = runTest {
        val preparation = preparation(rememberedDistribution = "Debian", installedVersion = InstalledWslCoreVersion.Unknown)

        preparation.lookAround()

        assertEquals(WslCorePlacePreparationState.Ready("Debian"), preparation.state.value)
        assertEquals(emptyList(), versionReadDistributions)
    }

    @Test
    fun `묶음이 있고 깔린 코어의 판이 같으면 깔지 않고 그 배포판으로 간다`() = runTest {
        val preparation = preparation(rememberedDistribution = "Debian", bundledCore = bundle(versionLabel = "v0.16.0"))

        preparation.lookAround()

        assertEquals(WslCorePlacePreparationState.Ready("Debian"), preparation.state.value)
        assertEquals(listOf("Debian"), versionReadDistributions)
        assertEquals(emptyList(), installedDistributions)
    }

    /** 판이 다른 코어는 규약이 같아도 다른 판의 기능으로 돈다 — 같은 빌드의 묶음이 손에 있으니 갈아 끼운다(6.17 의 4a 정정). */
    @Test
    fun `묶음이 있고 깔린 코어의 판이 다르면 다시 깔고 그 배포판으로 간다`() = runTest {
        val preparation = preparation(
            rememberedDistribution = "Debian",
            installedVersion = InstalledWslCoreVersion.Known("v0.15.0"),
            bundledCore = bundle(versionLabel = "v0.16.0"),
        )

        preparation.lookAround()

        assertEquals(WslCorePlacePreparationState.Ready("Debian"), preparation.state.value)
        assertEquals(listOf("Debian"), installedDistributions)
    }

    /** 4a 이전에 손으로 깐 코어에는 VERSION 이 없다 — 다른 판으로 본다. */
    @Test
    fun `묶음이 있고 깔린 코어의 판을 모르면 다시 깐다`() = runTest {
        val preparation = preparation(
            rememberedDistribution = "Debian",
            installedVersion = InstalledWslCoreVersion.Unknown,
            bundledCore = bundle(),
        )

        preparation.lookAround()

        assertEquals(WslCorePlacePreparationState.Ready("Debian"), preparation.state.value)
        assertEquals(listOf("Debian"), installedDistributions)
    }

    @Test
    fun `묶음이 있고 코어가 없으면 고른 배포판에 깔고 기억한 뒤 그 배포판으로 간다`() = runTest {
        val preparation = preparation(coreCheck = { WslCoreCheck.NotInstalled }, bundledCore = bundle())
        preparation.lookAround()

        preparation.choose("Debian")

        assertEquals(WslCorePlacePreparationState.Ready("Debian"), preparation.state.value)
        assertEquals(listOf("Debian"), installedDistributions)
        assertEquals(emptyList(), versionReadDistributions)
        assertEquals(listOf("Debian"), remembered)
    }

    @Test
    fun `까는 동안 걸음과 설치 스크립트의 줄을 상태로 보인다`() = runTest {
        lateinit var preparation: WslCorePlacePreparation
        val statesDuringInstall = mutableListOf<WslCorePlacePreparationState>()
        preparation = preparation(
            coreCheck = { WslCoreCheck.NotInstalled },
            bundledCore = bundle { _, onProgress ->
                statesDuringInstall += preparation.state.value
                onProgress(WslCoreInstallationProgress(WslCoreInstallationStep.RunningInstallScript, listOf("대상: linux_amd64")))
                statesDuringInstall += preparation.state.value
                WslCoreInstallationOutcome.Installed
            },
        )
        preparation.lookAround()

        preparation.choose("Debian")

        assertEquals(
            listOf<WslCorePlacePreparationState>(
                WslCorePlacePreparationState.InstallingCore("Debian", WslCoreInstallationStep.FindingLinuxHome, emptyList()),
                WslCorePlacePreparationState.InstallingCore("Debian", WslCoreInstallationStep.RunningInstallScript, listOf("대상: linux_amd64")),
            ),
            statesDuringInstall,
        )
    }

    /** 실패한 배포판은 기억하지 않는다 — 다음에 뜰 때 같은 자리에서 또 멈춘다. 손으로 까는 길과 다시 시도가 함께 보인다. */
    @Test
    fun `깔다 실패하면 그 걸음과 종료 코드와 마지막 줄들을 보이고 기억하지 않는다`() = runTest {
        val preparation = preparation(
            coreCheck = { WslCoreCheck.NotInstalled },
            bundledCore = bundle { _, _ ->
                WslCoreInstallationOutcome.Failed(WslCoreInstallationStep.RunningInstallScript, exitCode = 7, lastOutputLines = listOf("오류: 코어가 뜨지 않았습니다"))
            },
        )
        preparation.lookAround()

        preparation.choose("Debian")

        assertEquals(
            WslCorePlacePreparationState.Choosing(
                ubuntuAndDebian,
                preselectedName = "Debian",
                problem = ChosenDistributionProblem.CoreInstallFailed(
                    "Debian",
                    WslCoreInstallationStep.RunningInstallScript,
                    exitCode = 7,
                    lastOutputLines = listOf("오류: 코어가 뜨지 않았습니다"),
                ),
            ),
            preparation.state.value,
        )
        assertEquals(emptyList(), remembered)
    }

    @Test
    fun `실패한 뒤 같은 배포판을 다시 고르면 다시 깐다`() = runTest {
        var attempts = 0
        val preparation = preparation(
            coreCheck = { WslCoreCheck.NotInstalled },
            bundledCore = bundle { _, _ ->
                if (++attempts == 1) {
                    WslCoreInstallationOutcome.Failed(WslCoreInstallationStep.ExtractingBundle, exitCode = 2, lastOutputLines = emptyList())
                } else {
                    WslCoreInstallationOutcome.Installed
                }
            },
        )
        preparation.lookAround()
        preparation.choose("Debian")

        preparation.choose("Debian")

        assertEquals(WslCorePlacePreparationState.Ready("Debian"), preparation.state.value)
        assertEquals(listOf("Debian", "Debian"), installedDistributions)
    }

    @Test
    fun `리눅스 홈에 빈칸이 있어 깔지 않았으면 그 홈을 이유로 보인다`() = runTest {
        val preparation = preparation(
            coreCheck = { WslCoreCheck.NotInstalled },
            bundledCore = bundle { _, _ -> WslCoreInstallationOutcome.LinuxHomeUnusable("/home/yawn han") },
        )
        preparation.lookAround()

        preparation.choose("Debian")

        assertEquals(
            ChosenDistributionProblem.CoreInstallRefusedForLinuxHome("Debian", "/home/yawn han"),
            (preparation.state.value as WslCorePlacePreparationState.Choosing).problem,
        )
    }

    @Test
    fun `판을 읽다 배포판을 띄우지 못하면 wsl 의 말을 보인다`() = runTest {
        val preparation = preparation(
            rememberedDistribution = "Debian",
            installedVersion = InstalledWslCoreVersion.DistributionFailed("Catastrophic failure"),
            bundledCore = bundle(),
        )

        preparation.lookAround()

        assertEquals(
            ChosenDistributionProblem.DistributionFailed("Debian", "Catastrophic failure"),
            (preparation.state.value as WslCorePlacePreparationState.Choosing).problem,
        )
        assertEquals(emptyList(), installedDistributions)
    }
}
