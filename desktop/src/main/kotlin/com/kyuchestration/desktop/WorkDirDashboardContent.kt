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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.kyuchestration.desktop.dashboard.CardSessionFacts
import com.kyuchestration.desktop.dashboard.WorkDirDashboardState
import com.kyuchestration.desktop.dashboard.mergeCardSessionFacts
import com.kyuchestration.desktop.initialization.WorkDirInitializationState
import com.kyuchestration.desktop.terminal.SessionConversationChoice
import com.kyuchestration.desktop.terminal.SessionTarget
import com.kyuchestration.desktop.theme.KyuTheme
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
    /**
     * 카드를 눌러 세션에 들어간다. 어느 대화를 쓸지는 누른 자리가 정한다 — 카드 자체는 이어가기,
     * 카드의 새 대화 버튼은 새로 시작.
     */
    onEnterSessionRequested: (SessionTarget, SessionConversationChoice) -> Unit,
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
                accentColor = KyuTheme.statusColors.failure,
                title = "갱신하지 못했습니다 — 아래 목록은 마지막으로 읽은 상태입니다",
                lines = listOfNotNull(failure.message, failure.guidance),
            )
        }

        if (observed.snapshot.planWarnings.isNotEmpty()) {
            NoticeBanner(
                accentColor = KyuTheme.statusColors.caution,
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
        accentColor = KyuTheme.statusColors.notice,
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
 * 세션의 수명이 앱의 수명이라는 사실을 화면이 먼저 말하는 자리.
 *
 * **앱을 끌 때 할 말을 여기서 미리 한다.** 끄는 순간에 확인 창을 띄우지 않는 것이 결정이고
 * (설계 문서 8 절 4 번), 그렇다면 그 사실은 끄기 전에 이미 화면에 있어야 한다 — 세션이 도는 동안
 * 계속 보이는 이 배너가 그 자리다. 물을 것이 없는 이유도 함께 적는다: 끄는 것은 대화를 버리는
 * 일이 아니라 진행 중이던 턴 하나를 잃는 일이다.
 *
 * 보유한 세션이 하나라도 있을 때만 뜬다. 없는 사람에게는 아직 일어나지 않은 일이다.
 */
@Composable
private fun AppSessionBanner(appSessionCount: Int) {
    NoticeBanner(
        accentColor = KyuTheme.statusColors.session,
        title = "세션 ${appSessionCount}개가 이 앱 안에서 돌고 있습니다",
        lines = listOf(
            "세션은 앱이 직접 보유합니다. 끝내는 길은 터미널의 세션 끝내기 버튼이나 앱 종료입니다.",
            "앱을 끄면 함께 끝나지만 대화는 워크디렉토리에 남습니다(.coord/conversations.json). " +
                "잃는 것은 진행 중이던 턴이고, 다음에 앱을 켜서 그 카드를 누르면 대화가 이어집니다.",
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
            Text(
                text = "레포 ${snapshot.repos.size}개 · 세션 ${appSessionCount}개",
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
    onEnterSessionRequested: (SessionTarget, SessionConversationChoice) -> Unit,
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
                sessionFacts = mergeCardSessionFacts(
                    gitState = repo.state,
                    hasRecordedConversation = repo.hasRecordedConversation,
                    appSessionHeld = SessionTarget.Repo(repo.name) in heldSessionTargets,
                ),
                onEnterSessionRequested = {
                    onEnterSessionRequested(
                        SessionTarget.Repo(repo.name),
                        SessionConversationChoice.ContinueRecordedConversation,
                    )
                },
                onStartNewConversationRequested = {
                    onEnterSessionRequested(
                        SessionTarget.Repo(repo.name),
                        SessionConversationChoice.StartNewConversation,
                    )
                },
            )
        }

        item {
            MainSessionRow(
                sessionFacts = mergeCardSessionFacts(
                    // 워크디렉토리 최상위는 레포가 아니라 git 상태가 없다(설계 문서 5.4).
                    gitState = null,
                    hasRecordedConversation = snapshot.mainSessionHasRecordedConversation,
                    appSessionHeld = SessionTarget.Main in heldSessionTargets,
                ),
                onEnterSessionRequested = {
                    onEnterSessionRequested(SessionTarget.Main, SessionConversationChoice.ContinueRecordedConversation)
                },
                onStartNewConversationRequested = {
                    onEnterSessionRequested(SessionTarget.Main, SessionConversationChoice.StartNewConversation)
                },
            )
        }
    }
}

/**
 * 카드 한 장을 누르는 것이 곧 그 레포의 세션에 들어가는 일이다.
 *
 * **"열기" 버튼은 여전히 두지 않는다.** 카드가 가리키는 것이 레포 하나뿐이라 카드 전체가 그 뜻이고,
 * 같은 뜻의 버튼을 두면 카드의 나머지 자리가 눌러도 아무 일이 없는 죽은 면이 된다.
 *
 * **새 대화 버튼은 다르다.** 그것은 카드와 다른 일을 한다 — 카드는 이어가고 버튼은 앞 대화를
 * 버린다. 이어갈 대화가 있는 카드에서만 뜨는 이유가 그것이다: 기록이 없으면 두 자리가 같은 일을
 * 하게 되고, 그때 이 버튼은 다시 죽은 면이 된다.
 */
@Composable
private fun RepoCard(
    repo: RepoSnapshot,
    sessionFacts: CardSessionFacts,
    onEnterSessionRequested: () -> Unit,
    onStartNewConversationRequested: () -> Unit,
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
                if (sessionFacts.conversationToResume) {
                    Spacer(Modifier.width(8.dp))
                    StartNewConversationButton(onStartNewConversationRequested)
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
        }
    }
}

/**
 * 카드가 이 레포에 대해 아는 것을 칩으로 늘어놓는다.
 *
 * 세션 칩이 맨 앞이다. 지금 돌고 있는 것 · 누르면 일어날 일 · git 이 본 것은 급한 정도가 다르고,
 * 카드를 훑는 눈은 왼쪽부터 읽는다.
 */
@Composable
private fun SessionChips(sessionFacts: CardSessionFacts) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (sessionFacts.appSessionHeld) {
            SessionChip("세션", KyuTheme.statusColors.session)
        }
        if (sessionFacts.conversationToResume) {
            SessionChip("이어갈 대화", KyuTheme.statusColors.resumableConversation)
        }
        sessionFacts.gitState?.let { SessionChip(it.rawValue, it.chipColor) }
    }
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
            .background(color.copy(alpha = CHIP_TINT_ALPHA), MaterialTheme.shapes.extraSmall)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("●", color = color, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            // 글자는 상태 색이 아니라 바탕 위의 기본 글자색으로 둔다. 뜻을 지는 것은 낱말이고
            // 색은 그 옆의 점이 진다(설계 6.7 — 색만으로 가르지 않는다). 글자까지 상태 색으로
            // 칠하면 밝은 바탕에서 DIRTY 주황이 3.6:1 까지 떨어져 작은 글자가 읽히지 않는다.
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun MainSessionRow(
    sessionFacts: CardSessionFacts,
    onEnterSessionRequested: () -> Unit,
    onStartNewConversationRequested: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEnterSessionRequested)
            .padding(top = 8.dp, start = 4.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SessionChips(sessionFacts)
        // 칩이 하나도 없는 줄에서는 간격만 남아 이름이 밀려 보인다.
        if (sessionFacts.anyChipShown) {
            Spacer(Modifier.width(10.dp))
        }
        Text("main", style = MaterialTheme.typography.titleSmall, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.width(10.dp))
        Text(
            text = if (sessionFacts.appSessionHeld) {
                "워크디렉토리 세션이 떠 있습니다"
            } else {
                "워크디렉토리 세션 없음"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (sessionFacts.conversationToResume) {
            Spacer(Modifier.weight(1f))
            StartNewConversationButton(onStartNewConversationRequested)
        }
    }
}

/**
 * 이어가는 대신 새로 시작하는 자리.
 *
 * 문구가 "새 대화" 가 아니라 "새 대화로 시작" 인 것은, 이 버튼이 대화를 만드는 것이 아니라
 * **세션을 여는** 것이어서다 — 누르면 그 자리에서 터미널이 열린다.
 */
@Composable
private fun StartNewConversationButton(onStartNewConversationRequested: () -> Unit) {
    TextButton(onClick = onStartNewConversationRequested) {
        Text("새 대화로 시작", style = MaterialTheme.typography.labelMedium)
    }
}

/**
 * 이 줄에 그려질 칩이 하나라도 있는가. 메인 세션에는 git 상태가 없으므로 둘 중 하나다.
 */
private val CardSessionFacts.anyChipShown: Boolean
    get() = appSessionHeld || conversationToResume

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
            .background(accentColor.copy(alpha = BANNER_TINT_ALPHA), MaterialTheme.shapes.small)
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
 * 상태마다 다른 색. 어느 색인지는 테마가 정하고 여기서는 어느 뜻인지만 고른다.
 *
 * 모르는 상태는 IDLE 과 같은 중립으로 둔다. 없는 뜻을 색으로 지어내는 것보다, 낯선 낱말을
 * 눈에 띄지 않게 보여주고 사용자가 kyu 쪽을 보게 하는 편이 낫다.
 */
private val RepoState.chipColor: Color
    @Composable
    @ReadOnlyComposable
    get() = when (this) {
        RepoState.Dirty -> KyuTheme.statusColors.repoDirty
        RepoState.Ahead -> KyuTheme.statusColors.repoAhead
        RepoState.Idle -> KyuTheme.statusColors.neutral
        is RepoState.Unrecognized -> KyuTheme.statusColors.neutral
    }

/** 칩과 배너는 색을 글자에 쓰고 바탕에는 그 색을 옅게 깐다. 두 옅기가 다른 것은 면적이 달라서다. */
private const val CHIP_TINT_ALPHA = 0.14f

private const val BANNER_TINT_ALPHA = 0.10f
