package com.kyuchestration.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kyuchestration.desktop.terminal.chat.ChatEntry
import com.kyuchestration.desktop.terminal.chat.PermissionAnswer
import com.kyuchestration.desktop.terminal.chat.PermissionDenial
import com.kyuchestration.desktop.terminal.chat.PermissionCardChoice
import com.kyuchestration.desktop.terminal.chat.ToolCallAnswer
import com.kyuchestration.desktop.terminal.chat.TurnOutcome
import com.kyuchestration.desktop.theme.KyuTheme
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * 전사의 항목 하나를 그린다(설계 6.3).
 *
 * **갈래를 여기 한 곳에서 가른다.** when 이 남김없이 덮는지는 컴파일러가 봐 주므로, 상태 쪽에
 * 항목이 하나 늘면 이 파일이 컴파일되지 않는다 — 그릴 자리 없는 항목이 조용히 사라지지 않는다.
 */
@Composable
internal fun ChatEntryView(
    entry: ChatEntry,
    /**
     * 이 세션이 도는 자리. 승인 카드가 "어느 레포의 물음인가" 를 말하는 근거다(6.4) — 챗이
     * 여럿 열려 있으면 그것이 판단의 절반이다.
     */
    sessionWorkingDirectory: Path?,
    /** 승인 카드의 버튼이 눌린 자리. 도구 호출 하나와 사용자가 고른 것을 함께 올린다. */
    onPermissionChoiceMade: (String, PermissionCardChoice) -> Unit,
) {
    when (entry) {
        // 중단 안내가 사용자 메시지와 같은 모양으로 온다(3.8). 가르는 자리가 여기다 —
        // 왜 어댑터가 아닌지는 isTurnInterruptionEcho 에 적혀 있다.
        is ChatEntry.UserSaid ->
            if (isTurnInterruptionEcho(entry.text)) TurnInterruptedNotice() else UserMessageBubble(entry.text)
        is ChatEntry.AssistantSaid -> AssistantMessageBlock(entry.text)
        is ChatEntry.AssistantThought -> ThinkingBlock(entry.text)
        // 서브에이전트 카드로 갈리는 근거는 도구 이름이 아니라 스트림이 말해 준 사실이다
        // (ChatEntry.SubagentRun) — 그 이름은 판마다 달라진다.
        is ChatEntry.ToolCall -> when {
            entry.subagentRun != null ->
                SubagentCard(entry, entry.subagentRun, sessionWorkingDirectory, onPermissionChoiceMade)

            // 위임은 도구 이름으로 갈린다. 서브에이전트와 달리 이 이름은 우리 엔진이 정하는
            // 것이라(claude_command.go), 판이 바뀌어도 우리가 바꾸지 않는 한 그대로다.
            entry.toolName == RUN_IN_REPO_TOOL_NAME -> DelegationCard(entry)

            else -> ToolCallCard(entry, sessionWorkingDirectory, onPermissionChoiceMade)
        }

        is ChatEntry.PermissionAsked ->
            PermissionRequestCard(entry, sessionWorkingDirectory, onPermissionChoiceMade)
        is ChatEntry.TurnEnded -> TurnFooter(entry)
        is ChatEntry.EngineNotice -> EngineNoticeRow(entry.line)
    }
}

/**
 * 사용자가 한 말. 오른쪽에 붙는 말풍선이다.
 *
 * 폭을 다 쓰지 않는다. 모델의 말은 폭 전체를 흐르고 사용자의 말은 그러지 않는 것이, 둘을
 * 이름표 없이 가르는 가장 조용한 방법이다.
 */
