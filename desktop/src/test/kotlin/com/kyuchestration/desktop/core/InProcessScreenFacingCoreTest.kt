package com.kyuchestration.desktop.core

import com.kyuchestration.desktop.claudeauth.ClaudeAuthenticationState
import com.kyuchestration.desktop.claudeauth.ClaudeAuthenticationStateHolder
import com.kyuchestration.desktop.claudeauth.ClaudeBrowserLogin
import com.kyuchestration.desktop.claudeauth.ClaudeCliAuthentication
import com.kyuchestration.desktop.claudeauth.ClaudeTokenStore
import com.kyuchestration.desktop.claudeauth.ClaudeTokenVerdict
import com.kyuchestration.desktop.claudeauth.StoredClaudeCredential
import com.kyuchestration.desktop.dashboard.WorkDirDashboardState
import com.kyuchestration.desktop.dashboard.WorkDirDashboardStateHolder
import com.kyuchestration.desktop.engine.EngineInstallationState
import com.kyuchestration.desktop.engine.EngineInstallationStateHolder
import com.kyuchestration.desktop.engine.EngineInstaller
import com.kyuchestration.desktop.initialization.WorkDirInitializationStateHolder
import com.kyuchestration.desktop.repoclone.CloneOutcome
import com.kyuchestration.desktop.repoclone.GitHubRepositoryCatalog
import com.kyuchestration.desktop.repoclone.RegisteredTokenProfile
import com.kyuchestration.desktop.repoclone.RemoteRepository
import com.kyuchestration.desktop.repoclone.RepositoryCloneStateHolder
import com.kyuchestration.desktop.repoclone.RepositoryOwner
import com.kyuchestration.desktop.repoclone.TokenProfile
import com.kyuchestration.desktop.repoclone.TokenProfileRegistry
import com.kyuchestration.desktop.repoclone.WorkDirRepositoryCloner
import com.kyuchestration.desktop.terminal.SessionConversationChoice
import com.kyuchestration.desktop.terminal.SessionTarget
import com.kyuchestration.desktop.terminal.chat.RecordingChatSessionOpener
import com.kyuchestration.desktop.terminal.chat.RecordingSessionCommandSource
import com.kyuchestration.desktop.terminal.chat.stateHolder
import com.kyuchestration.desktop.workdir.WorkDirInitializationResult
import com.kyuchestration.desktop.workdir.WorkDirInitializer
import com.kyuchestration.desktop.workdir.WorkDirSnapshot
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * 같은 JVM 의 홀더들을 화면 쪽 경계로 내놓는 자리(core-ui-split-design.md 6.2).
 *
 * 여기서 갈리는 것은 화면에 있던 조립의 결정들이 이 자리로 옮겨 와서도 같게 도는가다 — 엔진이 선 뒤에 인증을 묻고,
 * 고른 자리를 초기화를 거쳐 대시보드로 열고, 세션을 열린 워크디렉토리에서 띄우고, 워크디렉토리를 닫을 때 그 안의
 * 것을 함께 놓는다. 홀더 각자의 규칙은 그 홀더의 시험이 본다. 포트는 람다와 작은 가짜로 세운다.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class InProcessScreenFacingCoreTest {

    private val workDirPath: Path = createTempDirectory("kyu-screen-facing-core")

    @AfterTest
    fun 임시_디렉토리를_치운다() {
        workDirPath.toFile().deleteRecursively()
    }

    @Test
    fun `엔진이 이미 있으면 곧바로 claude 인증을 묻는다`() = runTest {
        val bed = coreTestBed(engineInstalledAtStart = true)
        runCurrent()

        assertEquals(1, bed.claudeCli.credentialChecks)
        assertEquals(ClaudeAuthenticationState.CredentialsReady, bed.core.claudeAuthenticationState.value)
    }

    @Test
    fun `엔진이 없으면 묻지 않다가 엔진을 받아 서면 그때 묻는다`() = runTest {
        // 토큰을 맡아 두는 자리가 kyu 라서, 엔진이 없을 때 물으면 진짜 문제가 아닌 실패가 기록에 쌓인다.
        val bed = coreTestBed(engineInstalledAtStart = false)

        assertEquals(EngineInstallationState.InstallationRunning, bed.core.engineInstallationState.value)
        assertEquals(0, bed.claudeCli.credentialChecks)

        runCurrent()

        assertEquals(EngineInstallationState.EngineReady, bed.core.engineInstallationState.value)
        assertEquals(1, bed.claudeCli.credentialChecks)
    }

    @Test
    fun `고른 자리를 열면 초기화를 거쳐 대시보드가 그 자리를 관찰한다`() = runTest {
        val bed = coreTestBed()

        bed.core.openWorkDir(workDirPath)
        runCurrent()

        assertIs<WorkDirDashboardState.WorkDirObserved>(bed.core.workDirDashboardState.value)
        assertEquals(workDirPath, bed.core.workDirDashboardState.value.workDirPath)
    }

    @Test
    fun `세션은 열린 워크디렉토리에서 띄우고 보유 세션은 대상만 내놓는다`() = runTest {
        val bed = coreTestBed()
        bed.core.openWorkDir(workDirPath)
        runCurrent()

        bed.core.enterSession(SessionTarget.Main, SessionConversationChoice.ContinueRecordedConversation)
        runCurrent()

        assertEquals(listOf<SessionTarget>(SessionTarget.Main), bed.sessionCommandSource.askedTargets)
        assertEquals(setOf<SessionTarget>(SessionTarget.Main), bed.core.heldChatSessionTargets.value)
    }

    @Test
    fun `열린 워크디렉토리가 없으면 세션을 띄우지 않는다`() = runTest {
        val bed = coreTestBed()

        bed.core.enterSession(SessionTarget.Main, SessionConversationChoice.ContinueRecordedConversation)
        runCurrent()

        assertEquals(emptyList(), bed.sessionCommandSource.askedTargets)
    }

    @Test
    fun `워크디렉토리를 닫으면 그 안의 세션을 끝내고 대시보드를 닫는다`() = runTest {
        val bed = coreTestBed()
        bed.core.openWorkDir(workDirPath)
        runCurrent()
        bed.core.enterSession(SessionTarget.Main, SessionConversationChoice.ContinueRecordedConversation)
        runCurrent()

        bed.core.closeWorkDir()
        runCurrent()

        assertEquals(1, bed.chatSessionOpener.lastSession.endSessionCount)
        assertEquals(emptySet(), bed.core.heldChatSessionTargets.value)
        assertEquals(WorkDirDashboardState.NoWorkDirOpened, bed.core.workDirDashboardState.value)
    }

    @Test
    fun `워크디렉토리를 닫는 그 자리에서 세션과 전사를 놓고 대시보드까지 닫는다`() = runTest {
        // 세션 놓기가 뒤로 밀리면, 닫힌 대시보드에서 새 워크디렉토리를 열고 메인에 들어가는 사이 옛 메인이 아직 보유
        // 중이라 그 진입이 버려지고 옛 전사가 화면에 선다.
        val bed = coreTestBed()
        bed.core.openWorkDir(workDirPath)
        runCurrent()
        bed.core.enterSession(SessionTarget.Main, SessionConversationChoice.ContinueRecordedConversation)
        runCurrent()

        bed.core.closeWorkDir()

        assertEquals(emptySet(), bed.core.heldChatSessionTargets.value)
        assertEquals(emptyMap(), bed.core.chatConversations.value)
        assertEquals(WorkDirDashboardState.NoWorkDirOpened, bed.core.workDirDashboardState.value)
    }

    private fun TestScope.coreTestBed(engineInstalledAtStart: Boolean = true): CoreTestBed {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        var engineExecutable: Path? = if (engineInstalledAtStart) ENGINE_EXECUTABLE else null
        val claudeCli = RecordingClaudeCliAuthentication()
        val sessionCommandSource = RecordingSessionCommandSource()
        val chatSessionOpener = RecordingChatSessionOpener()

        val core = InProcessScreenFacingCore(
            engineInstallation = EngineInstallationStateHolder(
                engineInstaller = object : EngineInstaller {
                    override fun installEngine() {
                        engineExecutable = ENGINE_EXECUTABLE
                    }
                },
                coroutineScope = backgroundScope,
                findEngineExecutable = { engineExecutable },
                placeBundledEngine = {},
                installationDispatcher = testDispatcher,
            ),
            claudeAuthentication = ClaudeAuthenticationStateHolder(
                claudeCliAuthentication = claudeCli,
                claudeTokenStore = NothingStoredClaudeTokenStore,
                coroutineScope = backgroundScope,
                tokenStoreDispatcher = testDispatcher,
            ),
            workDirDashboard = WorkDirDashboardStateHolder(
                workDirObserver = { observedPath -> emptySnapshotOf(observedPath) },
                planFileExists = { true },
                coroutineScope = backgroundScope,
                observationDispatcher = testDispatcher,
            ),
            workDirInitialization = WorkDirInitializationStateHolder(
                workDirInitializer = UnusedWorkDirInitializer,
                planFileExists = { true },
                coroutineScope = backgroundScope,
                userHomeDirectory = null,
                initializationDispatcher = testDispatcher,
            ),
            repositoryClone = RepositoryCloneStateHolder(
                tokenProfileRegistry = UnusedTokenProfileRegistry,
                gitHubRepositoryCatalog = UnusedGitHubRepositoryCatalog,
                workDirRepositoryCloner = UnusedWorkDirRepositoryCloner,
                coroutineScope = backgroundScope,
            ),
            chatSessions = stateHolder(chatSessionOpener, sessionCommandSource),
            engineDirectoryLabel = "/tmp/engine",
            coroutineScope = backgroundScope,
        )
        return CoreTestBed(core, claudeCli, sessionCommandSource, chatSessionOpener)
    }

    private class CoreTestBed(
        val core: InProcessScreenFacingCore,
        val claudeCli: RecordingClaudeCliAuthentication,
        val sessionCommandSource: RecordingSessionCommandSource,
        val chatSessionOpener: RecordingChatSessionOpener,
    )

    /** 로그인돼 있다고 답하고, 몇 번 물었는지 센다. 이 시험은 "언제 묻는가" 만 본다. */
    private class RecordingClaudeCliAuthentication : ClaudeCliAuthentication {
        var credentialChecks = 0
            private set

        override suspend fun credentialsArePresent(tokenToInject: String?): Boolean {
            credentialChecks++
            return true
        }

        override suspend fun startBrowserLogin(): ClaudeBrowserLogin = error("이 시험은 브라우저 로그인을 부르지 않는다")

        override suspend fun tokenIsAccepted(token: String): ClaudeTokenVerdict = error("이 시험은 토큰을 검증하지 않는다")
    }

    private object NothingStoredClaudeTokenStore : ClaudeTokenStore {
        override fun storedCredential() = StoredClaudeCredential(token = null, newTokenWouldBeStoredInPlaintext = false)

        override fun storeToken(token: String) = error("이 시험은 토큰을 맡기지 않는다")
    }

    /** 계획 파일이 이미 있는 자리만 연다 — 초기화를 부르면 시험이 엉뚱한 길로 간 것이다. */
    private object UnusedWorkDirInitializer : WorkDirInitializer {
        override fun initializeInPlace(workDirPath: Path): WorkDirInitializationResult = error("이 시험은 초기화를 부르지 않는다")
    }

    private object UnusedTokenProfileRegistry : TokenProfileRegistry {
        override fun listProfiles(): List<TokenProfile> = error("이 시험은 클론을 열지 않는다")

        override fun registerProfile(profileName: String, token: String): RegisteredTokenProfile = error("이 시험은 클론을 열지 않는다")

        override fun removeProfile(profileName: String) = error("이 시험은 클론을 열지 않는다")
    }

    private object UnusedGitHubRepositoryCatalog : GitHubRepositoryCatalog {
        override fun listOwners(profileName: String): List<RepositoryOwner> = error("이 시험은 클론을 열지 않는다")

        override fun listRepositories(profileName: String, ownerLogin: String): List<RemoteRepository> =
            error("이 시험은 클론을 열지 않는다")
    }

    private object UnusedWorkDirRepositoryCloner : WorkDirRepositoryCloner {
        override fun cloneInto(workDirPath: Path, profileName: String, repositoryFullNames: List<String>): CloneOutcome =
            error("이 시험은 클론을 열지 않는다")
    }

    private companion object {
        val ENGINE_EXECUTABLE: Path = Path.of("/tmp/engine/kyu")

        fun emptySnapshotOf(workDirPath: Path) = WorkDirSnapshot(
            name = workDirPath.fileName.toString(),
            absolutePath = workDirPath.toString(),
            repos = emptyList(),
            mainSessionHasRecordedConversation = false,
            planWarnings = emptyList(),
        )
    }
}
