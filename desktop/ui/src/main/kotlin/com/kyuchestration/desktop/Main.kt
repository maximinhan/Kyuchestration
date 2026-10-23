package com.kyuchestration.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.kyuchestration.desktop.diagnostics.DiagnosticLog
import com.kyuchestration.desktop.diagnostics.DiagnosticLogEntry
import com.kyuchestration.desktop.diagnostics.DiagnosticLogOwner
import com.kyuchestration.desktop.diagnostics.FileDiagnosticLog
import com.kyuchestration.desktop.diagnostics.diagnosticLogFile
import com.kyuchestration.desktop.diagnostics.recordUncaughtFailuresIn
import com.kyuchestration.desktop.mirror.CoreChildProcess
import com.kyuchestration.desktop.mirror.CoreConnection
import com.kyuchestration.desktop.mirror.CoreMirror
import com.kyuchestration.desktop.mirror.CorePlace
import com.kyuchestration.desktop.mirror.wslDistributionAcrossScreen
import com.kyuchestration.desktop.platform.currentOperatingSystemName
import com.kyuchestration.desktop.platform.runningOnWindows
import com.kyuchestration.desktop.protocol.ScreenFacingCore
import com.kyuchestration.desktop.terminal.SessionTarget
import com.kyuchestration.desktop.theme.ThemePreference
import com.kyuchestration.desktop.wsl.ChosenWslDistributionFile
import com.kyuchestration.desktop.wsl.CoreWorkDirPath
import com.kyuchestration.desktop.wsl.WslCommandLine
import com.kyuchestration.desktop.wsl.WslCorePlacePreparation
import com.kyuchestration.desktop.wsl.WslCorePlacePreparationState
import com.kyuchestration.desktop.wsl.chosenWslDistributionFilePath
import com.kyuchestration.desktop.wsl.coreWorkDirPathOf
import com.kyuchestration.desktop.wsl.screenPathOfCorePath
import com.kyuchestration.desktop.wsl.wslExecutablePath
import java.nio.file.Path
import javax.swing.JOptionPane
import kotlin.io.path.isRegularFile
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * 화면이 뜨는 자리.
 *
 * 화면은 코어를 [ScreenFacingCore] 하나로만 본다 — 상태 홀더도, 그 뒤의 kyu 도 모른다(core-ui-split-design.md 6.2). 그
 * [ScreenFacingCore] 는 거울([CoreMirror])이고, 거울은 화면이 자식으로 띄운 코어 프로세스의 stdio 위에 선다(7 절 2 단계).
 * 같은 머신이어도 언제나 이 길이다(원칙 20) — 화면 모듈은 코어의 클래스를 컴파일 시점에 볼 수조차 없다. 이 파일에 남은 것은 화면의
 * 것뿐이다: 창, 테마, 지금 보는 세션, 파일 선택기, 윈도우에서 코어를 띄울 WSL 배포판.
 *
 * 코어를 어디서 띄우는지([CorePlace])만 OS 가 가른다. 맥 · 리눅스는 같은 머신에서, 윈도우는 사람이 고른 WSL 배포판 안에서
 * 띄운다(core-ui-split-design.md 6.12 · 6.17). 띄운 뒤는 같은 길이다.
 *
 * @param sameMachineCoreLaunchCommand 같은 머신에서 코어를 `serve --stdio` 로 띄우는 명령. 무엇으로 띄우는지는 이 프로그램을 띄운
 *   쪽(app 모듈)이 안다 — 설치 패키지에서는 앱의 실행기, 소스에서는 같은 클래스패스의 java 다. 윈도우에서는 묻지 않는다.
 */
