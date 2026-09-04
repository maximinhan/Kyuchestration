package com.kyuchestration.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
        is ChatEntry.UserSaid -> UserMessageBubble(entry.text)
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
 * 모델이 완성한 텍스트 블록. 말풍선 없이 폭 전체를 쓴다(6.3).
 *
 * 마크다운은 다음 걸음에서 붙는다(5.8) — 지금은 온 글자 그대로다.
 */
@Composable
private fun AssistantMessageBlock(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth(),
    )
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
        MonospaceBlock(text)
    }
}

/**
 * 도구 호출 하나(6.3).
 *
 * **갈래 없이 하나로 그린다.** Bash 는 명령을, Edit 는 diff 를 보여야 하지만 그것은 4 단계의
 * 몫이다 — 지금은 모든 도구가 이 밋밋한 카드로 떨어지고, 그 자리가 곧 4 단계에서 갈릴 자리다.
 * 모르는 도구가 이 카드로 떨어지는 것은 그때도 그대로다: MCP 도구는 얼마든지 새로 붙는다.
 *
 * **실패는 접힌 채로도 보인다.** 결과의 첫 줄을 머리말에 두지 않으면 무엇이 잘못됐는지 알려고
 * 카드를 하나씩 펴 봐야 하고, 그러면 실패가 대화에 묻힌다.
 */
@Composable
private fun ToolCallCard(entry: ChatEntry.ToolCall) {
    CollapsibleBlock(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        header = {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = entry.toolName,
                    style = MaterialTheme.typography.labelLarge,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.width(10.dp))
                ToolCallStatus(entry.answer)
            }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            MonospaceBlock(prettyPrintedToolInput(entry.input))
            entry.answer?.let { MonospaceBlock(it.modelVisibleText) }

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
 * 긴 것은 잘려 보인다 — 말줄임표가 그 사실을 말하고, 전문을 보는 상세 패널은 4 단계다(6.7).
 */
@Composable
private fun MonospaceBlock(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = MONOSPACE_BLOCK_MAX_LINES,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest, MaterialTheme.shapes.small)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    )
}

private fun prettyPrintedToolInput(input: JsonObject): String = toolInputJson.encodeToString(JsonObject.serializer(), input)

/** 인자를 한 줄로 붙여 두면 Write 의 내용이나 Bash 의 긴 명령이 통째로 한 줄이 된다. */
private val toolInputJson = Json { prettyPrint = true }

/** 사용자의 말이 폭을 다 쓰지 않게 하는 선. 짧은 말이 대부분이라 이 값은 긴 말에만 걸린다. */
private const val USER_BUBBLE_WIDTH_FRACTION = 0.82f

/** 도구의 인자·결과를 접힌 카드 안에서 보여줄 상한. 넘으면 말줄임표가 붙는다. */
private const val MONOSPACE_BLOCK_MAX_LINES = 40
