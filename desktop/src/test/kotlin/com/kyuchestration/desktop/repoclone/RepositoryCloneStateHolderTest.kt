package com.kyuchestration.desktop.repoclone

import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RepositoryCloneStateHolderTest {

    @Test
    fun `열기 전에는 아무것도 보여주지 않는다`() = runTest {
        val holder = stateHolder()

        assertEquals(RepositoryCloneState.Closed, holder.state.value)
    }

    @Test
    fun `열면 먼저 등록된 토큰 프로필을 묻는다`() = runTest {
        val registry = FakeTokenProfileRegistry()
        val holder = stateHolder(registry = registry)

        holder.open(WORK_DIR_PATH, alreadyClonedRepositoryNames = emptySet())

        assertEquals(RepositoryCloneState.StepRunning(CloneStep.LoadTokenProfiles), holder.state.value)
        runCurrent()
        assertEquals(1, registry.listProfilesCallCount)
    }

    @Test
    fun `등록된 프로필이 있으면 고르게 한다`() = runTest {
        val holder = stateHolder(registry = FakeTokenProfileRegistry(profiles = TWO_PROFILES))

        holder.open(WORK_DIR_PATH, emptySet())
        runCurrent()

        assertEquals(RepositoryCloneState.ChoosingTokenProfile(TWO_PROFILES), holder.state.value)
    }

    @Test
    fun `등록된 프로필이 없으면 고를 것 없는 목록 대신 등록 폼으로 간다`() = runTest {
        val holder = stateHolder(registry = FakeTokenProfileRegistry(profiles = emptyList()))

        holder.open(WORK_DIR_PATH, emptySet())
        runCurrent()

        assertEquals(
            RepositoryCloneState.RegisteringTokenProfile(profilesToReturnTo = emptyList(), rejectionReason = null),
            holder.state.value,
        )
    }

    @Test
    fun `프로필을 고르면 그 토큰으로 볼 수 있는 계정을 묻는다`() = runTest {
        val catalog = FakeGitHubRepositoryCatalog(owners = TWO_OWNERS)
        val holder = stateHolder(registry = FakeTokenProfileRegistry(profiles = TWO_PROFILES), catalog = catalog)

        holder.open(WORK_DIR_PATH, emptySet())
        runCurrent()
        holder.chooseTokenProfile("개인")
        runCurrent()

        assertEquals(listOf("개인"), catalog.askedOwnerProfileNames)
        assertEquals(RepositoryCloneState.ChoosingOwner("개인", TWO_OWNERS), holder.state.value)
    }

    @Test
    fun `고를 계정이 하나뿐이면 묻지 않고 그 계정의 레포로 넘어간다`() = runTest {
        val catalog = FakeGitHubRepositoryCatalog(
            owners = listOf(RepositoryOwner("maximinhan", OwnerKind.Personal)),
            repositories = TWO_REPOSITORIES,
        )
        val holder = stateHolder(registry = FakeTokenProfileRegistry(profiles = TWO_PROFILES), catalog = catalog)

        holder.open(WORK_DIR_PATH, emptySet())
        runCurrent()
        holder.chooseTokenProfile("개인")
        runCurrent()

        // 한 줄짜리 목록을 누르게 하는 것은 걸음이 아니라 걸림돌이다.
        val choosing = assertIs<RepositoryCloneState.ChoosingRepositories>(holder.state.value)
        assertEquals("maximinhan", choosing.ownerLogin)
        assertEquals(listOf("개인" to "maximinhan"), catalog.askedRepositoryOwners)
    }

    @Test
    fun `계정을 고르면 그 계정의 레포를 묻는다`() = runTest {
        val catalog = FakeGitHubRepositoryCatalog(owners = TWO_OWNERS, repositories = TWO_REPOSITORIES)
        val holder = stateHolder(registry = FakeTokenProfileRegistry(profiles = TWO_PROFILES), catalog = catalog)

        holder.open(WORK_DIR_PATH, emptySet())
        runCurrent()
        holder.chooseTokenProfile("개인")
        runCurrent()
        holder.chooseOwner("acme")
        runCurrent()

        val choosing = assertIs<RepositoryCloneState.ChoosingRepositories>(holder.state.value)
        assertEquals("acme", choosing.ownerLogin)
        assertEquals(TWO_REPOSITORIES, choosing.repositories)
        assertEquals(emptySet(), choosing.selectedRepositoryFullNames)
    }

    @Test
    fun `등록은 이름과 토큰을 그대로 넘기고 어느 계정인지 확인받는다`() = runTest {
        val registry = FakeTokenProfileRegistry(profiles = emptyList())
        val holder = stateHolder(registry = registry)

        holder.open(WORK_DIR_PATH, emptySet())
        runCurrent()
        holder.registerTokenProfile("  개인  ", "  ghp_예시토큰  ")
        assertEquals(
            RepositoryCloneState.StepRunning(CloneStep.RegisterTokenProfile("개인")),
            holder.state.value,
        )
        runCurrent()

        assertEquals(listOf("개인" to "ghp_예시토큰"), registry.registeredProfiles)
        assertEquals(
            RepositoryCloneState.TokenProfileRegistered(RegisteredTokenProfile("개인", "maximinhan")),
            holder.state.value,
        )
    }

    @Test
    fun `등록이 거절당하면 다시 시도가 아니라 폼으로 돌아간다`() = runTest {
        val registry = FakeTokenProfileRegistry(profiles = emptyList()).apply {
            failRegistrationWith(CloneStepFailure.KyuExitedWithFailure(1, "아무것도 저장하지 않았습니다"))
        }
        val holder = stateHolder(registry = registry)

        holder.open(WORK_DIR_PATH, emptySet())
        runCurrent()
        holder.registerTokenProfile("개인", "ghp_틀린토큰")
        runCurrent()

        // 다시 시도로 되돌리려면 토큰을 들고 있어야 한다. 앱이 토큰을 쥐고 있는 시간은 건네는
        // 그 한 번으로 끝나므로, 되풀이는 사람이 다시 붙여넣는 것으로만 일어난다.
        val form = assertIs<RepositoryCloneState.RegisteringTokenProfile>(holder.state.value)
        assertEquals("아무것도 저장하지 않았습니다", form.rejectionReason)
    }

    @Test
    fun `이름이나 토큰이 비어 있으면 엔진을 부르지 않는다`() = runTest {
        val registry = FakeTokenProfileRegistry(profiles = emptyList())
        val holder = stateHolder(registry = registry)

        holder.open(WORK_DIR_PATH, emptySet())
        runCurrent()
        holder.registerTokenProfile("   ", "ghp_예시토큰")
        runCurrent()
        holder.registerTokenProfile("개인", "   ")
        runCurrent()

        assertEquals(emptyList(), registry.registeredProfiles)
        assertIs<RepositoryCloneState.RegisteringTokenProfile>(holder.state.value)
    }

    @Test
    fun `등록을 확인하고 계속하면 그 프로필로 계정을 묻는다`() = runTest {
        val catalog = FakeGitHubRepositoryCatalog(owners = TWO_OWNERS)
        val holder = stateHolder(registry = FakeTokenProfileRegistry(profiles = emptyList()), catalog = catalog)

        holder.open(WORK_DIR_PATH, emptySet())
        runCurrent()
        holder.registerTokenProfile("개인", "ghp_예시토큰")
        runCurrent()
        holder.continueWithRegisteredTokenProfile()
        runCurrent()

        assertEquals(listOf("개인"), catalog.askedOwnerProfileNames)
        assertIs<RepositoryCloneState.ChoosingOwner>(holder.state.value)
    }

    @Test
    fun `등록을 그만두면 돌아갈 목록으로 돌아간다`() = runTest {
        val holder = stateHolder(registry = FakeTokenProfileRegistry(profiles = TWO_PROFILES))

        holder.open(WORK_DIR_PATH, emptySet())
        runCurrent()
        holder.startRegisteringTokenProfile()
        holder.stopRegisteringTokenProfile()

        // 그만두는 것은 GitHub 왕복을 시킬 일이 아니다 — 목록은 열 때 이미 받아 두었다.
        assertEquals(RepositoryCloneState.ChoosingTokenProfile(TWO_PROFILES), holder.state.value)
    }

    @Test
    fun `검색어에 걸린 레포만 보여주되 목록 자체는 그대로 들고 있는다`() = runTest {
        val holder = holderAtRepositoryChoice()

        holder.changeRepositorySearchText("PROJ-B")

        val choosing = assertIs<RepositoryCloneState.ChoosingRepositories>(holder.state.value)
        assertEquals(listOf("proj-b"), choosing.matchedRepositories.map { it.name })
        assertEquals(TWO_REPOSITORIES, choosing.repositories)
    }

    @Test
    fun `검색어를 바꿔도 고른 것은 그대로 남는다`() = runTest {
        val holder = holderAtRepositoryChoice()

        holder.toggleRepositorySelection("maximinhan/proj-a")
        holder.changeRepositorySearchText("proj-b")

        // 두 낱말로 나눠 고른 사용자가 마지막 낱말에 걸린 것만 받게 되면 안 된다.
        val choosing = assertIs<RepositoryCloneState.ChoosingRepositories>(holder.state.value)
        assertEquals(setOf("maximinhan/proj-a"), choosing.selectedRepositoryFullNames)
    }

    @Test
    fun `고르고 다시 누르면 선택이 풀린다`() = runTest {
        val holder = holderAtRepositoryChoice()

        holder.toggleRepositorySelection("maximinhan/proj-a")
        holder.toggleRepositorySelection("maximinhan/proj-b")
        holder.toggleRepositorySelection("maximinhan/proj-a")

        val choosing = assertIs<RepositoryCloneState.ChoosingRepositories>(holder.state.value)
        assertEquals(setOf("maximinhan/proj-b"), choosing.selectedRepositoryFullNames)
    }

    @Test
    fun `이미 워크디렉토리에 있는 레포는 목록에 남되 고를 수 없다`() = runTest {
        val holder = holderAtRepositoryChoice(alreadyClonedRepositoryNames = setOf("proj-a"))

        holder.toggleRepositorySelection("maximinhan/proj-a")

        val choosing = assertIs<RepositoryCloneState.ChoosingRepositories>(holder.state.value)
        // 목록에서 지우면 "GitHub 에 없다" 와 "이미 받아 뒀다" 를 구분할 수 없다.
        assertEquals(listOf("proj-a", "proj-b"), choosing.matchedRepositories.map { it.name })
        assertTrue(choosing.isAlreadyCloned(TWO_REPOSITORIES.first()))
        assertEquals(emptySet(), choosing.selectedRepositoryFullNames)
    }

    @Test
    fun `고른 것이 없으면 클론을 시작하지 않는다`() = runTest {
        val cloner = FakeWorkDirRepositoryCloner()
        val holder = holderAtRepositoryChoice(cloner = cloner)

        holder.cloneSelectedRepositories()
        runCurrent()

        assertEquals(emptyList(), cloner.cloneRequests)
        assertIs<RepositoryCloneState.ChoosingRepositories>(holder.state.value)
    }

    @Test
    fun `고른 레포를 열어 둔 워크디렉토리로 받아 오고 결과를 보여준다`() = runTest {
        val cloner = FakeWorkDirRepositoryCloner()
        val holder = holderAtRepositoryChoice(cloner = cloner)

        holder.toggleRepositorySelection("maximinhan/proj-a")
        holder.toggleRepositorySelection("maximinhan/proj-b")
        holder.cloneSelectedRepositories()
        runCurrent()

        assertEquals(
            listOf(Triple(WORK_DIR_PATH, "개인", listOf("maximinhan/proj-a", "maximinhan/proj-b"))),
            cloner.cloneRequests,
        )
        val finished = assertIs<RepositoryCloneState.CloneFinished>(holder.state.value)
        assertTrue(finished.outcome.mainSessionRestartNeeded)
    }

    @Test
    fun `걸음이 실패하면 어느 걸음이 왜 실패했는지를 들고 있는다`() = runTest {
        val catalog = FakeGitHubRepositoryCatalog(owners = TWO_OWNERS).apply {
            failOwnersWith(CloneStepFailure.KyuExitedWithFailure(1, "GitHub 이 토큰을 거절했습니다"))
        }
        val holder = stateHolder(registry = FakeTokenProfileRegistry(profiles = TWO_PROFILES), catalog = catalog)

        holder.open(WORK_DIR_PATH, emptySet())
        runCurrent()
        holder.chooseTokenProfile("개인")
        runCurrent()

        val failed = assertIs<RepositoryCloneState.StepFailed>(holder.state.value)
        assertEquals(CloneStep.LoadOwners("개인"), failed.step)
        assertTrue("거절했습니다" in failed.failure.guidance)
    }

    @Test
    fun `다시 시도는 실패한 그 걸음만 되풀이한다`() = runTest {
        val registry = FakeTokenProfileRegistry(profiles = TWO_PROFILES)
        val catalog = FakeGitHubRepositoryCatalog(owners = TWO_OWNERS).apply {
            failOwnersWith(CloneStepFailure.KyuExitedWithFailure(1, "일시적인 실패"))
        }
        val holder = stateHolder(registry = registry, catalog = catalog)

        holder.open(WORK_DIR_PATH, emptySet())
        runCurrent()
        holder.chooseTokenProfile("개인")
        runCurrent()
        catalog.stopFailing()
        holder.retryFailedStep()
        runCurrent()

        // 프로필을 다시 묻지 않는다. 실패한 것은 계정을 묻는 걸음 하나이고, 그 재료는 그대로 있다.
        assertEquals(1, registry.listProfilesCallCount)
        assertEquals(listOf("개인", "개인"), catalog.askedOwnerProfileNames)
        assertIs<RepositoryCloneState.ChoosingOwner>(holder.state.value)
    }

    @Test
    fun `닫으면 고르던 것이 남지 않는다`() = runTest {
        val holder = holderAtRepositoryChoice()

        holder.toggleRepositorySelection("maximinhan/proj-a")
        holder.close()

        assertEquals(RepositoryCloneState.Closed, holder.state.value)
    }

    private fun TestScope.holderAtRepositoryChoice(
        alreadyClonedRepositoryNames: Set<String> = emptySet(),
        cloner: FakeWorkDirRepositoryCloner = FakeWorkDirRepositoryCloner(),
    ): RepositoryCloneStateHolder {
        val holder = stateHolder(
            registry = FakeTokenProfileRegistry(profiles = TWO_PROFILES),
            catalog = FakeGitHubRepositoryCatalog(
                owners = listOf(RepositoryOwner("maximinhan", OwnerKind.Personal)),
                repositories = TWO_REPOSITORIES,
            ),
            cloner = cloner,
        )
        holder.open(WORK_DIR_PATH, alreadyClonedRepositoryNames)
        runCurrent()
        holder.chooseTokenProfile("개인")
        runCurrent()
        return holder
    }

    private fun TestScope.stateHolder(
        registry: FakeTokenProfileRegistry = FakeTokenProfileRegistry(),
        catalog: FakeGitHubRepositoryCatalog = FakeGitHubRepositoryCatalog(),
        cloner: FakeWorkDirRepositoryCloner = FakeWorkDirRepositoryCloner(),
    ) = RepositoryCloneStateHolder(
        tokenProfileRegistry = registry,
        gitHubRepositoryCatalog = catalog,
        workDirRepositoryCloner = cloner,
        coroutineScope = backgroundScope,
        // 앱에서는 IO 디스패처로 나간다. 여기서는 가상 시간을 쓰는 디스패처로 바꿔 진행 중인
        // 한순간(스피너가 도는 자리)을 붙잡아 볼 수 있게 한다.
        cloneStepDispatcher = StandardTestDispatcher(testScheduler),
    )

    private class FakeTokenProfileRegistry(
        private val profiles: List<TokenProfile> = emptyList(),
    ) : TokenProfileRegistry {

        var listProfilesCallCount = 0
            private set

        val registeredProfiles = mutableListOf<Pair<String, String>>()

        private var registrationFailure: CloneStepFailure? = null

        fun failRegistrationWith(failure: CloneStepFailure) {
            registrationFailure = failure
        }

        override fun listProfiles(): List<TokenProfile> {
            listProfilesCallCount++
            return profiles
        }

        override fun registerProfile(profileName: String, token: String): RegisteredTokenProfile {
            registrationFailure?.let { throw it }
            registeredProfiles.add(profileName to token)
            return RegisteredTokenProfile(profileName, "maximinhan")
        }
    }

    private class FakeGitHubRepositoryCatalog(
        private val owners: List<RepositoryOwner> = emptyList(),
        private val repositories: List<RemoteRepository> = emptyList(),
    ) : GitHubRepositoryCatalog {

        val askedOwnerProfileNames = mutableListOf<String>()
        val askedRepositoryOwners = mutableListOf<Pair<String, String>>()

        private var ownersFailure: CloneStepFailure? = null

        fun failOwnersWith(failure: CloneStepFailure) {
            ownersFailure = failure
        }

        fun stopFailing() {
            ownersFailure = null
        }

        override fun listOwners(profileName: String): List<RepositoryOwner> {
            askedOwnerProfileNames.add(profileName)
            ownersFailure?.let { throw it }
            return owners
        }

        override fun listRepositories(profileName: String, ownerLogin: String): List<RemoteRepository> {
            askedRepositoryOwners.add(profileName to ownerLogin)
            return repositories
        }
    }

    private class FakeWorkDirRepositoryCloner : WorkDirRepositoryCloner {

        val cloneRequests = mutableListOf<Triple<Path, String, List<String>>>()

        override fun cloneInto(
            workDirPath: Path,
            profileName: String,
            repositoryFullNames: List<String>,
        ): CloneOutcome {
            cloneRequests.add(Triple(workDirPath, profileName, repositoryFullNames))
            return CloneOutcome(
                results = repositoryFullNames.map { RepositoryCloneResult(it, CloneStatus.Cloned, "") },
                mainSessionRestartNeeded = true,
            )
        }
    }

    private companion object {
        val WORK_DIR_PATH: Path = Path.of("/home/me/work/WorkDir-featureX")

        val TWO_PROFILES = listOf(
            TokenProfile("개인", TokenStorage.Keychain),
            TokenProfile("회사", TokenStorage.ConfigFile),
        )

        val TWO_OWNERS = listOf(
            RepositoryOwner("maximinhan", OwnerKind.Personal),
            RepositoryOwner("acme", OwnerKind.Organization),
        )

        val TWO_REPOSITORIES = listOf(
            RemoteRepository(
                name = "proj-a",
                fullName = "maximinhan/proj-a",
                isPrivate = true,
                lastUpdatedAt = Instant.parse("2026-08-27T09:30:00Z"),
            ),
            RemoteRepository(
                name = "proj-b",
                fullName = "maximinhan/proj-b",
                isPrivate = false,
                lastUpdatedAt = null,
            ),
        )
    }
}
