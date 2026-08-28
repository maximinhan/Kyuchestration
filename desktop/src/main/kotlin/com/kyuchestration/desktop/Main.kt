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
import com.kyuchestration.desktop.initialization.WorkDirInitializationStateHolder
import com.kyuchestration.desktop.kyu.planFileExistsIn
import com.kyuchestration.desktop.terminal.EmbeddedTerminalStateHolder
import com.kyuchestration.desktop.terminal.pty.PtyKyuSessionTerminalAttacher
import com.kyuchestration.desktop.workdir.kyucli.KyuCliWorkDirInitializer
import com.kyuchestration.desktop.workdir.kyucli.KyuCliWorkDirObserver
import com.kyuchestration.desktop.workdir.kyucli.ProcessKyuCommandRunner

/**
 * 조립이 일어나는 유일한 자리.
 *
 * 화면은 상태 홀더만, 상태 홀더는 자기 포트만 안다 — 목록은 관찰 포트를, 터미널은 진입 포트를.
 * "그 포트 뒤에 kyu 프로세스가 있다" 는 사실을 아는 파일이 여기 하나뿐이라, 엔진을 바꿔 끼울 때
 * 고칠 곳도 여기 하나다.
 */
fun main() = application {
    val applicationCoroutineScope = rememberCoroutineScope()
    // 관찰과 초기화가 같은 실행기를 함께 쓴다. 실행기는 부를 때마다 kyu 를 새로 찾는 무상태라
    // 둘로 나눌 이유가 없다.
    val kyuCommandRunner = remember { ProcessKyuCommandRunner() }
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
            coroutineScope = applicationCoroutineScope,
        )
    }
    val terminalStateHolder = remember(applicationCoroutineScope) {
        EmbeddedTerminalStateHolder(
            sessionTerminalAttacher = PtyKyuSessionTerminalAttacher(),
            coroutineScope = applicationCoroutineScope,
        )
    }
    val dashboardState by dashboardStateHolder.state.collectAsState()
    val initializationState by initializationStateHolder.state.collectAsState()
    val terminalState by terminalStateHolder.state.collectAsState()

    Window(
        onCloseRequest = {
            // 창을 닫는 것도 세션을 놓는 일이다. JVM 이 끝나면 PTY 도 닫혀 어차피 detach 되지만,
            // 그 정리를 프로세스 종료에 맡기지 않고 여기서 뜻으로 밝힌다.
            terminalStateHolder.closeTerminal()
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
            dashboardState = dashboardState,
            initializationState = initializationState,
            terminalState = terminalState,
            onOpenWorkDirRequested = {
                chooseWorkDirDirectory(ownerWindow)?.let {
                    // 만들다 실패한 문구를 여기서 거둔다. 그대로 두면 방금 연 워크디렉토리의
                    // 배너에 다른 자리의 거절 이유가 뜬다.
                    initializationStateHolder.forgetLastFailure()
                    dashboardStateHolder.openWorkDir(it)
                }
            },
            onChooseNewWorkDirParentRequested = { chooseNewWorkDirParentDirectory(ownerWindow) },
            onCreateWorkDirRequested = { parentDirectory, newWorkDirName ->
                // 다 만들면 그 자리를 곧바로 연다. 만들어 놓고 다시 "열기" 로 찾아 들어가게 하면,
                // 앱이 시작점이 된 뜻이 반쯤만 산다.
                initializationStateHolder.createWorkDir(
                    parentDirectory = parentDirectory,
                    newWorkDirName = newWorkDirName,
                    onWorkDirCreated = dashboardStateHolder::openWorkDir,
                )
            },
            onCreateWorkDirGivenUp = initializationStateHolder::forgetLastFailure,
            onInitializeOpenedWorkDirRequested = {
                dashboardState.workDirPath?.let(initializationStateHolder::initializeExistingWorkDir)
            },
            onRefreshRequested = dashboardStateHolder::refreshNow,
            onCloseWorkDirRequested = {
                // 워크디렉토리를 바꾸면 그 안의 세션을 보고 있을 이유가 없다. 놓지 않으면 다른
                // 워크디렉토리의 목록 아래에 이전 워크디렉토리의 터미널이 남는다.
                terminalStateHolder.closeTerminal()
                // 초기화 실패 문구도 그 워크디렉토리의 것이다. 같은 이유로 여기서 함께 놓는다.
                initializationStateHolder.forgetLastFailure()
                dashboardStateHolder.closeWorkDir()
            },
            onEnterSessionRequested = { target ->
                dashboardState.workDirPath?.let { terminalStateHolder.enterSession(it, target) }
            },
            onCloseTerminalRequested = terminalStateHolder::closeTerminal,
        )
    }
}
