package com.kyuchestration.desktop.terminal.chat

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * 챗 세션 하나가 앱에게 말하는 것 전부.
 *
 * **스트림의 JSON 을 그대로 화면에 흘리지 않는다**(chat-ui-design.md 5.3.2). 화면이
 * `subtype == "task_progress"` 같은 문자열을 알기 시작하면 `claude` 의 판이 바뀔 때 고칠 자리가
 * 화면 곳곳이 된다. 그 문자열을 아는 자리는 [chatSessionEventsFrom] 하나뿐이다.
 *
 * 갈래는 실측이 정했다(3.2 의 일곱 가지 최상위 `type` 과 그 아래 `subtype`). 재지 못한 것에는
 * 갈래를 두지 않는다 — 못 그리는 것을 그린 척하지 않는 것이 원칙 15 다.
 */
sealed interface ChatSessionEvent {

    /**
     * `system/init` — 이 세션이 무엇을 가지고 있는지.
     *
     * **턴마다 다시 온다.** 두 턴짜리 실측에서 `init` 이 두 번 왔고 `session_id` 는 같았다(3.1).
     * 이것을 "새 세션이 시작됐다" 로 읽으면 매 턴 대화가 비워진다 — 세션의 시작은 프로세스의
     * 시작이지 이 이벤트가 아니다.
     */
    data class SessionDescribed(
        val conversationId: String,
        val modelName: String,
        val availableSlashCommands: List<String>,
        val connectedMcpServers: List<McpServerStatus>,
    ) : ChatSessionEvent

    /**
     * `--replay-user-messages` 가 되돌려준 사용자 메시지(3.14 · 원칙 14).
     *
     * 앱이 방금 보낸 말이 스트림을 한 바퀴 돌아 여기로 온다. 화면의 말풍선은 이것으로 그린다 —
     * 앱이 자기가 보낸 것을 직접 넣으면 큐잉(3.11)이나 중단이 끼는 순간 순서가 어긋난다.
     *
     * **중단 표시도 이 모양으로 온다.** 턴을 끊으면 `[Request interrupted by user]` 가 `user`
     * 이벤트의 텍스트로 온다(A.9). 그것을 사용자의 말과 가르는 것은 화면의 몫이다 — 어댑터가
     * 문구를 알아보고 다른 갈래로 옮기면, 그 문구가 바뀌는 날 조용히 사용자 말풍선이 된다.
     */
    data class UserMessageEchoed(val text: String) : ChatSessionEvent

    /**
     * 모델이 텍스트 블록 하나를 **완성**했다.
     *
     * `assistant` 이벤트는 메시지 단위가 아니라 콘텐츠 블록 단위다(3.2). 한 턴에서 텍스트 하나와
     * 도구 호출 하나가 나오면 이 이벤트와 [ToolCallRequested] 가 각각 하나씩이다.
     *
     * @param parentToolUseId 서브에이전트 안쪽의 말이면 그 바깥 도구 호출의 id(3.10). 값이 있는
     *   이벤트는 그 도구 카드 안으로 접히고, null 인 것만 대화 본문에 놓인다.
     */
    data class AssistantTextArrived(val text: String, val parentToolUseId: String?) : ChatSessionEvent

    /** 모델의 사고 블록 하나. 화면이 접어 두는 자리다. */
    data class AssistantThinkingArrived(val text: String, val parentToolUseId: String?) : ChatSessionEvent

    /**
     * `stream_event` 의 글자 조각(3.3).
     *
     * **전사의 사실이 아니다.** 조각은 화면을 부드럽게 하려고 있고, 같은 내용의 완성본이 뒤이어
     * [AssistantTextArrived] 로 온다. 상태 홀더는 조각을 임시 버퍼에 쌓다가 완성본이 오면 버퍼를
     * 버린다 — 조각만으로 전사를 만들면 중단된 턴의 잘린 문장이 영구히 전사에 남는다.
     */
    data class AssistantTextStreaming(val chunk: String) : ChatSessionEvent

    /**
     * 모델이 도구 하나를 부르려 한다. 인자가 다 채워진 완성본이다(3.4).
     *
     * 조각(`input_json_delta`)은 이 이벤트로 오지 않는다. 잘린 JSON 이라 그 자체로 파싱되지 않고,
     * 완성본이 곧 오기 때문이다(3.3).
     */
    data class ToolCallRequested(
        val toolUseId: String,
        val toolName: String,
        val input: JsonObject,
        val parentToolUseId: String?,
    ) : ChatSessionEvent

    /**
     * 그 도구 호출의 결과. [ToolCallRequested] 와 `toolUseId` 로 이어진다(3.4).
     *
     * @param failed 거절되었거나 실패했다(`is_error`). 거절된 호출의 문구가 여기로 온다(A.5).
     * @param modelVisibleText 모델이 읽는 텍스트. Read 라면 줄 번호가 붙어 있다.
     * @param typedResult `tool_use_result` 곁가지 — 그 도구가 실제로 한 일을 도구마다 다른 모양으로
     *   담는다(3.4). **도구별 카드를 그리는 근거가 이것이다.** 모양이 도구마다 다르므로 여기서
     *   갈래를 두지 않고 원래 값을 그대로 들고 간다 — 갈래는 카드를 그리는 4 단계가 정한다.
     */
    data class ToolCallAnswered(
        val toolUseId: String,
        val failed: Boolean,
        val modelVisibleText: String,
        val typedResult: JsonElement?,
    ) : ChatSessionEvent

