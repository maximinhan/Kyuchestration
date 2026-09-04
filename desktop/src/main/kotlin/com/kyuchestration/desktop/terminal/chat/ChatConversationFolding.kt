package com.kyuchestration.desktop.terminal.chat

/**
 * 이벤트 하나가 대화를 어떻게 바꾸는가(chat-ui-design.md 5.3.2 · 5.5).
 *
 * **순수 함수인 것이 요점이다.** 프로세스도 코루틴도 없이 이벤트 목록만 넣으면 화면에 그려질
 * 것이 나온다 — 실제로 `claude` 가 낸 줄을 어댑터에 통과시켜 여기에 넣는 것이 이 규칙들의
 * 검증이다(ChatConversationFoldingTest). 상태 홀더는 이 함수를 흐름에 이어 붙일 뿐이다.
 *
 * 세 규칙이 이 함수의 전부이고, 셋 다 실측이 정했다.
 *
 * | 규칙 | 근거 |
 * |---|---|
 * | `system/init` 이 전사를 비우지 않는다 | 턴마다 다시 온다(3.1) |
 * | 사용자 말풍선은 되돌아온 것으로만 그린다 | 전사가 한 물줄기에서 온다(3.14) |
 * | 조각은 버퍼에 쌓고 완성본이 오면 버린다 | 잘린 문장을 전사에 남기지 않는다(3.3) |
 */
internal fun ChatConversation.after(event: ChatSessionEvent): ChatConversation = when (event) {

    // 세션이 무엇인지 말하는 줄이지 시작을 알리는 줄이 아니다. 여기서 entries 를 비우면 매 턴
    // 대화가 사라진다 — 이 앱이 가장 쉽게 저지를 수 있었던 결함이 이것이다(3.1).
    is ChatSessionEvent.SessionDescribed -> copy(
        conversationId = event.conversationId,
        modelName = event.modelName,
    )

    // 되돌아왔으므로 기다리던 목록에서 뺀다. 같은 말을 두 번 보냈으면 앞의 하나만 빠진다 —
    // minus 가 첫 항목만 지우는 것이 여기서는 바라는 동작이다(둘째는 아직 안 돌아왔다).
    //
    // **되돌아온 것이 그 턴의 시작이다**(3.11). 큐에 든 말은 그 턴이 시작할 때에야 돌아오므로,
    // 이 자리가 "턴이 실제로 돌고 있다" 를 아는 유일한 자리다 — 앱이 보낸 순간이 아니다.
    is ChatSessionEvent.UserMessageEchoed -> copy(
        entries = entries + ChatEntry.UserSaid(event.text),
        pendingUserMessages = pendingUserMessages - event.text,
        // 끊어 달라고 말해 둔 턴의 중단 안내도 이 이벤트로 온다(3.8). 그것을 새 턴의 시작으로
        // 읽으면 끊는 중이던 화면이 한순간 되살아난다.
        turnState = if (turnState == TurnState.Interrupting) TurnState.Interrupting else TurnState.Running,
    )

    // 완성본이 왔으므로 조각 버퍼를 버린다. 안쪽 대화(parentToolUseId 가 있는 것)의 완성본이어도
    // 버린다 — 조각에는 부모 정보가 실려 오지 않아(AssistantTextStreaming) 버퍼에 섞여 있다.
    is ChatSessionEvent.AssistantTextArrived -> copy(
        entries = entries.withEntryAdded(ChatEntry.AssistantSaid(event.text), event.parentToolUseId),
        streamingText = null,
    )

    // 실측에서 사고 블록의 본문이 빈 문자열로 왔다(RecordedChatStreamLines.ASSISTANT_THINKING).
    // 그것을 그대로 쌓으면 아무것도 안 든 접힌 줄이 대화에 끼어든다.
    is ChatSessionEvent.AssistantThinkingArrived -> if (event.text.isBlank()) {
        this
    } else {
        copy(entries = entries.withEntryAdded(ChatEntry.AssistantThought(event.text), event.parentToolUseId))
    }

    is ChatSessionEvent.AssistantTextStreaming -> copy(
        streamingText = streamingText.orEmpty() + event.chunk,
        // 버퍼가 비어 있었다면 이 조각이 새 블록의 첫 글자다. 화면은 이 번호가 오를 때 스트리밍
        // 렌더러의 상태를 새로 세운다 — 그 상태가 덧붙이기 전용이라 이어 쓸 수 없다.
        streamingBlockOrdinal = if (streamingText == null) streamingBlockOrdinal + 1 else streamingBlockOrdinal,
    )

    is ChatSessionEvent.ToolCallRequested -> copy(
        entries = entries.withEntryAdded(
            ChatEntry.ToolCall(
                toolUseId = event.toolUseId,
                toolName = event.toolName,
                input = event.input,
            ),
            event.parentToolUseId,
        ),
    )

    is ChatSessionEvent.ToolCallAnswered -> copy(
        entries = entries.withToolCallAnswered(
            toolUseId = event.toolUseId,
            answer = ToolCallAnswer(
                failed = event.failed,
                modelVisibleText = event.modelVisibleText,
                typedResult = event.typedResult,
            ),
        ),
    )

    // 서브에이전트의 진행은 6 단계가 그린다. 지금 이것을 어딘가에 얹어 두면 그 값을 보여줄 화면이
    // 없는 채로 자리만 생긴다 — 도구 카드는 이미 "도는 중" 을 말하고 있다(5.7).
    is ChatSessionEvent.DelegationProgressed -> this

    is ChatSessionEvent.TurnFinished -> copy(
        entries = entries + ChatEntry.TurnEnded(
            outcome = event.outcome,
            costUsd = event.costUsd,
            usage = event.usage,
            durationMillis = event.durationMillis,
            permissionDenialCount = event.permissionDenials.size,
        ),
        // 턴이 끝났는데 조각이 남아 있다면 완성본이 오지 않은 것이다(중단된 턴이 그렇다).
        // 그 잘린 문장을 화면에 남겨 두면 다음 턴의 답 위에 계속 떠 있는다.
        streamingText = null,
        // 큐에 든 말이 남아 있어도 여기서는 Idle 이다. 그 말의 턴은 아직 시작하지 않았고(시작은
        // 되돌아옴이다), 시작하지 않은 턴을 도는 것으로 읽으면 그 사이에 누른 중단이 그 말의 첫
        // 낱말을 끊는다. 기다리는 중이라는 사실은 pendingUserMessages 가 말한다.
        turnState = TurnState.Idle,
        totalCostUsd = totalCostUsd + event.costUsd,
    )

    is ChatSessionEvent.UsageWindowsObserved -> copy(usageWindows = event.windows)

    is ChatSessionEvent.EngineSpoke -> copy(entries = entries + ChatEntry.EngineNotice(event.line))

    is ChatSessionEvent.SessionEnded -> copy(
        endedExitCode = event.exitCode,
        streamingText = null,
        turnState = TurnState.Idle,
        // 되돌아올 말이 없다. 프로세스가 끝났으므로 큐에 남아 있던 것도 함께 사라졌다 —
        // 남겨 두면 끝난 세션의 전사 아래에 영영 기다리는 줄이 붙는다.
        pendingUserMessages = emptyList(),
    )

    // **원문을 기록에 남기지 않는다.** 모르는 줄을 들고 있는 것은 판이 바뀐 것을 알아채기
    // 위해서인데(ChatSessionEvent.Unrecognized), 그 줄에는 사용자의 대화가 통째로 실려 있을 수
    // 있다. 진단 기록은 남의 손에 건네지는 파일이라(DiagnosticLogEntry) 그리로 보낼 수 없고,
    // 화면에 그대로 뿌리면 대화에 JSON 한 줄이 끼어든다. 이 줄을 어디로 보낼지는 그것이 실제로
    // 필요해질 때 정한다.
    is ChatSessionEvent.Unrecognized -> this
}

