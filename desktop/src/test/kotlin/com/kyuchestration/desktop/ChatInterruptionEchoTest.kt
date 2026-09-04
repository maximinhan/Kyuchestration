package com.kyuchestration.desktop

import com.kyuchestration.desktop.terminal.chat.ChatSessionEvent
import com.kyuchestration.desktop.terminal.chat.RecordedChatStreamLines
import com.kyuchestration.desktop.terminal.chat.chatSessionEventsFrom
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 중단 안내와 사용자의 말을 가르는 규칙(설계 3.8).
 *
 * 화면 없이 시험한다 — 이것은 "이 텍스트가 무엇인가" 라는 물음이라 Compose 가 없어도 답이
 * 정해진다.
 */
class ChatInterruptionEchoTest {

    @Test
    fun `실측으로 온 줄을 알아본다`() {
        // 문구를 손으로 적지 않는다. 녹화한 줄을 어댑터에 통과시켜 나온 텍스트를 그대로 넣어야,
        // 그 문구가 바뀌는 날 이 검증이 먼저 깨진다.
        val echoed = assertIs<ChatSessionEvent.UserMessageEchoed>(
            chatSessionEventsFrom(RecordedChatStreamLines.INTERRUPTION_ECHOED).single(),
        )

        assertTrue(isTurnInterruptionEcho(echoed.text))
    }

    @Test
    fun `뒤에 낱말이 붙어 와도 알아본다`() {
        assertTrue(isTurnInterruptionEcho("[Request interrupted by user for tool use]"))
    }

    @Test
    fun `사용자의 말은 그대로 사용자의 말이다`() {
        assertFalse(isTurnInterruptionEcho("중단이 어떻게 도는지 설명해줘"))
        // 사용자가 그 문구를 스스로 적을 수도 있다. 그때 안내로 서는 것은 감수한다 —
        // 문구를 인용하는 대화보다 진짜 중단이 훨씬 잦다.
        assertFalse(isTurnInterruptionEcho("이 문구가 [Request interrupted by user] 로 오나요?"))
    }
}
