package com.kyuchestration.desktop.mirror

import com.kyuchestration.desktop.claudeauth.ClaudeAuthenticationState
import com.kyuchestration.desktop.protocol.ScreenFacingCore
import com.kyuchestration.desktop.dashboard.WorkDirDashboardState
import com.kyuchestration.desktop.diagnostics.DiagnosticLog
import com.kyuchestration.desktop.diagnostics.DiagnosticLogEntry
import com.kyuchestration.desktop.engine.EngineInstallationState
import com.kyuchestration.desktop.initialization.WorkDirInitializationState
import com.kyuchestration.desktop.protocol.CommandOutcome
import com.kyuchestration.desktop.protocol.CoreStateImage
import com.kyuchestration.desktop.protocol.FailureView
import com.kyuchestration.desktop.protocol.FrameToCore
import com.kyuchestration.desktop.protocol.FrameToScreen
import com.kyuchestration.desktop.protocol.SCREEN_PROTOCOL_VERSION
import com.kyuchestration.desktop.protocol.ScreenChange
import com.kyuchestration.desktop.protocol.ScreenCommand
import com.kyuchestration.desktop.protocol.decodeFrameToScreen
import com.kyuchestration.desktop.protocol.encodeFrameToCore
import com.kyuchestration.desktop.protocol.operatingSystemWordOf
import com.kyuchestration.desktop.repoclone.RepositoryCloneState
import com.kyuchestration.desktop.terminal.PersonOpenedSessionTarget
import com.kyuchestration.desktop.terminal.SessionConversationChoice
import com.kyuchestration.desktop.terminal.SessionTarget
import com.kyuchestration.desktop.terminal.chat.ChatConversation
import com.kyuchestration.desktop.terminal.chat.ChildSessionGateQuestion
import com.kyuchestration.desktop.terminal.chat.PermissionCardChoice
import com.kyuchestration.desktop.terminal.chat.SessionEntryState
import com.kyuchestration.desktop.terminal.chat.SessionTree
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 화면 쪽에서 코어를 비추는 거울(core-ui-split-design.md 6.5). 코어가 보낸 틀로 `StateFlow` 열 개를 다시 세우고, 명령은 틀로
 * 보낸다 — 화면은 코어 프로세스 안의 구현(InProcessScreenFacingCore)과 같은 [ScreenFacingCore] 를 본다.
 *
 * **`seq` 가 하나라도 건너뛰면 다시 인사한다**(6.4). 건너뛴 변화를 모른 채 다음 변화를 얹으면 거울이 코어와 조용히 어긋난다.
 * 다시 받은 스냅샷의 마지막 조각이 올 때까지는 거울을 그대로 둔다 — 반쯤 온 스냅샷을 그리면 대화가 잘려 보인다(6.6).
 *
 * **진입을 보낸 세션은 답이 올 때까지 띄우는 중으로 센다.** 화면은 진입을 보내자마자 그 세션을 본다(ChatViewing). 같은
 * JVM 의 코어는 진입 상태를 그 호출 안에서 세웠지만, 거울에는 그 변화가 한 박자 뒤에 온다. 그 사이 세션이 대화에도 진입
 * 상태에도 없으면 보는 세션이 null 로 접히고 사람이 고른 것까지 놓친다. 코어는 명령이 남긴 변화를 답보다 먼저 보내므로
 * (serveScreen), 답이 온 뒤에는 코어가 보낸 진입 상태가 그대로 사실이다. 이미 대화가 선 세션(코어가 아무것도 하지 않는다)과
 * 코어가 진입 상태를 이미 보낸 세션은 짐작하지 않는다.
 *
 * 거울의 상태(받은 상태 · 다음 `seq` · 모으는 중인 스냅샷)는 틀을 읽는 코루틴 하나만 바꾼다. 명령은 화면 스레드에서 오고,
 * 그쪽이 바꾸는 것은 답을 기다리는 명령의 표뿐이다. 진입 상태 흐름은 두 쪽이 함께 다시 짓는 자리라 자물쇠 하나로 묶는다.
 */
