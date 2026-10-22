package com.kyuchestration.desktop.core

import com.kyuchestration.desktop.terminal.SessionConversationChoice
import com.kyuchestration.desktop.terminal.SessionTarget
import com.kyuchestration.desktop.terminal.chat.PermissionCardChoice

/**
 * 경계의 명령 서른셋을 한 번씩 부른다. 같은 목록을 거울과 같은 JVM 의 코어에 함께 부어, 명령이 틀을 건너도 같은 모양으로
 * 닿는지 견준다 — 명령 하나를 빠뜨린 거울이나 디스패치는 그 명령의 줄에서 어긋난다.
 */
val CALLS_TO_EVERY_SCREEN_COMMAND: List<(ScreenFacingCore) -> Unit> = listOf(
    { it.installEngine() },
    { it.lookForEngineAgain() },
    { it.checkClaudeCredentials() },
    { it.startClaudeBrowserLogin() },
    { it.submitClaudeBrowserLoginCode("code-123") },
    { it.cancelClaudeBrowserLogin() },
    { it.useClaudeToken("sk-ant-oat01-secret") },
    { it.openWorkDir("/home/yawn/작업") },
    { it.initializeOpenedWorkDir() },
    { it.refreshWorkDir() },
    { it.closeWorkDir() },
    { it.enterSession(SessionTarget.Repo("proj-a"), SessionConversationChoice.StartNewConversation) },
    { it.dismissSessionEntryFailure(SessionTarget.Main) },
    { it.sendUserMessage(SessionTarget.Child("child-1"), "안녕") },
    { it.interruptTurn(SessionTarget.Main) },
    { it.answerPermission(SessionTarget.Main, "toolu_01", PermissionCardChoice.Deny("안 됩니다")) },
    { it.endSession(SessionTarget.Child("child-1")) },
    { it.answerGate("gate-1", approved = false) },
    { it.openRepositoryClone() },
    { it.closeRepositoryClone() },
    { it.chooseTokenProfile("work") },
    { it.startRemovingTokenProfile("old") },
    { it.stopRemovingTokenProfile() },
    { it.removeChosenTokenProfile() },
    { it.startRegisteringTokenProfile() },
    { it.stopRegisteringTokenProfile() },
    { it.registerTokenProfile("work", "ghp_secret") },
    { it.continueWithRegisteredTokenProfile() },
    { it.chooseRepositoryOwner("maximinhan") },
    { it.changeRepositorySearchText("kyu") },
    { it.toggleRepositorySelection("maximinhan/Kyuchestration") },
    { it.cloneSelectedRepositories() },
    { it.retryFailedCloneStep() },
)
