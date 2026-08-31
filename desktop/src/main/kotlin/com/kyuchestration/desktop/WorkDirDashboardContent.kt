package com.kyuchestration.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.kyuchestration.desktop.dashboard.CardSessionFacts
import com.kyuchestration.desktop.dashboard.WorkDirDashboardState
import com.kyuchestration.desktop.dashboard.mergeRepoCardSessionFacts
import com.kyuchestration.desktop.initialization.WorkDirInitializationState
import com.kyuchestration.desktop.terminal.SessionTarget
import com.kyuchestration.desktop.workdir.RepoSnapshot
import com.kyuchestration.desktop.workdir.RepoState
import com.kyuchestration.desktop.workdir.RepoTask
import com.kyuchestration.desktop.workdir.WorkDirSnapshot

@Composable
fun WorkDirDashboardContent(
    observed: WorkDirDashboardState.WorkDirObserved,
    /**
     * 앱이 지금 보유 중인 세션의 대상. 엔진이 답한 목록 위에 얹는 앱 자신의 사실이다.
     *
     * 통로가 아니라 대상만 받는다. 목록이 세션을 읽거나 쓸 일은 없고, 통로를 넘기면 카드를
     * 그리는 자리가 세션을 끝낼 수도 있는 자리가 된다.
     */
    heldSessionTargets: Set<SessionTarget>,
    initializationState: WorkDirInitializationState,
    onInitializeOpenedWorkDirRequested: () -> Unit,
    onCloneRepositoriesRequested: () -> Unit,
    onRefreshRequested: () -> Unit,
    onCloseWorkDirRequested: () -> Unit,
    onEnterSessionRequested: (SessionTarget) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        WorkDirHeader(
            snapshot = observed.snapshot,
            appSessionCount = heldSessionTargets.size,
            onCloneRepositoriesRequested = onCloneRepositoriesRequested,
            onRefreshRequested = onRefreshRequested,
            onCloseWorkDirRequested = onCloseWorkDirRequested,
        )

        if (!observed.planFilePresent) {
            PlanFileMissingBanner(initializationState, onInitializeOpenedWorkDirRequested)
        }

        observed.lastRefreshFailure?.let { failure ->
            // 목록은 그대로 두고 이 줄만 덧붙인다. 아래 카드들은 실패 직전에 본 것이라는 사실을
            // 여기서 밝혀 두면, 사용자는 오래된 값을 최신으로 착각하지 않는다.
            NoticeBanner(
                accentColor = REFRESH_FAILURE_COLOR,
                title = "갱신하지 못했습니다 — 아래 목록은 마지막으로 읽은 상태입니다",
                lines = listOfNotNull(failure.message, failure.guidance),
            )
        }

        if (observed.snapshot.planWarnings.isNotEmpty()) {
            NoticeBanner(
                accentColor = PLAN_WARNING_COLOR,
                title = "계획을 그대로 쓰지 못했습니다",
                lines = observed.snapshot.planWarnings,
            )
        }

        if (heldSessionTargets.isNotEmpty()) {
            AppSessionBanner(heldSessionTargets.size)
        }

        RepoCardList(observed.snapshot, heldSessionTargets, onEnterSessionRequested)
    }
}

/**
 * 계획이 아직 없다는 안내. 막는 것이 아니라 알리는 것이다.
 *
 * 계획이 없어도 카드와 세션은 그대로 쓸 수 있다 — kyu list 는 계획 없이도 답한다. 그래서 이
 * 자리는 화면을 가로막는 벽이 아니라 목록 위에 얹히는 한 줄이고, 초기화 버튼은 그 한 줄에서
 * 곧바로 다음 걸음을 밟게 해 주는 것뿐이다.
 *
 * 여는 걸음이 이미 초기화까지 하므로, 열자마자 이 배너를 보는 일은 없다. 그래도 남겨 두는 것은
 * 앱이 도는 동안 밖에서 .coord 가 지워지는 길이 있기 때문이다 — 3 초마다 도는 관찰이 그 사실을
 * 실어 오고, 그때 워크디렉토리를 닫았다 다시 고르지 않고 계획을 되돌릴 길은 여기뿐이다.
 */
