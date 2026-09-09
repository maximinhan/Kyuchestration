package com.kyuchestration.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kyuchestration.desktop.terminal.chat.ChatEntry
import com.kyuchestration.desktop.terminal.chat.PermissionCardChoice
import com.kyuchestration.desktop.terminal.chat.SubagentRun
import com.kyuchestration.desktop.theme.KyuTheme
import java.nio.file.Path

/**
 * 서브에이전트 하나가 한 일(설계 6.3 의 `SubagentCard` · 3.10).
 *
 * **안쪽 대화를 접어 담는 것이 이 카드의 존재 이유다.** 서브에이전트의 말과 도구 호출은
 * `parent_tool_use_id` 가 채워진 채로 메인 스트림에 섞여 온다 — 접지 않으면 메인 대화와 안쪽
 * 대화가 한 줄기가 되고, 누가 한 말인지 알 수 없게 된다.
 *
 * **접힌 줄이 "지금 무엇을 하는 중인가" 를 말한다.** 서브에이전트는 몇 분씩 도는 일이라,
 * 접힌 카드가 이름만 말하면 사용자는 그것이 살아 있는지조차 모른다. `task_progress` 가 주는
 * 설명과 마지막 도구 이름이 그 자리에 선다(3.10) — 진행 막대는 없다. 몇 걸음 중 몇 번째인지는
 * 스트림이 말해 주지 않고, 모르는 것을 그린 척하지 않는다(원칙 15).
 */
@Composable
internal fun SubagentCard(
    entry: ChatEntry.ToolCall,
    run: SubagentRun,
    sessionWorkingDirectory: Path?,
    onPermissionChoiceMade: (String, PermissionCardChoice) -> Unit,
) {
    CollapsibleBlock(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        header = {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "서브에이전트",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )

                // 종류는 시작 이벤트가 알려준다. 그것을 놓친 카드에서는 비어 있고, 그때는 적지
                // 않는다 — 빈 자리를 "일반" 같은 우리 낱말로 채우면 그것이 사실처럼 보인다.
                if (run.subagentType.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = run.subagentType,
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.width(10.dp))
                SubagentStatus(entry, run)
            }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // 안쪽 대화가 곧 이 카드의 본문이다. 들여쓰기가 "여기서부터는 다른 대화" 를 말한다.
            if (entry.nestedEntries.isNotEmpty()) {
                Column(
                    modifier = Modifier.padding(start = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    entry.nestedEntries.forEach {
                        ChatEntryView(it, sessionWorkingDirectory, onPermissionChoiceMade)
                    }
                }
            } else {
                // 안쪽 대화가 하나도 안 온 자리다. 그래도 그 에이전트가 낸 답은 바깥 도구
                // 결과에 있으므로, 카드가 비어 보이게 두지 않는다.
                entry.answer?.let { MonospaceBlock(it.modelVisibleText, detailTitle = "서브에이전트가 낸 답") }
            }

            // 이 실행의 원출력 자리(`task_notification.output_file`). 파일을 여기서 읽어 오지
            // 않는다 — 진단 기록 자리를 알려 주는 자세 그대로, 경로를 그대로 보인다.
            run.outputFilePath?.let {
                Text(
                    text = "원출력: $it",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * 그 서브에이전트가 지금 어떤 상태인가.
 *
 * 색만으로 가르지 않는다(6.7) — 점이 색을 지고 낱말이 뜻을 진다. 도는 중에는 그 낱말 뒤에
 * 스트림이 준 설명과 마지막 도구 이름을 붙인다.
 */
@Composable
private fun SubagentStatus(entry: ChatEntry.ToolCall, run: SubagentRun) {
    val failed = entry.answer?.failed == true
    val (label, color) = when {
        failed -> "실패" to KyuTheme.statusColors.failure
        entry.answer != null -> finishedLabel(run) to KyuTheme.statusColors.success
        else -> "도는 중" to MaterialTheme.colorScheme.onSurfaceVariant
    }

    Text("●", color = color, style = MaterialTheme.typography.labelSmall)
    Spacer(Modifier.width(5.dp))
    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)

    val progress = runningProgressText(entry, run)
    if (progress != null) {
        Spacer(Modifier.width(8.dp))
        Text(
            text = progress,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 끝난 카드가 다는 낱말.
 *
 * `completed` 가 아닌 값이 오면 그 낱말을 그대로 보인다. 끊긴 서브에이전트를 "완료" 로 적으면
 * 그 카드는 사실이 아닌 말을 하게 되고, 우리가 본 적 없는 상태를 우리 낱말로 옮기는 것도
 * 같은 종류의 지어내기다.
 */
private fun finishedLabel(run: SubagentRun): String = when (run.finishedStatus) {
    null, SUBAGENT_COMPLETED_STATUS -> "완료"
    else -> run.finishedStatus
}

/** 도는 중에만 붙는 한 줄 — 무엇을 하는 중이고 마지막으로 부른 도구가 무엇인가. */
private fun runningProgressText(entry: ChatEntry.ToolCall, run: SubagentRun): String? {
    if (entry.answer != null) {
        return null
    }
    return listOfNotNull(run.lastDescription?.takeIf { it.isNotEmpty() }, run.lastToolName)
        .takeIf { it.isNotEmpty() }
        ?.joinToString(" · ")
}

/** 실측에서 온 정상 종료 값(`task_notification.status`). */
private const val SUBAGENT_COMPLETED_STATUS = "completed"
