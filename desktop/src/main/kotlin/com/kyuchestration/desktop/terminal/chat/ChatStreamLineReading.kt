package com.kyuchestration.desktop.terminal.chat

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * `claude` 의 출력 한 줄을 이 앱의 이벤트로 옮긴다.
 *
 * **스트림의 낱말을 아는 자리는 여기 하나다**(chat-ui-design.md 5.3.3). `"task_progress"` 나
 * `"aborted_streaming"` 같은 문자열이 화면까지 새어 나가면 `claude` 의 판이 바뀔 때 고칠 자리가
 * 화면 곳곳이 된다.
 *
 * 한 줄이 이벤트 몇 개가 될지는 정해져 있지 않다.
 *
 * | 나오는 수 | 언제 |
 * |---|---|
 * | 여럿 | `assistant` 한 줄에 콘텐츠 블록이 여럿일 때(3.2) — 텍스트 하나와 도구 호출 하나면 둘이다 |
 * | 하나 | 흔한 경우 |
 * | 없음 | **무엇인지 알지만 화면에 실을 것이 없는 줄** — 조각의 뼈대(`message_start`)나 아직 그릴 화면이 없는 `system` 갈래들 |
 *
 * 마지막 칸과 [ChatSessionEvent.Unrecognized] 를 가르는 것이 이 함수의 규율이다. **아는 줄은 조용히
 * 흘려보내고, 모르는 줄은 원문째로 들고 나간다.** 둘을 섞으면 둘 다 못 쓰게 된다 — 다 버리면 판이
 * 바뀐 것을 아무도 모르고, 다 실으면 턴마다 오는 `system/status` 가 진단 기록을 덮는다.
 *
 * 던지지 않는다. 이 함수를 부르는 것은 파이프를 읽는 코루틴 하나뿐이라(ProcessChatSession),
 * 여기서 예외가 나면 그 세션의 나머지 출력이 통째로 끊긴다. 읽을 수 없는 줄은 [ChatSessionEvent.Unrecognized] 다.
 */
internal fun chatSessionEventsFrom(streamLine: String): List<ChatSessionEvent> {
    val line = parsedObjectOrNull(streamLine) ?: return listOf(ChatSessionEvent.Unrecognized(streamLine))

    return when (line.stringOrNull("type")) {
        "system" -> systemEventsIn(line, streamLine)
        "assistant" -> assistantEventsIn(line, streamLine)
        "user" -> userEventsIn(line, streamLine)
        "stream_event" -> streamFragmentEventsIn(line, streamLine)
        "result" -> listOf(turnFinishedIn(line))
        "rate_limit_event" -> listOf(usageWindowsIn(line))

        // 앱이 보낸 제어 요청의 답이다(3.8). 중단이 받아들여졌다는 사실은 뒤이어 오는
        // result(Interrupted)가 말하므로, 이 줄이 화면에 더할 것은 없다.
        "control_response" -> emptyList()

        else -> listOf(ChatSessionEvent.Unrecognized(streamLine))
    }
}

/**
 * `system` 갈래(3.2 의 열 가지 `subtype`).
 *
 * 화면이 아직 쓰지 않는 갈래는 빈 목록이다. 지금 이벤트 타입을 미리 만들어 두면 그것을 그릴
 * 화면이 없는 채로 필드 이름이 굳는다 — 서브에이전트 카드는 6 단계, 훅은 아직 아무 단계도
 * 아니다(원칙 4).
 */
private fun systemEventsIn(line: JsonObject, streamLine: String): List<ChatSessionEvent> =
    when (line.stringOrNull("subtype")) {
        "init" -> listOf(
            ChatSessionEvent.SessionDescribed(
                conversationId = line.stringOrNull("session_id").orEmpty(),
                modelName = line.stringOrNull("model").orEmpty(),
                availableSlashCommands = line.arrayOrNull("slash_commands")
                    ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    .orEmpty(),
                connectedMcpServers = line.arrayOrNull("mcp_servers")
                    ?.filterIsInstance<JsonObject>()
                    ?.map {
                        McpServerStatus(
                            name = it.stringOrNull("name").orEmpty(),
                            status = it.stringOrNull("status").orEmpty(),
                        )
                    }
                    .orEmpty(),
            ),
        )

        "task_progress" -> listOf(
            ChatSessionEvent.DelegationProgressed(
                taskId = line.stringOrNull("task_id").orEmpty(),
                toolUseId = line.stringOrNull("tool_use_id").orEmpty(),
                description = line.stringOrNull("description").orEmpty(),
                lastToolName = line.stringOrNull("last_tool_name"),
            ),
        )

        "status", "thinking_tokens", "hook_started", "hook_response",
        "task_started", "task_updated", "task_notification", "background_tasks_changed",
        -> emptyList()

        else -> listOf(ChatSessionEvent.Unrecognized(streamLine))
    }