@Composable
private fun PlanFileMissingBanner(
    initializationState: WorkDirInitializationState,
    onInitializeOpenedWorkDirRequested: () -> Unit,
) {
    NoticeBanner(
        accentColor = PLAN_MISSING_COLOR,
        title = "계획 파일이 없습니다",
        lines = listOf(
            "이 워크디렉토리에는 아직 .coord/plan.md 가 없습니다. 카드와 세션은 그대로 쓸 수 있고, " +
                "계획을 두면 카드마다 지금 볼 작업이 함께 뜹니다.",
        ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = onInitializeOpenedWorkDirRequested,
                enabled = initializationState != WorkDirInitializationState.Running,
            ) {
                Text("초기화")
            }
            Spacer(Modifier.width(12.dp))
            InitializationProgressOrFailure(
                initializationState = initializationState,
                runningLabel = "kyu init 으로 계획 파일을 만드는 중입니다",
            )
        }
    }
}

/**
 * 앱 세션이 CLI 와 갈린다는 사실을 화면이 먼저 말하는 자리(설계 원칙 12).
 *
 * 감추면 사용자는 터미널에서 `kyu list` 를 쳤을 때, 또는 `kyu kill` 이 듣지 않을 때 그것을 알게
 * 된다. 그때는 이미 "앱이 고장났다" 로 읽힌다.
 *
 * 보유한 세션이 하나라도 있을 때만 뜬다. 없는 사람에게는 아직 일어나지 않은 일이다.
 */
@Composable
private fun AppSessionBanner(appSessionCount: Int) {
    NoticeBanner(
        accentColor = APP_SESSION_COLOR,
        title = "앱 세션 ${appSessionCount}개는 이 앱 안에서만 삽니다",
        lines = listOf(
            "터미널에서 kyu list 를 쳐도 이 세션들은 보이지 않고, kyu kill 로 끝낼 수 없습니다. " +
                "끝내는 길은 터미널의 세션 끝내기 버튼이나 앱 종료입니다.",
            "앱을 끄면 함께 끝납니다. 잃는 것은 진행 중이던 턴이고, 다음에 카드를 누르면 " +
                "그 대화를 이어서 새 세션을 엽니다.",
        ),
    )
}

@Composable
private fun WorkDirHeader(
    snapshot: WorkDirSnapshot,
    appSessionCount: Int,
    onCloneRepositoriesRequested: () -> Unit,
    onRefreshRequested: () -> Unit,
    onCloseWorkDirRequested: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(snapshot.name, style = MaterialTheme.typography.headlineSmall)
            Text(
                text = snapshot.absolutePath,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // 두 수를 갈라 센다. 합쳐 놓으면 "세션 3 개" 중 어느 것을 이 앱이 끝낼 수 있는지
            // 화면이 말하지 않게 된다.
            Text(
                text = "레포 ${snapshot.repos.size}개 · 앱 세션 ${appSessionCount}개 · " +
                    "CLI 세션 ${snapshot.cliSessionCount}개",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.weight(1f))

        // 워크디렉토리를 채우는 일이라 목록 위가 그 자리다. 카드가 없는 워크디렉토리에서는 이
        // 버튼이 유일한 다음 걸음이고, 카드가 있어도 레포를 더 넣는 일은 늘 여기서 시작한다.
        Button(onClick = onCloneRepositoriesRequested) { Text("레포 클론") }
        Spacer(Modifier.width(8.dp))

        // 화면은 3 초마다 저절로 갱신되지만 버튼을 둔다. 방금 세션을 띄운 사람은 그 결과를
        // 곧바로 보고 싶어 하고, 다음 주기까지 기다리는 동안 앱이 멈춘 것처럼 보인다.
        OutlinedButton(onClick = onRefreshRequested) { Text("새로고침") }
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = onCloseWorkDirRequested) { Text("다른 워크디렉토리") }
    }
}

