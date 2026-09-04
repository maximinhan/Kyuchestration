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
import androidx.compose.ui.unit.dp
import com.kyuchestration.desktop.terminal.chat.ChatConversation
import com.kyuchestration.desktop.terminal.chat.TurnState

/**
 * 사용자가 말을 거는 자리(설계 6.5).
 *
 * | 정한 것 | 왜 |
 * |---|---|
 * | Enter 로 보내고 Shift+Enter 로 줄바꿈 | 대화창의 관례다. 보내는 일이 훨씬 잦다 |
 * | 턴이 도는 동안 잠근다 | **설계 6.5 와 다르다** — 아래 설명 |
 * | 도는 동안 중단 버튼이 함께 선다 | 3.8 — 턴만 끊기고 대화는 산다 |
 * | 끝난 세션에서는 아예 없다 | 보낼 곳이 없는 입력창은 눌러도 아무 일이 없는 죽은 면이다 |
 *
 * **잠그는 쪽으로 정한 근거를 적어둔다.** 설계는 잠그지 않는 쪽이었다 — `claude` 가 큐를 갖고
 * 있어(3.11) 도는 중에 보내도 받아 주고, 잠그면 사용자가 생각난 것을 적어둘 자리가 사라진다.
 * 다만 3 단계에는 중단 버튼이 없다(4 단계). 열어 두면 큐에 쌓인 말을 되돌릴 길이 없고, 큐에
 * 들어간 둘이 한 턴으로 합쳐져 답이 하나만 오는 것도 화면이 설명하지 못한다. 중단이 서는
 * 4 단계에 이 판단을 다시 본다.
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
    val turnRunning = conversation.turnState != TurnState.Idle
    val sendable = !turnRunning && composerValue.text.isNotBlank()

    val send = {
        if (sendable) {
            onSendUserMessageRequested(composerValue.text.trim())
            // 조합 중이던 글자까지 함께 놓는다. text 만 비우면 IME 가 쥐고 있던 조합이 다음
            // 글자에 되살아난다.
            composerValue = TextFieldValue()
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        // 요청을 보냈다는 표시(6.6). 답이 오기 시작하기 전의 몇 초 동안 화면에 아무 일도 일어나지
        // 않는데, 그 사이를 이 줄이 메운다.
        if (turnRunning) {
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
                enabled = !turnRunning,
                placeholder = {
                    Text(
                        text = if (turnRunning) {
                            "답이 오는 중입니다 — 끝나면 다시 보낼 수 있습니다"
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
            if (turnRunning) {
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

/** 입력창이 이보다 커지면 대화가 밀린다. 넘치는 것은 입력창 안에서 스크롤된다. */
private val COMPOSER_MAX_HEIGHT = 160.dp

private val PROGRESS_LINE_HEIGHT = 2.dp