/**
 * 모델이 완성한 콘텐츠 블록들(3.2).
 *
 * **이벤트 하나가 말풍선 하나가 아니다.** 블록 단위로 오므로 한 줄이 여러 이벤트가 되고, 이
 * 사실을 모르면 도구 호출이 빈 말풍선이 된다.
 */
private fun assistantEventsIn(line: JsonObject, streamLine: String): List<ChatSessionEvent> {
    val parentToolUseId = line.stringOrNull("parent_tool_use_id")
    val contentBlocks = line.objectOrNull("message")?.arrayOrNull("content")
        ?: return listOf(ChatSessionEvent.Unrecognized(streamLine))

    return contentBlocks.filterIsInstance<JsonObject>().map { block ->
        when (block.stringOrNull("type")) {
            "text" -> ChatSessionEvent.AssistantTextArrived(
                text = block.stringOrNull("text").orEmpty(),
                parentToolUseId = parentToolUseId,
            )

            "thinking" -> ChatSessionEvent.AssistantThinkingArrived(
                text = block.stringOrNull("thinking").orEmpty(),
                parentToolUseId = parentToolUseId,
            )

            "tool_use" -> ChatSessionEvent.ToolCallRequested(
                toolUseId = block.stringOrNull("id").orEmpty(),
                toolName = block.stringOrNull("name").orEmpty(),
                input = block.objectOrNull("input") ?: JsonObject(emptyMap()),
                parentToolUseId = parentToolUseId,
            )

            else -> ChatSessionEvent.Unrecognized(streamLine)
        }
    }
}

/**
 * `user` 갈래 — 되돌려받은 사용자 메시지와 도구 결과가 같은 모양으로 온다(3.14 · 3.4).
 *
 * `tool_use_result` 곁가지는 줄의 최상위에 하나 있고, 실측에서 그 줄의 도구 결과 블록도 하나였다
 * (A.3). 블록이 둘 이상인 줄은 재지 못했으므로 곁가지를 그 줄의 결과들에 그대로 실어 보낸다 —
 * 어느 하나에만 붙일 근거가 스트림에 없다.
 */
private fun userEventsIn(line: JsonObject, streamLine: String): List<ChatSessionEvent> {
    val typedResult = line["tool_use_result"]
    val contentBlocks = line.objectOrNull("message")?.arrayOrNull("content")
        ?: return listOf(ChatSessionEvent.Unrecognized(streamLine))

    return contentBlocks.filterIsInstance<JsonObject>().map { block ->
        when (block.stringOrNull("type")) {
            "text" -> ChatSessionEvent.UserMessageEchoed(block.stringOrNull("text").orEmpty())

            "tool_result" -> ChatSessionEvent.ToolCallAnswered(
                toolUseId = block.stringOrNull("tool_use_id").orEmpty(),
                failed = block.booleanOrNull("is_error") ?: false,
                modelVisibleText = modelVisibleTextOf(block["content"]),
                typedResult = typedResult,
            )

            else -> ChatSessionEvent.Unrecognized(streamLine)
        }
    }
}

/**
 * `--include-partial-messages` 가 붙인 글자 단위 조각(3.3).
 *
 * **조각을 이어 붙여 파싱하려 들지 않는다.** `input_json_delta` 는 잘린 JSON 이라 그 자체로는
 * 읽히지 않고, 완성된 인자는 블록이 끝난 뒤 `assistant` 이벤트에 온전한 객체로 다시 온다.
 * 사고 조각도 같다 — 완성본이 [ChatSessionEvent.AssistantThinkingArrived] 로 온다.
 *
 * 블록의 시작·끝과 메시지의 시작·끝도 실어 나르지 않는다. 그 경계는 완성본 이벤트가 이미
 * 말하고, 도구 이름을 인자보다 먼저 보이는 화면(3.3)은 4 단계의 몫이다.
 */
private fun streamFragmentEventsIn(line: JsonObject, streamLine: String): List<ChatSessionEvent> {
    val fragment = line.objectOrNull("event") ?: return listOf(ChatSessionEvent.Unrecognized(streamLine))

    return when (fragment.stringOrNull("type")) {
        "content_block_delta" -> {
            val delta = fragment.objectOrNull("delta")
            when (delta?.stringOrNull("type")) {
                "text_delta" -> listOf(
                    ChatSessionEvent.AssistantTextStreaming(delta.stringOrNull("text").orEmpty()),
                )

                "input_json_delta", "thinking_delta", "signature_delta" -> emptyList()

                else -> listOf(ChatSessionEvent.Unrecognized(streamLine))
            }
        }

        "message_start", "content_block_start", "content_block_stop", "message_delta", "message_stop",
        -> emptyList()

        else -> listOf(ChatSessionEvent.Unrecognized(streamLine))
    }
}

