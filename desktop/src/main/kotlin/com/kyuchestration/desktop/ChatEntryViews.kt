package com.kyuchestration.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.kyuchestration.desktop.terminal.chat.ToolCallAnswer
import com.kyuchestration.desktop.terminal.chat.TurnOutcome
import com.kyuchestration.desktop.theme.KyuTheme
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * 전사의 항목 하나를 그린다(설계 6.3).
 *
 * **갈래를 여기 한 곳에서 가른다.** when 이 남김없이 덮는지는 컴파일러가 봐 주므로, 상태 쪽에
 * 항목이 하나 늘면 이 파일이 컴파일되지 않는다 — 그릴 자리 없는 항목이 조용히 사라지지 않는다.
 */
@Composable
internal fun ChatEntryView(entry: ChatEntry) {
    when (entry) {
        // 중단 안내가 사용자 메시지와 같은 모양으로 온다(3.8). 가르는 자리가 여기다 —
        // 왜 어댑터가 아닌지는 isTurnInterruptionEcho 에 적혀 있다.
        is ChatEntry.UserSaid ->
            if (isTurnInterruptionEcho(entry.text)) TurnInterruptedNotice() else UserMessageBubble(entry.text)
        is ChatEntry.AssistantSaid -> AssistantMessageBlock(entry.text)
        is ChatEntry.AssistantThought -> ThinkingBlock(entry.text)
        is ChatEntry.ToolCall -> ToolCallCard(entry)
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
private fun ToolCallCard(entry: ChatEntry.ToolCall) {
    val cardContent = toolCallCardContentOf(entry.toolName, entry.input, entry.answer?.typedResult)

    CollapsibleBlock(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        header = {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                ToolCallSummary(cardContent)
                Spacer(Modifier.width(10.dp))
                ToolCallStatus(entry.answer)
            }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ToolCallDetail(cardContent, entry)

            // 안쪽에서 일어난 것들 — 서브에이전트의 대화가 여기 접혀 있다(3.10). 카드 안이라는
            // 것을 들여쓰기로 말한다.
            if (entry.nestedEntries.isNotEmpty()) {
                Column(
                    modifier = Modifier.padding(start = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    entry.nestedEntries.forEach { ChatEntryView(it) }
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
 */
@Composable
private fun ToolCallSummary(cardContent: ToolCallCardContent) {
    val (name, detail) = when (cardContent) {
        is ToolCallCardContent.ShellCommand -> "Bash" to cardContent.command.lineSequence().first()

        is ToolCallCardContent.FileChanged -> {
            val counts = if (cardContent.createdFile) {
                "새 파일 +${cardContent.addedLineCount}"
            } else {
                "+${cardContent.addedLineCount} −${cardContent.removedLineCount}"
            }
            "편집" to "${fileName(cardContent.filePath)} $counts"
        }

        is ToolCallCardContent.FileRead ->
            "읽기" to fileName(cardContent.filePath) + cardContent.totalLineCount?.let { " · ${it}줄" }.orEmpty()

        is ToolCallCardContent.AnyTool -> cardContent.toolLabel to ""
    }

    Text(
        text = name,
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
private fun ToolCallDetail(cardContent: ToolCallCardContent, entry: ChatEntry.ToolCall) {
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
            MonospaceBlock(prettyPrintedToolInput(entry.input), detailTitle = "${cardContent.toolLabel} 인자")
            entry.answer?.let { MonospaceBlock(it.modelVisibleText, detailTitle = "${cardContent.toolLabel} 결과") }
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

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest, MaterialTheme.shapes.small)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        hunks.forEachIndexed { hunkOrdinal, hunk ->
            // 덩이 사이가 붙어 있으면 떨어진 두 자리의 변경이 한 덩이로 읽힌다.
            if (hunkOrdinal > 0) {
                Text(
                    text = "⋯",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            hunk.lines.take(DIFF_LINES_SHOWN_IN_CARD).forEach { line ->
                val (marker, color) = when (line.kind) {
                    DiffLineKind.Added -> "+" to KyuTheme.statusColors.success
                    DiffLineKind.Removed -> "−" to KyuTheme.statusColors.failure
                    DiffLineKind.Context -> " " to MaterialTheme.colorScheme.onSurfaceVariant
                }

                Text(
                    text = "$marker ${line.text}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = color,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (hunk.lines.size > DIFF_LINES_SHOWN_IN_CARD) {
                Text(
                    text = "…그 밖에 ${hunk.lines.size - DIFF_LINES_SHOWN_IN_CARD}줄",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (hunks.any { it.lines.size > DIFF_LINES_SHOWN_IN_CARD }) {
        FullTextPanelButton("바뀐 줄") { showingFullDiff = true }
    }

    if (showingFullDiff) {
        FullTextPanel("바뀐 줄", plainTextDiff(hunks)) { showingFullDiff = false }
    }
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

/** 카드에는 파일 이름만 둔다. 전체 경로는 펼친 자리에 있다 — 접힌 줄에서는 이름이 먼저 읽힌다. */
private fun fileName(filePath: String): String = filePath.substringAfterLast('/')

/**
 * 그 도구 호출이 어떻게 됐는가.
 *
 * 색만으로 가르지 않는다(6.7). 점은 색을 지고 낱말이 뜻을 진다 — 이 앱의 상태 칩이 이미 지키는
 * 규율이다(WorkDirSessionPanel 의 SessionChip).
 */
@Composable
private fun ToolCallStatus(answer: ToolCallAnswer?) {
    val (label, color) = when {
        answer == null -> "도는 중" to MaterialTheme.colorScheme.onSurfaceVariant
        answer.failed -> "실패" to KyuTheme.statusColors.failure
        else -> "완료" to KyuTheme.statusColors.success
    }

    Text("●", color = color, style = MaterialTheme.typography.labelSmall)
    Spacer(Modifier.width(5.dp))
    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)

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
        // 권한이 없어 못 한 일이 있으면 그 사실이 화면에 있어야 한다(3.6). 없으면 모델이 "권한이
        // 없어서 못 했습니다" 라고 말하는 것이 유일한 통로가 된다.
        entry.permissionDenialCount.takeIf { it > 0 }?.let { "권한 거절 ${it}건" },
    )

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
private fun CollapsibleBlock(
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
private fun MonospaceBlock(
    text: String,
    detailTitle: String,
    textColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    var showingFullText by remember(text) { mutableStateOf(false) }

    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = textColor,
        maxLines = MONOSPACE_BLOCK_MAX_LINES,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest, MaterialTheme.shapes.small)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    )

    if (text.lineSequence().count() > MONOSPACE_BLOCK_MAX_LINES) {
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

/** 상세 패널의 폭과 높이. 창(1360dp)보다 좁게 두어 뒤의 대화가 가장자리에 남아 있게 한다. */
private val FULL_TEXT_PANEL_WIDTH = 900.dp

private val FULL_TEXT_PANEL_MAX_HEIGHT = 520.dp
