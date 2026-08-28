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
import com.kyuchestration.desktop.kyu.planFileExistsIn
import com.kyuchestration.desktop.terminal.EmbeddedTerminalStateHolder
import com.kyuchestration.desktop.terminal.pty.PtyKyuSessionTerminalAttacher
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
    val dashboardStateHolder = remember(applicationCoroutineScope) {
        WorkDirDashboardStateHolder(
            workDirObserver = KyuCliWorkDirObserver(ProcessKyuCommandRunner()),
            planFileExists = ::planFileExistsIn,
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
            terminalState = terminalState,
            onOpenWorkDirRequested = {
                chooseWorkDirDirectory(ownerWindow)?.let(dashboardStateHolder::openWorkDir)
            },
            onRefreshRequested = dashboardStateHolder::refreshNow,
            onCloseWorkDirRequested = {
                // 워크디렉토리를 바꾸면 그 안의 세션을 보고 있을 이유가 없다. 놓지 않으면 다른
                // 워크디렉토리의 목록 아래에 이전 워크디렉토리의 터미널이 남는다.
                terminalStateHolder.closeTerminal()
                dashboardStateHolder.closeWorkDir()
            },
            onEnterSessionRequested = { target ->
                dashboardState.workDirPath?.let { terminalStateHolder.enterSession(it, target) }
            },
            onCloseTerminalRequested = terminalStateHolder::closeTerminal,
        )
    }
}
