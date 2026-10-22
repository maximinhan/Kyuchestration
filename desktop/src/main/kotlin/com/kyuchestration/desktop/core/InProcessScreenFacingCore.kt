package com.kyuchestration.desktop.core

import com.kyuchestration.desktop.claudeauth.ClaudeAuthenticationState
import com.kyuchestration.desktop.claudeauth.ClaudeAuthenticationStateHolder
import com.kyuchestration.desktop.dashboard.WorkDirDashboardState
import com.kyuchestration.desktop.dashboard.WorkDirDashboardStateHolder
import com.kyuchestration.desktop.engine.EngineInstallationState
import com.kyuchestration.desktop.engine.EngineInstallationStateHolder
import com.kyuchestration.desktop.initialization.WorkDirInitializationState
import com.kyuchestration.desktop.initialization.WorkDirInitializationStateHolder
import com.kyuchestration.desktop.repoclone.RepositoryCloneState
import com.kyuchestration.desktop.repoclone.RepositoryCloneStateHolder
import com.kyuchestration.desktop.terminal.PersonOpenedSessionTarget
import com.kyuchestration.desktop.terminal.SessionConversationChoice
import com.kyuchestration.desktop.terminal.SessionTarget
import com.kyuchestration.desktop.terminal.chat.ChatConversation
import com.kyuchestration.desktop.terminal.chat.ChatSessionStateHolder
import com.kyuchestration.desktop.terminal.chat.ChildSessionGateQuestion
import com.kyuchestration.desktop.terminal.chat.PermissionCardChoice
import com.kyuchestration.desktop.terminal.chat.SessionEntryState
import com.kyuchestration.desktop.terminal.chat.SessionTree
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 같은 JVM 안의 상태 홀더 여섯을 [ScreenFacingCore] 로 내놓는 자리.
 *
 * 홀더끼리 서로를 부르지 않는 것이 이 자리의 몫이다. 워크디렉토리를 바꿀 때 세션을 놓고 초기화 실패 문구를 거두는 것,
 * 엔진이 선 뒤에 인증을 묻는 것은 어느 한 홀더의 일이 아니라 조립하는 쪽의 결정이고(설계 6.2 (나)), 조립은 코어에
 * 있다. 화면에 있던 그 결정들이 이리로 왔다 — 화면이 홀더 둘을 람다로 잇던 자리가 명령 하나가 된다.
 *
 * @param coroutineScope 홀더들과 같은 단일 스레드 스코프. 홀더의 상태를 만지는 코루틴이 한 번에 하나라는 전제를
 *   여기서도 지킨다(ChatSessionStateHolder 의 conversations).
 */
