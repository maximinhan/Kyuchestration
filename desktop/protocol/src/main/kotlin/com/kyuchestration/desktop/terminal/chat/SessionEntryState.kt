package com.kyuchestration.desktop.terminal.chat

import com.kyuchestration.desktop.protocol.FailureView
import com.kyuchestration.desktop.terminal.SessionConversationChoice
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 사람이 띄우라고 한 세션 하나가 아직 보유되지 못한 사이의 모습. 보유되면 이 값이 없어지고 대화가 선다.
 *
 * "없음" 을 갈래로 두지 않는다. 진입이 없는 세션은 [ChatSessionStateHolder.sessionEntries] 에 열쇠가 없다 — 갈래로
 * 두면 그 맵의 값이 "없음" 인 것과 열쇠가 없는 것이 같은 뜻의 두 모양이 된다.
 */
@Serializable
sealed interface SessionEntryState {

    /** 무엇을 띄울지 엔진에게 묻고 프로세스를 띄우는 중이다. */
    @Serializable
    @SerialName("running")
    data object Running : SessionEntryState

    /**
     * 이어가기를 눌렀는데 앱에 적힌 것보다 최근인 바깥 대화가 있어 사람에게 고르게 하는 중이다(app-owned-sessions-design.md 5.5.7).
     *
     * 아직 엔진에게 세션 명령을 묻지 않았다. 묻는 것이 곧 기록하는 것이라(5.5.3), 고르기 전에 물으면 고르기도 전에 기록이 바뀐다.
     *
     * @param candidates 그 자리에서 앱 밖으로 나눈 대화들. 마지막 활동이 늦은 것부터다. 비어 있으면 이 갈래에 오지 않는다.
     * @param recorded 이 세션의 대화로 적혀 있고 전사가 있는 대화. 없으면 null 이다.
     * @param defaultChoice 미리 골라 둘 것. 화면이 계산하지 않는다 — 고르기의 규칙이 두 곳에 놓이면 어긋난다.
     */
    @Serializable
    @SerialName("choosing_conversation")
    data class ChoosingConversation(
        val candidates: List<ExistingConversation>,
        val recorded: ExistingConversation?,
        val defaultChoice: SessionConversationChoice,
    ) : SessionEntryState

    @Serializable
    @SerialName("failed")
    data class Failed(val failure: FailureView) : SessionEntryState
}

/**
 * 고르기 화면에 보일 대화 하나. 엔진의 `kyu conversations list` 가 답한 것을 화면에 필요한 만큼만 옮긴 값이다.
 *
 * 시각은 엔진이 낸 RFC 3339 UTC 문자열 그대로다. 현지 시간으로 보이는 것은 화면의 일이고, 코어와 화면의 시간대가 다를 수
 * 있다(윈도우 화면 + WSL 코어).
 *
 * @param title 엔진이 답한 제목. 비어 있을 수 있다 — 화면이 id 앞자리로 대신 보인다.
 * @param startedAt 첫 메시지 시각. 엔진이 알 수 없으면 null 이다.
 */
@Serializable
data class ExistingConversation(
    val id: String,
    val title: String,
    val startedAt: String?,
    val lastActiveAt: String,
)