fun runScreen(sameMachineCoreLaunchCommand: () -> List<String>) {
    val diagnosticLogPath = diagnosticLogFile(DiagnosticLogOwner.Screen)
    val diagnosticLog = FileDiagnosticLog(diagnosticLogPath)

    // 창을 세우기 전에 세운다. 화면을 조립하다 죽는 예외도 받아야 하기 때문이다.
    recordUncaughtFailuresIn(diagnosticLog)
    diagnosticLog.record(
        DiagnosticLogEntry.ApplicationStarted(
            versionLabel = DesktopBuildVersion.label,
            operatingSystemName = currentOperatingSystemName(),
            javaRuntimeVersion = System.getProperty("java.version").orEmpty(),
        ),
    )

    try {
        val corePlace = if (runningOnWindows()) {
            prepareWslCorePlace(diagnosticLog, diagnosticLogPath.toString()) ?: return
        } else {
            CorePlace.SameMachine(sameMachineCoreLaunchCommand())
        }
        val coreProcess = CoreChildProcess.start(corePlace, diagnosticLog)
        val connection = runBlocking {
            coreProcess.connectMirror(
                // 화면 스레드가 아니다. 창을 닫는 자리가 화면 스레드를 붙잡고 코어가 끝나기를 기다리는데, 그것을 알리는 틀을 읽는
                // 것이 이 스코프다.
                readingScope = CoroutineScope(Dispatchers.Default + CoroutineName("core-mirror")),
                screenVersionLabel = DesktopBuildVersion.label,
                leaveTimeout = CORE_LEAVE_TIMEOUT,
            )
        }
        when (connection) {
            is CoreConnection.Connected -> {
                val coreWslDistributionAcrossScreen = corePlace.wslDistributionAcrossScreen(connection.mirror.coreWslDistributionName)
                runDesktopApplication(
                    screenFacingCore = connection.mirror,
                    coreProcess = coreProcess,
                    coreWslDistributionAcrossScreen = coreWslDistributionAcrossScreen,
                    diagnosticLogPathLabel = diagnosticLogPathsLabel(
                        screenDiagnosticLogPath = diagnosticLogPath.toString(),
                        // 사람이 탐색기에 붙여 넣어 열 수 있는 모양으로 알린다(6.2 (다)).
                        coreDiagnosticLogPath = screenPathOfCorePath(connection.mirror.coreDiagnosticLogPath, coreWslDistributionAcrossScreen),
                    ),
                )
            }
            is CoreConnection.ProtocolMismatch -> runProtocolMismatchWindow(connection, diagnosticLogPath.toString())
        }
    } catch (failure: Throwable) {
        // Compose 의 합성에서 빠져나온 예외가 닿는 자리다. 기본 미처리 예외 핸들러는 스레드가
        // 예외로 끝날 때 도는데, 이쪽은 `application` 이 예외를 그대로 되던지므로 그 핸들러를
        // 지나지 않는다 — 두 자리를 합칠 수 없어 둘 다 둔다.
        //
        // 삼키지 않고 되던진다. 기록은 더하는 일이지, 앱이 죽어야 할 자리에서 죽지 않게 하는
        // 일이 아니다 — 여기서 삼키면 창이 사라진 채 프로세스만 남는다.
        diagnosticLog.record(DiagnosticLogEntry.UnhandledFailure(Thread.currentThread().name, failure))
        throw failure
    }
}

/**
 * 윈도우 화면이 코어를 띄울 WSL 배포판을 정한다(6.17). 사람이 창을 닫아 정하지 않고 떠나면 null.
 *
 * 준비 창을 먼저 띄우고 그 안에서 목록을 묻고 코어를 본다. 지난번에 고른 배포판이 멈춰 있으면 `wsl.exe -d … -e test` 가 VM 을
 * 깨우느라 몇 초에서 이십 초까지 걸린다 — 창 없이 그것을 기다리면 사람은 앱이 뜨지 않은 줄 안다. 정해지면 준비 창을 닫고 본 창으로
 * 넘어간다.
 */
