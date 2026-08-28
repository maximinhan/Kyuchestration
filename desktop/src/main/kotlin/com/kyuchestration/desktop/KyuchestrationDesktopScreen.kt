package com.kyuchestration.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
    onOpenWorkDirRequested: () -> Unit,
    onRefreshRequested: () -> Unit,
    onCloseWorkDirRequested: () -> Unit,
) {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            when (dashboardState) {
                is WorkDirDashboardState.NoWorkDirOpened ->
                    StartScreen(versionLabel, onOpenWorkDirRequested)

                is WorkDirDashboardState.FirstObservationRunning ->
                    FirstObservationRunningScreen(dashboardState.workDirPath, onCloseWorkDirRequested)

                is WorkDirDashboardState.WorkDirObserved ->
                    WorkDirDashboardContent(dashboardState, onRefreshRequested, onCloseWorkDirRequested)

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

@Composable
private fun StartScreen(versionLabel: String, onOpenWorkDirRequested: () -> Unit) {
    CenteredColumn {
        Text("뀨케스트레이션", style = MaterialTheme.typography.headlineMedium)
        Text(
            text = versionLabel,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(0.dp))
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