/**
 * 항목 하나를 전사에 더한다. 부모 도구 호출이 지정돼 있으면 그 카드 안으로 접는다(3.10).
 *
 * **부모를 못 찾으면 본문에 둔다.** 서브에이전트의 첫 말이 그 도구의 `assistant` 이벤트보다 먼저
 * 오는 순서를 재지 못했으므로, 그때 항목을 버리지 않는다 — 대화의 일부가 조용히 사라지는 것보다
 * 자리가 어색한 편이 낫다.
 */
private fun List<ChatEntry>.withEntryAdded(entry: ChatEntry, parentToolUseId: String?): List<ChatEntry> {
    if (parentToolUseId == null) {
        return this + entry
    }
    return withToolCallChanged(parentToolUseId) { it.copy(nestedEntries = it.nestedEntries + entry) }
        ?: (this + entry)
}

/** 그 도구 호출의 결과를 채운다. 카드가 없으면 그대로 둔다 — 결과만 있는 카드를 지어내지 않는다. */
private fun List<ChatEntry>.withToolCallAnswered(toolUseId: String, answer: ToolCallAnswer): List<ChatEntry> =
    withToolCallChanged(toolUseId) { it.copy(answer = answer) } ?: this

/**
 * 그 `toolUseId` 의 카드를 찾아 고친다. 없으면 null.
 *
 * 중첩까지 내려간다. 서브에이전트 안쪽의 도구 호출도 자기 결과를 받아야 하고, 그 카드는 바깥
 * 카드의 [ChatEntry.ToolCall.nestedEntries] 에 있다.
 */
private fun List<ChatEntry>.withToolCallChanged(
    toolUseId: String,
    change: (ChatEntry.ToolCall) -> ChatEntry.ToolCall,
): List<ChatEntry>? {
    var changed = false

    val changedEntries = map { entry ->
        when {
            changed || entry !is ChatEntry.ToolCall -> entry

            entry.toolUseId == toolUseId -> {
                changed = true
                change(entry)
            }

            else -> entry.nestedEntries.withToolCallChanged(toolUseId, change)
                ?.also { changed = true }
                ?.let { entry.copy(nestedEntries = it) }
                ?: entry
        }
    }

    return changedEntries.takeIf { changed }
}
