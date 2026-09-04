package com.kyuchestration.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kyuchestration.desktop.terminal.chat.ChatConversation
import com.kyuchestration.desktop.terminal.chat.TurnState

/**
 * 사용자가 말을 거는 자리(설계 6.5).
 *
 * | 정한 것 | 왜 |
 * |---|---|
 * | Enter 로 보내고 Shift+Enter 로 줄바꿈 | 대화창의 관례다. 보내는 일이 훨씬 잦다 |
 * | 도는 중에도 보낼 수 있다 | 3.11 — `claude` 가 큐를 갖고 있다. 3 단계의 잠금을 여기서 푼다 |
 * | 보낸 것이 되돌아올 때까지 "대기 중" 으로 선다 | 아래 설명 |
 * | 도는 동안 중단 버튼이 함께 선다 | 3.8 — 턴만 끊기고 대화는 산다 |
 * | 끝난 세션에서는 아예 없다 | 보낼 곳이 없는 입력창은 눌러도 아무 일이 없는 죽은 면이다 |
 *
 * **잠금을 푼 근거가 실측이다.** 3 단계는 잠갔다 — 중단 버튼이 없어 쌓아 둔 말을 되돌릴 길이
 * 없어서였다(열린 질문 8). 그 버튼이 이 단계에 섰고, 큐에 든 말이 중단 뒤에도 살아남아 다음
 * 턴으로 도는 것을 쟀다(2026-09-04). 그래서 잠글 이유가 사라졌다.
 *
 * **다만 열어 두는 것만으로는 모자랐다.** 같은 실측에서, 도는 중에 보낸 말은 그 턴이 끝나고
 * 다음 턴이 시작할 때에야 되돌아왔다 — **보낸 지 78 초 뒤**다. 전사는 되돌아온 것으로만
 * 그리므로(원칙 14), 그동안 사용자가 친 말은 화면 어디에도 없다. 그 공백을 입력창 위의
 * "대기 중" 줄이 메운다 — 전사가 아니라 앱이 자기가 보낸 것을 아는 자리다(3.11 이 허락한 셈).
 *
 * **보내기와 중단이 같은 자리를 나눠 쓰지 않는다.** 설계 6.5 는 "같은 자리의 버튼이 중단으로
 * 바뀐다" 였다. 입력창이 열려 있는 지금은 그럴 수 없다 — 도는 중에 친 말을 보낼 버튼이 그
 * 자리에 있어야 하고, 하나가 두 일을 하면 보내려던 손이 턴을 끊는다.
 */
@Composable
internal fun ChatComposer(
    conversation: ChatConversation,
    onSendUserMessageRequested: (String) -> Unit,
    onInterruptTurnRequested: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!conversation.alive) {
        return
    }

    var composerValue by remember(conversation.target) { mutableStateOf(TextFieldValue()) }
    val waitingForAnswer = conversation.turnState != TurnState.Idle
    val sendable = composerValue.text.isNotBlank()

    val send = {
        if (sendable) {
            onSendUserMessageRequested(composerValue.text.trim())
            // 조합 중이던 글자까지 함께 놓는다. text 만 비우면 IME 가 쥐고 있던 조합이 다음
            // 글자에 되살아난다.
            composerValue = TextFieldValue()
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        PendingUserMessages(conversation.pendingUserMessages)

        // 요청을 보냈다는 표시(6.6). 답이 오기 시작하기 전의 몇 초 동안 화면에 아무 일도 일어나지
        // 않는데, 그 사이를 이 줄이 메운다.
        if (waitingForAnswer) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().heightIn(max = PROGRESS_LINE_HEIGHT))
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            OutlinedTextField(
                value = composerValue,
                onValueChange = { composerValue = it },
                placeholder = {
                    Text(
                        text = if (waitingForAnswer) {
                            "지금 보내면 답이 오는 대로 이어서 답합니다"
                        } else {
                            "${conversation.target.label} 에게 말하기 (Enter 로 보내기 · Shift+Enter 로 줄바꿈)"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                textStyle = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(max = COMPOSER_MAX_HEIGHT)
                    // 미리 보는 자리에서 가른다. 텍스트 필드가 먼저 받으면 줄바꿈이 이미 들어간
                    // 뒤라, 보낼 때마다 빈 줄 하나가 딸려 간다.
                    .onPreviewKeyEvent { keyEvent ->
                        val sendingKey = keyEvent.type == KeyEventType.KeyDown &&
                            keyEvent.key == Key.Enter &&
                            !keyEvent.isShiftPressed
                        if (sendingKey) {
                            send()
                        }
                        sendingKey
                    },
            )

            // 도는 동안에만 선다. 늘 두면 누를 수 없는 버튼이 화면의 절반을 차지하고, 그것은
            // 이 자리에서 할 수 있는 일이 무엇인지를 흐린다.
            if (waitingForAnswer) {
                OutlinedButton(
                    onClick = onInterruptTurnRequested,
                    // 이미 말했으면 다시 누르지 못한다. 끝났다는 사실은 result 가 말하므로
                    // (TurnState.Interrupting) 그때까지 이 버튼은 자기가 한 말을 보여준다.
                    enabled = conversation.turnState == TurnState.Running,
                ) {
                    Text(if (conversation.turnState == TurnState.Running) "중단" else "끊는 중")
                }
            }

            Button(onClick = send, enabled = sendable) { Text("보내기") }
        }
    }
}

/**
 * 보냈는데 아직 되돌아오지 않은 말들.
 *
 * **말풍선이 아니라 입력창 쪽에 둔다.** 전사는 스트림이 되돌려준 것만으로 그린다(원칙 14) —
 * 앱이 자기가 보낸 것을 전사에 끼워 넣으면 큐에 든 둘이 한 턴으로 합쳐질 때(3.11) 순서가
 * 어긋난다. 이 줄은 전사가 아니라 "지금 무엇을 기다리는가" 이고, 되돌아오는 순간 사라지면서
 * 같은 말이 전사에 말풍선으로 선다.
 */
@Composable
private fun PendingUserMessages(pendingUserMessages: List<String>) {
    if (pendingUserMessages.isEmpty()) {
        return
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        pendingUserMessages.forEach { pending ->
            Text(
                text = "대기 중 · $pending",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 입력창이 이보다 커지면 대화가 밀린다. 넘치는 것은 입력창 안에서 스크롤된다. */
private val COMPOSER_MAX_HEIGHT = 160.dp

private val PROGRESS_LINE_HEIGHT = 2.dp
