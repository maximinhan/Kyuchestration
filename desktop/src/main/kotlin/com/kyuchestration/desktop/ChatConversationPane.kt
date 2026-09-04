package com.kyuchestration.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kyuchestration.desktop.terminal.SessionTarget
import com.kyuchestration.desktop.terminal.TerminalSessionFailure
import com.kyuchestration.desktop.terminal.chat.ChatConversation
import com.kyuchestration.desktop.terminal.chat.ChatScreenState
import com.kyuchestration.desktop.theme.KyuTheme

/**
 * 셸의 오른쪽 자리에 서는 대화(설계 6.2 의 가운데 칸 · 6.3).
 *
 * **이 자리가 이 전환의 전부다.** 예전에는 여기에 `claude` 가 그린 격자가 그대로 떠 있었고, 앱은
 * 그 위에 아무 말도 얹지 못했다(1.3). 이제 화면에 있는 것은 우리가 이벤트에서 그린 것이라,
 * 도구 호출도 비용도 앱이 아는 사실이다.
 *
 * @param onStartNewConversationRequested 이어가지 못한 채 끝난 세션을 새 대화로 다시 여는 자리.
 *   실패한 자리에서 앱이 대신 열어 주지 않는 것이 이 콜백의 존재 이유다(5.5.4) — 이어가기가
 *   실패했다는 판단은 짐작이고, 짐작으로 사용자의 대화 기록을 갈아치우지 않는다.
 */
