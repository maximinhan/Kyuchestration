package com.kyuchestration.desktop

import com.kyuchestration.desktop.terminal.SessionConversationChoice
import com.kyuchestration.desktop.terminal.chat.ExistingConversation
import com.kyuchestration.desktop.terminal.chat.SessionEntryState
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 고르기 화면에 설 줄들(app-owned-sessions-design.md 5.5.7). 화면이 고르는 규칙을 갖지 않고, 코어가 준 갈래를 사람이 읽을
 * 줄로 옮기기만 하는지 본다.
 */
class ConversationChoiceOptionsTest {

    @Test
    fun `바깥 대화 · 앱에서 하던 대화 · 새 대화 순으로 서고 각 갈래가 그 줄의 선택이다`() {
        val options = conversationChoiceOptions(CHOOSING, SEOUL)

        assertEquals(
            listOf(
                SessionConversationChoice.AdoptConversation(TERMINAL_ID),
                SessionConversationChoice.AdoptConversation(UNTITLED_ID),
                SessionConversationChoice.ContinueRecordedConversation,
                SessionConversationChoice.StartNewConversation,
            ),
            options.map { it.choice },
        )
    }

    @Test
    fun `시각은 현지 시간으로 보이고 시작 시각은 있을 때만 보인다`() {
        val options = conversationChoiceOptions(CHOOSING, SEOUL)

        assertEquals("터미널에서 하던 설계 검토", options[0].title)
        assertEquals(listOf("마지막 활동 2026-11-01 18:00", "시작 2026-08-27 15:05"), options[0].details)
        assertEquals(listOf("마지막 활동 2026-10-30 10:00"), options[1].details)
    }

    @Test
    fun `제목이 비면 id 앞 여덟 자로 보인다`() {
        assertEquals("1f0c2a7e", conversationChoiceOptions(CHOOSING, SEOUL)[1].title)
    }

    @Test
    fun `앱에서 하던 대화는 그 사실을 제목 앞에 단다`() {
        val options = conversationChoiceOptions(CHOOSING, SEOUL)

        assertEquals("앱에서 하던 대화 · 앱에서 하던 대화의 제목", options[2].title)
        assertEquals("새 대화", options[3].title)
    }

    @Test
    fun `적힌 대화가 없으면 그 줄도 없다`() {
        val options = conversationChoiceOptions(CHOOSING.copy(recorded = null), SEOUL)

        assertEquals(3, options.size)
        assertEquals(SessionConversationChoice.StartNewConversation, options.last().choice)
    }

    private companion object {
        const val TERMINAL_ID = "654ce605-b030-40e8-8448-2e9003ad32b1"
        const val UNTITLED_ID = "1f0c2a7e-0000-4000-8000-000000000001"

        val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")

        val CHOOSING = SessionEntryState.ChoosingConversation(
            candidates = listOf(
                ExistingConversation(TERMINAL_ID, "터미널에서 하던 설계 검토", "2026-08-27T06:05:50Z", "2026-11-01T09:00:56Z"),
                ExistingConversation(UNTITLED_ID, "", null, "2026-10-30T01:00:00Z"),
            ),
            recorded = ExistingConversation("2a9d3b8f-0000-4000-8000-000000000002", "앱에서 하던 대화의 제목", null, "2026-10-31T12:00:00Z"),
            defaultChoice = SessionConversationChoice.AdoptConversation(TERMINAL_ID),
        )
    }
}