class InProcessScreenFacingCore(
    private val engineInstallation: EngineInstallationStateHolder,
    private val claudeAuthentication: ClaudeAuthenticationStateHolder,
    private val workDirDashboard: WorkDirDashboardStateHolder,
    private val workDirInitialization: WorkDirInitializationStateHolder,
    private val repositoryClone: RepositoryCloneStateHolder,
    private val chatSessions: ChatSessionStateHolder,
    override val engineDirectoryLabel: String,
    private val coroutineScope: CoroutineScope,
) : ScreenFacingCore {

    override val engineInstallationState: StateFlow<EngineInstallationState> = engineInstallation.state
    override val claudeAuthenticationState: StateFlow<ClaudeAuthenticationState> = claudeAuthentication.state
    override val workDirDashboardState: StateFlow<WorkDirDashboardState> = workDirDashboard.state
    override val workDirInitializationState: StateFlow<WorkDirInitializationState> = workDirInitialization.state
    override val chatConversations: StateFlow<Map<SessionTarget, ChatConversation>> = chatSessions.conversations
    override val chatSessionEntries: StateFlow<Map<PersonOpenedSessionTarget, SessionEntryState>> = chatSessions.sessionEntries
    override val sessionTree: StateFlow<SessionTree> = chatSessions.sessionTree
    override val childSessionGateQuestions: StateFlow<List<ChildSessionGateQuestion>> = chatSessions.gateQuestions
    override val repositoryCloneState: StateFlow<RepositoryCloneState> = repositoryClone.state

    override val heldChatSessionTargets: StateFlow<Set<SessionTarget>> = chatSessions.heldSessionTargets

    init {
        // 엔진이 선 뒤에 묻는다. 온보딩 순서가 곧 이 순서다 — 토큰을 맡아 두는 자리가 kyu 라서, 엔진이 아직 없을 때
        // 물으면 "저장소가 답하지 않음" 이 기록에 쌓이고 그 줄은 진짜 문제가 아니다. 인증 홀더의 생성자에서 곧바로 묻지
        // 않는 이유가 그것이다. 엔진을 다시 받아 다시 서면 다시 묻는다.
        coroutineScope.launch {
            engineInstallation.state.collect { engineState ->
                if (engineState == EngineInstallationState.EngineReady) {
                    claudeAuthentication.checkCredentials()
                }
            }
        }
    }

    override fun installEngine() = engineInstallation.installEngine()

    override fun lookForEngineAgain() = engineInstallation.lookForEngineAgain()

    override fun checkClaudeCredentials() = claudeAuthentication.checkCredentials()

    override fun startClaudeBrowserLogin() = claudeAuthentication.startBrowserLogin()

    override fun submitClaudeBrowserLoginCode(code: String) = claudeAuthentication.submitBrowserLoginCode(code)

    override fun cancelClaudeBrowserLogin() = claudeAuthentication.cancelBrowserLogin()

    override fun useClaudeToken(token: String) = claudeAuthentication.useToken(token)

    override fun openWorkDir(chosenDirectory: Path) {
        // 여는 일과 초기화하는 일이 홀더 둘로 나뉘어 있어 여기서 이어 붙인다. 초기화 쪽이 고른 자리를 조율할 수 있게
        // 만들어 놓고, 그때 무엇을 "연다" 고 하는지는 대시보드 쪽이 정한다.
        workDirInitialization.openChosenDirectoryAsWorkDir(
            workDirPath = chosenDirectory,
            onWorkDirReady = workDirDashboard::openWorkDir,
        )
    }

    override fun initializeOpenedWorkDir() {
        workDirDashboard.state.value.workDirPath?.let(workDirInitialization::initializeOpenedWorkDir)
    }

    override fun refreshWorkDir() = workDirDashboard.refreshNow()

    override fun closeWorkDir() {
        // 워크디렉토리를 바꾸면 그 안의 세션을 둘 이유가 없다. 끝내지 않으면 다른 워크디렉토리의 목록 아래에 이전
        // 워크디렉토리의 세션이 남는다.
        coroutineScope.launch { chatSessions.endAllSessions() }
        // 초기화 실패 문구도, 고르던 레포도 그 워크디렉토리의 것이다. 같은 이유로 함께 놓는다 — 다른 워크디렉토리를 연
        // 채로 앞 워크디렉토리에 받을 레포를 고르고 있으면, 받아 온 것이 화면에 없는 자리에 떨어진다.
        workDirInitialization.forgetLastFailure()
        repositoryClone.close()
        workDirDashboard.closeWorkDir()
    }

    override fun enterSession(target: PersonOpenedSessionTarget, conversationChoice: SessionConversationChoice) {
        workDirDashboard.state.value.workDirPath?.let { workDirPath ->
            chatSessions.enterSession(workDirPath, target, conversationChoice)
        }
    }

    override fun sendUserMessage(target: SessionTarget, text: String) = chatSessions.sendUserMessage(target, text)

    override fun interruptTurn(target: SessionTarget) = chatSessions.interruptTurn(target)

    override fun answerPermission(target: SessionTarget, toolUseId: String, choice: PermissionCardChoice) =
        chatSessions.answerPermission(target, toolUseId, choice)

    override fun endSession(target: SessionTarget) {
        coroutineScope.launch { chatSessions.endSession(target) }
    }

    override fun answerGate(gateQuestionId: String, approved: Boolean) =
        chatSessions.answerGateQuestion(gateQuestionId, approved)

    override fun openRepositoryClone() {
        // 무엇을 이미 받아 뒀는지는 방금 관찰한 목록이 알고 있다. 대화상자가 그것을 다시 알아내려면 워크디렉토리를 한 번
        // 더 훑어야 하는데, 그 앎은 대시보드의 것이다.
        (workDirDashboard.state.value as? WorkDirDashboardState.WorkDirObserved)?.let { observed ->
            repositoryClone.open(
                workDirPath = observed.workDirPath,
                alreadyClonedRepositoryNames = observed.snapshot.repos.map { it.name }.toSet(),
            )
        }
    }

    override fun closeRepositoryClone() = repositoryClone.close()

    override fun chooseTokenProfile(profileName: String) = repositoryClone.chooseTokenProfile(profileName)

    override fun startRemovingTokenProfile(profileName: String) = repositoryClone.startRemovingTokenProfile(profileName)

    override fun stopRemovingTokenProfile() = repositoryClone.stopRemovingTokenProfile()

    override fun removeChosenTokenProfile() = repositoryClone.removeChosenTokenProfile()

    override fun startRegisteringTokenProfile() = repositoryClone.startRegisteringTokenProfile()

    override fun stopRegisteringTokenProfile() = repositoryClone.stopRegisteringTokenProfile()

    override fun registerTokenProfile(profileName: String, token: String) =
        repositoryClone.registerTokenProfile(profileName, token)

    override fun continueWithRegisteredTokenProfile() = repositoryClone.continueWithRegisteredTokenProfile()

    override fun chooseRepositoryOwner(ownerLogin: String) = repositoryClone.chooseOwner(ownerLogin)

    override fun changeRepositorySearchText(searchText: String) = repositoryClone.changeRepositorySearchText(searchText)

    override fun toggleRepositorySelection(repositoryFullName: String) =
        repositoryClone.toggleRepositorySelection(repositoryFullName)

    override fun cloneSelectedRepositories() = repositoryClone.cloneSelectedRepositories()

    override fun retryFailedCloneStep() = repositoryClone.retryFailedStep()

    override suspend fun endAllSessions() = chatSessions.endAllSessions()
}
