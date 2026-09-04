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
 * @param endedExitCode 프로세스가 끝났으면 그 종료 코드. 살아 있으면 null.
 */
data class ChatConversation(
    val target: SessionTarget,
    val conversationId: String? = null,
    val modelName: String? = null,
    val resumedConversationId: String? = null,
    val entries: List<ChatEntry> = emptyList(),
    val streamingText: String? = null,
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
 *
 * 중단(`Interrupting`)은 아직 갈래에 없다. 중단 버튼이 4 단계에 서므로, 그 전에 갈래만 만들어
 * 두면 어느 화면도 그 상태를 만들지 못한 채 이름이 굳는다(원칙 4).
 */
enum class TurnState {

    /** 사용자의 다음 말을 받을 수 있다. */
    Idle,

    /**
     * 턴이 돌고 있다. 입력창이 잠긴다.
     *
     * **설계 6.5 와 다르게 정했다.** 그 절은 잠그지 않는 쪽이었다 — `claude` 가 큐를 갖고 있어
     * (3.11) 도는 중에 보내도 받아 주기 때문이다. 다만 3 단계에는 중단 버튼이 없어서, 열어 두면
     * 사용자가 쌓아 둔 말을 되돌릴 길이 없다. 중단이 서는 4 단계에 이 판단을 다시 본다.
     */
    Running,
}
