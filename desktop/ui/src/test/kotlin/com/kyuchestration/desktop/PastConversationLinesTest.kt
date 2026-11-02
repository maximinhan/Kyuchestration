package com.kyuchestration.desktop

import com.kyuchestration.desktop.terminal.SessionTarget
import com.kyuchestration.desktop.terminal.chat.ChatConversation
import com.kyuchestration.desktop.terminal.chat.ChatEntry
import com.kyuchestration.desktop.terminal.chat.PastTurnContent
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PastConversationLinesTest {

    @Test
    fun `새 대화에는 머리줄이 없다`() {
        assertNull(resumedConversationHeadline(ChatConversation(SessionTarget.Main, entries = PAST_TURNS)))
    }

    @Test
    fun `이어온 대화의 머리줄은 지난 항목 수를 말한다`() {
        val conversation = ChatConversation(SessionTarget.Main, resumedConversationId = RESUMED_ID, entries = PAST_TURNS + LIVE_ENTRY)

        // 건너뛴 줄 자리표시는 대화의 한 턴이 아니다 — 세면 보이는 말보다 수가 크다.
        assertEquals("이어온 대화 · 2개", resumedConversationHeadline(conversation))
    }

    @Test
    fun `앞에서 생략된 항목이 있으면 머리줄이 그 수를 함께 말한다`() {
        val conversation = ChatConversation(
            SessionTarget.Main,
            resumedConversationId = RESUMED_ID,
            entries = PAST_TURNS,
            pastTurnsOmittedBefore = 933,
        )

        assertEquals("이어온 대화 · 2개 · 앞선 933개는 보이지 않습니다", resumedConversationHeadline(conversation))
    }

    @Test
    fun `지난 항목을 받지 못한 이어가기는 예전 안내를 그대로 둔다`() {
        val conversation = ChatConversation(
            SessionTarget.Main,
            resumedConversationId = RESUMED_ID,
            entries = listOf(ChatEntry.EngineNotice("지난 대화를 불러오지 못했습니다: …")),
        )

        assertEquals("이 대화에는 앞선 내용이 있습니다 — 화면에는 이번 실행부터 보입니다.", resumedConversationHeadline(conversation))
    }

    @Test
    fun `이번 세션 구분선은 마지막 지난 항목 바로 뒤에 선다`() {
        // 엔진의 경고는 이번 세션이 뜨며 남긴 말이라 선 아래다.
        assertEquals(3, thisSessionDividerIndex(PAST_TURNS + ChatEntry.EngineNotice("경고") + LIVE_ENTRY))
        assertEquals(3, thisSessionDividerIndex(PAST_TURNS))
    }

    @Test
    fun `지난 항목이 없으면 구분선도 없다`() {
        assertNull(thisSessionDividerIndex(listOf(LIVE_ENTRY)))
        assertNull(thisSessionDividerIndex(emptyList()))
    }

    @Test
    fun `지난 항목의 시각은 현지 시간의 월-일 시분이다`() {
        assertEquals("11-01 18:06", pastTurnTimeLabel(Instant.parse("2026-11-01T09:06:18Z"), ZoneId.of("Asia/Seoul")))
    }

    @Test
    fun `지난 도구 호출은 이름과 요약 한 줄이고 요약이 없으면 이름만이다`() {
        assertEquals("도구 Bash · ls -la", pastToolUseLine("Bash", "ls -la"))
        assertEquals("도구 TodoWrite", pastToolUseLine("TodoWrite", ""))
    }

    @Test
    fun `건너뛴 줄은 그 수를 말한다`() {
        assertEquals("하위 에이전트 줄 3개 생략", omittedPastLinesLine(3))
    }

    private companion object {
        const val RESUMED_ID = "211f6974-88a8-4453-9248-a02b0d6febae"

        val PAST_TURNS = listOf(
            ChatEntry.PastTurn(PastTurnContent.UserSaid("ls 해 줘"), Instant.parse("2026-11-01T09:06:18Z")),
            ChatEntry.PastTurn(PastTurnContent.ToolUsed("Bash", "ls -la"), null),
            ChatEntry.PastTurn(PastTurnContent.LinesOmitted(3)),
        )

        val LIVE_ENTRY = ChatEntry.UserSaid("이어서 하자")
    }
}
