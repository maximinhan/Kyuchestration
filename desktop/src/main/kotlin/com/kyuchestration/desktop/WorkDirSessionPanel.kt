package com.kyuchestration.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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

/**
 * 셸 가운데 기둥. 이 워크디렉토리에서 열 수 있는 세션이 전부 여기 있다.
 *
 * **메인과 레포가 같은 높이로 늘어서 있던 것을 위계로 바꾼다.** 예전 화면은 레포 카드를 쌓고
 * 맨 아래에 `main` 한 줄을 붙였는데, 그러면 메인은 "레포가 아닌 것 하나" 로 보인다. 실제로
 * 메인은 레포들을 **위임으로 부리는 자리**다 — 그 관계가 화면에 없으면 처음 여는 사람은
 * 무엇을 먼저 눌러야 하는지 알 수 없다.
 *
 * 그래서 셋으로 나눠 말한다: 조율 블록이 위에 서고, 레포 행들은 그 아래에서 가이드 선으로
 * 들여쓰이고, 두 무리 사이에 소제목이 놓인다.
 *
 * @param onScreenTarget 지금 오른쪽 자리에 떠 있는 세션. 어느 줄이 화면과 이어져 있는지를
 *   표시하는 데만 쓴다 — 이 패널은 세션을 열 뿐 화면을 갈아 끼우는 판단을 하지 않는다.
 */
@Composable
internal fun WorkDirSessionPanel(
    observed: WorkDirDashboardState.WorkDirObserved,
    /**
     * 앱이 지금 보유 중인 세션의 대상. 엔진이 답한 목록 위에 얹는 앱 자신의 사실이다.
     *
     * 통로가 아니라 대상만 받는다. 목록이 세션을 읽거나 쓸 일은 없고, 통로를 넘기면 줄을
     * 그리는 자리가 세션을 끝낼 수도 있는 자리가 된다.
     */
    heldSessionTargets: Set<SessionTarget>,
    onScreenTarget: SessionTarget?,
    initializationState: WorkDirInitializationState,
    onInitializeOpenedWorkDirRequested: () -> Unit,
    /**
     * 줄을 눌러 세션에 들어간다. 어느 대화를 쓸지는 누른 자리가 정한다 — 줄 자체는 이어가기,
     * 줄의 새 대화 버튼은 새로 시작.
     */
    onEnterSessionRequested: (SessionTarget, SessionConversationChoice) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = modifier.fillMaxHeight()) {
        Column(modifier = Modifier.fillMaxHeight()) {
            WorkDirPanelHeader(observed.snapshot, heldSessionTargets.size)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
            ) {
                if (!observed.planFilePresent) {
                    item { PlanFileMissingBanner(initializationState, onInitializeOpenedWorkDirRequested) }
                }

                observed.lastRefreshFailure?.let { failure ->
                    // 목록은 그대로 두고 이 줄만 덧붙인다. 아래 줄들은 실패 직전에 본 것이라는
                    // 사실을 여기서 밝혀 두면, 사용자는 오래된 값을 최신으로 착각하지 않는다.
                    item {
                        NoticeBanner(
                            accentColor = KyuTheme.statusColors.failure,
                            title = "갱신하지 못했습니다",
                            lines = listOfNotNull(failure.message, failure.guidance),
                        )
                    }
                }

                if (observed.snapshot.planWarnings.isNotEmpty()) {
                    item {
                        NoticeBanner(
                            accentColor = KyuTheme.statusColors.caution,
                            title = "계획을 그대로 쓰지 못했습니다",
                            lines = observed.snapshot.planWarnings,
                        )
                    }
                }

                item { SectionHeading("조율") }
                item {
                    OrchestrationSessionBlock(
                        repoCount = observed.snapshot.repos.size,
                        sessionFacts = mergeCardSessionFacts(
                            // 워크디렉토리 최상위는 레포가 아니라 git 상태가 없다(설계 문서 5.4).
                            gitState = null,
                            hasRecordedConversation = observed.snapshot.mainSessionHasRecordedConversation,
                            appSessionHeld = SessionTarget.Main in heldSessionTargets,
                        ),
                        onScreen = onScreenTarget == SessionTarget.Main,
                        onEnterSessionRequested = {
                            onEnterSessionRequested(
                                SessionTarget.Main,
                                SessionConversationChoice.ContinueRecordedConversation,
                            )
                        },
                        onStartNewConversationRequested = {
                            onEnterSessionRequested(SessionTarget.Main, SessionConversationChoice.StartNewConversation)
                        },
                    )
                }

                item { SectionHeading("레포 세션 · ${observed.snapshot.repos.size}") }

                if (observed.snapshot.repos.isEmpty()) {
                    item { EmptyRepoNotice() }
                }

                // 절대경로를 키로 쓴다. 이름은 워크디렉토리 안에서만 유일하고, 경로가 그 레포의
                // 진짜 신원이다(설계 문서 11 의 4번).
                items(observed.snapshot.repos, key = { it.absolutePath }) { repo ->
                    RepoSessionRow(
                        repo = repo,
                        sessionFacts = mergeCardSessionFacts(
                            gitState = repo.state,
                            hasRecordedConversation = repo.hasRecordedConversation,
                            appSessionHeld = SessionTarget.Repo(repo.name) in heldSessionTargets,
                        ),
                        onScreen = onScreenTarget == SessionTarget.Repo(repo.name),
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
            }

            if (heldSessionTargets.isNotEmpty()) {
                HeldSessionFootnote(heldSessionTargets.size)
            }
        }
    }
}