@Composable
private fun UserMessageBubble(text: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Box(
            modifier = Modifier
                .fillMaxWidth(USER_BUBBLE_WIDTH_FRACTION)
                .background(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.shapes.large)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

/**
 * 여기서 사용자가 턴을 끊었다는 줄(3.8).
 *
 * 오른쪽 말풍선으로 두지 않는 것이 요점이다. 이것은 사용자가 한 **말**이 아니라 사용자가 한
 * **일**이고, 말풍선에 넣으면 다음 턴의 모델이 그 문장에 답한 것처럼 읽힌다.
 */
@Composable
private fun TurnInterruptedNotice() {
    Text(
        text = "여기서 중단했습니다 — 대화는 그대로 이어집니다.",
        style = MaterialTheme.typography.labelSmall,
        color = KyuTheme.statusColors.caution,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * 모델이 완성한 텍스트 블록. 말풍선 없이 폭 전체를 쓰고 마크다운으로 그린다(6.3 · 5.8).
 *
 * 말풍선을 두지 않는 것이 뜻이다. 표와 코드 블록이 든 답이 흔한데, 말풍선 안에 넣으면 그것들이
 * 폭에 눌린다 — 사용자의 말과 가르는 일은 오른쪽 말풍선 쪽이 이미 하고 있다.
 */
@Composable
private fun AssistantMessageBlock(text: String) {
    AssistantMarkdown(text)
}

/** 모델의 사고 블록. 접힌 채로 서고, 눌러야 펴진다. */
@Composable
private fun ThinkingBlock(text: String) {
    CollapsibleBlock(
        header = {
            Text(
                text = "생각",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    ) {
        MonospaceBlock(text, detailTitle = "생각")
    }
}

/**
 * 도구 호출 하나(6.3).
 *
 * **접힌 줄이 그 호출을 요약한다.** 도구 이름만 있는 줄로는 무엇을 했는지 알 수 없어서, 접힌
 * 채로도 Bash 는 명령을, Edit 는 파일과 `+n −m` 을, Read 는 파일과 줄 수를 말한다. 무엇을
 * 요약할지는 [toolCallCardContentOf] 가 곁가지를 보고 정한다.
 *
 * **모르는 도구는 일반 카드로 떨어진다.** MCP 도구는 얼마든지 새로 붙으므로 그것이 기본값이다.
 *
 * **실패는 접힌 채로도 보인다.** 결과의 첫 줄을 머리말에 두지 않으면 무엇이 잘못됐는지 알려고
 * 카드를 하나씩 펴 봐야 하고, 그러면 실패가 대화에 묻힌다.
 */
@Composable
private fun ToolCallCard(
    entry: ChatEntry.ToolCall,
    sessionWorkingDirectory: Path?,
    onPermissionChoiceMade: (String, PermissionCardChoice) -> Unit,
) {
    // 글자가 흐르는 동안 대화가 프레임마다 새로 나므로, 곁가지를 그때마다 다시 파면 보이는 카드
    // 전부가 패치를 새로 만든다. 이 값을 정하는 것은 셋뿐이라 그 셋을 열쇠로 든다.
    val cardContent = remember(entry.toolName, entry.input, entry.answer) {
        toolCallCardContentOf(entry.toolName, entry.input, entry.answer?.typedResult)
    }
    val toolLabel = remember(entry.toolName) { toolLabel(entry.toolName) }

    CollapsibleBlock(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        header = {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                ToolCallSummary(toolLabel, cardContent)
                Spacer(Modifier.width(10.dp))
                ToolCallStatus(entry.answer, entry.requestedAt)
            }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ToolCallDetail(cardContent, entry, toolLabel)

            // 안쪽에서 일어난 것들 — 서브에이전트의 대화가 여기 접혀 있다(3.10). 카드 안이라는
            // 것을 들여쓰기로 말한다.
            if (entry.nestedEntries.isNotEmpty()) {
                Column(
                    modifier = Modifier.padding(start = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    entry.nestedEntries.forEach {
                        ChatEntryView(it, sessionWorkingDirectory, onPermissionChoiceMade)
                    }
                }
            }
        }
    }
}

/**
 * 접힌 줄에 서는 요약.
 *
 * 이름은 굵게, 그 도구가 다룬 것은 보통 굵기로 둔다 — 한 줄 안에서 "무슨 도구인가" 와 "무엇에
 * 대해서인가" 가 갈려 읽힌다.
 *
 * **이름은 갈래와 무관하게 `claude` 가 부른 도구 이름 그대로다.** 아는 도구만 우리 낱말로
 * 옮기면 같은 줄에 "편집" 과 `Skill` 이 섞여 서고, 사용자가 전사에서 본 이름으로 검색할 수도
 * 없다. 옮기는 것은 MCP 이름의 마디를 가르는 것 하나뿐이다(toolLabel).
 */
@Composable
private fun ToolCallSummary(toolLabel: String, cardContent: ToolCallCardContent) {
    val detail = when (cardContent) {
        is ToolCallCardContent.ShellCommand -> cardContent.command.lineSequence().first()

        is ToolCallCardContent.FileChanged -> {
            val counts = if (cardContent.createdFile) {
                "새 파일 +${cardContent.addedLineCount}"
            } else {
                "+${cardContent.addedLineCount} −${cardContent.removedLineCount}"
            }
            "${fileName(cardContent.filePath)} $counts"
        }

        is ToolCallCardContent.FileRead ->
            fileName(cardContent.filePath) + cardContent.totalLineCount?.let { " · ${it}줄" }.orEmpty()

        is ToolCallCardContent.AnyTool -> ""
    }

    Text(
        text = toolLabel,
        style = MaterialTheme.typography.labelLarge,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
    )

    if (detail.isNotEmpty()) {
        Spacer(Modifier.width(8.dp))
        Text(
            text = detail,
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 펼친 카드의 본문.
 *
 * 갈래마다 다른 것을 보이되, **일반 카드는 인자 JSON 과 결과 전문**이라는 3 단계의 모습을 그대로
 * 둔다 — 모르는 도구에 대해 화면이 할 수 있는 정직한 일이 그것뿐이다.
 */
@Composable
private fun ToolCallDetail(cardContent: ToolCallCardContent, entry: ChatEntry.ToolCall, toolLabel: String) {
    when (cardContent) {
        is ToolCallCardContent.ShellCommand -> {
            MonospaceBlock(cardContent.command, detailTitle = "명령")
            if (cardContent.standardOutput.isNotEmpty()) {
                MonospaceBlock(cardContent.standardOutput, detailTitle = "명령 출력")
            }
            // stderr 를 stdout 과 섞지 않는다. 섞으면 어느 줄이 오류였는지 알 수 없고, 그것을
            // 아는 것이 실패한 명령을 볼 때 사용자가 하려는 일이다.
            if (cardContent.standardError.isNotEmpty()) {
                MonospaceBlock(
                    text = cardContent.standardError,
                    detailTitle = "표준 오류",
                    textColor = KyuTheme.statusColors.failure,
                )
            }
            // 거절되거나 실패한 호출은 곁가지에 출력이 없다. 그 이유가 모델이 읽은 텍스트에 있다.
            if (cardContent.standardOutput.isEmpty() && cardContent.standardError.isEmpty()) {
                entry.answer?.let { MonospaceBlock(it.modelVisibleText, detailTitle = "결과") }
            }
        }

        is ToolCallCardContent.FileChanged -> {
            MonospaceBlock(cardContent.filePath, detailTitle = "파일 경로")
            DiffBlock(cardContent.hunks)
        }

        is ToolCallCardContent.FileRead -> {
            MonospaceBlock(cardContent.filePath, detailTitle = "파일 경로")
            MonospaceBlock(cardContent.content, detailTitle = fileName(cardContent.filePath))
        }

        is ToolCallCardContent.AnyTool -> {
            MonospaceBlock(prettyPrintedToolInput(entry.input), detailTitle = "$toolLabel 인자")
            entry.answer?.let { MonospaceBlock(it.modelVisibleText, detailTitle = "$toolLabel 결과") }
        }
    }
}

/**
 * 바뀐 줄들(6.3 — 추가·삭제를 `tertiary`·`error` 계열로).
 *
 * **색만으로 가르지 않는다**(6.7). 줄 앞의 `+` 와 `−` 가 뜻을 지고 색은 그것을 거든다 — 색을
 * 구별하지 못하는 눈에도, 흑백으로 캡처한 화면에도 남는 것이 그 기호다.
 */
@Composable
private fun DiffBlock(hunks: List<DiffHunk>) {
    var showingFullDiff by remember(hunks) { mutableStateOf(false) }
    // 한 줄이 폭에 눌려 잘리는 것도 전문을 볼 이유다. 어느 줄이든 잘렸으면 그 사실을 든다.
    var anyLineTruncated by remember(hunks) { mutableStateOf(false) }

    // **상한을 카드 전체에 건다.** 덩이마다 걸면 다섯 줄짜리 덩이 마흔 개가 전사에 그대로 펴진다.
    val shownRows = diffRows(hunks).take(DIFF_LINES_SHOWN_IN_CARD)
    val hiddenRowCount = diffRows(hunks).size - shownRows.size

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest, MaterialTheme.shapes.small)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        shownRows.forEach { row ->
            when (row) {
                // 덩이 사이가 붙어 있으면 떨어진 두 자리의 변경이 한 덩이로 읽힌다.
                null -> Text(
                    text = "⋯",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                else -> {
                    val (marker, color) = when (row.kind) {
                        DiffLineKind.Added -> "+" to KyuTheme.statusColors.success
                        DiffLineKind.Removed -> "−" to KyuTheme.statusColors.failure
                        DiffLineKind.Context -> " " to MaterialTheme.colorScheme.onSurfaceVariant
                    }

                    Text(
                        text = "$marker ${row.text}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = color,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        onTextLayout = { if (it.hasVisualOverflow) anyLineTruncated = true },
                    )
                }
            }
        }

        if (hiddenRowCount > 0) {
            Text(
                text = "…그 밖에 ${hiddenRowCount}줄",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (hiddenRowCount > 0 || anyLineTruncated) {
        FullTextPanelButton("바뀐 줄") { showingFullDiff = true }
    }

    if (showingFullDiff) {
        FullTextPanel("바뀐 줄", plainTextDiff(hunks)) { showingFullDiff = false }
    }
}

/** 덩이들을 한 줄기로 편다. 덩이 사이의 null 이 "여기가 떨어진 자리" 다. */
private fun diffRows(hunks: List<DiffHunk>): List<DiffLine?> =
    hunks.flatMapIndexed { hunkOrdinal, hunk ->
        if (hunkOrdinal == 0) hunk.lines else listOf(null) + hunk.lines
    }

/**
 * 상세 패널에 넣을 diff 한 벌.
 *
 * 색을 잃는 자리라 기호가 뜻을 다 져야 한다 — 카드에서 색과 함께 쓰던 그 기호를 그대로 쓴다.
 */
private fun plainTextDiff(hunks: List<DiffHunk>): String = hunks.joinToString("\n⋯\n") { hunk ->
    hunk.lines.joinToString("\n") { line ->
        when (line.kind) {
            DiffLineKind.Added -> "+ "
            DiffLineKind.Removed -> "− "
            DiffLineKind.Context -> "  "
        } + line.text
    }
}

/**
 * 카드에는 파일 이름만 둔다. 전체 경로는 펼친 자리에 있다 — 접힌 줄에서는 이름이 먼저 읽힌다.
 *
 * 구분자 둘을 다 본다. 이 앱은 윈도우에서도 도는 것을 전제로 두고 있고, 그 판의 경로에는
 * `\` 가 온다 — `/` 만 보면 접힌 줄에 전체 경로가 통째로 선다.
 */
private fun fileName(filePath: String): String =
    filePath.substringAfterLast('/').substringAfterLast('\\')

/**
 * 그 도구 호출이 어떻게 됐는가.
 *
 * 색만으로 가르지 않는다(6.7). 점은 색을 지고 낱말이 뜻을 진다 — 이 앱의 상태 칩이 이미 지키는
 * 규율이다(WorkDirSessionPanel 의 SessionChip).
 */
@Composable
private fun ToolCallStatus(answer: ToolCallAnswer?, requestedAt: Instant?) {
    val (label, color) = when {
        answer == null -> "도는 중" to MaterialTheme.colorScheme.onSurfaceVariant
        answer.failed -> "실패" to KyuTheme.statusColors.failure
        else -> "완료" to KyuTheme.statusColors.success
    }

    Text("●", color = color, style = MaterialTheme.typography.labelSmall)
    Spacer(Modifier.width(5.dp))
    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)

    if (answer == null) {
        RunningElapsedText(requestedAt)
    }

    // 실패한 까닭을 머리말에 그대로 붙인다. 카드를 펴지 않아도 무엇이 잘못됐는지 보여야 한다.
    if (answer?.failed == true) {
        Spacer(Modifier.width(8.dp))
        Text(
            text = answer.modelVisibleText.lineSequence().firstOrNull().orEmpty(),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = KyuTheme.statusColors.failure,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 도는 중인 것 옆에 흐르는 경과 시간(6.6 — "도구가 도는 중 · 카드에 경과 시간").
 *
 * **시작한 때는 스트림이 주고, 지금은 이 기계의 시계가 준다.** 앞의 것을 앱이 재지 않는 이유는
 * 두 숫자가 갈리지 않게 하려는 것이고(ChatSessionEvent.ToolCallRequested), 뒤의 것을 스트림이
 * 줄 수 없는 이유는 도는 동안 아무 줄도 오지 않기 때문이다 — 위임이 그 예다(3.10 가).
 *
 * **진행 막대가 아니다.** 몇 걸음 중 몇 번째인지는 아무도 말해 주지 않는다(원칙 15). 이 줄이
 * 말하는 것은 "아직 돌고 있고, 이만큼 됐다" 하나다.
 */
@Composable
internal fun RunningElapsedText(since: Instant?) {
    if (since == null) {
        return
    }

    var now by remember(since) { mutableStateOf(Instant.now()) }
    LaunchedEffect(since) {
        while (true) {
            delay(ELAPSED_TICK_MILLIS)
            now = Instant.now()
        }
    }

    val label = runningElapsedLabel(Duration.between(since, now).toMillis()) ?: return
    Spacer(Modifier.width(8.dp))
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * 턴 하나가 끝났다는 배지 — 비용 · 토큰 · 소요 시간(6.3).
 *
 * 전사 안에 놓인다. 화면 아래 한 자리에 마지막 턴의 것만 두면 스크롤을 올렸을 때 어느 답이
 * 얼마였는지 알 수 없다.
 */
@Composable
private fun TurnFooter(entry: ChatEntry.TurnEnded) {
    val badges = listOfNotNull(
        turnOutcomeLabel(entry.outcome),
        turnCostLabel(entry.costUsd),
        turnTokenLabel(entry.usage),
        turnElapsedLabel(entry.durationMillis),
    )

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = badges.joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = if (entry.outcome == TurnOutcome.Completed) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                KyuTheme.statusColors.caution
            },
            modifier = Modifier.fillMaxWidth(),
        )

        if (entry.permissionDenials.isNotEmpty()) {
            PermissionDenialNotice(entry.permissionDenials)
        }
    }
}

/**
 * 권한에 막혀 아예 일어나지 못한 호출들(6.3 의 `PermissionDenialNotice` · 3.6).
 *
 * **이 줄이 없으면 그 사실이 화면 어디에도 없다.** 묻지 않고 거절하는 모드에서는 승인 카드가
 * 뜨지 않으므로(3.6 의 `dontAsk`), 사용자가 "왜 그 파일이 안 만들어졌지" 를 알 통로는 모델이
 * 그것을 말해 주기를 바라는 것뿐이다.
 *
 * **묻고 거부한 것은 여기 없다.** 그것은 그 승인 카드가 이미 말한다(ChatEntry.TurnEnded).
 *
 * 인자를 접어 둔다. 무엇이 막혔는지는 이름으로 충분하고, 그 인자에는 쓰려던 파일 내용이
 * 통째로 들어 있을 수 있다 — 대화 흐름 안에 그것을 펴 두면 그 위아래가 화면 밖으로 밀린다.
 */
@Composable
private fun PermissionDenialNotice(denials: List<PermissionDenial>) {
    CollapsibleBlock(
        header = {
            Text(
                text = "권한에 막혀 못 한 호출 ${denials.size}건 — " +
                    denials.joinToString(", ") { toolLabel(it.toolName) },
                style = MaterialTheme.typography.labelSmall,
                color = KyuTheme.statusColors.caution,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "묻지 않고 거절되었습니다 — 이 세션의 권한 모드가 그렇게 정해져 있습니다.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            denials.forEach {
                MonospaceBlock(
                    text = prettyPrintedToolInput(it.input),
                    detailTitle = "${toolLabel(it.toolName)} 인자",
                )
            }
        }
    }
}

/** 잘 끝난 턴은 굳이 말하지 않는다 — 배지에 비용이 있는 것이 이미 끝났다는 뜻이다. */
private fun turnOutcomeLabel(outcome: TurnOutcome): String? = when (outcome) {
    TurnOutcome.Completed -> null
    TurnOutcome.Interrupted -> "중단됨"
    TurnOutcome.Failed -> "실패"
}

/**
 * 엔진이 stderr 에 남긴 한 줄(3.9).
 *
 * PTY 시절에는 이 줄이 터미널 화면에 그대로 보였다. 챗에서 자리를 주지 않으면 통째로 사라진다 —
 * 이어갈 대화를 찾지 못한 이유가 여기로 온다.
 */
@Composable
private fun EngineNoticeRow(line: String) {
    Text(
        text = line,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = KyuTheme.statusColors.caution,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * 눌러서 펴는 블록. 도구 카드와 사고 블록이 같은 몸짓을 쓴다.
 *
 * 두 사용처가 실제로 있어서 함수로 뽑았다(원칙 4). 하나였으면 그 자리에 그대로 두었다.
 */
@Composable
internal fun CollapsibleBlock(
    header: @Composable () -> Unit,
    containerColor: Color = Color.Transparent,
    content: @Composable () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(containerColor, MaterialTheme.shapes.medium)
            .clickable { expanded = !expanded }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            // 펴진 상태를 낱말 없이 말하는 자리. 색이 아니라 모양이라 대비와 무관하다.
            Text(
                text = if (expanded) "▾" else "▸",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            header()
        }

        if (expanded) {
            content()
        }
    }
}

/**
 * 도구의 인자와 결과가 놓이는 자리. **마크다운으로 그리지 않는다**(5.8).
 *
 * 파일 내용이나 명령 출력에 마크다운 문법처럼 보이는 글자가 있으면 화면이 원본과 달라진다.
 *
 * **긴 것은 카드 안에서 잘리고 상세 패널이 전문을 연다**(6.7). 카드는 대화의 흐름 안에 있는
 * 자리라, 여기서 천 줄짜리 출력을 다 펴면 그 위아래의 대화가 화면 밖으로 밀려난다.
 *
 * @param detailTitle 상세 패널의 제목. 무엇의 전문인지가 패널 안에서도 보여야, 카드를 여럿 펴 둔
 *   사용자가 어느 것을 열었는지 안다.
 */
@Composable
internal fun MonospaceBlock(
    text: String,
    detailTitle: String,
    textColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    var showingFullText by remember(text) { mutableStateOf(false) }
    // **줄 수로 세지 않는다.** 상한은 줄바꿈된 화면 줄에 걸리므로, 줄바꿈 없는 거대한 한 줄
    // (한 줄로 뭉친 JSON 이 그렇다)은 잘리면서도 원문의 줄 수는 하나다 — 그렇게 세면 전문을
    // 볼 길이 없는 잘린 카드가 생긴다. 실제로 잘렸는지는 배치가 말해 준다.
    var truncated by remember(text) { mutableStateOf(false) }

    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = textColor,
        maxLines = MONOSPACE_BLOCK_MAX_LINES,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { truncated = it.hasVisualOverflow },
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest, MaterialTheme.shapes.small)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    )

    if (truncated) {
        FullTextPanelButton(detailTitle) { showingFullText = true }
    }

    if (showingFullText) {
        FullTextPanel(detailTitle, text) { showingFullText = false }
    }
}

/** 잘렸다는 사실과 그것을 펴는 길을 한 줄에 둔다. 잘림만 보이면 사용자는 나머지를 볼 길이 없다고 읽는다. */
@Composable
private fun FullTextPanelButton(detailTitle: String, onOpenRequested: () -> Unit) {
    TextButton(onClick = onOpenRequested, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
        Text("$detailTitle 전문 보기", style = MaterialTheme.typography.labelSmall)
    }
}

/**
 * 긴 출력의 전문(6.7).
 *
 * **글자를 고를 수 있다.** 도구 출력을 보는 사람이 다음에 하려는 일은 대개 그것을 어딘가로
 * 옮기는 것이다 — 고를 수 없으면 화면을 다시 찍어 옮겨 적는 수밖에 없다.
 */
@Composable
private fun FullTextPanel(title: String, text: String, onDismissRequested: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismissRequested,
        modifier = Modifier.width(FULL_TEXT_PANEL_WIDTH),
        title = { Text(title, style = MaterialTheme.typography.titleSmall) },
        text = {
            SelectionContainer {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = FULL_TEXT_PANEL_MAX_HEIGHT)
                        .verticalScroll(rememberScrollState()),
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismissRequested) { Text("닫기") } },
    )
}

/**
 * 관문이 열려 사람에게 묻는 카드(6.4).
 *
 * **다이얼로그가 아니라 전사 안이다.** 물음의 맥락 — 직전에 모델이 무엇을 하겠다고 했는지 — 이
 * 바로 위에 있어야 사용자가 판단할 수 있고, 다이얼로그는 그것을 가린다.
 *
 * **인자를 고쳐서 허용할 수 있다.** 고친 대로 실제로 실행되는 것을 실측이 쟀다(3.5): 모델이
 * `echo ORIGINAL` 을 요청했는데 결과가 `MODIFIED-BY-APP` 이었고 원래 파일은 만들어지지 않았다.
 * 그래서 이 자리의 편집은 시늉이 아니라 실행될 것을 바꾸는 일이다.
 *
 * **읽을 수 없는 JSON 으로는 보내지 않는다.** 보내면 엔진이 그것을 인자로 넘기지 못하고, 그
 * 실패는 사용자가 고친 글자와 한참 떨어진 자리에서 드러난다.
 */
@Composable
private fun PermissionRequestCard(
    entry: ChatEntry.PermissionAsked,
    sessionWorkingDirectory: Path?,
    onPermissionChoiceMade: (String, PermissionCardChoice) -> Unit,
) {
    val requestedInputText = remember(entry.input) { prettyPrintedToolInput(entry.input) }
    var inputText by remember(entry.toolUseId) { mutableStateOf(requestedInputText) }
    var denyReason by remember(entry.toolUseId) { mutableStateOf("") }
    var inputUnreadable by remember(entry.toolUseId) { mutableStateOf(false) }
    val toolLabel = remember(entry.toolName) { toolLabel(entry.toolName) }

    fun chooseWithEditedInput(choice: (JsonObject) -> PermissionCardChoice) {
        val input = parsedToolInputOrNull(inputText)
        if (input == null) {
            inputUnreadable = true
            return
        }
        onPermissionChoiceMade(entry.toolUseId, choice(input))
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.shapes.medium)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = if (entry.answer == null) "승인이 필요합니다" else "승인 물음",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = toolLabel,
                style = MaterialTheme.typography.labelLarge,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }

        // 어느 자리에서 도는 세션의 물음인가. 챗이 여럿 열려 있으면 이것이 판단의 절반이다(6.4).
        sessionWorkingDirectory?.let {
            Text(
                text = it.toString(),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (entry.answer == null) {
            OutlinedTextField(
                value = inputText,
                onValueChange = {
                    inputText = it
                    inputUnreadable = false
                },
                label = { Text("인자 — 고쳐서 허용할 수 있습니다") },
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth().heightIn(max = PERMISSION_INPUT_MAX_HEIGHT),
            )

            if (inputUnreadable) {
                Text(
                    text = "이 인자를 JSON 으로 읽지 못했습니다 — 고친 글자를 확인하세요.",
                    style = MaterialTheme.typography.labelSmall,
                    color = KyuTheme.statusColors.failure,
                )
            }

            OutlinedTextField(
                value = denyReason,
                onValueChange = { denyReason = it },
                label = { Text("거부 이유 (선택) — 모델이 그대로 읽습니다") },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { chooseWithEditedInput(PermissionCardChoice::AllowOnce) }) {
                    Text("허용")
                }
                TextButton(onClick = { chooseWithEditedInput(PermissionCardChoice::AllowForThisSession) }) {
                    // 무엇을 계속 허용하는지 버튼이 직접 말한다. "계속 허용" 만 적으면 사용자는
                    // 그것이 이 호출인지 이 도구인지 이 세션인지 알 수 없다.
                    Text("이 세션에서 $toolLabel 계속 허용")
                }
                TextButton(
                    onClick = {
                        onPermissionChoiceMade(
                            entry.toolUseId,
                            PermissionCardChoice.Deny(denyReason.ifBlank { DEFAULT_DENY_REASON }),
                        )
                    },
                ) {
                    Text("거부", color = KyuTheme.statusColors.failure)
                }
            }
        } else {
            MonospaceBlock(requestedInputText, detailTitle = "$toolLabel 인자")
            PermissionAnswerLine(entry.answer, toolLabel)
        }
    }
}

/**
 * 답한 뒤에 카드가 남기는 한 줄.
 *
 * **규칙이 자동으로 허용한 것을 사람이 허용한 것과 같은 모양으로 그리지 않는다.** 그러면 사용자는
 * 자기가 보지 않은 승인을 자기가 한 것으로 읽는다.
 */
@Composable
private fun PermissionAnswerLine(answer: PermissionAnswer, toolLabel: String) {
    val (text, color) = when (answer) {
        is PermissionAnswer.Allowed -> if (answer.editedInput == null) {
            "허용했습니다" to KyuTheme.statusColors.success
        } else {
            "인자를 고쳐서 허용했습니다" to KyuTheme.statusColors.success
        }

        is PermissionAnswer.AllowedForThisSession ->
            "허용했습니다 — 이 세션에서 $toolLabel 은 다시 묻지 않습니다" to KyuTheme.statusColors.success

        is PermissionAnswer.AllowedByThisSessionRule ->
            "이 세션의 규칙이 허용했습니다 — 묻지 않았습니다" to KyuTheme.statusColors.caution

        is PermissionAnswer.Denied -> "거부했습니다 — ${answer.reason}" to KyuTheme.statusColors.failure
    }

    Text(text = text, style = MaterialTheme.typography.labelMedium, color = color)

    // 고친 인자는 원본과 나란히 둔다. 무엇이 실제로 돌았는지가 카드에 없으면, 나중에 전사를 읽는
    // 사람은 모델이 요청한 것이 그대로 돈 줄 안다.
    if (answer is PermissionAnswer.Allowed && answer.editedInput != null) {
        MonospaceBlock(prettyPrintedToolInput(answer.editedInput), detailTitle = "고쳐서 실행한 인자")
    }
}

/** 카드에서 고친 인자를 다시 JSON 으로 읽는다. 객체가 아니면 null 이다 — 도구 인자는 늘 객체다. */
private fun parsedToolInputOrNull(inputText: String): JsonObject? = try {
    toolInputJson.parseToJsonElement(inputText) as? JsonObject
} catch (failure: SerializationException) {
    null
} catch (failure: IllegalArgumentException) {
    null
}

/** 사용자가 이유를 적지 않았을 때 모델이 읽을 문구. 빈 문구를 보내면 모델은 말할 것이 없다. */
private const val DEFAULT_DENY_REASON = "사용자가 이 도구 호출을 거절했습니다"

/** 승인 카드의 인자 편집칸 높이. 넘치면 그 안에서 스크롤된다. */
private val PERMISSION_INPUT_MAX_HEIGHT = 260.dp

private fun prettyPrintedToolInput(input: JsonObject): String = toolInputJson.encodeToString(JsonObject.serializer(), input)

/** 인자를 한 줄로 붙여 두면 Write 의 내용이나 Bash 의 긴 명령이 통째로 한 줄이 된다. */
private val toolInputJson = Json { prettyPrint = true }

/** 사용자의 말이 폭을 다 쓰지 않게 하는 선. 짧은 말이 대부분이라 이 값은 긴 말에만 걸린다. */
private const val USER_BUBBLE_WIDTH_FRACTION = 0.82f

/** 도구의 인자·결과를 접힌 카드 안에서 보여줄 상한. 넘으면 말줄임표가 붙는다. */
private const val MONOSPACE_BLOCK_MAX_LINES = 40

/**
 * diff 한 덩이에서 카드가 보여줄 줄 수.
 *
 * 대화의 흐름 안에 있는 카드라 파일 하나를 통째로 담는 자리가 아니다. 넘치는 줄이 몇인지를
 * 적어 두어, 사용자가 "다 본 것" 과 "일부만 본 것" 을 가를 수 있게 한다.
 */
private const val DIFF_LINES_SHOWN_IN_CARD = 30

/** 경과 시간이 다시 그려지는 주기. 1 초보다 잦게 그릴 이유가 없다 — 화면이 적는 단위가 초다. */
private const val ELAPSED_TICK_MILLIS = 1_000L

/** 상세 패널의 폭과 높이. 창(1360dp)보다 좁게 두어 뒤의 대화가 가장자리에 남아 있게 한다. */
private val FULL_TEXT_PANEL_WIDTH = 900.dp

private val FULL_TEXT_PANEL_MAX_HEIGHT = 520.dp
