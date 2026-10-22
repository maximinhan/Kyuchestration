package com.kyuchestration.desktop.core

import com.kyuchestration.desktop.claudeauth.ClaudeAuthenticationState
import com.kyuchestration.desktop.dashboard.WorkDirDashboardState
import com.kyuchestration.desktop.engine.EngineInstallationState
import com.kyuchestration.desktop.initialization.WorkDirInitializationState
import com.kyuchestration.desktop.protocol.ScreenCommand
import com.kyuchestration.desktop.repoclone.RepositoryCloneState
import com.kyuchestration.desktop.terminal.PersonOpenedSessionTarget
import com.kyuchestration.desktop.terminal.SessionConversationChoice
import com.kyuchestration.desktop.terminal.SessionTarget
import com.kyuchestration.desktop.terminal.chat.ChatConversation
import com.kyuchestration.desktop.terminal.chat.ChildSessionGateQuestion
import com.kyuchestration.desktop.terminal.chat.PermissionCardChoice
import com.kyuchestration.desktop.terminal.chat.SessionEntryState
import com.kyuchestration.desktop.terminal.chat.SessionTree
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 상태를 시험이 손으로 바꾸고, 받은 명령을 틀 위의 모양([ScreenCommand])으로 적어 두는 코어.
 *
 * 게시자 · 거울 시험은 홀더의 규칙이 아니라 "상태가 바뀐 대로 틀이 나가고, 틀이 온 대로 상태가 서는가" 를 본다. 홀더를
 * 세우면 그 규칙이 시험 사이에 끼어 무엇이 틀렸는지 흐려진다 — 홀더까지 잇는 시험은 따로 있다(파이프 왕복).
 */
class RecordingScreenFacingCore(
    override val engineDirectoryLabel: String = "/home/yawn/.local/share/kyuchestration/engine",
) : ScreenFacingCore {

    override val engineInstallationState = MutableStateFlow<EngineInstallationState>(EngineInstallationState.EngineReady)
    override val claudeAuthenticationState = MutableStateFlow<ClaudeAuthenticationState>(ClaudeAuthenticationState.CredentialsReady)
    override val workDirDashboardState = MutableStateFlow<WorkDirDashboardState>(WorkDirDashboardState.NoWorkDirOpened)
    override val workDirInitializationState = MutableStateFlow<WorkDirInitializationState>(WorkDirInitializationState.Idle)
    override val chatConversations = MutableStateFlow<Map<SessionTarget, ChatConversation>>(emptyMap())
    override val chatSessionEntries = MutableStateFlow<Map<PersonOpenedSessionTarget, SessionEntryState>>(emptyMap())
    override val heldChatSessionTargets = MutableStateFlow<Set<SessionTarget>>(emptySet())
    override val sessionTree = MutableStateFlow(SessionTree())
    override val childSessionGateQuestions = MutableStateFlow<List<ChildSessionGateQuestion>>(emptyList())
    override val repositoryCloneState = MutableStateFlow<RepositoryCloneState>(RepositoryCloneState.Closed)

    val receivedCommands = mutableListOf<ScreenCommand>()

    var endAllSessionsCount = 0
        private set

    override fun installEngine() = record(ScreenCommand.InstallEngine)

    override fun lookForEngineAgain() = record(ScreenCommand.LookForEngineAgain)

    override fun checkClaudeCredentials() = record(ScreenCommand.CheckClaudeCredentials)

    override fun startClaudeBrowserLogin() = record(ScreenCommand.StartClaudeBrowserLogin)

    override fun submitClaudeBrowserLoginCode(code: String) = record(ScreenCommand.SubmitClaudeBrowserLoginCode(code))

    override fun cancelClaudeBrowserLogin() = record(ScreenCommand.CancelClaudeBrowserLogin)

    override fun useClaudeToken(token: String) = record(ScreenCommand.UseClaudeToken(token))

    override fun openWorkDir(hostPath: String) = record(ScreenCommand.OpenWorkDir(hostPath))

    override fun initializeOpenedWorkDir() = record(ScreenCommand.InitializeOpenedWorkDir)

    override fun refreshWorkDir() = record(ScreenCommand.RefreshWorkDir)

    override fun closeWorkDir() = record(ScreenCommand.CloseWorkDir)

    override fun enterSession(target: PersonOpenedSessionTarget, conversationChoice: SessionConversationChoice) =
        record(ScreenCommand.EnterSession(target, conversationChoice))

    override fun dismissSessionEntryFailure(target: PersonOpenedSessionTarget) =
        record(ScreenCommand.DismissSessionEntryFailure(target))

    override fun sendUserMessage(target: SessionTarget, text: String) = record(ScreenCommand.SendUserMessage(target, text))

    override fun interruptTurn(target: SessionTarget) = record(ScreenCommand.InterruptTurn(target))

    override fun answerPermission(target: SessionTarget, toolUseId: String, choice: PermissionCardChoice) =
        record(ScreenCommand.AnswerPermission(target, toolUseId, choice))

    override fun endSession(target: SessionTarget) = record(ScreenCommand.EndSession(target))

    override fun answerGate(gateQuestionId: String, approved: Boolean) = record(ScreenCommand.AnswerGate(gateQuestionId, approved))

    override fun openRepositoryClone() = record(ScreenCommand.OpenRepositoryClone)

    override fun closeRepositoryClone() = record(ScreenCommand.CloseRepositoryClone)

    override fun chooseTokenProfile(profileName: String) = record(ScreenCommand.ChooseTokenProfile(profileName))

    override fun startRemovingTokenProfile(profileName: String) = record(ScreenCommand.StartRemovingTokenProfile(profileName))

    override fun stopRemovingTokenProfile() = record(ScreenCommand.StopRemovingTokenProfile)

    override fun removeChosenTokenProfile() = record(ScreenCommand.RemoveChosenTokenProfile)

    override fun startRegisteringTokenProfile() = record(ScreenCommand.StartRegisteringTokenProfile)

    override fun stopRegisteringTokenProfile() = record(ScreenCommand.StopRegisteringTokenProfile)

    override fun registerTokenProfile(profileName: String, token: String) =
        record(ScreenCommand.RegisterTokenProfile(profileName, token))

    override fun continueWithRegisteredTokenProfile() = record(ScreenCommand.ContinueWithRegisteredTokenProfile)

    override fun chooseRepositoryOwner(ownerLogin: String) = record(ScreenCommand.ChooseRepositoryOwner(ownerLogin))

    override fun changeRepositorySearchText(searchText: String) = record(ScreenCommand.ChangeRepositorySearchText(searchText))

    override fun toggleRepositorySelection(repositoryFullName: String) =
        record(ScreenCommand.ToggleRepositorySelection(repositoryFullName))

    override fun cloneSelectedRepositories() = record(ScreenCommand.CloneSelectedRepositories)

    override fun retryFailedCloneStep() = record(ScreenCommand.RetryFailedCloneStep)

    override suspend fun endAllSessions() {
        endAllSessionsCount++
    }

    private fun record(command: ScreenCommand) {
        receivedCommands += command
    }
}
