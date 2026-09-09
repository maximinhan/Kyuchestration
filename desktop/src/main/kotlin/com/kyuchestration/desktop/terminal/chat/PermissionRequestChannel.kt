package com.kyuchestration.desktop.terminal.chat

import com.kyuchestration.desktop.terminal.TerminalSessionFailure
import java.nio.file.Path
import kotlinx.coroutines.flow.Flow

/**
 * 세션 하나의 승인 물음이 들어오는 통로(chat-ui-design.md 5.4).
 *
 * 실제로 이것을 여는 것은 유닉스 도메인 소켓 하나다([PermissionRequestSocket]). 그런데도 통로를
 * 따로 이름 붙인 이유는 상태 홀더가 재는 것과 소켓이 재는 것이 다르기 때문이다 — 홀더 쪽에서
 * 갈리는 것은 "물음이 카드가 되는가 · 세션 규칙이 그것을 자동으로 답하는가 · 세션이 끝날 때
 * 통로가 닫히는가" 이고, 그 답에 진짜 소켓이 필요하지 않다. 소켓 자체의 왕복은 진짜 소켓으로
 * 따로 잰다(PermissionRequestSocketTest).
 */
interface PermissionRequestChannel {

    /**
     * 이 통로의 자리. 엔진에게 `--approval-socket` 으로 넘어가는 값이 이것이다(설계 5.2).
     *
     * **앱이 정해 엔진에게 준다.** 엔진이 스스로 정하면 앱이 그것을 되읽어야 하고, 그러면 소켓을
     * 여는 쪽과 자리를 정하는 쪽이 갈린다.
     */
    val socketPath: Path

    /** 이 세션에 올라온 승인 물음들. 받는 쪽은 하나다(상태 홀더). */
    val requests: Flow<AskedPermission>

    /**
     * 이 통로를 닫는다.
     *
     * 답을 기다리던 물음은 함께 끝난다 — 그쪽에서는 연결이 끊기는 것이고, 그것을 엔진은 거절로
     * 읽는다(mcp_ask.go 의 EOF 갈래).
     */
    suspend fun close()
}

/**
 * 세션 하나를 위해 승인 통로를 여는 자리.
 *
 * 세션마다 새로 연다. 하나를 공유하면 어느 세션의 물음인지를 메시지 안에 담아야 하고, 그러면 그
 * 식별자를 누가 정하고 누가 검증하는지가 새로 생긴다(설계 5.4).
 */
fun interface PermissionRequestChannelOpener {

    /**
     * @throws TerminalSessionFailure.ApprovalSocketFailedToOpen 통로를 열지 못했을 때.
     * @throws TerminalSessionFailure.ApprovalSocketPathTooLong 자리가 107 바이트를 넘을 때.
     */
    fun openPermissionRequestChannel(): PermissionRequestChannel
}