@Composable
private fun RepoCardList(
    snapshot: WorkDirSnapshot,
    heldSessionTargets: Set<SessionTarget>,
    onEnterSessionRequested: (SessionTarget) -> Unit,
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (snapshot.repos.isEmpty()) {
            item {
                Text(
                    text = "이 워크디렉토리 바로 아래에 클론된 레포가 없습니다. 위의 레포 클론으로 받아 오세요.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // 절대경로를 키로 쓴다. 이름은 워크디렉토리 안에서만 유일하고, 경로가 그 레포의 진짜
        // 신원이다(설계 문서 11 의 4번).
        items(snapshot.repos, key = { it.absolutePath }) { repo ->
            RepoCard(
                repo = repo,
                sessionFacts = mergeRepoCardSessionFacts(
                    engineReportedState = repo.state,
                    appSessionHeld = SessionTarget.Repo(repo.name) in heldSessionTargets,
                ),
            ) { onEnterSessionRequested(SessionTarget.Repo(repo.name)) }
        }

        item {
            MainSessionRow(
                // 메인 세션은 git 상태가 없다 — 워크디렉토리 최상위는 레포가 아니다.
                sessionFacts = CardSessionFacts(
                    appSessionHeld = SessionTarget.Main in heldSessionTargets,
                    cliSessionRunning = snapshot.mainSessionAlive,
                    gitState = null,
                ),
            ) { onEnterSessionRequested(SessionTarget.Main) }
        }
    }
}

/**
 * 카드 한 장을 누르는 것이 곧 그 레포의 세션에 들어가는 일이다.
 *
 * 카드 안에 "열기" 버튼을 따로 두지 않는다. 카드가 가리키는 것이 레포 하나뿐이라 누를 곳마다
 * 뜻이 갈릴 여지가 없고, 버튼을 두면 카드의 나머지 자리가 눌러도 아무 일이 없는 죽은 면이 된다.
 */
@Composable
private fun RepoCard(
    repo: RepoSnapshot,
    sessionFacts: CardSessionFacts,
    onEnterSessionRequested: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onEnterSessionRequested)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SessionChips(sessionFacts)
                Spacer(Modifier.width(12.dp))
                Text(repo.name, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                if (repo.totalTaskCount > 0) {
                    Text(
                        text = "done ${repo.doneTaskCount}/${repo.totalTaskCount}",
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Text(
                text = repo.task?.let(::taskLabel) ?: "계획에 걸린 작업 없음",
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = if (repo.task == null) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )

            if (sessionFacts.cliSessionRunning) {
                CliSessionNotice()
            }
        }
    }
}

/**
 * 카드가 두 종류의 세션을 갈라 보여준다(설계 문서 5.4.2).
 *
 * 앱 세션과 CLI 세션이 함께 뜰 수 있다. 그 레포에 `claude` 가 둘 돌고 있다는 뜻이고, 그것을
 * 한 칩으로 뭉치면 사용자는 자기가 만든 것이 몇 개인지 알 수 없다.
 */
@Composable
private fun SessionChips(sessionFacts: CardSessionFacts) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (sessionFacts.appSessionHeld) {
            SessionChip("앱 세션", APP_SESSION_COLOR)
        }
        if (sessionFacts.cliSessionRunning) {
            SessionChip("CLI 세션", CLI_SESSION_COLOR)
        }
        sessionFacts.gitState?.let { SessionChip(it.rawValue, it.chipColor) }
    }
}

/**
 * 앱이 붙을 수 없는 세션이라는 사실을 카드가 먼저 말한다.
 *
 * 막지 않고 알린다(설계 문서 5.4.2). 막으면 이 카드에서 사용자가 할 수 있는 일이 아예 없어지고
 * (앱은 CLI 세션에 붙을 수도 죽일 수도 없다), 같은 레포에 `claude` 를 둘 띄우는 것 자체는
 * 사용자가 실제로 원할 수 있는 일이다.
 */
@Composable
private fun CliSessionNotice() {
    Text(
        text = "CLI 세션이 떠 있습니다 — 앱은 여기에 붙지 못합니다. 누르면 앱이 보유하는 새 세션이 열립니다.",
        style = MaterialTheme.typography.bodySmall,
        color = CLI_SESSION_COLOR,
    )
}

