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
import com.kyuchestration.desktop.dashboard.WorkDirDashboardStateHolder
import com.kyuchestration.desktop.diagnostics.DiagnosticLog
import com.kyuchestration.desktop.diagnostics.DiagnosticLogEntry
import com.kyuchestration.desktop.diagnostics.FileDiagnosticLog
import com.kyuchestration.desktop.diagnostics.diagnosticLogFile
import com.kyuchestration.desktop.diagnostics.recordUncaughtFailuresIn
import com.kyuchestration.desktop.engine.EngineInstallationStateHolder
import com.kyuchestration.desktop.engine.bundled.placeBundledEngineWhereItCanRun
import com.kyuchestration.desktop.engine.githubrelease.GitHubReleaseEngineInstaller
import com.kyuchestration.desktop.initialization.WorkDirInitializationStateHolder
import com.kyuchestration.desktop.dashboard.WorkDirDashboardState
import com.kyuchestration.desktop.kyu.ProcessKyuCommandRunner
import com.kyuchestration.desktop.kyu.findKyuExecutable
import com.kyuchestration.desktop.kyu.managedEngineDirectory
import com.kyuchestration.desktop.kyu.planFileExistsIn
import com.kyuchestration.desktop.repoclone.RepositoryCloneStateHolder
import com.kyuchestration.desktop.repoclone.kyucli.KyuCliGitHubRepositoryCatalog
import com.kyuchestration.desktop.repoclone.kyucli.KyuCliTokenProfileRegistry
import com.kyuchestration.desktop.repoclone.kyucli.KyuCliWorkDirRepositoryCloner
import com.kyuchestration.desktop.terminal.EmbeddedTerminalStateHolder
import com.kyuchestration.desktop.terminal.SessionConversationChoice
import com.kyuchestration.desktop.terminal.SessionMode
import com.kyuchestration.desktop.terminal.chat.ChatSessionStateHolder
import com.kyuchestration.desktop.terminal.chat.ProcessChatSessionOpener
import com.kyuchestration.desktop.terminal.kyucli.KyuCliSessionCommandSource
import com.kyuchestration.desktop.terminal.pty.PtySessionTerminalOpener
import com.kyuchestration.desktop.theme.ThemePreference
import com.kyuchestration.desktop.workdir.kyucli.KyuCliWorkDirInitializer
import com.kyuchestration.desktop.workdir.kyucli.KyuCliWorkDirObserver
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * 조립이 일어나는 유일한 자리.
 *
 * 화면은 상태 홀더만, 상태 홀더는 자기 포트만 안다 — 목록은 관찰 포트를, 여는 걸음은 초기화
 * 포트를, 터미널은 진입 포트를. "그 포트 뒤에 kyu 프로세스가 있다" 는 사실을 아는 파일이 여기
 * 하나뿐이라, 엔진을 바꿔 끼울 때 고칠 곳도 여기 하나다.
 *
 * 홀더끼리 서로를 부르지 않는 것도 이 자리의 몫이다. 워크디렉토리를 바꿀 때 터미널을 놓고
 * 초기화 실패 문구를 거두는 것은 어느 한 홀더의 일이 아니라 조립하는 쪽의 결정이다.
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
    // 관찰과 초기화가 같은 실행기를 함께 쓴다. 실행기는 부를 때마다 kyu 를 새로 찾는 무상태라
    // 둘로 나눌 이유가 없다.
    val kyuCommandRunner = remember { ProcessKyuCommandRunner(diagnosticLog = diagnosticLog) }
    // 앱의 첫 갈림길. 다른 홀더보다 먼저 세우는 것이 뜻이다 — 엔진이 없으면 나머지 화면은
    // 뜨지 않는다. 세우는 것 자체가 걸음이기도 하다: 어디에도 엔진이 없으면 이 홀더가 그
    // 자리에서 곧바로 받기 시작한다.
    val engineInstallationStateHolder = remember(applicationCoroutineScope) {
        EngineInstallationStateHolder(
            // 이 두 자리도 기록을 받는다. 여기서 걸린 실행은 설치 화면에 멈춰 서고 그 뒤로
            // 엔진을 부르는 일이 하나도 없어서, 넘기지 않으면 그 실행의 기록이 "앱이 시작했다"
            // 한 줄로 끝난다 — 맥이 격리해 둔 바이너리처럼 원격으로 봐야 하는 실패가 그렇게 끝난다.
            engineInstaller = GitHubReleaseEngineInstaller(diagnosticLog = diagnosticLog),
            findEngineExecutable = ::findKyuExecutable,
            placeBundledEngine = { placeBundledEngineWhereItCanRun(diagnosticLog = diagnosticLog) },
            coroutineScope = applicationCoroutineScope,
        )
    }
    val dashboardStateHolder = remember(applicationCoroutineScope) {
        WorkDirDashboardStateHolder(
            workDirObserver = KyuCliWorkDirObserver(kyuCommandRunner),
            planFileExists = ::planFileExistsIn,
            coroutineScope = applicationCoroutineScope,
        )
    }
    val initializationStateHolder = remember(applicationCoroutineScope) {
        WorkDirInitializationStateHolder(
            workDirInitializer = KyuCliWorkDirInitializer(kyuCommandRunner),
            planFileExists = ::planFileExistsIn,
            coroutineScope = applicationCoroutineScope,
        )
    }
    val repositoryCloneStateHolder = remember(applicationCoroutineScope) {
        RepositoryCloneStateHolder(
            tokenProfileRegistry = KyuCliTokenProfileRegistry(kyuCommandRunner),
            gitHubRepositoryCatalog = KyuCliGitHubRepositoryCatalog(kyuCommandRunner),
            workDirRepositoryCloner = KyuCliWorkDirRepositoryCloner(kyuCommandRunner),
            coroutineScope = applicationCoroutineScope,
        )
    }
    val terminalStateHolder = remember(applicationCoroutineScope) {
        EmbeddedTerminalStateHolder(
            // 앱이 스스로 조립하지 않고 엔진에게 묻는다(설계 원칙 11). 그 답을 실행하는 것이
            // PTY 어댑터의 일이라, 두 어댑터가 여기서 만난다.
            sessionTerminalOpener = PtySessionTerminalOpener(KyuCliSessionCommandSource(kyuCommandRunner)),
            coroutineScope = applicationCoroutineScope,
            diagnosticLog = diagnosticLog,
        )
    }
    val chatSessionStateHolder = remember(applicationCoroutineScope) {
        ChatSessionStateHolder(
            // 챗 모드로 묻는다. 엔진이 답하는 argv 가 터미널의 것과 통째로 다르고(설계 5.2),
            // 어느 쪽을 묻는지는 이 자리가 정한다 — 화면이 정할 일이 아니다.
            sessionCommandSource = KyuCliSessionCommandSource(kyuCommandRunner, SessionMode.Chat),
            chatSessionOpener = ProcessChatSessionOpener(),
            coroutineScope = applicationCoroutineScope,
            diagnosticLog = diagnosticLog,
        )
    }
    val engineInstallationState by engineInstallationStateHolder.state.collectAsState()
    val dashboardState by dashboardStateHolder.state.collectAsState()
    val initializationState by initializationStateHolder.state.collectAsState()
    val terminalState by terminalStateHolder.state.collectAsState()
    val chatScreenState by chatSessionStateHolder.state.collectAsState()
    val heldChatSessions by chatSessionStateHolder.heldSessions.collectAsState()
    // 화면에 보이는 세션과 따로 구독한다. 카드를 옮기는 것과 세션이 늘고 주는 것은 다른 사건이라,
    // 하나로 합치면 카드를 오갈 때마다 목록 전체가 다시 그려진다.
    val heldSessions by terminalStateHolder.heldSessions.collectAsState()
    // 받아 둘 자리는 앱이 도는 동안 바뀌지 않는다. 그릴 때마다 홈 디렉토리와 os.name 을 다시
    // 읽을 이유가 없다.
    val engineDirectoryLabel = remember { managedEngineDirectory().toString() }

    // 테마만 상태 홀더 없이 여기 둔다. 다른 상태들은 포트를 부르고 그 결과를 갈래로 옮기는
    // 일이 있어 홀더가 필요하지만, 이것은 사용자가 고른 값 하나이고 옮길 갈래가 없다 —
    // 고른 것과 시스템이 답한 것을 합치는 판단만 순수 함수로 갈라 두었다(resolveDarkTheme).
    var themePreference by remember { mutableStateOf(ThemePreference.AlwaysDark) }

    // 챗이 기본이고, 원시 터미널은 사용자가 챗 머리말에서 일부러 고른 동안만 그 자리에 선다
    // (설계 7 절). 상태 홀더가 아니라 창이 드는 이유는 이것이 "사용자가 방금 무엇을 눌렀는가"
    // 이지 어느 세션이 도는가가 아니어서다.
    var conversationPaneContent by remember { mutableStateOf(ConversationPaneContent.Chat) }

    Window(
        onCloseRequest = {
            // 창을 닫는 것이 곧 세션을 끝내는 일이다. 앱이 그 프로세스를 보유하므로 앱과 생사를
            // 같이한다(설계 원칙 5 개정). JVM 이 끝나면 PTY 가 닫혀 자식에게 SIGHUP 이 가지만,
            // 그것을 무시하는 프로그램은 살아남아 어디에도 보이지 않는 고아가 된다 —
            // 그래서 정리를 프로세스 종료에 맡기지 않고 여기서 명시적으로 끝낸다.
            terminalStateHolder.endAllSessions()
            // 파이프에 물린 claude 는 부모가 사라진 것을 모른 채 계속 산다 — PTY 와 달리 SIGHUP
            // 조차 가지 않는다. 그래서 창이 닫히기 전에 여기서 기다린다. 프로세스는 stdin 이
            // 닫히면 곧 끝나고, 그러지 않는 것만 이 자리가 붙잡는다(ProcessChatSession).
            runBlocking { chatSessionStateHolder.endAllSessions() }
            exitApplication()
        },
        title = "뀨케스트레이션",
        // 레일 80 과 세션 패널 320 이 왼쪽을 먹고, 남는 폭을 터미널이 쓴다. 1100 이던 폭을
        // 키운 것이 그 400 을 메우기 위해서다 — 세션 안의 화면은 여전히 80 칸을 기준으로
        // 그려지고, 그 폭이 나오지 않으면 줄이 접힌다(설계 1.3). 1360 이면 터미널이 백 칸
        // 넘게 쓴다. 1440 까지 키우지 않은 것은 13 인치 맥의 화면이 정확히 1440 이라, 창이
        // 화면에 딱 붙어 뜨는 자리를 만들지 않기 위해서다.
        state = rememberWindowState(size = DpSize(1360.dp, 880.dp)),
    ) {
        // 대화상자의 주인 창을 넘겨 준다. 주인이 없으면 창이 화면 한가운데에 홀로 뜨고,
        // 작업 표시줄에서 본 창과 별개로 잡힌다.
        val ownerWindow = window

        KyuchestrationDesktopScreen(
            versionLabel = DesktopBuildVersion.label,
            diagnosticLogPathLabel = diagnosticLogPathLabel,
            engineInstallationState = engineInstallationState,
            engineDirectoryLabel = engineDirectoryLabel,
            dashboardState = dashboardState,
            initializationState = initializationState,
            chatScreenState = chatScreenState,
            heldChatSessionTargets = heldChatSessions.map { it.target }.toSet(),
            conversationPaneContent = conversationPaneContent,
            terminalState = terminalState,
            heldSessions = heldSessions,
            repositoryCloneStateHolder = repositoryCloneStateHolder,
            themePreference = themePreference,
            onThemePreferenceChosen = { themePreference = it },
            onRetryEngineInstallationRequested = engineInstallationStateHolder::installEngine,
            onLookForEngineAgainRequested = engineInstallationStateHolder::lookForEngineAgain,
            onOpenWorkDirRequested = {
                chooseWorkDirDirectory(ownerWindow)?.let { chosenDirectory ->
                    // 여는 일과 초기화하는 일이 홀더 둘로 나뉘어 있어 여기서 이어 붙인다. 초기화
                    // 쪽이 고른 자리를 조율할 수 있게 만들어 놓고, 그때 무엇을 "연다" 고 하는지는
                    // 대시보드 쪽이 정한다.
                    initializationStateHolder.openChosenDirectoryAsWorkDir(
                        workDirPath = chosenDirectory,
                        onWorkDirReady = dashboardStateHolder::openWorkDir,
                    )
                }
            },
            onInitializeOpenedWorkDirRequested = {
                dashboardState.workDirPath?.let(initializationStateHolder::initializeOpenedWorkDir)
            },
            onCloneRepositoriesRequested = {
                // 무엇을 이미 받아 뒀는지는 방금 관찰한 목록이 알고 있다. 대화상자가 그것을 다시
                // 알아내려면 워크디렉토리를 한 번 더 훑어야 하는데, 그 앎은 대시보드의 것이다.
                (dashboardState as? WorkDirDashboardState.WorkDirObserved)?.let { observed ->
                    repositoryCloneStateHolder.open(
                        workDirPath = observed.workDirPath,
                        alreadyClonedRepositoryNames = observed.snapshot.repos.map { it.name }.toSet(),
                    )
                }
            },
            onRefreshRequested = dashboardStateHolder::refreshNow,
            onCloseWorkDirRequested = {
                // 워크디렉토리를 바꾸면 그 안의 세션을 보고 있을 이유가 없다. 끝내지 않으면 다른
                // 워크디렉토리의 목록 아래에 이전 워크디렉토리의 세션이 남는다.
                terminalStateHolder.endAllSessions()
                applicationCoroutineScope.launch { chatSessionStateHolder.endAllSessions() }
                conversationPaneContent = ConversationPaneContent.Chat
                // 초기화 실패 문구도, 고르던 레포도 그 워크디렉토리의 것이다. 같은 이유로 여기서
                // 함께 놓는다 — 다른 워크디렉토리를 연 채로 앞 워크디렉토리에 받을 레포를 고르고
                // 있으면, 받아 온 것이 화면에 없는 자리에 떨어진다.
                initializationStateHolder.forgetLastFailure()
                repositoryCloneStateHolder.close()
                dashboardStateHolder.closeWorkDir()
            },
            onEnterSessionRequested = { target, conversationChoice ->
                dashboardState.workDirPath?.let { workDirPath ->
                    // 한 세션은 한 종류다(설계 7 절). 같은 대상의 터미널이 남아 있으면 대화 하나를
                    // 두 프로세스가 열게 되고, 엔진이 그것을 거절한다.
                    terminalStateHolder.endSessionFor(target)
                    chatSessionStateHolder.enterSession(workDirPath, target, conversationChoice)
                    conversationPaneContent = ConversationPaneContent.Chat
                }
            },
            onSendUserMessageRequested = chatSessionStateHolder::sendUserMessage,
            onInterruptTurnRequested = chatSessionStateHolder::interruptOnScreenTurn,
            onEndChatSessionRequested = {
                applicationCoroutineScope.launch { chatSessionStateHolder.endOnScreenSession() }
            },
            onOpenRawTerminalRequested = { target ->
                // 챗을 먼저 끝내고 그 프로세스가 실제로 끝난 뒤에 터미널을 연다. 순서를 뒤집으면
                // 같은 대화를 두 프로세스가 열려 하고, 엔진이 뒤엣것을 거절한다(5.6).
                applicationCoroutineScope.launch {
                    chatSessionStateHolder.endOnScreenSession()
                    dashboardState.workDirPath?.let { workDirPath ->
                        terminalStateHolder.enterSession(
                            workDirPath,
                            target,
                            SessionConversationChoice.ContinueRecordedConversation,
                        )
                        conversationPaneContent = ConversationPaneContent.RawTerminal
                    }
                }
            },
            onEndTerminalSessionRequested = {
                terminalStateHolder.endSession()
                // 터미널을 끝내면 대화 자리는 기본으로 돌아온다. 돌아갈 자리를 두지 않으면
                // 사용자가 빈 터미널 화면에 남는다.
                conversationPaneContent = ConversationPaneContent.Chat
            },
        )
    }
}
