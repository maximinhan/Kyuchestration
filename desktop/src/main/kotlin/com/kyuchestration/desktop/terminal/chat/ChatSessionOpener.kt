package com.kyuchestration.desktop.terminal.chat

import com.kyuchestration.desktop.terminal.SessionEntryPlan
import com.kyuchestration.desktop.terminal.TerminalSessionFailure
import kotlinx.coroutines.flow.Flow

/**
 * 챗 세션 하나를 여는 통로. PTY 를 여는 `SessionTerminalOpener` 와 같은 자리다
 * (chat-ui-design.md 5.3.1).
 *
 * 받는 것이 [SessionEntryPlan] 이다 — 엔진이 답한 argv·cwd·env 를 실행한다는 사실은 화면 모델이
 * 바뀌어도 그대로이고, 그것을 PTY 에 넣느냐 파이프에 넣느냐만 다르다. **무엇을 띄울지는 여전히
 * `kyu session-command` 가 답한다**(원칙 11) — 이 자리는 답을 실행할 뿐이다.
 *
 * `fun interface` 라 3 단계의 상태 홀더 시험은 람다 하나로 세울 수 있다.
 */
fun interface ChatSessionOpener {

    /**
     * 그 계획을 파이프 셋으로 띄우고, 앱이 보유할 세션을 돌려준다.
     *
     * @throws TerminalSessionFailure.ChatProcessFailedToStart 띄우지 못했을 때.
     */
    suspend fun openChatSession(plan: SessionEntryPlan): OpenedChatSession
}

/**
 * 열려 있는 챗 세션 하나 — 프로세스 하나가 대화 하나를 통째로 산다(3.1).
 *
 * `claude` 를 턴마다 새로 띄우는 것이 아니다. 그랬다면 매 턴 3 초의 부팅과 전체 컨텍스트 재전송이
 * 붙는다. [sendUserMessage] 한 번이 한 턴이고, 그 턴은 `TurnFinished` 로 끝난다.
 */
interface OpenedChatSession {

    /**
     * 이 세션이 말하는 것 전부. 마지막은 언제나 [ChatSessionEvent.SessionEnded] 다.
     *
     * **받는 쪽이 하나다.** 이 흐름은 프로세스가 뱉은 것을 순서대로 한 번씩 건네는 통로이고,
     * 둘이 나눠 받으면 전사가 두 조각으로 갈린다. 화면 여럿이 같은 대화를 보아야 하는 날에는
     * 그것을 여기가 아니라 대화를 들고 있는 상태 홀더가 푼다(5.5).
     *
     * 모으기 시작하기 전에 온 것도 잃지 않는다 — 어댑터가 프로세스가 뜨는 순간부터 읽어 쌓아
     * 둔다(ProcessChatSession).
     */
    val events: Flow<ChatSessionEvent>

    /**
     * 사용자의 말 한 줄을 보낸다. 이것이 한 턴의 시작이다.
     *
     * 보낸 말은 화면에 앱이 직접 넣지 않는다. 스트림을 한 바퀴 돌아
     * [ChatSessionEvent.UserMessageEchoed] 로 되돌아오고, 전사는 그것으로 그린다(원칙 14 · 3.14).
     *
     * 턴이 도는 중에 보내도 된다 — `claude` 가 큐를 갖고 있다(3.11). 다만 큐에 들어간 둘 이상이
     * 한 턴으로 합쳐질 수 있어, 보낸 수와 돌아오는 `TurnFinished` 수가 같지 않다.
     *
     * @throws java.io.IOException 프로세스가 이미 끝나 stdin 이 닫혀 있을 때. 그 사실은 앞서
     *   [ChatSessionEvent.SessionEnded] 로도 왔다.
     */
    suspend fun sendUserMessage(text: String)

    /**
     * 도는 턴을 끊는다. **대화도 프로세스도 죽지 않는다**(3.8).
     *
     * 실측: 긴 글을 쓰던 중에 끊고 0.5 초 뒤에 다음 말을 걸었더니 앞 맥락을 기억한 채로 답했다.
     * 이것이 없었으면 챗 화면의 중단 버튼은 세션을 끝내는 버튼이 됐을 것이다.
     *
     * `SIGINT` 을 쓰지 않는다. 같은 조건에서 시그널을 보내면 턴은 똑같이 끊기지만 **프로세스가
     * 함께 끝난다**(3.8).
     */
    suspend fun interruptCurrentTurn()

    /**
     * 이 세션을 끝낸다. 돌아오면 프로세스는 살아 있지 않다.
     *
     * 화면이 사라지는 것으로 끝나지 않는다. 아무도 읽지 않는 세션을 남겨 두면 그 프로세스는
     * 앱 어디에도 보이지 않은 채로 계속 산다.
     */
    suspend fun endSession()
}
