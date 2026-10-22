package com.kyuchestration.desktop.core

import com.kyuchestration.desktop.protocol.ByeReason
import com.kyuchestration.desktop.protocol.CommandOutcome
import com.kyuchestration.desktop.protocol.FrameToCore
import com.kyuchestration.desktop.protocol.FrameToScreen
import com.kyuchestration.desktop.protocol.SCREEN_PROTOCOL_VERSION
import com.kyuchestration.desktop.protocol.ScreenCommand
import com.kyuchestration.desktop.protocol.commandIdOfUnreadableFrame
import com.kyuchestration.desktop.protocol.decodeFrameToCore
import com.kyuchestration.desktop.protocol.encodeFrameToScreen
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.ReceiveChannel
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
 * **화면이 밀리면 다시 맞춘다**(6.5). 틀은 [framesToScreen] 에 쓰고, 쓰기는 멈추지 않는다. 화면이 읽지 못해 아직 건네지 못한
 * 바이트가 마지막 스냅샷의 크기에 [screenBacklogLimitBytes] 를 더한 것을 넘으면, 그 뒤의 변화는 쓰지 않고 버린다. 밀린 것이 다
 * 건네지면 그때의 상태로 스냅샷을 다시 보낸다 — 상태를 보내는 것이지 사건 기록을 보내는 것이 아니라 버린 변화로 잃는 것이 없다.
 * 마지막 스냅샷의 크기를 더하는 것은 큰 대화의 스냅샷 자체가 한도를 넘어도 그 뒤의 변화가 곧바로 버려지지 않게다 — 그러지 않으면
 * 스냅샷이 다 건네질 때마다 또 스냅샷을 보내는 일이 되풀이된다. 명령의 답은 버리지 않는다. 화면은 답을 기다린다.
 *
 * 화면이 줄을 닫으면(stdin EOF) 모든 세션을 끝내고 자기 줄을 닫는다 — 창을 닫든 화면이 죽든 같은 길이다(6.13 의 2~4 단계).
 * 명령 도중 코어가 실패하거나 코어 스코프가 취소돼도 같은 끝을 지난다. 실패는 그 뒤에 그대로 던진다.
 *
 * 틀 규약의 판이 다른 화면에는 인사 답 뒤에 작별(`bye`)하고 같은 끝을 지난다(6.14). 판이 다르면 같은 필드가 다른 뜻일 수
 * 있어, 그 화면의 명령은 하나도 받지 않는다.
 */
