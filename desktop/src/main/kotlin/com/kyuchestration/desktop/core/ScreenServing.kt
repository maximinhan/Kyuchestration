package com.kyuchestration.desktop.core

import com.kyuchestration.desktop.protocol.CommandOutcome
import com.kyuchestration.desktop.protocol.FrameToCore
import com.kyuchestration.desktop.protocol.FrameToScreen
import com.kyuchestration.desktop.protocol.ScreenCommand
import com.kyuchestration.desktop.protocol.commandIdOfUnreadableFrame
import com.kyuchestration.desktop.protocol.decodeFrameToCore
import com.kyuchestration.desktop.protocol.encodeFrameToScreen
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException

/**
 * 화면 하나를 코어에 잇는다 — 화면이 보낸 틀 줄을 읽어 명령을 [core] 에 부르고, [core] 의 변화를 틀 줄로 써 보낸다
 * (core-ui-split-design.md 6.4 · 6.5).
 *
 * **코어의 단일 스레드에서 부른다.** 홀더가 상태를 바꾸는 스레드와 게시자가 견주는 스레드가 같아야, 명령 하나가 남긴
 * 변화를 그 명령의 답보다 먼저 보낼 수 있다 — 명령을 부른 바로 뒤에 견주면 그 명령이 그 자리에서 바꾼 것이 다 들어 있다.
 * 그래서 화면은 답을 받는 순간 그 명령의 즉각적인 결과(띄우는 중인 진입 같은 것)를 이미 거울에 들고 있다.
 *
 * [framesToScreen] 은 받는 쪽이 밀려도 쓰기가 멈추지 않는 줄이어야 한다. 쓰는 도중에 멈추면 그사이 다른 코루틴이 견준
 * 변화가 앞 변화보다 먼저 나가 `seq` 가 뒤섞인다. 화면이 밀릴 때 버리고 스냅샷부터 다시 보내는 한도(6.5 의 4 MiB)는
 * 코어가 프로세스로 갈라져 그 줄이 stdout 이 될 때 선다.
 *
 * 화면이 줄을 닫으면(stdin EOF) 모든 세션을 끝내고 자기 줄을 닫는다 — 창을 닫든 화면이 죽든 같은 길이다(6.13 의 2~4 단계).
 * 명령 도중 코어가 실패하거나 코어 스코프가 취소돼도 같은 끝을 지난다. 실패는 그 뒤에 그대로 던진다.
 */
suspend fun serveScreen(
    core: ScreenFacingCore,
    framesFromScreen: ReceiveChannel<String>,
    framesToScreen: SendChannel<String>,
) {
    val publisher = ScreenChangePublisher(core)
    fun writeLine(line: String) {
        framesToScreen.trySend(line).getOrThrow()
    }
    fun write(frame: FrameToScreen) = writeLine(encodeFrameToScreen(frame))
    fun publishPendingChanges() = publisher.pendingChangeFrames().forEach(::write)

    try {
        coroutineScope {
            val watchingCore = launch {
                merge(
                    core.engineInstallationState,
                    core.claudeAuthenticationState,
                    core.workDirDashboardState,
                    core.workDirInitializationState,
                    core.chatConversations,
                    core.chatSessionEntries,
                    core.heldChatSessionTargets,
                    core.sessionTree,
                    core.childSessionGateQuestions,
                    core.repositoryCloneState,
                ).collect { publishPendingChanges() }
            }
            for (line in framesFromScreen) {
                val frame = try {
                    decodeFrameToCore(line)
                } catch (unreadable: SerializationException) {
                    // 화면이 이 코어보다 새 판이면 모르는 틀 · 모르는 명령이 온다(6.14). 코어는 그것을 견딘다 — 줄 하나로 코어가
                    // 끝나면 세션이 모두 끊긴다. 명령이면 그 id 로 거절을 알린다 — 조용히 버리면 화면은 그 명령이 갔다고 안다.
                    // 답을 기다리지 않는 틀(새 판의 bye 같은 것)은 건너뛴다. 사유에 예외 문구를 싣지 않는 것은 그 문구가 줄의
                    // 일부(토큰일 수 있다)를 인용하기 때문이다.
                    commandIdOfUnreadableFrame(line)?.let { commandId ->
                        write(FrameToScreen.Reply(commandId, CommandOutcome.Rejected, reason = UNREADABLE_COMMAND))
                    }
                    continue
                }
                when (frame) {
                    FrameToCore.Hello -> {
                        write(FrameToScreen.Welcome(engineDirectory = core.engineDirectoryLabel))
                        publisher.snapshotLines().forEach(::writeLine)
                    }
                    is FrameToCore.Command -> {
                        frame.command.applyTo(core)
                        publishPendingChanges()
                        write(FrameToScreen.Reply(frame.id, CommandOutcome.Accepted))
                    }
                }
            }
            watchingCore.cancel()
        }
    } finally {
        // 화면이 떠났든(줄의 끝), 명령 도중 코어가 실패했든, 코어 스코프가 취소됐든 세션은 여기서 끝낸다. 건너뛰면 claude
        // 프로세스들이 주인 없이 남는다 — 취소된 뒤에도 끝내기가 마저 돌도록 취소를 받지 않는 자리에서 부른다.
        withContext(NonCancellable) { core.endAllSessions() }
        framesToScreen.close()
    }
}