/**
 * 사람용 목록의 오른쪽 칸과 같은 문구를 만든다: `[consume] blocked ← needs commons-schema`.
 */
private fun taskLabel(task: RepoTask): String {
    val label = "[${task.id}] ${task.status}"
    if (task.blockedBy.isEmpty()) {
        return label
    }
    return "$label ← needs ${task.blockedBy.joinToString(", ")}"
}

@Composable
private fun SessionChip(label: String, color: Color) {
    Row(
        modifier = Modifier
            .background(color.copy(alpha = 0.14f), RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("●", color = color, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            color = color,
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun MainSessionRow(sessionFacts: CardSessionFacts, onEnterSessionRequested: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEnterSessionRequested)
            .padding(top = 8.dp, start = 4.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SessionChips(sessionFacts)
            if (sessionFacts.appSessionHeld || sessionFacts.cliSessionRunning) {
                Spacer(Modifier.width(10.dp))
            }
            Text("main", style = MaterialTheme.typography.titleSmall, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.width(10.dp))
            Text(
                text = if (sessionFacts.appSessionHeld || sessionFacts.cliSessionRunning) {
                    "워크디렉토리 세션이 떠 있습니다"
                } else {
                    "워크디렉토리 세션 없음"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (sessionFacts.cliSessionRunning) {
            CliSessionNotice()
        }
    }
}

/**
 * @param trailingContent 배너 아래에 붙는 것. 안내만 하는 배너는 비워 두고, 사람이 그 자리에서
 *   할 일이 있는 배너는 여기에 버튼을 둔다.
 */
@Composable
private fun NoticeBanner(
    accentColor: Color,
    title: String,
    lines: List<String>,
    trailingContent: @Composable () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(accentColor.copy(alpha = 0.10f), RoundedCornerShape(8.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = accentColor)
        lines.forEach { line ->
            Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
        }
        trailingContent()
    }
}

/**
 * 상태마다 다른 색. 어두운 배경에서도 읽히도록 밝기를 낮춘 계열을 쓴다.
 *
 * 모르는 상태는 IDLE 과 같은 회색으로 둔다. 없는 뜻을 색으로 지어내는 것보다, 낯선 낱말을
 * 눈에 띄지 않게 보여주고 사용자가 kyu 쪽을 보게 하는 편이 낫다.
 */
private val RepoState.chipColor: Color
    get() = when (this) {
        // 엔진의 RUNNING 은 카드에서 CLI 세션 칩이 되므로 이 자리에 오지 않는다. when 이 값을
        // 남김없이 덮어야 해서 남겨 두고, 색은 CLI 세션과 같게 둔다.
        RepoState.Running -> CLI_SESSION_COLOR
        RepoState.Dirty -> Color(0xFFE65100)
        RepoState.Ahead -> Color(0xFF1565C0)
        RepoState.Idle -> NEUTRAL_COLOR
        is RepoState.Unrecognized -> NEUTRAL_COLOR
    }

private val NEUTRAL_COLOR = Color(0xFF6B6B6B)

/**
 * 두 세션의 색을 갈라 둔다.
 *
 * 앱 세션은 예전의 RUNNING 이 쓰던 초록을 그대로 이어받는다 — 사용자가 앱에서 여는 세션이
 * 그쪽이라 눈이 기대하는 자리가 같다. CLI 세션은 앱이 손댈 수 없는 것이라 다른 계열로 둬서,
 * 낱말을 읽기 전에 "이건 내 것이 아니다" 가 먼저 보이게 한다.
 */
private val APP_SESSION_COLOR = Color(0xFF2E7D32)
private val CLI_SESSION_COLOR = Color(0xFF6A1B9A)
private val PLAN_WARNING_COLOR = Color(0xFFB26A00)
private val REFRESH_FAILURE_COLOR = Color(0xFFB3261E)

// 계획이 없는 것은 잘못이 아니라 아직 밟지 않은 걸음이다. 경고색(주황)이 아니라 안내하는
// 파랑으로 둬서, 목록 위의 다른 배너들과 급한 정도가 다르다는 것이 색으로 먼저 읽히게 한다.
private val PLAN_MISSING_COLOR = Color(0xFF1565C0)