@Composable
private fun WorkDirPanelHeader(snapshot: WorkDirSnapshot, appSessionCount: Int) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = snapshot.name,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = snapshot.absolutePath,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            // 경로는 앞이 아니라 뒤가 중요하다 — 어느 홈 아래인지보다 어느 폴더인지를 먼저 본다.
            overflow = TextOverflow.StartEllipsis,
        )
        Text(
            text = "레포 ${snapshot.repos.size}개 · 세션 ${appSessionCount}개",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 두 무리를 가르는 소제목. 조율이 하나 있고 그 아래에 레포들이 있다는 것을 낱말로도 말한다.
 */
@Composable
private fun SectionHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
    )
}

/**
 * 레포들을 부리는 자리.
 *
 * **레포 행과 모양이 다른 것이 요점이다.** 강조 톤의 바탕과 테두리, 허브 그림, 굵은 제목 —
 * 넷 다 "이것은 목록의 한 항목이 아니다" 를 말한다. 아래의 레포 행들은 바탕도 테두리도 없이
 * 가이드 선으로 들여쓰여, 눈이 위아래 관계를 먼저 읽는다.
 */
@Composable
private fun OrchestrationSessionBlock(
    repoCount: Int,
    sessionFacts: CardSessionFacts,
    onScreen: Boolean,
    onEnterSessionRequested: () -> Unit,
    onStartNewConversationRequested: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .border(
                width = if (onScreen) 2.dp else 1.dp,
                color = if (onScreen) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
                shape = MaterialTheme.shapes.medium,
            )
            .clickable(onClick = onEnterSessionRequested)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OrchestrationHubIcon(MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(Modifier.width(10.dp))
            Text(
                text = SessionTarget.Main.label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }

        Text(
            text = "조율 세션 · 레포 ${repoCount}개를 위임으로 부립니다",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = SUBTITLE_ALPHA),
        )

        if (sessionFacts.anyChipShown) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SessionChips(sessionFacts)
                if (sessionFacts.conversationToResume) {
                    Spacer(Modifier.weight(1f))
                    StartNewConversationButton(onStartNewConversationRequested)
                }
            }
        }
    }
}

/**
 * 레포 하나. 줄 전체를 누르는 것이 곧 그 레포의 세션에 들어가는 일이다.
 *
 * **"열기" 버튼은 여전히 두지 않는다.** 줄이 가리키는 것이 레포 하나뿐이라 줄 전체가 그 뜻이고,
 * 같은 뜻의 버튼을 두면 줄의 나머지 자리가 눌러도 아무 일이 없는 죽은 면이 된다.
 *
 * **새 대화 버튼은 다르다.** 그것은 줄과 다른 일을 한다 — 줄은 이어가고 버튼은 앞 대화를 버린다.
 * 이어갈 대화가 있는 줄에서만 뜨는 이유가 그것이다: 기록이 없으면 두 자리가 같은 일을 하게 되고,
 * 그때 이 버튼은 다시 죽은 면이 된다.
 */
