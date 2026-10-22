package com.kyuchestration.desktop

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
import com.kyuchestration.desktop.core.assembleInProcessScreenFacingCore
import com.kyuchestration.desktop.diagnostics.DiagnosticLog
import com.kyuchestration.desktop.diagnostics.DiagnosticLogEntry
import com.kyuchestration.desktop.diagnostics.FileDiagnosticLog
import com.kyuchestration.desktop.diagnostics.diagnosticLogFile
import com.kyuchestration.desktop.diagnostics.recordUncaughtFailuresIn
import com.kyuchestration.desktop.terminal.SessionTarget
import com.kyuchestration.desktop.theme.ThemePreference
import kotlinx.coroutines.runBlocking

/**
 * 화면이 뜨는 자리.
 *
 * 화면은 코어를 [com.kyuchestration.desktop.core.ScreenFacingCore] 하나로만 본다 — 상태 홀더도, 그 뒤의 kyu 도 모른다
 * (core-ui-split-design.md 6.2). 조립은 코어 쪽(assembleInProcessScreenFacingCore)에 있다. 이 파일에 남은 것은 화면의
 * 것뿐이다: 창, 테마, 지금 보는 세션, 파일 선택기.
 */
fun main() {
    val diagnosticLogPath = diagnosticLogFile()
    val diagnosticLog = FileDiagnosticLog(diagnosticLogPath)

    // 창을 세우기 전에 세운다. 화면을 조립하다 죽는 예외도 받아야 하기 때문이다.
    recordUncaughtFailuresIn(diagnosticLog)
    diagnosticLog.record(
        DiagnosticLogEntry.ApplicationStarted(
            versionLabel = DesktopBuildVersion.label,
            operatingSystemName = System.getProperty("os.name").orEmpty(),
            javaRuntimeVersion = System.getProperty("java.version").orEmpty(),
        ),
    )

    try {
        runDesktopApplication(diagnosticLog, diagnosticLogPath.toString())
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
 * @param diagnosticLogPathLabel 화면이 오류 자리마다 알려 줄 기록 파일의 경로. 사용자가 무엇을
 *   보고할 때 건네면 되는지가 그 자리에 있어야, 원격에서 진단할 수 있는 것이 화면 문구를 넘어선다.
 */
private fun runDesktopApplication(
    diagnosticLog: DiagnosticLog,
    diagnosticLogPathLabel: String,
) = application {
    val applicationCoroutineScope = rememberCoroutineScope()
    val screenFacingCore = remember(applicationCoroutineScope) {
        assembleInProcessScreenFacingCore(applicationCoroutineScope, diagnosticLog)
    }
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
            // 창을 닫는 것이 곧 세션을 끝내는 일이다. 앱이 그 프로세스를 보유하므로 앱과 생사를
            // 같이한다(설계 원칙 5 개정).
            //
            // 파이프에 물린 claude 는 부모가 사라진 것을 모른 채 계속 산다 — PTY 시절의 자식은
            // 적어도 SIGHUP 을 받았지만 이쪽은 그것조차 가지 않는다. 그래서 창이 닫히기 전에
            // 여기서 기다린다. 프로세스는 stdin 이 닫히면 곧 끝나고, 그러지 않는 것만 이 자리가
            // 붙잡는다(ProcessChatSession).
            runBlocking { screenFacingCore.endAllSessions() }
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
                chooseWorkDirDirectory(ownerWindow)?.let(screenFacingCore::openWorkDir)
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