private fun prepareWslCorePlace(diagnosticLog: DiagnosticLog, diagnosticLogPathLabel: String): CorePlace.WslDistribution? {
    val wslExecutable = wslExecutablePath()
    if (!wslExecutable.isRegularFile()) {
        application {
            Window(onCloseRequest = ::exitApplication, title = "뀨케스트레이션", state = rememberWindowState(size = DpSize(720.dp, 360.dp))) {
                WslMissingScreen(wslExecutable.toString())
            }
        }
        return null
    }
    val wslCommandLine = WslCommandLine(wslExecutable)
    val chosenDistribution = ChosenWslDistributionFile(chosenWslDistributionFilePath(), diagnosticLog)
    val preparation = WslCorePlacePreparation(
        listDistributions = wslCommandLine::listDistributions,
        checkCore = wslCommandLine::checkCore,
        rememberedDistribution = chosenDistribution::read,
        rememberDistribution = chosenDistribution::write,
    )
    // 본 창을 이어서 띄워야 하므로 이 창이 닫혀도 프로세스를 끝내지 않는다.
    application(exitProcessOnExit = false) {
        val state by preparation.state.collectAsState()
        val preparationScope = rememberCoroutineScope()
        // wsl.exe 를 기다리는 일은 WslCommandLine 이 IO 스레드에서 한다 — 창을 그리는 스레드는 막히지 않는다.
        LaunchedEffect(Unit) { preparation.lookAround() }
        LaunchedEffect(state) {
            if (state is WslCorePlacePreparationState.Ready) {
                exitApplication()
            }
        }
        Window(onCloseRequest = ::exitApplication, title = "뀨케스트레이션", state = rememberWindowState(size = DpSize(720.dp, 480.dp))) {
            WslCorePlaceScreen(
                state = state,
                diagnosticLogPathLabel = diagnosticLogPathLabel,
                onLookAroundAgainRequested = { preparationScope.launch { preparation.lookAround() } },
                onDistributionChosen = { distributionName -> preparationScope.launch { preparation.choose(distributionName) } },
            )
        }
    }
    val ready = preparation.state.value as? WslCorePlacePreparationState.Ready ?: return null
    return CorePlace.WslDistribution(wslExecutable, ready.distributionName)
}

/**
 * 윈도우 화면의 폴더 선택 창이 처음 여는 WSL 쪽 자리. 화면은 코어 사용자의 리눅스 홈을 모른다 — 그 한 칸 위다.
 */
private const val WSL_HOME_DIRECTORIES = "/home"

/**
 * 오류 자리마다 알릴 기록 파일들. 화면과 코어가 각자 적으므로(core-ui-split-design.md 6.10) 둘 다 알린다.
 */
private fun diagnosticLogPathsLabel(screenDiagnosticLogPath: String, coreDiagnosticLogPath: String): String =
    listOf(coreDiagnosticLogPath, screenDiagnosticLogPath).distinct().joinToString(" · ")

/** 코어가 다른 틀 규약을 말하면 세션을 하나도 열지 않고 두 판만 보인다(6.14). 코어는 작별하고 스스로 끝난다. */
private fun runProtocolMismatchWindow(mismatch: CoreConnection.ProtocolMismatch, diagnosticLogPathLabel: String) = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "뀨케스트레이션",
        state = rememberWindowState(size = DpSize(720.dp, 360.dp)),
    ) {
        CoreProtocolMismatchScreen(mismatch, diagnosticLogPathLabel)
    }
}

/**
 * 창을 닫을 때 코어가 모든 세션을 끝내고 나가기를 기다리는 한도.
 *
 * 코어는 세션마다 claude 가 스스로 끝나기를 최대 5 초 기다린 뒤 강제로 끝내고(ProcessChatSession), 하위부터 차례로 끝낸다. 세션
 * 여럿이 다 그 5 초를 채워도 넘지 않을 만큼 넉넉히 두되, 코어가 멈췄을 때 창이 영영 닫히지 않는 일은 없게 한다.
 */
private val CORE_LEAVE_TIMEOUT = 30.seconds

/**
 * @param coreWslDistributionAcrossScreen 화면이 윈도우이고 코어가 WSL 안이면 코어의 배포판. 화면의 경로와 코어의 경로를 옮기는
 *   근거다(coreWorkDirPathOf). 맥 · 리눅스 · WSLg 에서는 null.
 * @param diagnosticLogPathLabel 화면이 오류 자리마다 알려 줄 기록 파일의 경로. 사용자가 무엇을
 *   보고할 때 건네면 되는지가 그 자리에 있어야, 원격에서 진단할 수 있는 것이 화면 문구를 넘어선다.
 */