@Composable
private fun RepoSessionRow(
    repo: RepoSnapshot,
    sessionFacts: CardSessionFacts,
    onScreen: Boolean,
    onEnterSessionRequested: () -> Unit,
    onStartNewConversationRequested: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        // 조율 블록에 딸린 것들이라는 표시. 줄마다 자기 몫을 그리므로 줄이 이어지면 선도 이어진다.
        Box(
            modifier = Modifier
                .width(GUIDE_LINE_WIDTH)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
        Spacer(Modifier.width(12.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .clip(MaterialTheme.shapes.small)
                .background(
                    if (onScreen) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                )
                .clickable(onClick = onEnterSessionRequested)
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = repo.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (repo.totalTaskCount > 0) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "done ${repo.doneTaskCount}/${repo.totalTaskCount}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (sessionFacts.anyChipShown || sessionFacts.gitState != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SessionChips(sessionFacts)
                    if (sessionFacts.conversationToResume) {
                        Spacer(Modifier.weight(1f))
                        StartNewConversationButton(onStartNewConversationRequested)
                    }
                }
            }

            repo.task?.let { task ->
                Text(
                    text = taskLabel(task),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun EmptyRepoNotice() {
    Text(
        text = "이 워크디렉토리 바로 아래에 클론된 레포가 없습니다. 왼쪽의 클론으로 받아 오세요.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

/**
 * 세션의 수명이 앱의 수명이라는 사실을 화면이 먼저 말하는 자리.
 *
 * **앱을 끌 때 할 말을 여기서 미리 한다.** 끄는 순간에 확인 창을 띄우지 않는 것이 결정이고
 * (설계 문서 8 절 4 번), 그렇다면 그 사실은 끄기 전에 이미 화면에 있어야 한다. 목록 위의 배너
 * 였던 것을 패널 바닥으로 옮긴 것은, 목록이 길어져도 이 줄은 스크롤과 함께 사라지면 안 되기
 * 때문이다 — 세션이 도는 동안 늘 보이는 것이 이 안내의 존재 이유다.
 */
@Composable
private fun HeldSessionFootnote(appSessionCount: Int) {
    Column {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("●", color = KyuTheme.statusColors.session, style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "세션 ${appSessionCount}개가 이 앱 안에서 돌고 있습니다",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Text(
                text = "앱을 끄면 함께 끝나지만 대화는 워크디렉토리에 남습니다" +
                    "(.coord/conversations.json). 잃는 것은 진행 중이던 턴입니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 계획이 아직 없다는 안내. 막는 것이 아니라 알리는 것이다.
 *
 * 계획이 없어도 줄과 세션은 그대로 쓸 수 있다 — kyu list 는 계획 없이도 답한다. 그래서 이
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
            "아직 .coord/plan.md 가 없습니다. 줄과 세션은 그대로 쓸 수 있고, 계획을 두면 " +
                "레포마다 지금 볼 작업이 함께 뜹니다.",
        ),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedButton(
                onClick = onInitializeOpenedWorkDirRequested,
                enabled = initializationState != WorkDirInitializationState.Running,
            ) {
                Text("초기화")
            }
            InitializationProgressOrFailure(
                initializationState = initializationState,
                runningLabel = "kyu init 으로 계획 파일을 만드는 중입니다",
            )
        }
    }
}

/**
 * 줄이 이 세션에 대해 아는 것을 칩으로 늘어놓는다.
 *
 * 세션 칩이 맨 앞이다. 지금 돌고 있는 것 · 누르면 일어날 일 · git 이 본 것은 급한 정도가 다르고,
 * 목록을 훑는 눈은 왼쪽부터 읽는다.
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

@Composable
private fun SessionChip(label: String, color: Color) {
    Row(
        modifier = Modifier
            .background(color.copy(alpha = CHIP_TINT_ALPHA), MaterialTheme.shapes.extraSmall)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("●", color = color, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.width(5.dp))
        Text(
            text = label,
            // 글자는 상태 색이 아니라 바탕 위의 기본 글자색으로 둔다. 뜻을 지는 것은 낱말이고
            // 색은 그 옆의 점이 진다(설계 6.7 — 색만으로 가르지 않는다). 글자까지 상태 색으로
            // 칠하면 밝은 바탕에서 DIRTY 주황이 3.6:1 까지 떨어져 작은 글자가 읽히지 않는다.
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/**
 * 이어가는 대신 새로 시작하는 자리.
 *
 * 문구가 "새 대화" 가 아니라 "새 대화로 시작" 인 것은, 이 버튼이 대화를 만드는 것이 아니라
 * **세션을 여는** 것이어서다 — 누르면 그 자리에서 세션이 열린다.
 */
@Composable
private fun StartNewConversationButton(onStartNewConversationRequested: () -> Unit) {
    TextButton(
        onClick = onStartNewConversationRequested,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
    ) {
        Text("새 대화로 시작", style = MaterialTheme.typography.labelSmall)
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
            .padding(bottom = 8.dp)
            .background(accentColor.copy(alpha = BANNER_TINT_ALPHA), MaterialTheme.shapes.small)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = accentColor)
        lines.forEach { line ->
            Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
        }
        trailingContent()
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

/**
 * 이 줄에 그려질 칩이 하나라도 있는가. 조율 블록에는 git 상태가 없으므로 둘 중 하나다.
 */
private val CardSessionFacts.anyChipShown: Boolean
    get() = appSessionHeld || conversationToResume

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

/** 칩과 배너는 색을 점에 쓰고 바탕에는 그 색을 옅게 깐다. 두 옅기가 다른 것은 면적이 달라서다. */
private const val CHIP_TINT_ALPHA = 0.14f

private const val BANNER_TINT_ALPHA = 0.10f

/** 조율 블록의 부제. onPrimaryContainer 를 그대로 쓰면 제목과 무게가 같아 보인다. */
private const val SUBTITLE_ALPHA = 0.78f

/** 레포들을 조율 블록에 매다는 선. 굵으면 목록을 가르는 벽처럼 읽힌다. */
private val GUIDE_LINE_WIDTH = 1.dp
