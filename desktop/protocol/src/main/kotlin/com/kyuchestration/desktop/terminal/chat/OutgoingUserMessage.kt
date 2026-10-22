package com.kyuchestration.desktop.terminal.chat

import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 앱이 `claude` 에게 보내는 사용자의 말 한 줄.
 *
 * **`uuid` 를 앱이 정한다.** stdin 줄에 실어 보내면 되돌림(`user` · `isReplay`)과 `command_lifecycle`
 * 이 같은 값을 그대로 실어 온다(session-tree 3.5 (다)). 앱이 자기가 보낸 말을 알아보는 열쇠가 이것
 * 하나다 — 텍스트로 알아보면 같은 말을 두 번 보냈을 때, 또는 `claude` 가 스스로 지은 줄(중단
 * 안내)의 문구가 우연히 같을 때 둘을 가르지 못한다.
 *
 * 보내기 전부터 쓰인다. 상태 홀더는 쓰기가 끝나기 전에 이 말을 "대기 중" 으로 세워 두고
 * ([ChatConversation.pendingUserMessages]), 되돌아온 `uuid` 로 그것을 놓는다.
 *
 * @param sender 이 말을 누가 했는가. 되돌아온 말풍선을 누구의 것으로 그릴지가 이 값이다(session-tree 6.12) —
 *   `uuid` 가 이 말과 되돌아온 줄을 잇고, 이 값이 그 줄의 주인을 말한다.
 */
@Serializable
data class OutgoingUserMessage(
    val text: String,
    val sender: UserMessageSender = UserMessageSender.Human,
    val uuid: String = UUID.randomUUID().toString(),
)

/**
 * 사용자 메시지 자리로 들어간 말의 주인(session-tree-design.md 6.5).
 *
 * `claude` 에게는 모두 사용자 메시지다. 앱만 이 셋을 가른다 — 모델이 출처를 읽는 재료는 접두이고(3.5 (가)),
 * 화면이 출처를 읽는 재료는 이 값이다. **접두로 가르지 않는다.** 사람이 `[상위 세션]` 을 직접 쳐도 그것은
 * 사람의 말이다(3.5 (다)).
 */
@Serializable
sealed interface UserMessageSender {

    /** 사람이 입력창에 친 말. */
    @Serializable
    @SerialName("human")
    data object Human : UserMessageSender

    /** 상위 세션이 이 하위에게 한 말 — 첫 프롬프트와 `send_to_session`. */
    @Serializable
    @SerialName("parent_session")
    data object ParentSession : UserMessageSender

    /** 하위 세션이 상위에게 한 말 — 쉬는 상위에게 앱이 대신 옮긴 사건. */
    @Serializable
    @SerialName("child_session")
    data class ChildSession(val childId: String) : UserMessageSender
}
