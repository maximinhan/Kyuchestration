package com.kyuchestration.desktop

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.kyuchestration.desktop.terminal.kyucli.KyuCliSessionCommandSource
import com.kyuchestration.desktop.terminal.pty.PtySessionTerminalOpener
import com.kyuchestration.desktop.workdir.kyucli.KyuCliWorkDirInitializer
import com.kyuchestration.desktop.workdir.kyucli.KyuCliWorkDirObserver

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
            engineInstaller = GitHubReleaseEngineInstaller(),
            findEngineExecutable = ::findKyuExecutable,
            placeBundledEngine = ::placeBundledEngineWhereItCanRun,
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
    val engineInstallationState by engineInstallationStateHolder.state.collectAsState()
    val dashboardState by dashboardStateHolder.state.collectAsState()
    val initializationState by initializationStateHolder.state.collectAsState()
    val terminalState by terminalStateHolder.state.collectAsState()
    // 화면에 보이는 세션과 따로 구독한다. 카드를 옮기는 것과 세션이 늘고 주는 것은 다른 사건이라,
    // 하나로 합치면 카드를 오갈 때마다 목록 전체가 다시 그려진다.
    val heldSessions by terminalStateHolder.heldSessions.collectAsState()
    // 받아 둘 자리는 앱이 도는 동안 바뀌지 않는다. 그릴 때마다 홈 디렉토리와 os.name 을 다시
    // 읽을 이유가 없다.
    val engineDirectoryLabel = remember { managedEngineDirectory().toString() }

    Window(
        onCloseRequest = {
            // 창을 닫는 것이 곧 세션을 끝내는 일이다. 앱이 그 프로세스를 보유하므로 앱과 생사를
            // 같이한다(설계 원칙 5 개정). JVM 이 끝나면 PTY 가 닫혀 자식에게 SIGHUP 이 가지만,
            // 그것을 무시하는 프로그램은 살아남아 kyu list 에도 앱에도 보이지 않는 고아가 된다 —
            // 그래서 정리를 프로세스 종료에 맡기지 않고 여기서 명시적으로 끝낸다.
            terminalStateHolder.endAllSessions()
            exitApplication()
        },
        title = "뀨케스트레이션",
        // 목록과 터미널이 위아래로 함께 뜨는 창이라 세로가 넉넉해야 한다. 터미널 쪽이 스무 줄은
        // 되어야 세션 안의 화면을 읽을 수 있다.
        state = rememberWindowState(size = DpSize(1100.dp, 860.dp)),
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
            terminalState = terminalState,
            heldSessions = heldSessions,
            repositoryCloneStateHolder = repositoryCloneStateHolder,
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
                // 워크디렉토리의 목록 아래에 이전 워크디렉토리의 터미널이 남는다.
                terminalStateHolder.endAllSessions()
                // 초기화 실패 문구도, 고르던 레포도 그 워크디렉토리의 것이다. 같은 이유로 여기서
                // 함께 놓는다 — 다른 워크디렉토리를 연 채로 앞 워크디렉토리에 받을 레포를 고르고
                // 있으면, 받아 온 것이 화면에 없는 자리에 떨어진다.
                initializationStateHolder.forgetLastFailure()
                repositoryCloneStateHolder.close()
                dashboardStateHolder.closeWorkDir()
            },
            onEnterSessionRequested = { target, conversationChoice ->
                dashboardState.workDirPath?.let { terminalStateHolder.enterSession(it, target, conversationChoice) }
            },
            onEndSessionRequested = terminalStateHolder::endSession,
        )
    }
}
