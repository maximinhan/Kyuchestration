package com.kyuchestration.desktop.terminal

import com.jediterm.terminal.TtyConnector
import java.util.concurrent.CompletableFuture

/**
 * 방금 띄운 세션 하나 — 화면이 읽고 쓸 통로와, 그 세션이 끝났음을 알리는 자리.
 *
 * @param sessionExit 세션 안의 프로그램이 끝나면 그 종료 코드로 완료된다.
 *
 *   **끝을 폴링으로 알아내지 않기 위한 자리다.** 대시보드가 아는 것은 최대 3 초 묵은 것인데, 앱이
 *   자기가 보유한 세션에 대해 아는 것까지 그 지연을 타면 사용자가 `/exit` 한 뒤에도 카드가 한참
 *   세션이 도는 것처럼 보인다(설계 문서 부록 A 의 마지막 줄).
 *
 *   `TtyConnector.waitFor` 로도 같은 것을 알 수 있지만 그쪽은 세션마다 스레드 하나를 붙잡고
 *   앉아 있어야 하고, 상태 홀더의 시험에서는 그 블로킹이 가상 시간과 섞이지 않는다.
 *
 * @param resumedConversationId 이 세션이 이어가는 대화의 ID. 새 대화면 null.
 *
 *   **끝났을 때에야 쓰이는 값이다.** 이어가기로 연 세션이 0 이 아닌 코드로 끝났다면 이어갈
 *   대화를 찾지 못한 것으로 보고, 화면이 그 자리에서 새로 시작할 길을 낸다(설계 문서 5.5.4).
 */
class OpenedSessionTerminal(
    val ttyConnector: TtyConnector,
    val sessionExit: CompletableFuture<Int>,
    val resumedConversationId: String?,
)
