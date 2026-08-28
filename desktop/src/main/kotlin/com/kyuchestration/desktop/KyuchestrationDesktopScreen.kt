package com.kyuchestration.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kyuchestration.desktop.dashboard.WorkDirDashboardState
import com.kyuchestration.desktop.terminal.EmbeddedTerminalState
import com.kyuchestration.desktop.terminal.SessionTarget
import com.kyuchestration.desktop.workdir.WorkDirObservationFailure
import java.nio.file.Path

/**
 * 창 안의 모든 화면이 갈라지는 자리.
 *
 * 상태 홀더가 상태를 넷으로 좁혀 두었으므로 여기서 조합을 따질 것이 없다. when 이 남김없이
 * 다 덮는지도 컴파일러가 봐 준다 — 갈래가 늘면 이 파일이 컴파일되지 않는다.
 */
@Composable
fun KyuchestrationDesktopScreen(
    versionLabel: String,
    dashboardState: WorkDirDashboardState,
    terminalState: EmbeddedTerminalState,
    onOpenWorkDirRequested: () -> Unit,
    onRefreshRequested: () -> Unit,
    onCloseWorkDirRequested: () -> Unit,
    onEnterSessionRequested: (SessionTarget) -> Unit,
    onCloseTerminalRequested: () -> Unit,
) {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            when (dashboardState) {
                is WorkDirDashboardState.NoWorkDirOpened ->
                    StartScreen(versionLabel, onOpenWorkDirRequested)

                is WorkDirDashboardState.FirstObservationRunning ->
                    FirstObservationRunningScreen(dashboardState.workDirPath, onCloseWorkDirRequested)

                is WorkDirDashboardState.WorkDirObserved ->
                    DashboardWithTerminal(
                        observed = dashboardState,
                        terminalState = terminalState,
                        onRefreshRequested = onRefreshRequested,
                        onCloseWorkDirRequested = onCloseWorkDirRequested,
                        onEnterSessionRequested = onEnterSessionRequested,
                        onCloseTerminalRequested = onCloseTerminalRequested,
                    )

                is WorkDirDashboardState.FirstObservationFailed ->
                    ObservationFailureScreen(
                        workDirPath = dashboardState.workDirPath,
                        failure = dashboardState.failure,
                        onRetryRequested = onRefreshRequested,
                        onOpenAnotherWorkDirRequested = onCloseWorkDirRequested,
                    )
            }
        }
    }
}

/**
 * 목록과 터미널이 위아래로 나뉜다.
 *
 * 좌우가 아니라 위아래인 이유는 터미널이 폭을 먹기 때문이다. 세션 안의 화면은 80 칸을 기준으로
 * 그려지는데, 창을 반으로 갈라 오른쪽에 두면 그 폭이 나오지 않아 줄이 접힌다. 목록 쪽은 카드가
 * 세로로 쌓이므로 높이를 나눠 갖는 편이 덜 아쉽다.
 */
@Composable
private fun DashboardWithTerminal(
    observed: WorkDirDashboardState.WorkDirObserved,
    terminalState: EmbeddedTerminalState,
    onRefreshRequested: () -> Unit,
    onCloseWorkDirRequested: () -> Unit,
    onEnterSessionRequested: (SessionTarget) -> Unit,
    onCloseTerminalRequested: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(DASHBOARD_HEIGHT_WEIGHT)) {
            WorkDirDashboardContent(
                observed = observed,
                onRefreshRequested = onRefreshRequested,
                onCloseWorkDirRequested = onCloseWorkDirRequested,
                onEnterSessionRequested = onEnterSessionRequested,
            )
        }

        // 아무 세션에도 들어가지 않았으면 목록이 창을 다 쓴다.
        if (terminalState != EmbeddedTerminalState.NoTerminalOpen) {
            HorizontalDivider()
            EmbeddedTerminalPane(
                terminalState = terminalState,
                onCloseTerminalRequested = onCloseTerminalRequested,
                modifier = Modifier.weight(TERMINAL_HEIGHT_WEIGHT),
            )
        }
    }
}

@Composable
private fun StartScreen(versionLabel: String, onOpenWorkDirRequested: () -> Unit) {
    CenteredColumn {
        Text("뀨케스트레이션", style = MaterialTheme.typography.headlineMedium)
        Text(
            text = versionLabel,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = onOpenWorkDirRequested) { Text("워크디렉토리 열기") }
        Text(
            text = "레포가 클론된 워크디렉토리를 고르면 각 레포의 상태와 계획을 카드로 보여줍니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun FirstObservationRunningScreen(workDirPath: Path, onCancelRequested: () -> Unit) {
    CenteredColumn {
        CircularProgressIndicator(modifier = Modifier.size(32.dp))
        Text("kyu 에게 이 워크디렉토리를 묻는 중입니다", style = MaterialTheme.typography.bodyMedium)
        Text(
            text = workDirPath.toString(),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onCancelRequested) { Text("취소") }
    }
}

@Composable
private fun ObservationFailureScreen(
    workDirPath: Path,
    failure: WorkDirObservationFailure,
    onRetryRequested: () -> Unit,
    onOpenAnotherWorkDirRequested: () -> Unit,
) {
    CenteredColumn {
        Text("워크디렉토리를 읽지 못했습니다", style = MaterialTheme.typography.headlineSmall)
        Text(
            text = workDirPath.toString(),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = failure.message.orEmpty(),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        // 실패마다 사람이 할 일이 다르다. 그 문구는 실패 타입이 들고 있다.
        Text(
            text = failure.guidance,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onRetryRequested) { Text("다시 시도") }
            TextButton(onClick = onOpenAnotherWorkDirRequested) { Text("다른 워크디렉토리") }
        }
    }
}

@Composable
private fun CenteredColumn(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        content()
    }
}

/**
 * 창을 나눠 갖는 비율. 터미널 쪽을 조금 크게 둔다 — 그쪽이 사람이 실제로 일하는 자리다.
 */
private const val DASHBOARD_HEIGHT_WEIGHT = 0.45f
private const val TERMINAL_HEIGHT_WEIGHT = 0.55f
