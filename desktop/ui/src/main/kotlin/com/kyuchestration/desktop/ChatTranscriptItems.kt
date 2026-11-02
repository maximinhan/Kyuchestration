package com.kyuchestration.desktop

import com.kyuchestration.desktop.terminal.chat.ChatConversation

/*
 * 전사 목록(ChatTranscript 의 LazyColumn)이 세우는 항목과, "맨 아래를 보고 있었는가" 의 판정.
 *
 * 전사의 항목(ChatConversation.entries)과 목록의 항목은 같은 수가 아니다 — 머리줄 · "여기부터 이번 세션" 선 · 흐르는 글 ·
 * 세션이 끝났다는 줄이 끼어든다. 목록의 번호(배치 정보)와 견주는 수는 언제나 목록 항목의 수여야 한다.
 */

/** 전사 목록이 세우는 항목의 수. ChatTranscript 가 항목을 세우는 조건과 하나하나 같다. */
internal fun transcriptListItemCount(conversation: ChatConversation): Int {
    val headline = if (resumedConversationHeadline(conversation) != null) 1 else 0
    val divider = if (thisSessionDividerIndex(conversation.entries) != null) 1 else 0
    val streaming = if (conversation.streamingText != null) 1 else 0
    val ended = if (!conversation.alive) 1 else 0
    return headline + conversation.entries.size + divider + streaming + ended
}

/**
 * 앞 배치에서 마지막 목록 항목이 보였는가 — 보였으면 새 것을 따라 내려간다.
 *
 * @param lastVisibleItemIndex 앞 배치에서 보인 마지막 항목의 번호. 배치가 아직 없으면 null 이고, 그때는 맨 아래로 친다 —
 *   처음 여는 창은 가장 최근에서 시작한다.
 * @param previousListItemCount 앞 배치 때의 목록 항목 수([transcriptListItemCount]).
 */
internal fun wasShowingLastListItem(lastVisibleItemIndex: Int?, previousListItemCount: Int): Boolean =
    lastVisibleItemIndex == null || lastVisibleItemIndex >= previousListItemCount - 1