class CoreMirror private constructor(
    private val framesToCore: SendChannel<String>,
    initialImage: CoreStateImage,
    override val engineDirectoryLabel: String,
    /** 코어가 진단을 적는 파일(인사 답). 화면의 기록과 다른 프로세스의 파일이라 오류 자리에서 함께 알린다. */
    val coreDiagnosticLogPath: String,
    /** 코어가 WSL 안에서 돌면 그 배포판(인사 답). 윈도우 화면이 자기 경로와 코어 경로를 옮기는 근거다(6.9). */
    val coreWslDistributionName: String?,
    private val screenVersionLabel: String,
    private val screenOperatingSystem: String?,
    private val diagnosticLog: DiagnosticLog,
) : ScreenFacingCore {

    @Volatile
    private var image: CoreStateImage = initialImage

    private val mutableEngineInstallationState = MutableStateFlow(initialImage.engineInstallation)
    private val mutableClaudeAuthenticationState = MutableStateFlow(initialImage.claudeAuthentication)
    private val mutableWorkDirDashboardState = MutableStateFlow(initialImage.workDirDashboard)
    private val mutableWorkDirInitializationState = MutableStateFlow(initialImage.workDirInitialization)
    private val mutableChatConversations = MutableStateFlow(initialImage.conversations)
    private val mutableChatSessionEntries = MutableStateFlow(initialImage.sessionEntries)
    private val mutableHeldChatSessionTargets = MutableStateFlow(initialImage.heldSessions)
    private val mutableSessionTree = MutableStateFlow(initialImage.sessionTree)
    private val mutableChildSessionGateQuestions = MutableStateFlow(initialImage.gateQuestions)
    private val mutableRepositoryCloneState = MutableStateFlow(initialImage.repositoryClone)

    override val engineInstallationState: StateFlow<EngineInstallationState> = mutableEngineInstallationState.asStateFlow()
    override val claudeAuthenticationState: StateFlow<ClaudeAuthenticationState> = mutableClaudeAuthenticationState.asStateFlow()
    override val workDirDashboardState: StateFlow<WorkDirDashboardState> = mutableWorkDirDashboardState.asStateFlow()
    override val workDirInitializationState: StateFlow<WorkDirInitializationState> = mutableWorkDirInitializationState.asStateFlow()
    override val chatConversations: StateFlow<Map<SessionTarget, ChatConversation>> = mutableChatConversations.asStateFlow()
    override val chatSessionEntries: StateFlow<Map<PersonOpenedSessionTarget, SessionEntryState>> =
        mutableChatSessionEntries.asStateFlow()
    override val heldChatSessionTargets: StateFlow<Set<SessionTarget>> = mutableHeldChatSessionTargets.asStateFlow()
    override val sessionTree: StateFlow<SessionTree> = mutableSessionTree.asStateFlow()
    override val childSessionGateQuestions: StateFlow<List<ChildSessionGateQuestion>> = mutableChildSessionGateQuestions.asStateFlow()
    override val repositoryCloneState: StateFlow<RepositoryCloneState> = mutableRepositoryCloneState.asStateFlow()

    private var nextChangeSequence: Long = 0

    /** 모으는 중인 스냅샷 — 다시 인사했거나 코어가 밀린 화면을 다시 맞추는 중이다. 아니면 null 이다. */
    private var snapshotBeingAssembled: SnapshotAssembly? = null

    private val lastCommandNumber = AtomicLong(0)

    private val commandsAwaitingReply = ConcurrentHashMap<String, ScreenCommand>()

    private val coreClosedItsFrames = CompletableDeferred<Unit>()

    /** 화면이 떠나려고 코어로 가는 줄을 닫았다. 그 뒤의 줄 끝은 기다리던 끝이지 깨진 연결이 아니다. */
    @Volatile
    private var screenIsLeaving = false

    private val mutableCoreConnectionFailure = MutableStateFlow<FailureView?>(null)

    /**
     * 코어와의 연결이 깨졌으면 그 사실. 코어가 뜻밖에 줄을 닫았거나, 거울이 코어의 틀을 읽다 멈췄다.
     *
     * [ScreenFacingCore] 가 아니라 거울의 것이다 — 같은 JVM 의 코어에는 끊길 연결이 없다. 깨진 뒤의 다른 흐름들은 마지막으로
     * 받은 상태에 멈춰 있으므로, 화면은 이것이 서면 그 상태 대신 이것을 보여야 한다.
     */
    val coreConnectionFailure: StateFlow<FailureView?> = mutableCoreConnectionFailure.asStateFlow()

    private val sessionEntriesShownLock = Any()

    private fun startReading(framesFromCore: ReceiveChannel<String>, readingScope: CoroutineScope) {
        val reading = readingScope.launch {
            for (line in framesFromCore) {
                receive(decodeFrameToScreen(line))
            }
        }
        // 읽기가 어떻게 끝나든(줄의 끝 · 읽지 못할 틀 · 취소) 여기를 지난다. 예외로 끝나 이 끝을 알리지 않으면, 창을 닫는 자리가
        // 화면 스레드를 붙잡은 채 [endAllSessions] 에서 영원히 기다린다. 예외 자체는 읽기 스코프의 미처리 예외로 진단 기록에 간다.
        reading.invokeOnCompletion { readingFailure ->
            if (readingFailure != null || !screenIsLeaving) {
                mutableCoreConnectionFailure.value = CORE_CONNECTION_LOST
            }
            coreClosedItsFrames.complete(Unit)
        }
    }

    private fun receive(frame: FrameToScreen) {
        when (frame) {
            // 인사 답은 처음 붙을 때만 뜻이 있다. 다시 인사해 받은 것은 같은 코어의 같은 자리다.
            is FrameToScreen.Welcome -> Unit
            // 작별 뒤에는 코어가 줄을 닫는다. 연결이 끊긴 것을 화면에 세우는 일은 그 줄의 끝(startReading)이 맡는다.
            is FrameToScreen.Bye -> Unit
            // 다시 인사하지 않았는데 온 스냅샷은 코어가 밀린 화면을 다시 맞추는 것이다(설계 6.5) — 그사이 버린 변화를 이것이 담는다.
            is FrameToScreen.Snapshot -> {
                val assembly = snapshotBeingAssembled ?: SnapshotAssembly().also { snapshotBeingAssembled = it }
                assembly.add(frame)?.let { assembled ->
                    snapshotBeingAssembled = null
                    nextChangeSequence = frame.seq + 1
                    show(assembled)
                }
            }
            is FrameToScreen.Change -> receiveChange(frame)
            is FrameToScreen.Reply -> receiveReply(frame)
        }
    }

    private fun receiveChange(frame: FrameToScreen.Change) {
        if (snapshotBeingAssembled != null) {
            // 다시 인사하기 전에 코어가 이미 낸 변화다. 곧 올 스냅샷이 그것까지 담는다.
            return
        }
        if (frame.seq != nextChangeSequence) {
            snapshotBeingAssembled = SnapshotAssembly()
            sendFrame(FrameToCore.Hello(protocol = SCREEN_PROTOCOL_VERSION, screenVersion = screenVersionLabel, screenOs = screenOperatingSystem))
            return
        }
        nextChangeSequence = frame.seq + 1
        show(image.after(frame.change))
    }

    private fun receiveReply(reply: FrameToScreen.Reply) {
        val command = commandsAwaitingReply.remove(reply.id) ?: return
        if (command is ScreenCommand.EnterSession) {
            showSessionEntries()
        }
        if (reply.outcome == CommandOutcome.Rejected) {
            diagnosticLog.record(
                DiagnosticLogEntry.CoreRejectedScreenCommand(
                    commandKind = command::class.simpleName.orEmpty(),
                    reason = reply.reason.orEmpty(),
                ),
            )
        }
    }

    private fun show(nextImage: CoreStateImage) {
        image = nextImage
        mutableEngineInstallationState.value = nextImage.engineInstallation
        mutableClaudeAuthenticationState.value = nextImage.claudeAuthentication
        mutableWorkDirDashboardState.value = nextImage.workDirDashboard
        mutableWorkDirInitializationState.value = nextImage.workDirInitialization
        mutableChatConversations.value = nextImage.conversations
        showSessionEntries()
        mutableHeldChatSessionTargets.value = nextImage.heldSessions
        mutableSessionTree.value = nextImage.sessionTree
        mutableChildSessionGateQuestions.value = nextImage.gateQuestions
        mutableRepositoryCloneState.value = nextImage.repositoryClone
    }

    private fun showSessionEntries() {
        synchronized(sessionEntriesShownLock) {
            val shownImage = image
            val sessionsBeingEntered = commandsAwaitingReply.values
                .filterIsInstance<ScreenCommand.EnterSession>()
                .map { it.target }
                .filter { it !in shownImage.sessionEntries && it !in shownImage.conversations }
            mutableChatSessionEntries.value = shownImage.sessionEntries + sessionsBeingEntered.associateWith { SessionEntryState.Running }
        }
    }

    private fun send(command: ScreenCommand) {
        val commandId = "c-${lastCommandNumber.incrementAndGet()}"
        commandsAwaitingReply[commandId] = command
        if (command is ScreenCommand.EnterSession) {
            showSessionEntries()
        }
        sendFrame(FrameToCore.Command(commandId, command))
    }

    private fun sendFrame(frame: FrameToCore) {
        // 코어로 가는 줄이 닫혔다는 것은 화면이 모든 세션을 끝내고 떠나는 중이라는 뜻이다. 그 뒤에 눌린 것은 받을 코어가 없다.
        framesToCore.trySend(encodeFrameToCore(frame))
    }

    override fun installEngine() = send(ScreenCommand.InstallEngine)

    override fun lookForEngineAgain() = send(ScreenCommand.LookForEngineAgain)

    override fun checkClaudeCredentials() = send(ScreenCommand.CheckClaudeCredentials)

    override fun startClaudeBrowserLogin() = send(ScreenCommand.StartClaudeBrowserLogin)

    override fun submitClaudeBrowserLoginCode(code: String) = send(ScreenCommand.SubmitClaudeBrowserLoginCode(code))

    override fun cancelClaudeBrowserLogin() = send(ScreenCommand.CancelClaudeBrowserLogin)

    override fun useClaudeToken(token: String) = send(ScreenCommand.UseClaudeToken(token))

    override fun openWorkDir(hostPath: String) = send(ScreenCommand.OpenWorkDir(hostPath))

    override fun initializeOpenedWorkDir() = send(ScreenCommand.InitializeOpenedWorkDir)

    override fun refreshWorkDir() = send(ScreenCommand.RefreshWorkDir)

    override fun closeWorkDir() = send(ScreenCommand.CloseWorkDir)

    override fun enterSession(target: PersonOpenedSessionTarget, conversationChoice: SessionConversationChoice) =
        send(ScreenCommand.EnterSession(target, conversationChoice))

    override fun dismissSessionEntryFailure(target: PersonOpenedSessionTarget) =
        send(ScreenCommand.DismissSessionEntryFailure(target))

    override fun sendUserMessage(target: SessionTarget, text: String) = send(ScreenCommand.SendUserMessage(target, text))

    override fun interruptTurn(target: SessionTarget) = send(ScreenCommand.InterruptTurn(target))

    override fun answerPermission(target: SessionTarget, toolUseId: String, choice: PermissionCardChoice) =
        send(ScreenCommand.AnswerPermission(target, toolUseId, choice))

    override fun endSession(target: SessionTarget) = send(ScreenCommand.EndSession(target))

    override fun answerGate(gateQuestionId: String, approved: Boolean) = send(ScreenCommand.AnswerGate(gateQuestionId, approved))

    override fun openRepositoryClone() = send(ScreenCommand.OpenRepositoryClone)

    override fun closeRepositoryClone() = send(ScreenCommand.CloseRepositoryClone)

    override fun chooseTokenProfile(profileName: String) = send(ScreenCommand.ChooseTokenProfile(profileName))

    override fun startRemovingTokenProfile(profileName: String) = send(ScreenCommand.StartRemovingTokenProfile(profileName))

    override fun stopRemovingTokenProfile() = send(ScreenCommand.StopRemovingTokenProfile)

    override fun removeChosenTokenProfile() = send(ScreenCommand.RemoveChosenTokenProfile)

    override fun startRegisteringTokenProfile() = send(ScreenCommand.StartRegisteringTokenProfile)

    override fun stopRegisteringTokenProfile() = send(ScreenCommand.StopRegisteringTokenProfile)

    override fun registerTokenProfile(profileName: String, token: String) =
        send(ScreenCommand.RegisterTokenProfile(profileName, token))

    override fun continueWithRegisteredTokenProfile() = send(ScreenCommand.ContinueWithRegisteredTokenProfile)

    override fun chooseRepositoryOwner(ownerLogin: String) = send(ScreenCommand.ChooseRepositoryOwner(ownerLogin))

    override fun changeRepositorySearchText(searchText: String) = send(ScreenCommand.ChangeRepositorySearchText(searchText))

    override fun toggleRepositorySelection(repositoryFullName: String) =
        send(ScreenCommand.ToggleRepositorySelection(repositoryFullName))

    override fun cloneSelectedRepositories() = send(ScreenCommand.CloneSelectedRepositories)

    override fun retryFailedCloneStep() = send(ScreenCommand.RetryFailedCloneStep)

    /**
     * 코어로 가는 줄을 닫고, 코어가 모든 세션을 끝내고 자기 줄을 닫을 때까지 기다린다.
     *
     * 명령이 아니라 줄을 닫는 것으로 맡기는 이유는 코어가 갈라진 뒤의 모양과 같게 하려는 것이다 — 화면이 크래시해도 코어는
     * stdin 의 EOF 를 받는다(설계 6.13 의 2~4 단계). 끝내라는 명령이 따로 있으면 크래시한 화면은 그 명령을 보내지 못한다.
     */
    override suspend fun endAllSessions() {
        screenIsLeaving = true
        framesToCore.close()
        coreClosedItsFrames.await()
    }

    companion object {

        /**
         * 코어에 인사하고 인사 답과 스냅샷을 받아 거울을 세운다. 거울은 첫 스냅샷이 다 온 뒤에야 생긴다 — `StateFlow` 는 첫 값을
         * 들고 태어나야 하고, 반쯤 온 스냅샷을 첫 값으로 삼을 수는 없다.
         *
         * 코어의 틀 규약 판이 이 화면과 다르면 거울을 세우지 않고 두 판을 돌려준다(6.14). 그 코어의 틀을 이 화면의 뜻으로 읽으면
         * 뜻이 바뀐 필드를 다르게 그린다. 그때 코어로 가는 줄은 닫는다 — 코어는 작별하고 스스로 끝난다.
         *
         * @param readingScope 틀을 읽는 코루틴이 사는 자리. 화면 스레드가 아니어야 한다 — 창을 닫는 자리가 화면 스레드를 붙잡고
         *   [endAllSessions] 를 기다리는데, 그 기다림을 푸는 것이 이 코루틴이다.
         * @param screenVersionLabel 이 화면의 판. 인사에 실어 코어의 기록에 남고, 판이 갈렸을 때 사람에게 보인다.
         * @param screenOperatingSystem 이 화면이 도는 OS 의 낱말. 인사에 실어 코어의 기록에 남는다.
         */
        suspend fun connect(
            framesFromCore: ReceiveChannel<String>,
            framesToCore: SendChannel<String>,
            readingScope: CoroutineScope,
            diagnosticLog: DiagnosticLog,
            screenVersionLabel: String,
            screenOperatingSystem: String? = operatingSystemWordOf(System.getProperty("os.name").orEmpty()),
        ): CoreConnection {
            framesToCore.send(
                encodeFrameToCore(
                    FrameToCore.Hello(protocol = SCREEN_PROTOCOL_VERSION, screenVersion = screenVersionLabel, screenOs = screenOperatingSystem),
                ),
            )
            val welcome = decodeFrameToScreen(framesFromCore.receive()) as? FrameToScreen.Welcome
                ?: error("코어가 인사에 welcome 이 아닌 틀로 답했다")
            diagnosticLog.record(DiagnosticLogEntry.CoreGreeted(welcome.coreVersion, welcome.coreOs, welcome.wslDistro))
            if (welcome.protocol != SCREEN_PROTOCOL_VERSION) {
                framesToCore.close()
                return CoreConnection.ProtocolMismatch(
                    screenVersion = screenVersionLabel,
                    screenProtocol = SCREEN_PROTOCOL_VERSION,
                    coreVersion = welcome.coreVersion,
                    coreProtocol = welcome.protocol,
                )
            }
            val assembly = SnapshotAssembly()
            var assembled: CoreStateImage? = null
            var snapshotSequence = 0L
            while (assembled == null) {
                val snapshot = decodeFrameToScreen(framesFromCore.receive()) as? FrameToScreen.Snapshot
                    ?: error("코어가 첫 스냅샷이 끝나기 전에 스냅샷이 아닌 틀을 보냈다")
                snapshotSequence = snapshot.seq
                assembled = assembly.add(snapshot)
            }
            val mirror = CoreMirror(
                framesToCore = framesToCore,
                initialImage = assembled,
                engineDirectoryLabel = welcome.engineDirectory,
                coreDiagnosticLogPath = welcome.coreDiagnosticLog,
                coreWslDistributionName = welcome.wslDistro,
                screenVersionLabel = screenVersionLabel,
                screenOperatingSystem = screenOperatingSystem,
                diagnosticLog = diagnosticLog,
            ).apply {
                nextChangeSequence = snapshotSequence + 1
                startReading(framesFromCore, readingScope)
            }
            return CoreConnection.Connected(mirror)
        }
    }
}

private val CORE_CONNECTION_LOST = FailureView(
    kind = "CoreMirror.ConnectionLost",
    message = "코어와의 연결이 끊겼습니다.",
    guidance = "세션을 쥔 코어가 멈췄거나 이 화면이 코어의 말을 읽지 못했습니다. 지금 보이는 것은 끊기기 직전의 모습입니다. " +
        "진단 기록을 확인한 뒤 앱을 다시 띄우세요.",
)

/** 조각으로 오는 스냅샷 하나를 모은다. 첫 조각은 작은 상태 전부여야 한다 — 거울의 새 기준이 거기서 선다. */
private class SnapshotAssembly {

    private var image: CoreStateImage? = null

    /** 마지막 조각이면 모은 상태를, 아니면 null 을 돌려준다. */
    fun add(snapshot: FrameToScreen.Snapshot): CoreStateImage? {
        val part = snapshot.part
        val nextImage = image?.after(part)
            ?: CoreStateImage.of(
                (part as? ScreenChange.SmallStatesReplaced)?.states
                    ?: error("스냅샷의 첫 조각이 작은 상태가 아니다: ${part::class.simpleName}"),
            )
        image = nextImage
        return nextImage.takeIf { snapshot.last }
    }
}
