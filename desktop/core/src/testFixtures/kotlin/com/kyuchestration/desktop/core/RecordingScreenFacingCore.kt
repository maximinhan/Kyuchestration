package com.kyuchestration.desktop.core

import com.kyuchestration.desktop.claudeauth.ClaudeAuthenticationState
import com.kyuchestration.desktop.dashboard.WorkDirDashboardState
import com.kyuchestration.desktop.engine.EngineInstallationState
import com.kyuchestration.desktop.initialization.WorkDirInitializationState
import com.kyuchestration.desktop.protocol.ScreenCommand
import com.kyuchestration.desktop.protocol.ScreenFacingCore
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
) : ScreenServingCore {

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

    /** 물음에 대한 답이 열린 물음에 닿는가. false 면 답은 받되 닿지 않았다고 답한다 — 화면이 답을 보낸 사이 물음이 닫힌 자리다. */
    var questionsStillOpen = true

    /** 이 명령을 받으면 던진다 — 홀더가 명령 도중 예외를 낸 자리를 흉내 낸다. */
    var commandThatFails: ScreenCommand? = null

    override fun installEngine() = record(ScreenCommand.InstallEngine)

    override fun lookForEngineAgain() = record(ScreenCommand.LookForEngineAgain)

    override fun checkClaudeCredentials() = record(ScreenCommand.CheckClaudeCredentials)

    override fun startClaudeBrowserLogin() = record(ScreenCommand.StartClaudeBrowserLogin)

    override fun submitClaudeBrowserLoginCode(code: String) = record(ScreenCommand.SubmitClaudeBrowserLoginCode(code))

    override fun cancelClaudeBrowserLogin() = record(ScreenCommand.CancelClaudeBrowserLogin)

    override fun useClaudeToken(token: String) = record(ScreenCommand.UseClaudeToken(token))

    override fun openWorkDir(workDirPath: String) = record(ScreenCommand.OpenWorkDir(workDirPath))

    override fun initializeOpenedWorkDir() = record(ScreenCommand.InitializeOpenedWorkDir)

    override fun refreshWorkDir() = record(ScreenCommand.RefreshWorkDir)

    override fun closeWorkDir() = record(ScreenCommand.CloseWorkDir)

    override fun enterSession(target: PersonOpenedSessionTarget, conversationChoice: SessionConversationChoice) =
        record(ScreenCommand.EnterSession(target, conversationChoice))

    override fun dismissSessionEntryFailure(target: PersonOpenedSessionTarget) =
        record(ScreenCommand.DismissSessionEntryFailure(target))

    override fun sendUserMessage(target: SessionTarget, text: String) = record(ScreenCommand.SendUserMessage(target, text))

    override fun interruptTurn(target: SessionTarget) = record(ScreenCommand.InterruptTurn(target))

    override fun answerPermissionIfStillAsked(target: SessionTarget, toolUseId: String, choice: PermissionCardChoice): Boolean {
        record(ScreenCommand.AnswerPermission(target, toolUseId, choice))
        return questionsStillOpen
    }

    override fun endSession(target: SessionTarget) = record(ScreenCommand.EndSession(target))

    override fun answerGateIfStillOpen(gateQuestionId: String, approved: Boolean): Boolean {
        record(ScreenCommand.AnswerGate(gateQuestionId, approved))
        return questionsStillOpen
    }

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
        check(command != commandThatFails) { "홀더가 $command 를 받다 실패했다" }
        receivedCommands += command
    }
}
