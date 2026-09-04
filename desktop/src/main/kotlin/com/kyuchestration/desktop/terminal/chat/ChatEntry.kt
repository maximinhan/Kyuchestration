package com.kyuchestration.desktop.terminal.chat

import kotlinx.serialization.json.JsonObject

/**
 * 전사에 쌓이는 것 하나 — 화면이 그리는 단위다(chat-ui-design.md 5.5 · 6.3).
 *
 * **이벤트와 일대일이 아니다.** [ChatSessionEvent] 는 `claude` 가 말하는 단위이고 이쪽은 사람이
 * 읽는 단위다. 도구 호출 하나가 이벤트 둘([ChatSessionEvent.ToolCallRequested] 과
 * [ChatSessionEvent.ToolCallAnswered])로 오지만 화면에서는 카드 하나이고, 글자 조각 수백 개는
 * 전사에 아예 쌓이지 않는다 — 그것은 완성본이 오기 전까지의 임시 버퍼다(5.3.2).
 *
 * 갈래가 이번 단계에서 그릴 수 있는 것까지다. 승인 카드(5 단계)와 위임·서브에이전트 카드
 * (6 단계)는 그릴 화면이 설 때 더한다 — 지금 갈래만 만들어 두면 그것을 채울 자리가 없는 채로
 * 필드 이름이 굳는다(원칙 4).
 */
sealed interface ChatEntry {

    /** 사용자가 한 말. 스트림을 한 바퀴 돌아온 것이지 앱이 넣은 것이 아니다(3.14). */
    data class UserSaid(val text: String) : ChatEntry

    /** 모델이 완성한 텍스트 블록 하나. 마크다운으로 그린다(5.8). */
    data class AssistantSaid(val text: String) : ChatEntry

    /** 모델의 사고 블록 하나. 화면이 접어 두는 자리다. */
    data class AssistantThought(val text: String) : ChatEntry

    /**
     * 도구 호출 하나. 부른 순간 [answer] 없이 서고, 결과가 오면 그 자리에서 채워진다.
     *
     * @param nestedEntries 이 호출 **안쪽**에서 일어난 것들. 서브에이전트가 자기 대화를
     *   `parent_tool_use_id` 를 달고 보내면(3.10) 그것이 여기로 접힌다 — 대화 본문에 풀어 놓으면
     *   메인 대화와 안쪽 대화가 한 줄기로 섞인다.
     */
    data class ToolCall(
        val toolUseId: String,
        val toolName: String,
        val input: JsonObject,
        val answer: ToolCallAnswer? = null,
        val nestedEntries: List<ChatEntry> = emptyList(),
    ) : ChatEntry

    /**
     * 턴 하나가 끝났다는 배지(6.3 의 TurnFooter).
     *
     * 전사 안에 두는 것이 뜻이다. 화면 아래 한 자리에 마지막 턴의 것만 두면 스크롤을 올렸을 때
     * 어느 답이 얼마였는지 알 수 없다 — 비용은 그 턴에 붙는 사실이다.
     *
     * @param permissionDenialCount 권한이 없어 못 한 도구 호출의 수(3.6 의 `dontAsk`). 항목의
     *   안쪽 모양은 아직 재지 못했으므로 세기만 한다 — 없는 필드 이름을 지어내지 않는다(원칙 15).
     */
    data class TurnEnded(
        val outcome: TurnOutcome,
        val costUsd: Double,
        val usage: TurnUsage,
        val durationMillis: Long,
        val permissionDenialCount: Int,
    ) : ChatEntry

    /**
     * `claude` 가 stderr 에 남긴 한 줄(3.9).
     *
     * 이어갈 대화를 찾지 못한 실패가 여기로 온다. PTY 시절에는 이 줄이 터미널 화면에 그대로
     * 보였고, 챗에서는 전사에 자리를 주지 않으면 통째로 사라진다.
     */
    data class EngineNotice(val line: String) : ChatEntry
}

/**
 * 도구 호출 하나의 결과(3.4).
 *
 * @param failed 실패했거나 거절됐다. 접힌 카드에서도 이 사실이 보여야 실패가 대화에 묻히지 않는다(6.3).
 * @param modelVisibleText 모델이 읽은 텍스트. **마크다운으로 그리지 않는다** — 파일 내용이나
 *   명령 출력에 마크다운 문법처럼 보이는 글자가 있으면 화면이 원본과 달라진다(5.8).
 */
data class ToolCallAnswer(val failed: Boolean, val modelVisibleText: String)