@Composable
internal fun ChatConversationPane(
    chatScreenState: ChatScreenState,
    diagnosticLogPathLabel: String,
    onSendUserMessageRequested: (String) -> Unit,
    onEndSessionRequested: () -> Unit,
    onStartNewConversationRequested: (SessionTarget) -> Unit,
    /**
     * 이 대화를 원시 터미널로 다시 여는 자리(설계 7 절).
     *
     * **챗 세션을 끝내고 같은 대화를 터미널로 여는 일이다.** 한 세션은 한 종류라 두 화면이 같은
     * 대화를 동시에 열지 못한다(5.6) — 누르기 전에 그 사실을 알 수 있게 버튼 옆에 적어 둔다.
     */
    onOpenRawTerminalRequested: (SessionTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (chatScreenState) {
        is ChatScreenState.NoChatOpen -> NoChatOpenNotice(modifier)

        is ChatScreenState.ChatSessionEntryRunning ->
            CenteredChatNotice(modifier) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                Text(
                    text = "${chatScreenState.target.label} 세션을 띄우는 중입니다",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

        is ChatScreenState.ChatSessionEntryFailed ->
            ChatSessionEntryFailureNotice(chatScreenState.failure, diagnosticLogPathLabel, modifier)

        is ChatScreenState.ChatOnScreen ->
            OpenedChat(
                conversation = chatScreenState.conversation,
                onSendUserMessageRequested = onSendUserMessageRequested,
                onEndSessionRequested = onEndSessionRequested,
                onStartNewConversationRequested = onStartNewConversationRequested,
                onOpenRawTerminalRequested = onOpenRawTerminalRequested,
                modifier = modifier,
            )
    }
}

@Composable
private fun OpenedChat(
    conversation: ChatConversation,
    onSendUserMessageRequested: (String) -> Unit,
    onEndSessionRequested: () -> Unit,
    onStartNewConversationRequested: (SessionTarget) -> Unit,
    onOpenRawTerminalRequested: (SessionTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        ChatHeader(
            conversation = conversation,
            onEndSessionRequested = onEndSessionRequested,
            onStartNewConversationRequested = onStartNewConversationRequested,
            onOpenRawTerminalRequested = onOpenRawTerminalRequested,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        ChatTranscript(conversation, modifier = Modifier.weight(1f))
        ChatComposer(conversation, onSendUserMessageRequested)
    }
}

/**
 * 이 대화가 무엇인지와, 이 자리에서 할 수 있는 일.
 *
 * 모델 이름은 `system/init` 이 알려준 것 그대로다. **`permissionMode` 는 보이지 않는다** — 열린
 * 질문 10 의 답이다(설계 10 절). 플래그 이름과 보고 이름이 다르고(`manual` 이 `default` 로 온다),
 * 사용자가 그 값을 앱에서 바꿀 길도 없다. 관문이 실제로 열리는 5 단계에 승인 카드가 그 사실을
 * 맥락과 함께 말한다.
 */
@Composable
private fun ChatHeader(
    conversation: ChatConversation,
    onEndSessionRequested: () -> Unit,
    onStartNewConversationRequested: (SessionTarget) -> Unit,
    onOpenRawTerminalRequested: (SessionTarget) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 10.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = conversation.target.label,
            style = MaterialTheme.typography.titleSmall,
            fontFamily = FontFamily.Monospace,
        )

        conversation.modelName?.let { modelName ->
            Spacer(Modifier.width(10.dp))
            Text(
                text = modelName,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (conversation.totalCostUsd > 0) {
            Spacer(Modifier.width(10.dp))
            Text(
                // 머리말의 것은 이 대화가 지금까지 쓴 것 전부다. 턴마다의 것은 그 턴 아래 배지에 있다.
                text = "이 대화 " + turnCostLabel(conversation.totalCostUsd),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.weight(1f))

        // 이어가지 못한 자리에서만 뜬다. 늘 두면 사용자가 세션을 끝내려다 대화를 버리게 된다.
        if (conversation.resumeFailureSuspected) {
            TextButton(onClick = { onStartNewConversationRequested(conversation.target) }) {
                Text("새 대화로 시작")
            }
        }

        if (conversation.alive) {
            // 7 절이 정한 자리. 4 단계에 메뉴 안으로 내려가고, ❓ 셋(로그인 · MCP 승인 · 플랜 모드)이
            // 챗에서 되는 것이 확인되면 7 단계에 사라진다.
            TextButton(onClick = { onOpenRawTerminalRequested(conversation.target) }) {
                Text("터미널로 보기")
            }
            TextButton(onClick = onEndSessionRequested) { Text("세션 끝내기") }
        }
    }
}

/**
 * 전사. 위에서 아래로 쌓이고, 새 것이 오면 따라 내려간다.
 */
@Composable
private fun ChatTranscript(conversation: ChatConversation, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()

    // 사용자가 위로 올려 읽는 중이면 끌어내리지 않는다. 판단은 한 프레임 늦은 배치 정보로 하므로
    // 경계에서 한 번쯤 어긋날 수 있는데, 그 대가로 스크롤을 빼앗기지 않는다.
    var lastKnownItemCount by remember { mutableStateOf(0) }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (conversation.resumedConversationId != null) {
            item { ResumedConversationNotice() }
        }

        items(conversation.entries) { entry -> ChatEntryView(entry) }

        // 아직 완성본이 오지 않은 글자들. 완성본이 오면 이 자리가 비고 위의 항목 하나가 는다.
        conversation.streamingText?.let { streamingText ->
            item { StreamingAssistantMarkdown(streamingText, conversation.streamingBlockOrdinal) }
        }

        if (!conversation.alive) {
            item { SessionEndedNotice(conversation) }
        }
    }

    val itemCount = conversation.entries.size
    LaunchedEffect(itemCount, conversation.streamingText, conversation.alive) {
        val wasAtBottom = listState.layoutInfo.visibleItemsInfo.lastOrNull()
            ?.let { it.index >= lastKnownItemCount - 1 }
            ?: true
        lastKnownItemCount = itemCount
        if (wasAtBottom && listState.layoutInfo.totalItemsCount > 0) {
            // 항목의 시작이 아니라 끝으로 내린다. 글자가 흐르는 동안 마지막 항목이 계속 자라는데,
            // 시작에 맞추면 새 글자가 화면 아래로 밀려 나간다. 목록은 내용 끝에서 멈춘다.
            listState.scrollToItem(listState.layoutInfo.totalItemsCount - 1, SCROLL_PAST_LAST_ITEM)
        }
    }
}

/**
 * 마지막 항목의 끝까지 내리기 위해 주는 값.
 *
 * 어떤 답도 이보다 길지 않다는 뜻이 아니라, 목록이 내용의 끝을 넘어 스크롤하지 않는다는 뜻이다 —
 * 넘치게 주면 끝에서 멈춘다.
 */
private const val SCROLL_PAST_LAST_ITEM = 1_000_000

/**
 * 이어가기로 연 대화의 맨 위에 서는 줄(5.5).
 *
 * **화면이 먼저 말하는 자리다**(원칙 12). `claude` 는 앞 대화를 알지만 앱은 모른다 — 전사가
 * 메모리에만 있어서다. 이 줄이 없으면 사용자는 앱이 대화를 잃어버렸다고 읽는다.
 */
@Composable
private fun ResumedConversationNotice() {
    Text(
        text = "이 대화에는 앞선 내용이 있습니다 — 화면에는 이번 실행부터 보입니다.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * 세션이 끝났다는 줄. 이어가기에 실패한 것으로 보이면 다른 말을 한다(5.5.4).
 *
 * 이유를 지어내지 않는다. 엔진이 남긴 줄이 바로 위 전사에 그대로 있고, 이 자리는 어디를 볼지와
 * 무엇을 할 수 있는지만 말한다.
 */
@Composable
private fun SessionEndedNotice(conversation: ChatConversation) {
    val exitCodeLabel = conversation.endedExitCode?.let { " (종료 코드 $it)" }.orEmpty()

    Text(
        text = if (conversation.resumeFailureSuspected) {
            "이어가던 대화를 열지 못한 것 같습니다$exitCodeLabel — 위의 마지막 줄에 이유가 있습니다. " +
                "새 대화로 시작할 수 있습니다."
        } else {
            "세션이 끝났습니다$exitCodeLabel — 왼쪽에서 다시 고르면 그 대화를 이어서 새로 엽니다."
        },
        style = MaterialTheme.typography.bodySmall,
        color = if (conversation.resumeFailureSuspected) {
            KyuTheme.statusColors.caution
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun NoChatOpenNotice(modifier: Modifier = Modifier) {
    CenteredChatNotice(modifier) {
        Text("아직 연 세션이 없습니다", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "왼쪽에서 조율(main)이나 레포를 고르면 그 세션과의 대화가 여기서 열립니다.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ChatSessionEntryFailureNotice(
    failure: TerminalSessionFailure,
    diagnosticLogPathLabel: String,
    modifier: Modifier = Modifier,
) {
    CenteredChatNotice(modifier) {
        Text(
            text = failure.message.orEmpty(),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            text = failure.guidance,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        DiagnosticLogPathNotice(diagnosticLogPathLabel, textAlign = TextAlign.Center)
    }
}

@Composable
private fun CenteredChatNotice(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            content()
        }
    }
}
