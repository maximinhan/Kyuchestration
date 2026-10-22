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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kyuchestration.desktop.terminal.chat.ChatEntry
import com.kyuchestration.desktop.terminal.chat.UserMessageSender
import com.kyuchestration.desktop.theme.KyuTheme
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 하위가 상위에게 물은 도구 호출(session-tree-design.md 6.12 의 "상위에게 물음" 카드).
 *
 * 하위의 챗 안에 선다. 하위의 턴은 이 카드의 답이 올 때까지 멈춰 있다 — 그 사실이 머리말에 보여야, 하위 탭을 보던
 * 사람이 하위가 멈춘 까닭을 안다. 사람은 여기서 답하지 않는다(6.5 의 넷째 줄): 물음은 상위에게 한 것이고, 답을 주고
 * 싶으면 상위에게 말한다.
 */
@Composable
internal fun AskParentCard(entry: ChatEntry.ToolCall) {
    val answer = entry.answer
    val reply = remember(answer?.modelVisibleText) { answer?.modelVisibleText?.let(::parentReplyOrNull) }
    val question = (entry.input["message"] as? JsonPrimitive)?.contentOrNull.orEmpty()

    CollapsibleBlock(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        header = {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(text = "상위에게 물음", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(10.dp))

                when {
                    answer == null -> {
                        Text("상위의 답을 기다리는 중", style = MaterialTheme.typography.labelSmall)
                        RunningElapsedText(entry.requestedAt)
                    }

                    reply is ParentReply.Answered -> Text(
                        text = "상위가 답했습니다",
                        style = MaterialTheme.typography.labelSmall,
                        color = KyuTheme.statusColors.success,
                    )

                    reply is ParentReply.Ended -> Text(
                        text = parentReplyEndedLabel(reply.reason),
                        style = MaterialTheme.typography.labelSmall,
                        color = KyuTheme.statusColors.caution,
                    )

                    // 중단되어 claude 가 지은 거절 문장 · 앱에 닿지 못한 오류가 여기로 온다.
                    else -> Text(
                        text = "답을 받지 못함",
                        style = MaterialTheme.typography.labelSmall,
                        color = KyuTheme.statusColors.failure,
                    )
                }
            }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (question.isNotEmpty()) {
                MonospaceBlock(question, detailTitle = "상위에게 한 물음")
            }
            when (reply) {
                is ParentReply.Answered -> MonospaceBlock(reply.answer, detailTitle = "상위의 답")
                is ParentReply.Ended -> Unit
                null -> answer?.let { MonospaceBlock(it.modelVisibleText, detailTitle = "ask_parent 의 결과") }
            }
        }
    }
}

/** 하위의 물음에 돌아온 것 — 상위의 답이거나, 답 없이 끝난 까닭. */
internal sealed interface ParentReply {
    data class Answered(val answer: String) : ParentReply

    /** @param reason 앱이 실은 낱말 그대로(`parent_ended` · `session_ended`). */
    data class Ended(val reason: String) : ParentReply
}

/**
 * 도구 결과의 텍스트를 하위의 물음에 돌아온 것으로 읽는다. 우리 문서가 아니면 null.
 *
 * 모양의 출처는 앱이 소켓에 쓴 줄이다(SessionTreeRequest.kt 의 answerLineFor — ParentAnswered · AskEnded).
 */
internal fun parentReplyOrNull(answerText: String): ParentReply? {
    val document = try {
        Json.parseToJsonElement(answerText) as? JsonObject
    } catch (failure: SerializationException) {
        null
    } catch (failure: IllegalArgumentException) {
        null
    } ?: return null

    (document["answer"] as? JsonPrimitive)?.contentOrNull?.let { return ParentReply.Answered(it) }
    (document["ended"] as? JsonPrimitive)?.contentOrNull?.let { return ParentReply.Ended(it) }
    return null
}

private fun parentReplyEndedLabel(reason: String): String = when (reason) {
    "parent_ended" -> "상위가 끝나 답이 없습니다"
    "session_ended" -> "이 세션이 끝나 답이 없습니다"
    else -> "답 없이 끝났습니다 ($reason)"
}

/**
 * 사용자 메시지 자리로 들어온 말에 붙일 이름표. 사람의 말이면 null — 이름표 없는 말풍선이 사람의 것이다.
 *
 * 세션이 보낸 말을 사람의 말풍선과 같은 모양으로 그리면, 사람은 자기가 하지 않은 말을 자기 것으로 읽는다(6.12).
 */
internal fun userMessageSenderLabel(sender: UserMessageSender): String? = when (sender) {
    is UserMessageSender.Human -> null
    is UserMessageSender.ParentSession -> "상위로부터"
    is UserMessageSender.ChildSession -> "${sender.childId} 로부터"
}

/** 이 도구 이름이 하위의 물음이다. 서버 이름 `kyu` 는 엔진과 맺은 계약이다(SPAWN_SESSION_TOOL_NAME 과 같은 이유). */
internal const val ASK_PARENT_TOOL_NAME = "mcp__kyu__ask_parent"