    /**
     * 서브에이전트 하나의 진행(`system/task_progress` — 3.10).
     *
     * **MCP 도구의 진행은 오지 않는다.** 프로브 서버가 `notifications/progress` 를 세 번 보냈는데
     * 스트림에는 0 개였다(3.10 가). 그래서 오케스트레이션 위임 카드는 "도는 중" 까지만 그린다.
     */
    data class DelegationProgressed(
        val taskId: String,
        val toolUseId: String,
        val description: String,
        val lastToolName: String?,
    ) : ChatSessionEvent

    /**
     * 턴 하나가 끝났다(`result`). 턴마다 정확히 하나 온다(3.1).
     *
     * 입력창의 잠금을 풀고 비용 배지를 그리는 자리다.
     */
    data class TurnFinished(
        val outcome: TurnOutcome,
        val costUsd: Double,
        val usage: TurnUsage,
        val permissionDenials: List<PermissionDenial>,
    ) : ChatSessionEvent

    /** `rate_limit_event` — 한도 사용률을 창 두 개로 준다(3.12). 턴마다 온다. */
    data class UsageWindowsObserved(val windows: List<RateLimitWindow>) : ChatSessionEvent

    /**
     * `claude` 가 stderr 에 남긴 한 줄.
     *
     * 없는 대화를 이어가려 했을 때의 실패가 여기로 온다(3.9) — `No conversation found with
     * session ID: …`. PTY 시절에는 앱이 보는 것이 닫힌 PTY 뿐이라 사용자의 `/exit` 과 구분할 수
     * 없었던 자리다.
     */
    data class EngineSpoke(val line: String) : ChatSessionEvent

    /**
     * 이 어댑터가 **모르는** 줄. 원문 그대로다.
     *
     * **버리지 않는다.** `claude` 의 판이 올라 새 이벤트가 생기는 날, 버리는 어댑터는 아무 말도
     * 하지 않고 화면만 조용히 비게 한다. 원문을 들고 있으면 그 줄이 진단 기록에 남는다.
     *
     * 실측으로 아는데 아직 화면이 쓰지 않는 갈래(`system/status`·`task_started` 따위)는 여기로
     * 오지 않는다 — 그것들은 조용히 지나간다([chatSessionEventsFrom]). 둘을 섞으면 이 갈래가
     * 턴마다 오는 것으로 덮여 "판이 바뀌었다" 는 신호로 못 쓰게 된다.
     */
    data class Unrecognized(val rawLine: String) : ChatSessionEvent

    /**
     * 프로세스가 끝났다. 이 흐름의 마지막 이벤트다.
     *
     * 없는 대화를 이어가려 한 실패는 종료 코드 1 로 온다(3.9). 사용자가 세션을 닫은 것과 가르는
     * 근거는 앞서 온 [EngineSpoke] 와 [TurnFinished] 다.
     */
    data class SessionEnded(val exitCode: Int) : ChatSessionEvent
}

/** `system/init` 이 보고한 MCP 서버 하나 — 이름과 상태(실측: `{"name":"kyu","status":"connected"}`). */
data class McpServerStatus(val name: String, val status: String)

/**
 * 턴이 어떻게 끝났는가.
 *
 * `result` 의 `subtype` 과 `terminal_reason` 두 필드가 이 셋을 가른다(3.8 · 3.9). 화면이 두 필드를
 * 함께 보고 판단하게 두지 않는다 — 한쪽만 보는 자리가 생기면 중단이 실패로 그려진다.
 */
enum class TurnOutcome {

    /** `subtype: "success"`. */
    Completed,

    /** 사용자가 끊었다 — `terminal_reason: "aborted_streaming"`(3.8). 대화도 프로세스도 살아 있다. */
    Interrupted,

    /** 그 밖의 `error_during_execution`. 이어갈 대화를 찾지 못한 경우가 여기다(3.9). */
    Failed,
}

/**
 * 그 턴이 쓴 토큰(`result.usage` — 3.12).
 *
 * 캐시 적중을 따로 들고 있는다. 비용의 대부분이 그 둘로 갈리는데, 합계 하나로 접으면 화면이
 * "왜 이 턴이 쌌는가" 를 말할 수 없다.
 */
data class TurnUsage(
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheReadInputTokens: Long,
    val cacheCreationInputTokens: Long,
)

/**
 * 권한이 없어 못 한 도구 호출 하나(`result.permission_denials` — 3.6).
 *
 * **안쪽 모양을 재지 못했다.** 이 배열이 비어 있지 않았던 실측은 `dontAsk` 하나였고(A.6), 그때도
 * 건수만 셌지 항목의 키를 적어두지 않았다. 그래서 온 것을 그대로 들고 있는다 — 지금 필드 이름을
 * 지어내면 그 이름이 맞는지 아무도 모르는 채로 굳는다(원칙 15). 화면이 이것을 그리는 6 단계에서
 * 실제 값을 보고 가른다.
 */
data class PermissionDenial(val fields: JsonObject)

/**
 * 한도 창 하나(`rate_limit_event.rate_limit_info.unifiedWindows` — 3.12).
 *
 * @param windowName 실측에서 온 것은 `five_hour` 와 `seven_day` 둘이다. 이름을 열거형으로 굳히지
 *   않는다 — 창이 하나 더 생기는 날 그 창만 조용히 사라지는 것보다, 모르는 이름 그대로 보이는
 *   편이 낫다.
 * @param resetsAtEpochSeconds 이 창이 다시 차는 시각(유닉스 초).
 */
data class RateLimitWindow(
    val windowName: String,
    val utilization: Double,
    val resetsAtEpochSeconds: Long,
)
