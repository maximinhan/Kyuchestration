package com.kyuchestration.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kyuchestration.desktop.terminal.chat.ChatEntry
import com.kyuchestration.desktop.theme.KyuTheme
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 메인 세션이 레포에 시킨 일(설계 5.7 · 6.3 의 `DelegationCard`).
 *
 * **도는 동안 이 카드가 보여줄 수 있는 것은 셋뿐이다** — 어느 레포에, 무엇을 시켰고, 얼마나
 * 됐는가. **진행 막대는 없다.** MCP 도구의 진행 알림은 스트림에 오지 않는 것을 쟀다(3.10 가):
 * 프로브 서버가 세 번 보냈는데 이벤트는 0 개였다. 모르는 것을 그린 척하지 않는다(원칙 15).
 *
 * **끝난 뒤에 보이는 것은 엔진의 답 문서에 있는 것뿐이다**(DelegationAnswer). 바뀐 파일 목록
 * 같은 것은 그 문서에 없고, 위임의 답 본문에서 뽑아내지 않는다 — 모델의 문장을 파싱해 만든
 * 목록은 사실이 아니다.
 *
 * **완결되지 않은 위임을 완료로 그리지 않는다.** 권한에 막힌 위임은 종료 코드 0 으로 끝나므로
 * (orchestration 3.3), 엔진이 실어 보낸 `incomplete` 문장이 이 카드가 그것을 아는 유일한
 * 근거다 — 그것이 있으면 머리말이 "완결되지 않음" 이라고 말한다.
 */
@Composable
internal fun DelegationCard(entry: ChatEntry.ToolCall) {
    val answerText = entry.answer?.modelVisibleText
    val delegation = remember(answerText) { answerText?.let(::delegationAnswerOrNull) }

    val repoName = delegation?.repo ?: entry.input.stringOrNull("repo").orEmpty()
    val requestedTask = entry.input.stringOrNull("prompt").orEmpty()

    CollapsibleBlock(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        header = {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(text = "위임", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)

                if (repoName.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = repoName,
                        style = MaterialTheme.typography.labelLarge,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.width(10.dp))
                DelegationStatus(entry, delegation)
            }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (requestedTask.isNotEmpty()) {
                MonospaceBlock(requestedTask, detailTitle = "시킨 일")
            }

            if (delegation == null) {
                // 아직 안 끝났거나, 위임이 걸리기도 전에 끝난 물음이다. 뒤쪽은 사람이 읽을 문장
                // 하나로 오므로(레포 이름이 틀렸다 · 승인이 먼저다) 그것을 그대로 보인다.
                entry.answer?.let { MonospaceBlock(it.modelVisibleText, detailTitle = "위임의 답") }
            } else {
                DelegationOutcome(delegation)
            }
        }
    }
}

/** 도는 중이면 경과 시간, 끝났으면 그 결말과 숫자 배지. */
@Composable
private fun DelegationStatus(entry: ChatEntry.ToolCall, delegation: DelegationAnswer?) {
    val answer = entry.answer
    val (label, color) = when {
        answer == null -> "도는 중" to MaterialTheme.colorScheme.onSurfaceVariant
        delegation?.incomplete != null -> "완결되지 않음" to KyuTheme.statusColors.caution
        answer.failed -> "실패" to KyuTheme.statusColors.failure
        else -> "완료" to KyuTheme.statusColors.success
    }

    Text("●", color = color, style = MaterialTheme.typography.labelSmall)
    Spacer(Modifier.width(5.dp))
    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)

    if (answer == null) {
        RunningElapsedText(entry.requestedAt)
        return
    }

    // 끝난 뒤의 시간은 엔진이 잰 값을 쓴다. 앱이 두 이벤트 사이를 다시 재면 MCP 왕복까지 포함된
    // 다른 숫자가 나오고, 원출력 기록에 적힌 값과 화면이 어긋난다.
    val badges = delegation?.let {
        listOfNotNull(
            turnElapsedLabel(it.durationMillis),
            turnCostLabel(it.costUsd).takeIf { _ -> it.costUsd > 0 },
            "${it.turnCount}턴".takeIf { _ -> it.turnCount > 0 },
            "대화 이어감".takeIf { _ -> it.resumedConversation },
        )
    }.orEmpty()

    if (badges.isNotEmpty()) {
        Spacer(Modifier.width(8.dp))
        Text(
            text = badges.joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 끝난 위임이 펼쳐진 카드에서 말하는 것 — 답 본문과, 그 답이 완결되지 않았다면 그 이유. */
@Composable
private fun DelegationOutcome(delegation: DelegationAnswer) {
    delegation.incomplete?.let { NoticeLine(it, KyuTheme.statusColors.caution) }

    if (delegation.permissionDeniedTools.isNotEmpty()) {
        NoticeLine(
            text = "권한에 막혀 부르지 못한 도구: ${delegation.permissionDeniedTools.joinToString(", ")}",
            color = KyuTheme.statusColors.failure,
        )
    }

    // 앞선 대화를 잃었다는 사실이 여기로 온다. 엔진은 이것을 답에 실어 보낸다 — 위임의 stderr 는
    // 아무도 읽지 않기 때문이다(mcp_run_in_repo.go 의 ConversationWarnings).
    delegation.conversationWarnings.forEach { NoticeLine(it, KyuTheme.statusColors.caution) }

    if (delegation.result.isNotEmpty()) {
        MonospaceBlock(delegation.result, detailTitle = "위임의 답")
    }

    if (delegation.standardError.isNotEmpty()) {
        MonospaceBlock(
            text = delegation.standardError,
            detailTitle = "표준 오류",
            textColor = KyuTheme.statusColors.failure,
        )
    }

    // 원출력 자리를 그대로 보인다. 파일을 여기서 읽어 오지 않는 것은 orchestration 6.3 이 정한
    // 자세다 — 위임 출력 전용 뷰어는 실제로 불편해진 뒤에 만든다.
    if (delegation.logPath.isNotEmpty()) {
        Text(
            text = "원출력: ${delegation.logPath}",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    if (delegation.logFailure.isNotEmpty()) {
        NoticeLine("원출력을 남기지 못했습니다 — ${delegation.logFailure}", KyuTheme.statusColors.caution)
    }
}

@Composable
private fun NoticeLine(text: String, color: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = color,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * 이 도구 이름이 위임이다.
 *
 * 엔진이 서버를 `kyu` 라는 이름으로 등록하고(claude_command.go 의 `orchestrationServerName`),
 * MCP 도구 이름은 `mcp__<서버>__<도구>` 로 모델에게 보인다(실측 A.15). 그래서 이 문자열은 우리
 * 엔진과 맺은 계약이고, 다른 서버가 같은 이름의 도구를 열어도 이 카드가 되지 않는다.
 */
internal const val RUN_IN_REPO_TOOL_NAME = "mcp__kyu__run_in_repo"

private fun JsonObject.stringOrNull(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