suspend fun serveScreen(
    core: ScreenServingCore,
    coreIdentity: CoreIdentity,
    framesFromScreen: ReceiveChannel<String>,
    framesToScreen: ScreenFrameOutbox,
    screenBacklogLimitBytes: Long = SCREEN_BACKLOG_LIMIT_BYTES,
) {
    val publisher = ScreenChangePublisher(core)
    var lastSnapshotBytes = 0L
    var screenFellBehind = false

    fun write(frame: FrameToScreen) = framesToScreen.write(encodeFrameToScreen(frame))
    fun sendSnapshot() {
        screenFellBehind = false
        lastSnapshotBytes = publisher.snapshotLines().sumOf(framesToScreen::write)
    }
    fun publishPendingChanges() {
        // 밀린 동안에도 견주기는 한다 — 게시자의 기준선이 지금 상태를 따라가야, 다시 맞출 때 보낼 스냅샷과 그 뒤의 변화가 이어진다.
        val changeFrames = publisher.pendingChangeFrames()
        if (screenFellBehind || changeFrames.isEmpty()) {
            return
        }
        changeFrames.forEach(::write)
        if (framesToScreen.pendingBytes.value > lastSnapshotBytes + screenBacklogLimitBytes) {
            screenFellBehind = true
        }
    }

    // 코어가 실패로 끝났으면 그 까닭. 화면 쪽 줄을 그것을 실어 닫아야 코어 프로세스가 실패로 끝난다(CoreMain) — 실어 닫지 않으면 크래시가
    // 정상 종료처럼 보인다. 취소도 여기 실린다: 화면이 떠나지 않았는데 serveScreen 이 멈췄다는 뜻이라 정상 종료가 아니다.
    var coreFailure: Throwable? = null
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
            val catchingUpScreen = launch {
                framesToScreen.pendingBytes.collect { pendingBytes ->
                    if (screenFellBehind && pendingBytes == 0L) {
                        sendSnapshot()
                    }
                }
            }
            screenFrames@ for (line in framesFromScreen) {
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
                    is FrameToCore.Hello -> {
                        write(
                            FrameToScreen.Welcome(
                                protocol = SCREEN_PROTOCOL_VERSION,
                                coreVersion = coreIdentity.versionLabel,
                                coreDiagnosticLog = coreIdentity.diagnosticLogPath,
                                engineDirectory = core.engineDirectoryLabel,
                            ),
                        )
                        if (frame.protocol != SCREEN_PROTOCOL_VERSION) {
                            write(FrameToScreen.Bye(ByeReason.ProtocolMismatch))
                            break@screenFrames
                        }
                        sendSnapshot()
                    }
                    is FrameToCore.Command -> {
                        val rejection = frame.command.applyTo(core)
                        publishPendingChanges()
                        write(
                            if (rejection == null) {
                                FrameToScreen.Reply(frame.id, CommandOutcome.Accepted)
                            } else {
                                FrameToScreen.Reply(frame.id, CommandOutcome.Rejected, reason = rejection)
                            },
                        )
                    }
                }
            }
            watchingCore.cancel()
            catchingUpScreen.cancel()
        }
    } catch (failure: Throwable) {
        coreFailure = failure
        throw failure
    } finally {
        // 화면이 떠났든(줄의 끝), 명령 도중 코어가 실패했든, 코어 스코프가 취소됐든 세션은 여기서 끝낸다. 건너뛰면 claude
        // 프로세스들이 주인 없이 남는다 — 취소된 뒤에도 끝내기가 마저 돌도록 취소를 받지 않는 자리에서 부른다.
        withContext(NonCancellable) { core.endAllSessions() }
        framesToScreen.close(coreFailure)
    }
}

/** 화면이 밀렸다고 볼 한도(설계 6.5 의 v1 값). 3.1 의 실측에서 1.26 MB 한 줄 왕복이 65 ms 라, 4 MiB 가 쌓였다면 화면이 멈춘 것이다. */
const val SCREEN_BACKLOG_LIMIT_BYTES: Long = 4L * 1024 * 1024

private const val UNREADABLE_COMMAND = "unreadable_command"

/**
 * 열려 있지 않은 물음에 온 답의 거절 사유(core-ui-split-design.md 6.7). 화면이 답을 보낸 순간 물음이 이미 닫혔을 수 있다 — claude 가
 * 그 도구 호출을 거뒀거나, 세션이 끝났거나, 같은 물음에 먼저 답이 갔다. 어느 쪽인지는 가르지 않는다 — 화면이 할 일은 같다(그 답은
 * 가지 않았다).
 */
private const val QUESTION_NOT_OPEN = "question_not_open"

/** 명령을 코어에 부른다. 코어가 받지 않았으면 그 거절 사유, 받았으면 null. */
private fun ScreenCommand.applyTo(core: ScreenServingCore): String? {
    var rejection: String? = null
    when (this) {
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
        is ScreenCommand.AnswerPermission -> if (!core.answerPermissionIfStillAsked(target, toolUseId, choice)) rejection = QUESTION_NOT_OPEN
        is ScreenCommand.EndSession -> core.endSession(target)
        is ScreenCommand.AnswerGate -> if (!core.answerGateIfStillOpen(gateQuestionId, approved)) rejection = QUESTION_NOT_OPEN
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
    return rejection
}
