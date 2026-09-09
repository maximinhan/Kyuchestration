package com.kyuchestration.desktop.terminal.chat

import java.time.Instant
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * 전사에 쌓이는 것 하나 — 화면이 그리는 단위다(chat-ui-design.md 5.5 · 6.3).
 *
 * **이벤트와 일대일이 아니다.** [ChatSessionEvent] 는 `claude` 가 말하는 단위이고 이쪽은 사람이
 * 읽는 단위다. 도구 호출 하나가 이벤트 둘([ChatSessionEvent.ToolCallRequested] 과
 * [ChatSessionEvent.ToolCallAnswered])로 오지만 화면에서는 카드 하나이고, 글자 조각 수백 개는
 * 전사에 아예 쌓이지 않는다 — 그것은 완성본이 오기 전까지의 임시 버퍼다(5.3.2).
 *
 * 갈래가 이번 단계에서 그릴 수 있는 것까지다. 위임·서브에이전트 카드(6 단계)는 그릴 화면이 설
 * 때 더한다 — 지금 갈래만 만들어 두면 그것을 채울 자리가 없는 채로 필드 이름이 굳는다(원칙 4).
 * 승인 카드([PermissionAsked])가 이번 단계에서 그렇게 더해진 갈래다.
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
     * @param requestedAt 이 호출이 스트림에 실려 온 시각. 도는 동안 카드가 보이는 경과 시간이
     *   여기서 나온다 — 앱의 시계가 아니라 스트림이 준 값이다(ChatSessionEvent.ToolCallRequested).
     * @param nestedEntries 이 호출 **안쪽**에서 일어난 것들. 서브에이전트가 자기 대화를
     *   `parent_tool_use_id` 를 달고 보내면(3.10) 그것이 여기로 접힌다 — 대화 본문에 풀어 놓으면
     *   메인 대화와 안쪽 대화가 한 줄기로 섞인다.
     */
    data class ToolCall(
        val toolUseId: String,
        val toolName: String,
        val input: JsonObject,
        val requestedAt: Instant? = null,
        val answer: ToolCallAnswer? = null,
        val nestedEntries: List<ChatEntry> = emptyList(),
        val subagentRun: SubagentRun? = null,
    ) : ChatEntry

    /**
     * 관문이 열려 사람에게 물은 자리(6.4).
     *
     * **다이얼로그가 아니라 전사 안의 카드다.** 물음의 맥락 — 직전에 모델이 무엇을 하겠다고
     * 했는지 — 이 바로 위에 있어야 사용자가 판단할 수 있고, 다이얼로그는 그것을 가린다.
     *
     * **답한 뒤에도 남는다.** 무엇을 허용했고 무엇을 거절했는지가 그 자리에 남아 있어야, 나중에
     * 스크롤을 올린 사용자가 "이 파일은 왜 안 만들어졌지" 를 그 자리에서 읽는다.
     *
     * @param toolUseId 같은 id 의 도구 카드가 이미 전사에 서 있다([ToolCall]). 잇는 근거가 이 값이다.
     * @param input 모델이 요청한 인자. **사용자가 고친 인자는 여기가 아니라 [answer] 에 있다** —
     *   물음과 답을 한 자리에 겹쳐 쓰면 무엇을 고쳤는지가 화면에서 사라진다.
     */
    data class PermissionAsked(
        val toolUseId: String,
        val toolName: String,
        val input: JsonObject,
        val answer: PermissionAnswer? = null,
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
 * @param typedResult `tool_use_result` 곁가지 — 그 도구가 실제로 한 일이 도구마다 다른 모양으로
 *   들어 있다(3.4). **카드의 갈래를 정하는 것이 이 값이다**(6.3): Bash 의 `stdout`, Edit 의
 *   `structuredPatch`, Read 의 파일 메타가 전부 여기서 온다.
 *
 *   갈래를 여기서 나누지 않고 원래 값을 그대로 들고 있는다. 무엇을 어떤 카드로 그릴지는 그리는
 *   쪽의 판단이고(ToolCallCardContent), 모양이 도구마다 다른 값을 상태 쪽에서 미리 좁히면
 *   그 좁힘에 안 맞는 도구가 붙는 날 화면이 아니라 상태를 고쳐야 한다.
 */
data class ToolCallAnswer(
    val failed: Boolean,
    val modelVisibleText: String,
    val typedResult: JsonElement? = null,
    val answeredAt: Instant? = null,
)

/**
 * 이 도구 호출이 띄운 서브에이전트가 지금 어디까지 왔는가(3.10 · 6.3 의 SubagentCard).
 *
 * **도구 호출의 인자·결과가 아니라 `system/task_*` 에서 온다.** 그래서 [ToolCallAnswer] 옆이
 * 아니라 카드 자체에 붙는다 — 결과가 오기 전에도 채워지는 값이고, 결과가 온 뒤에도 남는다.
 *
 * 이 값이 있다는 것이 곧 "이 카드는 서브에이전트다" 다. **도구 이름으로 가르지 않는다** — 그
 * 이름은 판마다 달라진다(설계 문서의 `Task` 가 이 판에서는 `Agent` 였다).
 *
 * @param subagentType `general-purpose` 같은 것. 진행·끝만 받고 시작을 놓친 카드에서는 비어 있다 —
 *   그때도 진행은 사실이므로 카드를 세운다.
 * @param lastDescription 그 에이전트가 지금 무엇을 하는 중인가(`task_progress.description`).
 * @param lastToolName 마지막으로 부른 도구 이름.
 * @param finishedStatus 끝났으면 그 모양(`task_notification.status`). 아직이면 null.
 * @param outputFilePath 그 실행의 원출력이 남은 자리. 카드가 경로를 그대로 보인다.
 */
data class SubagentRun(
    val subagentType: String,
    val lastDescription: String? = null,
    val lastToolName: String? = null,
    val finishedStatus: String? = null,
    val outputFilePath: String? = null,
)

/**
 * 승인 물음에 무엇으로 답했는가 — 결정 뒤에 카드가 남는 모습이다(6.4).
 *
 * 참·거짓 하나로 두지 않는다. 네 갈래가 화면에서 서로 다른 사실을 말한다 — 특히 마지막 둘은
 * **사람이 이 카드를 보고 눌렀는가**가 갈린다. 규칙이 자동으로 허용한 것을 사람이 허용한 것과
 * 같은 모양으로 그리면, 사용자는 자기가 보지 않은 승인을 자기가 한 것으로 읽는다.
 */
sealed interface PermissionAnswer {

    /**
     * 이 한 번을 허용했다.
     *
     * @param editedInput 사용자가 카드에서 고친 인자. 고치지 않았으면 null 이다 — 원본과 같은 값을
     *   담아 두면 화면이 "고쳤음" 과 "그대로 허용" 을 가르지 못한다.
     */
    data class Allowed(val editedInput: JsonObject? = null) : PermissionAnswer

    /** 이 세션이 사는 동안 이 도구를 계속 허용하기로 했다. 이 호출도 그래서 허용됐다. */
    data object AllowedForThisSession : PermissionAnswer

    /**
     * 앞서 세운 이 세션의 규칙이 자동으로 허용했다 — **사람이 이 카드를 보고 누른 것이 아니다.**
     *
     * 카드를 그래도 세우는 이유가 여기 있다. 규칙이 무엇을 통과시켰는지가 전사에 남지 않으면,
     * 사용자는 자기가 세운 규칙이 실제로 무엇을 허용했는지 볼 길이 없다.
     */
    data object AllowedByThisSessionRule : PermissionAnswer

    /**
     * 거절했다.
     *
     * @param reason 모델에게 그대로 간 문구(A.5). 사용자가 적지 않았으면 앱이 정한 기본 문구다.
     */
    data class Denied(val reason: String) : PermissionAnswer
}
