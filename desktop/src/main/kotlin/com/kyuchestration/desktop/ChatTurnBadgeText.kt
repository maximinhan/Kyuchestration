package com.kyuchestration.desktop

import com.kyuchestration.desktop.terminal.chat.TurnUsage
import java.util.Locale

/**
 * 턴 배지(설계 6.3 의 TurnFooter)가 말하는 숫자들을 사람이 읽는 문자열로 옮긴다.
 *
 * 그리는 자리에서 갈라 둔 것은 이 셋이 화면 없이 시험되기 때문이다 — 0.0888635 달러를 어떻게
 * 적을지는 Compose 가 없어도 답이 정해지는 물음이다.
 *
 * 자릿수를 로케일에 맡기지 않는다([Locale.ROOT]). 이 앱이 도는 기계의 지역 설정에 따라 소수점이
 * 쉼표가 되면 비용이 천 배로 읽힌다.
 */
internal fun turnCostLabel(costUsd: Double): String {
    // 네 자리는 실측이 정했다 — 짧은 턴 하나가 0.0888635 달러였고(A.12), 두 자리로 자르면
    // 0.09 가 되어 여러 턴을 더한 값과 머리말의 누계가 눈에 띄게 어긋난다.
    val digits = String.format(Locale.ROOT, "%.4f", costUsd)
        .trimEnd('0')
        .trimEnd('.')

    return "$" + digits.ifEmpty { "0" }
}

/**
 * 그 턴이 쓴 토큰.
 *
 * 캐시 적중을 따로 적는다. 비용의 대부분이 그 둘로 갈리는데 합계 하나로 접으면 화면이
 * "왜 이 턴이 쌌는가" 를 말할 수 없다(5.3.2 의 TurnUsage).
 */
internal fun turnTokenLabel(usage: TurnUsage): String = buildString {
    append("입력 ").append(groupedNumber(usage.inputTokens))
    append(" · 출력 ").append(groupedNumber(usage.outputTokens))
    if (usage.cacheReadInputTokens > 0) {
        append(" · 캐시 읽음 ").append(groupedNumber(usage.cacheReadInputTokens))
    }
}

/**
 * 그 턴이 걸린 시간. 잰 값이 없으면 null — 배지에서 이 칸이 통째로 빠진다.
 *
 * 0 을 "0.0초" 로 적지 않는다. 중단된 턴의 `result` 에는 `duration_ms` 가 없었고(A.9), 그것을
 * 0 초라고 적으면 재지 못한 것이 잰 것처럼 보인다.
 */
internal fun turnElapsedLabel(durationMillis: Long): String? = when {
    durationMillis <= 0 -> null

    durationMillis < MILLIS_PER_MINUTE ->
        String.format(Locale.ROOT, "%.1f초", durationMillis / MILLIS_PER_SECOND.toDouble())

    else -> {
        val totalSeconds = durationMillis / MILLIS_PER_SECOND
        "${totalSeconds / SECONDS_PER_MINUTE}분 ${totalSeconds % SECONDS_PER_MINUTE}초"
    }
}

private fun groupedNumber(value: Long): String = String.format(Locale.ROOT, "%,d", value)

private const val MILLIS_PER_SECOND = 1_000L

private const val SECONDS_PER_MINUTE = 60L

private const val MILLIS_PER_MINUTE = MILLIS_PER_SECOND * SECONDS_PER_MINUTE