private fun runDesktopApplication(
    screenFacingCore: CoreMirror,
    coreProcess: CoreChildProcess,
    coreWslDistributionAcrossScreen: String?,
    diagnosticLogPathLabel: String,
) = application {
    val coreConnectionFailure by screenFacingCore.coreConnectionFailure.collectAsState()
    val engineInstallationState by screenFacingCore.engineInstallationState.collectAsState()
    val claudeAuthenticationState by screenFacingCore.claudeAuthenticationState.collectAsState()
    val dashboardState by screenFacingCore.workDirDashboardState.collectAsState()
    val initializationState by screenFacingCore.workDirInitializationState.collectAsState()
    // 화면에 보이는 세션과 따로 구독한다. 카드를 옮기는 것과 세션이 늘고 주는 것은 다른 사건이라,
    // 하나로 합치면 카드를 오갈 때마다 목록 전체가 다시 그려진다.
    val heldChatSessionTargets by screenFacingCore.heldChatSessionTargets.collectAsState()
    val sessionTree by screenFacingCore.sessionTree.collectAsState()
    val childSessionGateQuestions by screenFacingCore.childSessionGateQuestions.collectAsState()

    // 테마만 상태 홀더 없이 여기 둔다. 다른 상태들은 포트를 부르고 그 결과를 갈래로 옮기는
    // 일이 있어 홀더가 필요하지만, 이것은 사용자가 고른 값 하나이고 옮길 갈래가 없다 —
    // 고른 것과 시스템이 답한 것을 합치는 판단만 순수 함수로 갈라 두었다(resolveDarkTheme).
    var themePreference by remember { mutableStateOf(ThemePreference.AlwaysDark) }

    // 어느 세션을 보는가는 화면의 것이다(core-ui-split-design.md 6.8 (가) · 원칙 21). 대화 맵과 진입 상태는 여기서
    // 통째로 읽지 않는다 — 읽으면 보지 않는 하위의 글자 조각마다 창 전체가 다시 그려진다. ChatViewing 이 보는 세션의 몫만 꺼낸다.
    val chatConversations = screenFacingCore.chatConversations.collectAsState()
    val chatViewing = rememberChatViewing(
        chatConversations = chatConversations,
        chatSessionEntries = screenFacingCore.chatSessionEntries.collectAsState(),
        dismissSessionEntryFailure = screenFacingCore::dismissSessionEntryFailure,
    )

    Window(
        onCloseRequest = {
            // 창을 닫는 것이 곧 세션을 끝내는 일이다. 코어가 세션을 보유하고 화면이 코어를 띄웠으므로, 2~4 단계에서는 세션이
            // 화면과 생사를 같이한다(core-ui-split-design.md 6.13).
            //
            // 파이프에 물린 claude 는 부모가 사라진 것을 모른 채 계속 산다. 그래서 창이 닫히기 전에 코어가 세션을 다 끝내고 나가기를
            // 여기서 기다린다. 끝내는 것은 코어다 — 화면은 코어의 stdin 을 닫을 뿐이다. 한도 안에 나가지 않으면 강제로 끝내고 그 사실을
            // 기록한다(CoreChildProcess).
            runBlocking { coreProcess.leave(screenFacingCore, CORE_LEAVE_TIMEOUT) }
            exitApplication()
        },
        title = "뀨케스트레이션",
        // 레일 80 과 세션 패널 320 이 왼쪽을 먹고, 남는 폭을 대화 자리가 쓴다. 1440 까지 키우지
        // 않은 것은 13 인치 맥의 화면이 정확히 1440 이라, 창이 화면에 딱 붙어 뜨는 자리를
        // 만들지 않기 위해서다.
        state = rememberWindowState(size = DpSize(1360.dp, 880.dp)),
    ) {
        // 대화상자의 주인 창을 넘겨 준다. 주인이 없으면 창이 화면 한가운데에 홀로 뜨고,
        // 작업 표시줄에서 본 창과 별개로 잡힌다.
        val ownerWindow = window

        KyuchestrationDesktopScreen(
            versionLabel = DesktopBuildVersion.label,
            diagnosticLogPathLabel = diagnosticLogPathLabel,
            coreConnectionFailure = coreConnectionFailure,
            engineInstallationState = engineInstallationState,
            engineDirectoryLabel = screenFacingCore.engineDirectoryLabel,
            claudeAuthenticationState = claudeAuthenticationState,
            dashboardState = dashboardState,
            initializationState = initializationState,
            chatScreenState = chatViewing.screenState,
            heldChatSessionTargets = heldChatSessionTargets,
            screenFacingCore = screenFacingCore,
            themePreference = themePreference,
            onThemePreferenceChosen = { themePreference = it },
            onRetryEngineInstallationRequested = screenFacingCore::installEngine,
            onLookForEngineAgainRequested = screenFacingCore::lookForEngineAgain,
            onCheckClaudeCredentialsRequested = screenFacingCore::checkClaudeCredentials,
            onClaudeBrowserLoginRequested = screenFacingCore::startClaudeBrowserLogin,
            onClaudeBrowserLoginCodeSubmitted = screenFacingCore::submitClaudeBrowserLoginCode,
            onClaudeBrowserLoginCancelled = screenFacingCore::cancelClaudeBrowserLogin,
            onClaudeTokenSubmitted = screenFacingCore::useClaudeToken,
            onOpenWorkDirRequested = {
                // 고르는 창은 사람이 앉은 OS 의 것이라 화면에 남는다(원칙 19). 고른 경로만 코어에게 건넨다.
                val startingDirectory = coreWslDistributionAcrossScreen?.let { distribution ->
                    Path.of(screenPathOfCorePath(WSL_HOME_DIRECTORIES, distribution))
                }
                chooseWorkDirDirectory(ownerWindow, startingDirectory)?.let { chosenDirectory ->
                    when (val corePath = coreWorkDirPathOf(chosenDirectory.toString(), coreWslDistributionAcrossScreen)) {
                        is CoreWorkDirPath.Translated -> screenFacingCore.openWorkDir(corePath.corePath)
                        // 코어가 열 수 없는 자리는 고른 그 자리에서 알린다. 코어에 보내면 왕복 뒤에 같은 말을 듣는다.
                        is CoreWorkDirPath.Refused -> JOptionPane.showMessageDialog(
                            ownerWindow,
                            corePath.reason,
                            "이 폴더는 열 수 없습니다",
                            JOptionPane.WARNING_MESSAGE,
                        )
                    }
                }
            },
            onInitializeOpenedWorkDirRequested = screenFacingCore::initializeOpenedWorkDir,
            onCloneRepositoriesRequested = screenFacingCore::openRepositoryClone,
            onRefreshRequested = screenFacingCore::refreshWorkDir,
            // 보는 세션은 손으로 비우지 않는다. 대화가 놓이는 순간 ChatViewing 이 저절로 접는다.
            onCloseWorkDirRequested = screenFacingCore::closeWorkDir,
            onEnterSessionRequested = { target, conversationChoice ->
                // 이미 보유 중인 세션이면 코어는 아무것도 하지 않는다 — 화면만 옮긴다.
                chatViewing.moveTo(target)
                screenFacingCore.enterSession(target, conversationChoice)
            },
            onSendUserMessageRequested = { text ->
                chatViewing.viewedTarget?.let { target -> screenFacingCore.sendUserMessage(target, text) }
            },
            onInterruptTurnRequested = {
                chatViewing.viewedTarget?.let(screenFacingCore::interruptTurn)
            },
            onPermissionChoiceMade = { toolUseId, choice ->
                chatViewing.viewedTarget?.let { target -> screenFacingCore.answerPermission(target, toolUseId, choice) }
            },
            onEndChatSessionRequested = {
                chatViewing.viewedTarget?.let { target ->
                    chatViewing.moveTo(viewedTargetAfterEnding(target))
                    screenFacingCore.endSession(target)
                }
            },
            sessionTree = sessionTree,
            childSessionGateQuestions = childSessionGateQuestions,
            onChildSessionChosen = { childId ->
                // 끝난 하위도 전사가 남아 있으면 선다 — 왜 끝났는지 읽을 자리다.
                val childTarget = SessionTarget.Child(childId)
                if (childTarget in chatConversations.value) {
                    chatViewing.moveTo(childTarget)
                }
            },
            onChildSessionGateAnswered = screenFacingCore::answerGate,
        )
    }
}
