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
import com.kyuchestration.desktop.workdir.kyucli.KyuCliWorkDirObserver
import com.kyuchestration.desktop.workdir.kyucli.ProcessKyuCommandRunner

/**
 * 조립이 일어나는 유일한 자리.
 *
 * 화면은 상태 홀더만, 상태 홀더는 관찰 포트만 안다. "그 포트 뒤에 kyu 프로세스가 있다" 는
 * 사실을 아는 파일이 여기 하나뿐이라, 엔진을 바꿔 끼울 때 고칠 곳도 여기 하나다.
 */
fun main() = application {
    val applicationCoroutineScope = rememberCoroutineScope()
    val dashboardStateHolder = remember(applicationCoroutineScope) {
        WorkDirDashboardStateHolder(
            workDirObserver = KyuCliWorkDirObserver(ProcessKyuCommandRunner()),
            coroutineScope = applicationCoroutineScope,
        )
    }
    val dashboardState by dashboardStateHolder.state.collectAsState()

    Window(
        onCloseRequest = ::exitApplication,
        title = "뀨케스트레이션",
        state = rememberWindowState(size = DpSize(920.dp, 680.dp)),
    ) {
        // 대화상자의 주인 창을 넘겨 준다. 주인이 없으면 창이 화면 한가운데에 홀로 뜨고,
        // 작업 표시줄에서 본 창과 별개로 잡힌다.
        val ownerWindow = window

        KyuchestrationDesktopScreen(
            versionLabel = DesktopBuildVersion.label,
            dashboardState = dashboardState,
            onOpenWorkDirRequested = {
                chooseWorkDirDirectory(ownerWindow)?.let(dashboardStateHolder::openWorkDir)
            },
            onRefreshRequested = dashboardStateHolder::refreshNow,
            onCloseWorkDirRequested = dashboardStateHolder::closeWorkDir,
        )
    }
}
