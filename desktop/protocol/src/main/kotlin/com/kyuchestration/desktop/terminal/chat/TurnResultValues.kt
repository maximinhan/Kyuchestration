package com.kyuchestration.desktop.terminal.chat

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * 턴이 어떻게 끝났는가.
 *
 * `result` 의 `subtype` 과 `terminal_reason` 두 필드가 이 넷을 가른다(3.8 · 3.9 · session-tree 3.2).
 * 화면이 두 필드를 함께 보고 판단하게 두지 않는다 — 한쪽만 보는 자리가 생기면 중단이 실패로 그려진다.
 */
enum class TurnOutcome {

    /** `subtype: "success"`. */
    Completed,

    /** 사용자가 끊었다 — `terminal_reason: "aborted_streaming"`(3.8). 대화도 프로세스도 살아 있다. */
    Interrupted,

    /**
     * 도구 호출을 기다리던 중에 사용자가 끊었다 — `terminal_reason: "aborted_tools"`(session-tree 3.2).
     *
     * [Interrupted] 와 같이 대화도 프로세스도 살아 있다. 갈래를 나누는 것은 끊긴 자리가 달라서다 —
     * 이 턴에서는 도구 호출이 거절된 결과로 닫혔고, MCP 서버는 취소 알림을 받았다. 쓰다 만 글이
     * 아니라 **돌지 못한 도구**가 남는다.
     */
    InterruptedDuringToolWait,

    /** 그 밖의 `error_during_execution`. 이어갈 대화를 찾지 못한 경우가 여기다(3.9). */
    Failed,
}

/**
 * 그 턴이 쓴 토큰(`result.usage` — 3.12).
 *
 * 캐시 적중을 따로 들고 있는다. 비용의 대부분이 그 둘로 갈리는데, 합계 하나로 접으면 화면이
 * "왜 이 턴이 쌌는가" 를 말할 수 없다.
 */
@Serializable
data class TurnUsage(
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheReadInputTokens: Long,
    val cacheCreationInputTokens: Long,
)

/**
 * 권한이 없어 못 한 도구 호출 하나(`result.permission_denials` — 3.6).
 *
 * **6 단계가 안쪽 모양을 쟀다**(2026-09-08, `--permission-mode dontAsk` 로 Write 를 시켜 받았다).
 * 항목은 셋이다 — `tool_name` · `tool_use_id` · `tool_input`. 3 단계는 이 모양을 몰라 온 것을
 * 통째로 들고 있었는데, 화면이 "무엇이 막혔는가" 를 말하려면 이름과 인자가 갈려 있어야 한다.
 *
 * @param toolUseId 이 값이 전사의 카드와 이 항목을 잇는다. 사용자가 승인 카드에서 직접 거부한
 *   호출은 그 카드가 이미 말하고 있으므로, 화면은 이 값으로 그것을 가려낸다.
 */
@Serializable
data class PermissionDenial(val toolName: String, val toolUseId: String, val input: JsonObject)

/**
 * 한도 창 하나(`rate_limit_event.rate_limit_info.unifiedWindows` — 3.12).
 *
 * @param windowName 실측에서 온 것은 `five_hour` 와 `seven_day` 둘이다. 이름을 열거형으로 굳히지
 *   않는다 — 창이 하나 더 생기는 날 그 창만 조용히 사라지는 것보다, 모르는 이름 그대로 보이는
 *   편이 낫다.
 * @param resetsAtEpochSeconds 이 창이 다시 차는 시각(유닉스 초).
 */
@Serializable
data class RateLimitWindow(
    val windowName: String,
    val utilization: Double,
    val resetsAtEpochSeconds: Long,
)
