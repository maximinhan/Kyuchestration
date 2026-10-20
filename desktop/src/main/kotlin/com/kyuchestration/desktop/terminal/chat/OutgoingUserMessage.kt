package com.kyuchestration.desktop.terminal.chat

import java.util.UUID

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
 */
data class OutgoingUserMessage(
    val text: String,
    val uuid: String = UUID.randomUUID().toString(),
)
