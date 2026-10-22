package com.kyuchestration.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kyuchestration.desktop.terminal.chat.ChatEntry
import com.kyuchestration.desktop.terminal.chat.ChildEndReason
import com.kyuchestration.desktop.terminal.chat.ChildLifecycle
import com.kyuchestration.desktop.terminal.chat.ChildSessionNode
import com.kyuchestration.desktop.terminal.chat.ChildWaitingFor
import com.kyuchestration.desktop.theme.KyuTheme
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 메인이 하위 세션을 띄운 도구 호출(session-tree-design.md 6.12 의 `spawn_session` 카드).
 *
 * 위임 카드(`DelegationCard`)와 같은 자리에 선다. 다른 것은 끝난 뒤다 — 위임은 답을 들고 끝나지만, 하위는
 * 이제 막 뜬 것이라 카드가 할 말은 "어디에 열었고, 거기로 가려면 누르라" 이다. 하위가 한 일은 그 하위의
 * 챗에 있고, 여기에 복제하지 않는다.
 */
@Composable
internal fun SpawnSessionCard(entry: ChatEntry.ToolCall, onOpenChildSessionRequested: (String) -> Unit) {
    val answer = entry.answer
    val spawned = remember(answer?.modelVisibleText) { answer?.modelVisibleText?.let(::spawnedChildSessionOrNull) }
    val requestedTask = (entry.input["prompt"] as? JsonPrimitive)?.contentOrNull.orEmpty()

    CollapsibleBlock(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        header = {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(text = "하위 세션", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(10.dp))

                when {
                    answer == null -> {
                        Text("띄우는 중", style = MaterialTheme.typography.labelSmall)
                        RunningElapsedText(entry.requestedAt)
                    }

                    spawned != null -> {
                        Text(
                            text = "${spawned.childId} 을 ${spawned.relativeCwd} 에서 열었습니다",
                            style = MaterialTheme.typography.labelMedium,
                            fontFamily = FontFamily.Monospace,
                        )
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { onOpenChildSessionRequested(spawned.childId) }) { Text("열기") }
                    }

                    else -> Text(
                        text = "띄우지 못함",
                        style = MaterialTheme.typography.labelSmall,
                        color = KyuTheme.statusColors.failure,
                    )
                }
            }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (requestedTask.isNotEmpty()) {
                MonospaceBlock(requestedTask, detailTitle = "시킨 일")
            }
            spawned?.notice?.let {
                Text(it, style = MaterialTheme.typography.labelMedium, color = KyuTheme.statusColors.caution)
            }
            // 거절이면 그 문장이 상위가 고칠 것을 말한다(cwd 규칙 · 관문 거절). 그대로 보인다.
            if (spawned == null) {
                answer?.let { MonospaceBlock(it.modelVisibleText, detailTitle = "spawn 의 답") }
            }
        }
    }
}

/** spawn 의 답에서 카드가 쓰는 것. */
internal data class SpawnedChildSession(
    val childId: String,
    val relativeCwd: String,
    val title: String,
    val notice: String?,
)

/**
 * 도구 결과의 텍스트를 spawn 의 답으로 읽는다. 우리 문서가 아니면 null — 거절은 문장 하나로 온다.
 *
 * 모양의 출처는 앱이 소켓에 쓴 줄이다(SessionTreeRequest.kt 의 answerLineFor). 엔진은 그것을 들여쓰기만 한다.
 */
internal fun spawnedChildSessionOrNull(answerText: String): SpawnedChildSession? {
    val document = try {
        Json.parseToJsonElement(answerText) as? JsonObject
    } catch (failure: SerializationException) {
        null
    } catch (failure: IllegalArgumentException) {
        null
    } ?: return null

    return SpawnedChildSession(
        childId = document.stringOrNull("id") ?: return null,
        relativeCwd = document.stringOrNull("cwd") ?: return null,
        title = document.stringOrNull("title").orEmpty(),
        notice = document.stringOrNull("notice"),
    )
}

/** 패널의 하위 줄이 말하는 상태(6.12 의 표 — 오는 것만 그린다). */
internal fun childSessionStatusLabel(node: ChildSessionNode): String = when (val lifecycle = node.lifecycle) {
    is ChildLifecycle.Running -> when (node.waitingFor) {
        ChildWaitingFor.Nothing -> "쉬는 중"
        ChildWaitingFor.Model -> "도는 중"
        ChildWaitingFor.Parent -> "상위에게 묻는 중"
        ChildWaitingFor.Approval -> "승인 대기"
    }

    is ChildLifecycle.Ended -> "끝남 · " + when (lifecycle.reason) {
        ChildEndReason.Exited -> "스스로 끝남"
        ChildEndReason.Crashed -> "비정상 종료(${lifecycle.exitCode})"
        ChildEndReason.EndedByHuman -> "사람이 끝냄"
        ChildEndReason.EndedByParent -> "상위가 끝냄"
        ChildEndReason.ParentEnded -> "상위가 끝나서"
    }
}

/**
 * 이 도구 이름이 하위 세션 띄우기다. 서버 이름 `kyu` 는 엔진과 맺은 계약이다(RUN_IN_REPO_TOOL_NAME 과 같은 이유).
 */
internal const val SPAWN_SESSION_TOOL_NAME = "mcp__kyu__spawn_session"

private fun JsonObject.stringOrNull(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
