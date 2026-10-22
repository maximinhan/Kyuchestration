package com.kyuchestration.desktop.terminal.chat

import com.kyuchestration.desktop.protocol.FailureView
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

    @Serializable
    @SerialName("failed")
    data class Failed(val failure: FailureView) : SessionEntryState
}
