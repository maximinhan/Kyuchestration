package com.kyuchestration.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kyuchestration.desktop.dashboard.WorkDirDashboardState
import com.kyuchestration.desktop.engine.EngineInstallationState
import com.kyuchestration.desktop.initialization.WorkDirInitializationState
import com.kyuchestration.desktop.repoclone.RepositoryCloneStateHolder
import com.kyuchestration.desktop.terminal.EmbeddedTerminalState
import com.kyuchestration.desktop.terminal.HeldSession
import com.kyuchestration.desktop.terminal.SessionConversationChoice
import com.kyuchestration.desktop.terminal.SessionTarget
import com.kyuchestration.desktop.theme.KyuTheme
import com.kyuchestration.desktop.theme.ThemePreference
import com.kyuchestration.desktop.theme.resolveDarkTheme
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
    /**
     * 진단 기록이 쌓이는 파일의 경로.
     *
     * 오류가 뜨는 자리마다 이 한 줄을 함께 보여준다. 실기기에서만 나는 오류를 원격으로 볼 때
     * 사용자가 할 일이 "그 파일을 보내는 것" 하나로 정해져야, 진단이 화면 캡처 한 장에 걸리지 않는다.
     */
    diagnosticLogPathLabel: String,
    engineInstallationState: EngineInstallationState,
    engineDirectoryLabel: String,
    dashboardState: WorkDirDashboardState,
    initializationState: WorkDirInitializationState,
    terminalState: EmbeddedTerminalState,
    /**
     * 앱이 지금 보유 중인 세션 전부. 화면에 보이지 않는 것도 들어 있다.
     *
     * 엔진이 답한 목록에 얹을 앱 자신의 사실이다 — 세션을 띄우는 것이 앱뿐이라 엔진은 이것을
     * 알지 못하고, 앱이 자기 것을 스스로 아는 것 말고는 알 길이 없다(설계 문서 5.4).
     */
    heldSessions: List<HeldSession>,
    /**
     * 레포를 골라 받아 오는 대화상자가 자기 상태를 직접 구독한다. 다른 상태들처럼 위에서 받아
     * 내려보내지 않는 이유는, 그 상태를 보는 곳이 대화상자 하나뿐이기 때문이다 — 여기서
     * 구독하면 검색 칸에 한 글자 칠 때마다 목록과 터미널까지 다시 그려진다.
     */
    repositoryCloneStateHolder: RepositoryCloneStateHolder,
    /**
     * 사용자가 테마에 대해 고른 것. 앱을 띄우는 자리가 들고 있다.
     *
     * 화면이 스스로 들지 않는 이유는 이 값이 창 전체에 걸리기 때문이다 — 여기서 remember 로
     * 두면 셸이 다시 세워질 때(워크디렉토리를 바꿀 때) 함께 되돌아간다.
     */
    themePreference: ThemePreference,
    onThemePreferenceChosen: (ThemePreference) -> Unit,
    onRetryEngineInstallationRequested: () -> Unit,
    onLookForEngineAgainRequested: () -> Unit,
    onOpenWorkDirRequested: () -> Unit,
    onInitializeOpenedWorkDirRequested: () -> Unit,
    onCloneRepositoriesRequested: () -> Unit,
    onRefreshRequested: () -> Unit,
    onCloseWorkDirRequested: () -> Unit,
    onEnterSessionRequested: (SessionTarget, SessionConversationChoice) -> Unit,
    onEndSessionRequested: () -> Unit,
) {
    // 세션마다 살려 두는 터미널 위젯. 창이 사는 동안 같은 것 하나다 — 터미널 자리 안에 두면
    // 터미널을 접을 때 함께 사라지고, 그러면 그때까지 보유하던 세션들의 화면을 통째로 잃는다.
    val heldSessionTerminalWidgets = remember { HeldSessionTerminalWidgets() }

    // 보유가 끝났고 화면에도 없는 세션의 위젯은 죽은 격자다. 합성이 끝날 때마다 정리한다 —
    // 터미널을 접은 뒤에도 여기가 돌아야 마지막 위젯이 치워진다.
    val liveConnectors = heldSessions.map { it.ttyConnector } + listOfNotNull(onScreenConnector(terminalState))
    SideEffect { heldSessionTerminalWidgets.dropWidgetsOtherThan(liveConnectors.toSet()) }

    KyuTheme(darkTheme = resolveDarkTheme(themePreference, systemReportsDark = isSystemInDarkTheme())) {
        Surface(modifier = Modifier.fillMaxSize()) {
            // 엔진이 없으면 다른 화면을 열지 않는다. 워크디렉토리를 열고 초기화하고 세션에
            // 들어가는 걸음이 모두 kyu 를 부르는 일이라, 그 상태로 시작 화면을 보여주면 사용자는
            // 무엇을 눌러도 같은 실패를 만난다. 갈림길을 여기 하나 두어 그 자리를 아예 만들지 않는다.
            if (engineInstallationState != EngineInstallationState.EngineReady) {
                EngineInstallationScreen(
                    versionLabel = versionLabel,
                    engineDirectoryLabel = engineDirectoryLabel,
                    engineInstallationState = engineInstallationState,
                    onRetryEngineInstallationRequested = onRetryEngineInstallationRequested,
                    onLookForEngineAgainRequested = onLookForEngineAgainRequested,
                )
            } else {
                when (dashboardState) {
                    is WorkDirDashboardState.NoWorkDirOpened ->
                        StartScreen(
                            versionLabel = versionLabel,
                            initializationState = initializationState,
                            onOpenWorkDirRequested = onOpenWorkDirRequested,
                        )

                    is WorkDirDashboardState.FirstObservationRunning ->
                        FirstObservationRunningScreen(dashboardState.workDirPath, onCloseWorkDirRequested)

                    is WorkDirDashboardState.WorkDirObserved -> {
                        OrchestrationShell(
                            observed = dashboardState,
                            heldSessionTargets = heldSessions.map { it.target }.toSet(),
                            initializationState = initializationState,
                            terminalState = terminalState,
                            diagnosticLogPathLabel = diagnosticLogPathLabel,
                            heldSessionTerminalWidgets = heldSessionTerminalWidgets,
                            onInitializeOpenedWorkDirRequested = onInitializeOpenedWorkDirRequested,
                            onCloneRepositoriesRequested = onCloneRepositoriesRequested,
                            onRefreshRequested = onRefreshRequested,
                            onCloseWorkDirRequested = onCloseWorkDirRequested,
                            onEnterSessionRequested = onEnterSessionRequested,
                            onEndSessionRequested = onEndSessionRequested,
                            themePreference = themePreference,
                            onThemePreferenceChosen = onThemePreferenceChosen,
                        )

                        // 워크디렉토리를 연 자리에서만 뜬다. 받을 자리가 정해지지 않은 클론은 없다.
                        RepositoryCloneDialog(
                            repositoryCloneStateHolder = repositoryCloneStateHolder,
                            diagnosticLogPathLabel = diagnosticLogPathLabel,
                            mainSessionHeld = heldSessions.any { it.target == SessionTarget.Main },
                        )
                    }

                    is WorkDirDashboardState.FirstObservationFailed ->
                        ObservationFailureScreen(
                            workDirPath = dashboardState.workDirPath,
                            failure = dashboardState.failure,
                            diagnosticLogPathLabel = diagnosticLogPathLabel,
                            onRetryRequested = onRefreshRequested,
                            onOpenAnotherWorkDirRequested = onCloseWorkDirRequested,
                        )
                }
            }
        }
    }
}

