package com.kyuchestration.desktop.terminal.chat

import com.kyuchestration.desktop.terminal.SessionTarget
import com.kyuchestration.desktop.terminal.resumeFailureSuspected

/**
 * 세션 하나의 대화 전부 — 화면이 그릴 것과, 그리는 데 필요한 사실들(chat-ui-design.md 5.5).
 *
 * **전사가 데이터로 여기 있다는 것이 터미널 시절과 가장 다른 점이다.** 그때는 화면에 없는 세션의
 * 위젯을 살려 둬야 상태를 잃지 않았지만, 챗에서는 화면이 없어도 아무것도 잃지 않는다 — 살려
 * 둬야 하는 것은 stdout 을 읽는 코루틴 하나뿐이다(ProcessChatSession).
 *
 * **메모리에만 있다.** 앱을 닫으면 사라지고, 다시 열면 `--resume` 으로 대화는 이어지지만 화면의
 * 전사는 비어 있다. 그 어긋남을 화면이 먼저 말한다([resumedConversationId] 가 그 근거다).
 * 파일로 남기는 것은 v1 밖이다(10 절 열린 질문 3).
 *
 * @param conversationId `system/init` 이 알려준 이 대화의 ID. 턴마다 다시 오지만 값은 같다(3.1).
 * @param resumedConversationId 이 세션이 이어가려던 대화. 새 대화면 null. 두 자리에서 쓰인다 —
 *   전사 맨 위의 "앞선 내용이 있습니다" 안내와, 세션이 실패한 코드로 끝났을 때의 판단이다(5.5.4).
 * @param streamingText 아직 완성본이 오지 않은 글자 조각들(3.3). **전사가 아니다** — 완성본이
 *   오면 버려진다. 조각만으로 전사를 만들면 중단된 턴의 잘린 문장이 영구히 남는다(5.3.2).
 * @param pendingUserMessages 앱이 보냈는데 아직 되돌아오지 않은 말들(3.11 · 3.14).
 *
 *   **전사가 아니다.** 말풍선은 되돌아온 것으로만 그리고([entries]), 이 목록은 그 사이의 공백을
 *   메운다. 그 공백이 실제로 길다 — 턴이 도는 중에 보낸 말은 그 턴이 끝나고 **다음 턴이 시작할
 *   때에야** 되돌아온다(2026-09-04 실측: 보낸 지 78 초 뒤). 이것을 들고 있지 않으면 사용자가
 *   방금 친 말이 그동안 화면 어디에도 없다.
 * @param streamingBlockOrdinal 지금까지 시작된 조각 블록이 몇 번째인가.
 *
 *   **화면이 이 번호를 필요로 한다.** 스트리밍 렌더러의 상태는 덧붙이기 전용이라(5.8의
 *   `rememberStreamingMarkdownState`) 블록이 바뀌면 그 상태를 새로 세워야 하는데, 버퍼 문자열만
 *   보면 "짧아졌다" 와 "새 블록이 시작됐다" 를 가를 수 없다. 두 사건이 한 프레임에 겹쳐 오면
 *   앞 블록의 글자가 새 블록에 이어 붙는다.
 * @param endedExitCode 프로세스가 끝났으면 그 종료 코드. 살아 있으면 null.
 */
data class ChatConversation(
    val target: SessionTarget,
    val conversationId: String? = null,
    val modelName: String? = null,
    val resumedConversationId: String? = null,
    val entries: List<ChatEntry> = emptyList(),
    val pendingUserMessages: List<String> = emptyList(),
    val streamingText: String? = null,
    val streamingBlockOrdinal: Long = 0,
    val turnState: TurnState = TurnState.Idle,
    val totalCostUsd: Double = 0.0,
    val usageWindows: List<RateLimitWindow> = emptyList(),
    val endedExitCode: Int? = null,
) {

    /** 이 세션의 프로세스가 아직 살아 있는가. */
    val alive: Boolean get() = endedExitCode == null

    /**
     * 이어가려던 대화를 열지 못한 것으로 보인다 — 이어가기로 연 세션이 0 이 아닌 코드로 끝났다.
     *
     * 판단을 터미널 화면과 나눠 쓴다(EmbeddedTerminalState 의 같은 함수). 화면 모델이 둘이라고
     * 같은 판단을 두 벌 적으면 한쪽만 고쳐지는 날이 오고, 그날 두 화면은 같은 실패를 두고 다른
     * 말을 한다.
     */
    val resumeFailureSuspected: Boolean
        get() = resumeFailureSuspected(resumedConversationId, endedExitCode)
}

/**
 * 지금 이 대화가 답을 기다리고 있는가.
 */
enum class TurnState {

    /** 사용자의 다음 말을 받을 수 있다. */
    Idle,

    /**
     * 답을 기다리는 중이다 — 도는 턴이 있거나, 큐에 넣은 말이 아직 되돌아오지 않았다.
     *
     * **입력창을 잠그지 않는다**(설계 6.5 로 돌아왔다). 3 단계는 잠갔다 — 중단 버튼이 없어
     * 쌓아 둔 말을 되돌릴 길이 없어서였다. 그 버튼이 선 지금은 잠글 이유가 없고, 잠그면
     * `claude` 가 이미 가진 큐(3.11)를 앱이 막는 꼴이 된다.
     */
    Running,

    /**
     * 끊어 달라고 말했고 아직 그 턴의 끝을 받지 못했다(3.8).
     *
     * **[Idle] 로 곧바로 넘기지 않는 이유가 이 갈래의 존재 이유다.** 중단 요청은 stdin 에 한 줄을
     * 쓰는 일이고, 그 뒤에도 이미 만들어진 글자들이 잠시 더 흐른다. 요청한 순간 입력창을 열면
     * 화면은 턴이 끝났다고 말하는데 답은 계속 자라고, 그 사이에 보낸 말은 끊기려는 턴에 얹힌다.
     * 끝났다는 사실은 `result` 하나가 말한다(TurnFinished).
     */
    Interrupting,
}