private const val UNREADABLE_COMMAND = "unreadable_command"

private fun ScreenCommand.applyTo(core: ScreenFacingCore) = when (this) {
    ScreenCommand.InstallEngine -> core.installEngine()
    ScreenCommand.LookForEngineAgain -> core.lookForEngineAgain()
    ScreenCommand.CheckClaudeCredentials -> core.checkClaudeCredentials()
    ScreenCommand.StartClaudeBrowserLogin -> core.startClaudeBrowserLogin()
    is ScreenCommand.SubmitClaudeBrowserLoginCode -> core.submitClaudeBrowserLoginCode(code)
    ScreenCommand.CancelClaudeBrowserLogin -> core.cancelClaudeBrowserLogin()
    is ScreenCommand.UseClaudeToken -> core.useClaudeToken(token)
    is ScreenCommand.OpenWorkDir -> core.openWorkDir(hostPath)
    ScreenCommand.InitializeOpenedWorkDir -> core.initializeOpenedWorkDir()
    ScreenCommand.RefreshWorkDir -> core.refreshWorkDir()
    ScreenCommand.CloseWorkDir -> core.closeWorkDir()
    is ScreenCommand.EnterSession -> core.enterSession(target, conversationChoice)
    is ScreenCommand.DismissSessionEntryFailure -> core.dismissSessionEntryFailure(target)
    is ScreenCommand.SendUserMessage -> core.sendUserMessage(target, text)
    is ScreenCommand.InterruptTurn -> core.interruptTurn(target)
    is ScreenCommand.AnswerPermission -> core.answerPermission(target, toolUseId, choice)
    is ScreenCommand.EndSession -> core.endSession(target)
    is ScreenCommand.AnswerGate -> core.answerGate(gateQuestionId, approved)
    ScreenCommand.OpenRepositoryClone -> core.openRepositoryClone()
    ScreenCommand.CloseRepositoryClone -> core.closeRepositoryClone()
    is ScreenCommand.ChooseTokenProfile -> core.chooseTokenProfile(profileName)
    is ScreenCommand.StartRemovingTokenProfile -> core.startRemovingTokenProfile(profileName)
    ScreenCommand.StopRemovingTokenProfile -> core.stopRemovingTokenProfile()
    ScreenCommand.RemoveChosenTokenProfile -> core.removeChosenTokenProfile()
    ScreenCommand.StartRegisteringTokenProfile -> core.startRegisteringTokenProfile()
    ScreenCommand.StopRegisteringTokenProfile -> core.stopRegisteringTokenProfile()
    is ScreenCommand.RegisterTokenProfile -> core.registerTokenProfile(profileName, token)
    ScreenCommand.ContinueWithRegisteredTokenProfile -> core.continueWithRegisteredTokenProfile()
    is ScreenCommand.ChooseRepositoryOwner -> core.chooseRepositoryOwner(ownerLogin)
    is ScreenCommand.ChangeRepositorySearchText -> core.changeRepositorySearchText(searchText)
    is ScreenCommand.ToggleRepositorySelection -> core.toggleRepositorySelection(repositoryFullName)
    ScreenCommand.CloneSelectedRepositories -> core.cloneSelectedRepositories()
    ScreenCommand.RetryFailedCloneStep -> core.retryFailedCloneStep()
}
