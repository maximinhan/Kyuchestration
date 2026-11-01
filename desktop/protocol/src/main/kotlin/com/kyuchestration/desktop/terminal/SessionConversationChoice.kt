package com.kyuchestration.desktop.terminal

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 세션 하나를 열 때 그 세션이 쓸 대화를 어느 쪽으로 정할지.
 *
 * **앱은 대화 ID 를 짓지도 기록하지도 않는다.** 어느 ID 를 이어갈지, 새 ID 를 무엇으로 할지, 그것을 어디에 어떤 모양으로
 * 적을지는 엔진이 정한다(설계 원칙 11 · 설계 문서 5.5.3). 앱이 쥐는 ID 는 하나뿐이다 — 엔진이 `kyu conversations list` 로
 * 답한 id 를 [AdoptConversation] 으로 **그대로 되돌려 주는 것**(5.5.7). 앱은 그 값을 열어 보지도 기록 파일과 맞춰 보지도
 * 않는다. 기록 파일의 자리와 모양을 아는 곳은 여전히 엔진 하나라, 그 앎이 두 곳으로 갈라지지 않는다.
 *
 * 참·거짓 하나로 두지 않는다. `enterSession(path, target, true)` 를 읽는 사람은 그 참이
 * 이어가기인지 새로 시작인지 알 수 없고, 두 갈래는 한쪽이 되돌릴 수 없는 일이다.
 */
@Serializable
sealed interface SessionConversationChoice {

    /**
     * 적혀 있는 대화가 있으면 이어간다. 없으면 새 대화가 열린다.
     *
     * 카드를 그냥 누른 자리이고, 이것이 기본이다 — 사용자가 아무것도 고르지 않으면 대화가 이어진다. 다만 앱에 적힌 것보다
     * 최근인 바깥 대화가 있으면 코어가 먼저 사람에게 고르게 한다(5.5.7).
     */
    @Serializable
    @SerialName("continue_recorded_conversation")
    data object ContinueRecordedConversation : SessionConversationChoice

    /**
     * 적혀 있는 대화를 버리고 새로 시작한다.
     *
     * **되돌릴 수 없다.** 앞 대화를 가리키던 기록이 그 자리에서 새 것으로 바뀐다. 그래서 화면은
     * 이 길을 사용자가 일부러 고른 자리에만 낸다 — 이어갈 대화가 있는 카드의 버튼, 이어가지
     * 못한 채 끝난 세션의 머리말, 대화 고르기의 "새 대화".
     */
    @Serializable
    @SerialName("start_new_conversation")
    data object StartNewConversation : SessionConversationChoice

    /**
     * 그 자리에서 앱 밖(터미널)으로 나눈 대화 하나를 이 세션의 대화로 적고 이어간다.
     *
     * 적혀 있던 대화는 그 자리에서 이 id 로 바뀐다 — [StartNewConversation] 처럼 되돌릴 수 없는 일이라, 사람이 고르기
     * 화면에서 고른 자리에만 나온다.
     *
     * @param conversationId 엔진이 답한 값 그대로다. 앱이 짓거나 고치면 엔진이 거절한다(uuid 꼴 · 전사 유무 · 위임 대화).
     */
    @Serializable
    @SerialName("adopt_conversation")
    data class AdoptConversation(val conversationId: String) : SessionConversationChoice
}