/**
 * 셸이 좌우 셋으로 나뉜다 — 레일 · 세션 패널 · 대화 자리.
 *
 * **위아래였던 이유가 사라지는 중이다.** 예전 주석은 "터미널이 폭을 먹기 때문" 이라고 적었다:
 * 세션 안의 화면은 80 칸을 기준으로 그려지는데 창을 반으로 갈라 오른쪽에 두면 그 폭이 나오지
 * 않았다. 챗은 폭에 맞춰 흐르므로 그 제약이 없고(설계 6.2), 그래서 자리를 먼저 좌우로 옮긴다.
 *
 * **다만 지금 오른쪽에 있는 것은 아직 터미널이다.** 이 걸음에서 바뀌는 것은 자리이고 화면
 * 모델은 그대로다(설계 9 절 1 단계). 80 칸이 여전히 필요하므로 창 기본 폭을 함께 키웠다 —
 * 레일 80 과 패널 320 을 빼고도 터미널이 백 칸 넘게 쓴다(Main.kt 의 창 크기).
 */
@Composable
private fun OrchestrationShell(
    observed: WorkDirDashboardState.WorkDirObserved,
    heldSessionTargets: Set<SessionTarget>,
    initializationState: WorkDirInitializationState,
    terminalState: EmbeddedTerminalState,
    diagnosticLogPathLabel: String,
    heldSessionTerminalWidgets: HeldSessionTerminalWidgets,
    onInitializeOpenedWorkDirRequested: () -> Unit,
    onCloneRepositoriesRequested: () -> Unit,
    onRefreshRequested: () -> Unit,
    onCloseWorkDirRequested: () -> Unit,
    onEnterSessionRequested: (SessionTarget, SessionConversationChoice) -> Unit,
    onEndSessionRequested: () -> Unit,
    themePreference: ThemePreference,
    onThemePreferenceChosen: (ThemePreference) -> Unit,
) {
    Row(modifier = Modifier.fillMaxSize()) {
        SessionNavigationRail(
            diagnosticLogPathLabel = diagnosticLogPathLabel,
            themePreference = themePreference,
            onThemePreferenceChosen = onThemePreferenceChosen,
            onOpenAnotherWorkDirRequested = onCloseWorkDirRequested,
            onCloneRepositoriesRequested = onCloneRepositoriesRequested,
            onRefreshRequested = onRefreshRequested,
        )
        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        WorkDirSessionPanel(
            observed = observed,
            heldSessionTargets = heldSessionTargets,
            onScreenTarget = terminalState.target,
            initializationState = initializationState,
            onInitializeOpenedWorkDirRequested = onInitializeOpenedWorkDirRequested,
            onEnterSessionRequested = onEnterSessionRequested,
            modifier = Modifier.width(SESSION_PANEL_WIDTH),
        )
        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        ConversationPane(
            terminalState = terminalState,
            heldSessionTerminalWidgets = heldSessionTerminalWidgets,
            diagnosticLogPathLabel = diagnosticLogPathLabel,
            onEndSessionRequested = onEndSessionRequested,
            onEnterSessionRequested = onEnterSessionRequested,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 셸의 오른쪽 자리. 3 단계부터 여기에 대화가 들어온다.
 *
 * 지금은 세션의 터미널이 그대로 뜬다. 자리 이름을 터미널이 아니라 대화로 둔 것은, 이 자리가
 * 무엇을 위한 자리인지가 채워질 것보다 먼저 정해져 있기 때문이다 — 3 단계에서 바뀌는 것은
 * 이 함수 안이지 셸의 모양이 아니다.
 */
@Composable
private fun ConversationPane(
    terminalState: EmbeddedTerminalState,
    heldSessionTerminalWidgets: HeldSessionTerminalWidgets,
    diagnosticLogPathLabel: String,
    onEndSessionRequested: () -> Unit,
    onEnterSessionRequested: (SessionTarget, SessionConversationChoice) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (terminalState == EmbeddedTerminalState.NoTerminalOpen) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(32.dp),
            ) {
                Text("아직 연 세션이 없습니다", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "왼쪽에서 조율(main)이나 레포를 고르면 그 세션이 여기서 열립니다.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
        return
    }

    EmbeddedTerminalPane(
        terminalState = terminalState,
        heldSessionTerminalWidgets = heldSessionTerminalWidgets,
        diagnosticLogPathLabel = diagnosticLogPathLabel,
        onEndSessionRequested = onEndSessionRequested,
        // 세션에 들어가는 통로는 하나다. 끝난 화면에서 새로 시작하는 것도 왼쪽 줄을 누르는
        // 것과 같은 일이고, 다른 것은 어느 대화를 쓰라고 말하는가뿐이다.
        onStartNewConversationRequested = { target ->
            onEnterSessionRequested(target, SessionConversationChoice.StartNewConversation)
        },
        modifier = modifier,
    )
}

/**
 * 지금 화면에 떠 있는 세션의 통로. 터미널 자리가 비어 있으면 null.
 *
 * 끝난 세션도 여기 실린다 — 보유는 끝났지만 마지막 화면을 아직 보여주고 있어서, 그 위젯까지
 * 치우면 사용자가 왜 끝났는지 읽을 자리가 사라진다.
 */
private fun onScreenConnector(terminalState: EmbeddedTerminalState) = when (terminalState) {
    is EmbeddedTerminalState.SessionOnScreen -> terminalState.ttyConnector
    is EmbeddedTerminalState.SessionEndedOnScreen -> terminalState.ttyConnector
    is EmbeddedTerminalState.NoTerminalOpen,
    is EmbeddedTerminalState.SessionEntryRunning,
    is EmbeddedTerminalState.SessionEntryFailed,
    -> null
}

/**
 * 앱이 시작점이다 — 여기서 워크디렉토리를 고르는 것으로 모든 것이 시작된다.
 *
 * 고를 것이 하나다. 예전에는 "열기" 와 "새로 만들기" 가 나뉘어, 새로 시작하려면 만들 자리를
 * 고르고 이름을 또 적어야 했다 — 디렉토리를 고르는 창에서 이미 새 폴더를 만들 수 있는데도
 * 앱이 그 일을 한 번 더 물은 셈이다. 고른 자리가 곧 워크디렉토리라면 물을 것이 없다.
 */
@Composable
private fun StartScreen(
    versionLabel: String,
    initializationState: WorkDirInitializationState,
    onOpenWorkDirRequested: () -> Unit,
) {
    CenteredColumn {
        Text("뀨케스트레이션", style = MaterialTheme.typography.headlineMedium)
        Text(
            text = versionLabel,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))

        Button(
            onClick = onOpenWorkDirRequested,
            enabled = initializationState != WorkDirInitializationState.Running,
        ) {
            Text("워크디렉토리 열기")
        }

        Text(
            text = "고른 디렉토리가 곧 워크디렉토리입니다. 그 안의 레포를 카드로 보여주고, " +
                "계획 파일(.coord/plan.md)이 없으면 그 자리에 만들어 둡니다. " +
                "빈 자리에서 시작하려면 선택창의 새 폴더 버튼으로 만들어 고르세요.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        InitializationProgressOrFailure(
            initializationState = initializationState,
            // 계획 파일을 보는 것부터 kyu init 까지가 다 여는 걸음이라, 그중 어디를 지나는
            // 중인지로 문구를 가르지 않는다.
            runningLabel = "고른 자리를 워크디렉토리로 여는 중입니다",
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
    diagnosticLogPathLabel: String,
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
        DiagnosticLogPathNotice(diagnosticLogPathLabel, textAlign = TextAlign.Center)
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
 * 세션 패널의 폭.
 *
 * 고정 폭인 것이 뜻이다. 비율로 두면 창을 넓힐 때 목록도 함께 넓어지는데, 목록이 보여주는 것은
 * 이름과 칩 몇 개라 그 폭이 남아돌고 정작 넓어져야 할 대화 자리가 덜 넓어진다. 320dp 는 레포
 * 이름 대부분과 칩 둘이 한 줄에 드는 폭이다.
 */
private val SESSION_PANEL_WIDTH = 320.dp
