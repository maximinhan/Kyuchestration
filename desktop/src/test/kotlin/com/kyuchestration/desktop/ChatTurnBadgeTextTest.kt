package com.kyuchestration.desktop

import com.kyuchestration.desktop.terminal.chat.TurnUsage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChatTurnBadgeTextTest {

    @Test
    fun `비용은 실측이 준 자릿수까지 적는다`() {
        // 실측의 한 턴이 이 값이었다(A.12). 두 자리로 자르면 여러 턴을 더한 누계와 어긋난다.
        assertEquals("$0.0889", turnCostLabel(0.0888635))
    }

    @Test
    fun `뒤에 붙는 0 은 적지 않는다`() {
        assertEquals("$0.25", turnCostLabel(0.25))
        assertEquals("$0", turnCostLabel(0.0))
    }

    @Test
    fun `토큰은 캐시 적중을 따로 말한다`() {
        val label = turnTokenLabel(
            TurnUsage(
                inputTokens = 4,
                outputTokens = 263,
                cacheReadInputTokens = 26_703,
                cacheCreationInputTokens = 6_792,
            ),
        )

        assertEquals("입력 4 · 출력 263 · 캐시 읽음 26,703", label)
    }

    @Test
    fun `캐시 적중이 없으면 그 칸을 두지 않는다`() {
        val label = turnTokenLabel(
            TurnUsage(inputTokens = 3, outputTokens = 41, cacheReadInputTokens = 0, cacheCreationInputTokens = 0),
        )

        assertEquals("입력 3 · 출력 41", label)
    }

    @Test
    fun `소요 시간은 초와 분으로 읽힌다`() {
        assertEquals("6.5초", turnElapsedLabel(6_459))
        assertEquals("1분 5초", turnElapsedLabel(65_000))
    }

    @Test
    fun `재지 못한 시간은 말하지 않는다`() {
        // 중단된 턴의 result 에는 duration_ms 가 없었다(A.9). 0 초라고 적으면 재지 못한 것이
        // 잰 것처럼 보인다.
        assertNull(turnElapsedLabel(0))
    }
}
