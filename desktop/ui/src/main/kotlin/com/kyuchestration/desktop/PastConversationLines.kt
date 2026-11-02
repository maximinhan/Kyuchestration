package com.kyuchestration.desktop

import com.kyuchestration.desktop.terminal.chat.ChatConversation
import com.kyuchestration.desktop.terminal.chat.ChatEntry
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/*
 * 이어간 대화의 지난 항목을 어떤 글로 보일지(app-owned-sessions-design.md 5.5.8 · chat-ui-design.md 6.3).
 *
 * 그리는 일과 갈라 둔 것은 시험 때문이다. 머리줄의 수와 구분선의 자리는 사람이 "어디까지가 지난 것인가" 를 읽는 근거라,
 * 픽셀이 아니라 값으로 확인한다.
 */

/**
 * 이어가는 대화의 전사 맨 위에 서는 머리줄. 이어가지 않는 세션이면 null 이다.
 *
 * 지난 항목이 하나도 없으면 예전 문구를 그대로 쓴다 — 엔진이 지난 내용을 주지 못했거나 그 전사가 비어 있는 자리이고, 그래도
 * `claude` 는 앞선 맥락을 안다. 받지 못한 까닭은 바로 아래 엔진의 줄이 말한다.
 */
internal fun resumedConversationHeadline(conversation: ChatConversation): String? {
    if (conversation.resumedConversationId == null) {
        return null
    }
    val pastTurnCount = conversation.entries.count { it is ChatEntry.PastTurn }
    if (pastTurnCount == 0 && conversation.pastTurnsOmittedBefore == 0) {
        return "이 대화에는 앞선 내용이 있습니다 — 화면에는 이번 실행부터 보입니다."
    }
    val omittedNotice = if (conversation.pastTurnsOmittedBefore > 0) {
        " · 앞선 ${conversation.pastTurnsOmittedBefore}개는 보이지 않습니다"
    } else {
        ""
    }
    return "이어온 대화 · ${pastTurnCount}개$omittedNotice"
}

/**
 * "여기부터 이번 세션" 구분선이 설 자리 — [entries] 의 이 번호 앞이다. 지난 항목이 없으면 null.
 *
 * 마지막 지난 항목 바로 뒤다. 엔진의 경고도 이번 세션이 뜨며 남긴 말이라 선 아래에 선다.
 */
internal fun thisSessionDividerIndex(entries: List<ChatEntry>): Int? =
    entries.indexOfLast { it is ChatEntry.PastTurn }.takeIf { it >= 0 }?.plus(1)

/** 지난 항목의 시각. 날짜까지 붙인다 — 이어온 대화는 며칠 전의 것인 일이 흔하다. 사람의 시계(현지 시간)로 읽는다. */
internal fun pastTurnTimeLabel(at: Instant, zone: ZoneId): String = PAST_TURN_TIME_FORMAT.format(at.atZone(zone))

private val PAST_TURN_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

/** 지난 도구 호출 한 줄. 결과가 없으므로 카드로 세우지 않는다 — 카드로 세우면 결과를 기다리는 것처럼 보인다. */
internal fun pastToolUseLine(toolName: String, summary: String): String =
    if (summary.isBlank()) "도구 $toolName" else "도구 $toolName · $summary"

/** 엔진이 건너뛴 하위 에이전트 줄. */
internal fun omittedPastLinesLine(count: Int): String = "하위 에이전트 줄 ${count}개 생략"
