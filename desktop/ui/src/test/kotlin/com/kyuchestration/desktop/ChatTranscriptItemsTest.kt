package com.kyuchestration.desktop

import com.kyuchestration.desktop.terminal.SessionTarget
import com.kyuchestration.desktop.terminal.chat.ChatConversation
import com.kyuchestration.desktop.terminal.chat.ChatEntry
import com.kyuchestration.desktop.terminal.chat.PastTurnContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 전사 목록이 세우는 항목 수와 "맨 아래를 보고 있었는가" 의 판정(ChatTranscript).
 *
 * 전사의 항목(entries)과 목록의 항목은 같지 않다 — 머리줄 · 구분선 · 흐르는 글 · 끝난 줄이 끼어든다. 둘을 섞어 견주면 맨
 * 아래를 보던 사람을 위로 본 것으로 읽어 따라 내려가지 않는다.
 */
class ChatTranscriptItemsTest {

    @Test
    fun `새 대화의 목록 항목은 전사 항목뿐이다`() {
        assertEquals(2, transcriptListItemCount(ChatConversation(SessionTarget.Main, entries = LIVE_ENTRIES)))
    }

    @Test
    fun `이어온 대화는 머리줄 하나와 구분선 하나가 더 선다`() {
        val conversation = ChatConversation(SessionTarget.Main, resumedConversationId = RESUMED_ID, entries = PAST_TURNS + LIVE_ENTRIES)

        assertEquals(1 + 2 + 1 + 2, transcriptListItemCount(conversation))
    }

    @Test
    fun `지난 항목을 받지 못한 이어가기는 머리줄만 더 선다`() {
        val conversation = ChatConversation(SessionTarget.Main, resumedConversationId = RESUMED_ID, entries = LIVE_ENTRIES)

        assertEquals(1 + 2, transcriptListItemCount(conversation))
    }

    @Test
    fun `흐르는 글과 끝난 줄도 목록의 항목이다`() {
        val conversation = ChatConversation(
            SessionTarget.Main,
            resumedConversationId = RESUMED_ID,
            entries = PAST_TURNS + LIVE_ENTRIES,
            streamingText = "쓰는 중",
            endedExitCode = 0,
        )

        assertEquals(1 + 2 + 1 + 2 + 1 + 1, transcriptListItemCount(conversation))
    }

    @Test
    fun `마지막 목록 항목이 보이면 맨 아래를 보고 있던 것이다`() {
        assertTrue(wasShowingLastListItem(lastVisibleItemIndex = 5, previousListItemCount = 6))
    }

    @Test
    fun `마지막 목록 항목이 보이지 않으면 위로 올려 읽는 중이다`() {
        assertFalse(wasShowingLastListItem(lastVisibleItemIndex = 3, previousListItemCount = 6))
    }

    @Test
    fun `아직 배치가 없으면 맨 아래를 보는 것으로 친다`() {
        // 창이 막 열린 자리다. 처음 여는 창은 가장 최근에서 시작한다.
        assertTrue(wasShowingLastListItem(lastVisibleItemIndex = null, previousListItemCount = 0))
    }

    private companion object {
        const val RESUMED_ID = "211f6974-88a8-4453-9248-a02b0d6febae"

        val PAST_TURNS = listOf(
            ChatEntry.PastTurn(PastTurnContent.UserSaid("ls 해 줘")),
            ChatEntry.PastTurn(PastTurnContent.LinesOmitted(3)),
        )

        val LIVE_ENTRIES = listOf(ChatEntry.EngineNotice("경고"), ChatEntry.UserSaid("이어서 하자"))
    }
}
