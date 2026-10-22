package com.kyuchestration.desktop.core

import com.kyuchestration.desktop.claudeauth.ClaudeAuthenticationStateHolder
import com.kyuchestration.desktop.claudeauth.ClaudeBrowserLogin
import com.kyuchestration.desktop.claudeauth.ClaudeCliAuthentication
import com.kyuchestration.desktop.claudeauth.ClaudeTokenStore
import com.kyuchestration.desktop.claudeauth.ClaudeTokenVerdict
import com.kyuchestration.desktop.claudeauth.StoredClaudeCredential
import com.kyuchestration.desktop.dashboard.WorkDirDashboardStateHolder
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
import com.kyuchestration.desktop.terminal.chat.RecordingChatSessionOpener
import com.kyuchestration.desktop.terminal.chat.RecordingSessionCommandSource
import com.kyuchestration.desktop.terminal.chat.stateHolder
import com.kyuchestration.desktop.workdir.WorkDirInitializationResult
import com.kyuchestration.desktop.workdir.WorkDirInitializer
import com.kyuchestration.desktop.workdir.WorkDirSnapshot
import java.nio.file.Path
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope

/**
 * 같은 JVM 의 홀더 여섯을 [InProcessScreenFacingCore] 로 세운다. 포트는 람다와 작은 가짜다 — 조립 결정을 보는 시험과, 그
 * 조립 위에 파이프와 거울을 얹는 시험이 같은 코어를 쓴다.
 */
internal fun TestScope.inProcessCoreTestBed(
    engineInstalledAtStart: Boolean = true,
    claudeTokenStore: ClaudeTokenStore = NothingStoredClaudeTokenStore,
    tokenProfileRegistry: TokenProfileRegistry = UnusedTokenProfileRegistry,
): InProcessCoreTestBed {
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
            claudeTokenStore = claudeTokenStore,
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
            tokenProfileRegistry = tokenProfileRegistry,
            gitHubRepositoryCatalog = UnusedGitHubRepositoryCatalog,
            workDirRepositoryCloner = UnusedWorkDirRepositoryCloner,
            coroutineScope = backgroundScope,
            cloneStepDispatcher = testDispatcher,
        ),
        chatSessions = stateHolder(chatSessionOpener, sessionCommandSource),
        engineDirectoryLabel = "/tmp/engine",
        coroutineScope = backgroundScope,
    )
    return InProcessCoreTestBed(core, claudeCli, sessionCommandSource, chatSessionOpener)
}

internal class InProcessCoreTestBed(
    val core: InProcessScreenFacingCore,
    val claudeCli: RecordingClaudeCliAuthentication,
    val sessionCommandSource: RecordingSessionCommandSource,
    val chatSessionOpener: RecordingChatSessionOpener,
)

/** 로그인돼 있다고 답하고 붙여넣은 토큰도 받는다. 몇 번 물었는지 센다. */
class RecordingClaudeCliAuthentication : ClaudeCliAuthentication {
    var credentialChecks = 0
        private set

    override suspend fun credentialsArePresent(tokenToInject: String?): Boolean {
        credentialChecks++
        return true
    }

    override suspend fun startBrowserLogin(): ClaudeBrowserLogin = error("이 시험은 브라우저 로그인을 부르지 않는다")

    override suspend fun tokenIsAccepted(token: String): ClaudeTokenVerdict = ClaudeTokenVerdict.Accepted
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

private val ENGINE_EXECUTABLE: Path = Path.of("/tmp/engine/kyu")

private fun emptySnapshotOf(workDirPath: Path) = WorkDirSnapshot(
    name = workDirPath.fileName.toString(),
    absolutePath = workDirPath.toString(),
    repos = emptyList(),
    mainSessionHasRecordedConversation = false,
    planWarnings = emptyList(),
)