/**
 * 턴 하나의 끝(3.1 · 3.12).
 *
 * 모양이 어긋난 `result` 도 [ChatSessionEvent.TurnFinished] 로 옮긴다. 비용과 토큰은 배지 하나가
 * 비는 것으로 끝나지만, 이 이벤트가 아예 오지 않으면 화면은 턴이 끝난 것을 모른 채 영영 기다린다.
 */
private fun turnFinishedIn(line: JsonObject): ChatSessionEvent.TurnFinished {
    val usage = line.objectOrNull("usage")

    return ChatSessionEvent.TurnFinished(
        outcome = turnOutcomeIn(line),
        costUsd = line.doubleOrNull("total_cost_usd") ?: 0.0,
        usage = TurnUsage(
            inputTokens = usage?.longOrNull("input_tokens") ?: 0,
            outputTokens = usage?.longOrNull("output_tokens") ?: 0,
            cacheReadInputTokens = usage?.longOrNull("cache_read_input_tokens") ?: 0,
            cacheCreationInputTokens = usage?.longOrNull("cache_creation_input_tokens") ?: 0,
        ),
        permissionDenials = line.arrayOrNull("permission_denials")
            ?.filterIsInstance<JsonObject>()
            ?.map(::PermissionDenial)
            .orEmpty(),
        durationMillis = line.longOrNull("duration_ms") ?: 0,
    )
}

/**
 * 두 필드가 셋을 가른다(3.8 · 3.9).
 *
 * 중단을 성공보다 **뒤에** 본다. 중단된 턴은 `subtype` 이 `error_during_execution` 이라 순서가
 * 뒤집혀도 답은 같지만, 읽는 사람에게는 "성공이 아니면서 중단인 것" 이라는 순서가 사실에 가깝다.
 */
private fun turnOutcomeIn(line: JsonObject): TurnOutcome = when {
    line.stringOrNull("subtype") == "success" -> TurnOutcome.Completed
    line.stringOrNull("terminal_reason") == "aborted_streaming" -> TurnOutcome.Interrupted
    else -> TurnOutcome.Failed
}

/** 한도 창들(3.12). 창 이름은 온 그대로 들고 간다 — 셋째 창이 생기는 날 그것만 사라지지 않게. */
private fun usageWindowsIn(line: JsonObject): ChatSessionEvent.UsageWindowsObserved {
    val windows = line.objectOrNull("rate_limit_info")?.objectOrNull("unifiedWindows").orEmpty()

    return ChatSessionEvent.UsageWindowsObserved(
        windows = windows.mapNotNull { (windowName, window) ->
            val fields = window as? JsonObject ?: return@mapNotNull null
            RateLimitWindow(
                windowName = windowName,
                utilization = fields.doubleOrNull("utilization") ?: 0.0,
                resetsAtEpochSeconds = fields.longOrNull("resetsAt") ?: 0,
            )
        },
    )
}

/**
 * 모델이 읽은 도구 결과 텍스트.
 *
 * 실측에서 온 것은 문자열 하나였다(A.3). 블록 배열로 오는 도구를 재지는 못했지만 그 모양을
 * 빈 문자열로 흘려보내지 않는다 — 그러면 결과가 있는 도구 카드가 이유 없이 비어 보인다.
 */
private fun modelVisibleTextOf(content: JsonElement?): String = when (content) {
    is JsonPrimitive -> content.contentOrNull.orEmpty()
    is JsonArray -> content.filterIsInstance<JsonObject>()
        .mapNotNull { it.stringOrNull("text") }
        .joinToString("\n")

    else -> ""
}

private fun parsedObjectOrNull(streamLine: String): JsonObject? = try {
    chatStreamJson.parseToJsonElement(streamLine) as? JsonObject
} catch (failure: SerializationException) {
    // 줄이 잘리거나 JSON 이 아닌 것이 섞였을 때다. 이유를 삼키는 것이 아니라 원문을 그대로
    // 들고 나가는 것이 이 자리의 답이다 — 원문이 있으면 진단이 가능하고, 예외를 던지면 이
    // 세션의 나머지 출력이 통째로 끊긴다.
    null
} catch (failure: IllegalArgumentException) {
    // parseToJsonElement 가 숫자 모양이 어긋난 줄에 대해 내는 것.
    null
}

private val chatStreamJson = Json

private fun JsonObject.stringOrNull(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.booleanOrNull(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()

private fun JsonObject.doubleOrNull(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull

private fun JsonObject.longOrNull(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

private fun JsonObject.objectOrNull(key: String): JsonObject? = this[key] as? JsonObject

private fun JsonObject.arrayOrNull(key: String): JsonArray? = this[key] as? JsonArray
