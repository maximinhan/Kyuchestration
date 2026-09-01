package com.kyuchestration.desktop.terminal

import com.kyuchestration.desktop.diagnostics.DiagnosticLog
import com.kyuchestration.desktop.diagnostics.DiagnosticLogEntry
import java.nio.file.Path
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 앱이 보유한 세션들과, 그중 화면에 보이는 하나를 쥐고 있는 자리. Compose 를 모른다.
 *
 * **카드를 옮겨도 앞 세션은 계속 돈다.** 예전에 한 번에 하나만 붙어도 됐던 근거는 "전환이 상태를
 * 잃지 않는 것을 tmux 가 보장한다" 였는데, 앱이 세션을 직접 보유하면서 그 보장이 통째로
 * 사라졌다(설계 문서 8 절 3 번). 옮길 때마다 앞 세션을 끝내면 사용자는 카드를 한 번 잘못 눌러
 * 하던 작업을 잃는다. 그래서 **PTY 는 살려 두고 화면만 감춘다** — 대가는 `claude` 가 세션 수만큼
 * 사는 것이고, 그것이 앱 세션이 도는 동안 카드에 그대로 보인다.
 *
 * 화면에 보이는 것은 여전히 하나다. 탭은 다음 후보이고, 지금은 카드를 오가는 것으로 충분하다 —
 * 이제 오가도 잃는 것이 없으므로.
 */
class EmbeddedTerminalStateHolder(
    private val sessionTerminalOpener: SessionTerminalOpener,
    private val coroutineScope: CoroutineScope,
    /**
     * 세션의 생사를 남길 자리.
     *
     * 화면이 말하지 못하는 것을 이쪽이 말한다. 화면은 보고 있는 세션 하나만 고쳐 그리므로,
     * 뒤에서 죽은 세션은 사용자가 그 카드로 돌아갈 때까지 아무 데도 드러나지 않는다.
     */
    private val diagnosticLog: DiagnosticLog = DiagnosticLog.Discarding,
    // 무엇을 띄울지 묻고 PTY 를 여는 동안 프로세스를 기다린다. 화면을 그리는 스레드에서 하면 창이 멎는다.
    private val sessionEntryDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val mutableState = MutableStateFlow<EmbeddedTerminalState>(EmbeddedTerminalState.NoTerminalOpen)

    /** 터미널 자리가 지금 보여줄 것. */
    val state: StateFlow<EmbeddedTerminalState> = mutableState.asStateFlow()

    private val mutableHeldSessions = MutableStateFlow<List<HeldSession>>(emptyList())

    /**
     * 지금 앱이 보유 중인 세션 전부. 화면에 보이지 않는 것도 여기 있다.
     *
     * 상태를 둘로 나눠 내보낸다. 터미널 자리가 무엇을 그릴지와 대시보드가 어느 카드에 앱 세션을
     * 표시할지는 다른 물음이고, 하나로 합치면 카드를 옮길 때마다 대시보드 전체가 다시 그려진다.
     */
    val heldSessions: StateFlow<List<HeldSession>> = mutableHeldSessions.asStateFlow()

    /**
     * 지금 화면이 기다리고 있는 진입이 몇 번째인가.
     *
     * 진행 중인 코루틴을 취소하지 않고 이 번호로 가른다. 취소해도 이미 시작된 PTY 생성은 멈추지
     * 않으므로, 취소는 "열린 PTY 를 아무도 닫지 않는" 상태를 만든다.
     *
     * 번호가 어긋난 세션은 보유하지 않고 곧바로 끝낸다. 보유해 두면 아무 위젯도 그 통로를 읽지
     * 않는 세션이 하나 생기고, PTY 버퍼가 차는 순간 그 안의 `claude` 가 쓰기에서 멎는다.
     */
    private var latestSessionEntryRequestId = 0L

    /**
     * @param conversationChoice 이 세션이 쓸 대화를 어느 쪽으로 정할지. 카드를 그냥 누르면
     *   이어가기이고, 새로 시작은 사용자가 일부러 고른 자리에서만 온다.
     */
    fun enterSession(workDirPath: Path, target: SessionTarget, conversationChoice: SessionConversationChoice) {
        latestSessionEntryRequestId++

        // 이미 보유 중이면 다시 띄우지 않는다. 화면만 그쪽으로 옮기면 그 세션의 위젯이 그동안
        // 쥐고 있던 화면이 그대로 돌아온다.
        //
        // 새로 시작을 골랐어도 같다. 화면은 보유 중인 대상에 그 길을 내지 않으므로(카드의
        // 새 대화 버튼은 앱 세션이 없을 때만 뜬다) 여기 닿는 것은 카드를 다시 누른 사람뿐이고,
        // 그 사람이 잃어서는 안 되는 것이 돌고 있는 세션이다.
        val alreadyHeld = mutableHeldSessions.value.firstOrNull { it.target == target }
        if (alreadyHeld != null) {
            mutableState.value = EmbeddedTerminalState.SessionOnScreen(target, alreadyHeld.ttyConnector)
            return
        }

        val requestId = latestSessionEntryRequestId
        mutableState.value = EmbeddedTerminalState.SessionEntryRunning(target)

        coroutineScope.launch {
            val opened = withContext(sessionEntryDispatcher) {
                runCatching { sessionTerminalOpener.openSessionIn(workDirPath, target, conversationChoice) }
            }

            // 기다리는 동안 사용자가 다른 카드를 눌렀거나 터미널을 닫았다.
            if (requestId != latestSessionEntryRequestId) {
                opened.getOrNull()?.ttyConnector?.close()
                return@launch
            }

            opened.fold(
                onSuccess = { holdAndShow(target, it) },
                onFailure = {
                    val failure = it.asTerminalSessionFailure()
                    diagnosticLog.record(
                        DiagnosticLogEntry.SessionEntryFailed(
                            targetLabel = target.label,
                            failureMessage = failure.message.orEmpty(),
                            guidance = failure.guidance,
                        ),
                    )
                    mutableState.value = EmbeddedTerminalState.SessionEntryFailed(target, failure)
                },
            )
        }
    }

    /**
     * 보고 있는 세션을 끝낸다. 보유한 다른 세션은 그대로 둔다.
     *
     * **"이 자리에 kill 이 없는 것은 빠뜨린 것이 아니다" 라던 주석이 반대가 됐다.** 앱이 세션을
     * 보유하므로 통로를 닫는 것이 곧 PTY 안의 `claude` 를 끝내는 일이고(SessionTtyConnector),
     * 앱에는 detach 라는 개념 자체가 없다. 되돌릴 수 없는 일이라 사용자가 누르기 전에 알아야
     * 하고, 그래서 터미널 머리말이 먼저 말한다.
     */
    fun endSession() {
        val onScreenTarget = mutableState.value.target
        latestSessionEntryRequestId++
        mutableState.value = EmbeddedTerminalState.NoTerminalOpen
        onScreenTarget?.let(::endHeldSession)
    }

    /**
     * 보유한 세션을 전부 끝낸다 — 워크디렉토리를 바꾸거나 앱을 끌 때다.
     *
     * 앱이 그냥 죽어도 PTY 마스터가 닫히며 자식에게 SIGHUP 이 가지만, 그것을 무시하는 프로그램은
     * 살아남는다. 그러면 어디에도 보이지 않는 프로세스가 남고, 찾을 수 있는 자리가 `ps` 뿐이다
     * (설계 문서 8 절 1 번). 그래서 정리를 프로세스 종료에 맡기지 않는다.
     */
    fun endAllSessions() {
        latestSessionEntryRequestId++
        mutableState.value = EmbeddedTerminalState.NoTerminalOpen

        val endingSessions = mutableHeldSessions.value
        // 목록에서 먼저 뺀다. 끝나는 것을 지켜보던 자리가 이 목록으로 "우리가 끝낸 것인가" 를 가른다.
        mutableHeldSessions.value = emptyList()
        endingSessions.forEach { it.ttyConnector.close() }
    }

    private fun holdAndShow(target: SessionTarget, opened: OpenedSessionTerminal) {
        val heldSession = HeldSession(target, opened.ttyConnector, opened.resumedConversationId)
        mutableHeldSessions.value = mutableHeldSessions.value + heldSession
        mutableState.value = EmbeddedTerminalState.SessionOnScreen(target, opened.ttyConnector)

        // 이어간 대화의 ID 자체는 남기지 않는다. 뒤에 오는 종료를 읽는 근거는 "이어갔는가" 이고,
        // ID 는 그 판단에 쓰이지 않으면서 사용자의 대화를 가리키는 값이다.
        diagnosticLog.record(
            DiagnosticLogEntry.SessionStarted(
                targetLabel = target.label,
                resumingConversation = opened.resumedConversationId != null,
            ),
        )

        opened.sessionExit.whenComplete { exitCode, _ ->
            // 완료를 알리는 스레드는 이 홀더의 것이 아니다. 상태를 만지는 일은 전부 이 홀더의
            // 스코프 안에서 한다 — 두 스레드가 보유 목록을 동시에 고치면 세션 하나가 조용히 샌다.
            coroutineScope.launch { markSessionEnded(heldSession, exitCode) }
        }
    }

    /**
     * 세션이 스스로 끝났다 — 사용자가 `/exit` 했거나, 이어갈 대화가 없어 `claude` 가 곧바로 죽었거나.
     *
     * 보유 목록에서 빼되 화면에서는 내리지 않는다. 마지막 화면이 남아 있어야 사용자가 왜 끝났는지
     * (전사가 없다는 `claude` 의 한 줄까지) 읽을 수 있고, 그 자리에서 카드를 다시 누르면 새 세션이
     * 열린다.
     *
     * 이어가던 대화를 함께 넘긴다. 그 둘(이어가기였는가 · 어떤 코드로 끝났는가)이 만나야 화면이
     * "이어갈 대화를 찾지 못한 것 같다" 를 말할 수 있다(설계 문서 5.5.4).
     */
    private fun markSessionEnded(heldSession: HeldSession, exitCode: Int?) {
        if (heldSession !in mutableHeldSessions.value) {
            // 우리가 끝낸 세션이다. 끝내는 자리에서 이미 목록에서 뺐다.
            return
        }
        mutableHeldSessions.value = mutableHeldSessions.value - heldSession

        // 잘 끝난 세션은 남기지 않는다 — `/exit` 는 사용자가 시킨 일이라 진단할 것이 없다.
        // 화면과 달리 이 자리는 보고 있지 않은 세션의 죽음도 남긴다. 뒤에서 죽은 세션은 사용자가
        // 그 카드로 돌아갈 때까지 아무 데도 드러나지 않아, 기록이 없으면 영영 보이지 않는다.
        if (exitCode != 0) {
            diagnosticLog.record(
                DiagnosticLogEntry.SessionEndedUnexpectedly(
                    targetLabel = heldSession.target.label,
                    exitCode = exitCode,
                    resumeFailureSuspected = resumeFailureSuspected(heldSession.resumedConversationId, exitCode),
                ),
            )
        }

        val onScreen = mutableState.value
        if (onScreen is EmbeddedTerminalState.SessionOnScreen && onScreen.ttyConnector === heldSession.ttyConnector) {
            mutableState.value = EmbeddedTerminalState.SessionEndedOnScreen(
                target = heldSession.target,
                ttyConnector = heldSession.ttyConnector,
                exitCode = exitCode,
                resumedConversationId = heldSession.resumedConversationId,
            )
        }
    }

    private fun endHeldSession(target: SessionTarget) {
        val heldSession = mutableHeldSessions.value.firstOrNull { it.target == target } ?: return
        mutableHeldSessions.value = mutableHeldSessions.value - heldSession
        heldSession.ttyConnector.close()
    }
}

/**
 * 진입 실패는 전부 TerminalSessionFailure 로 온다 — 어댑터가 프로세스 경계의 예외를 거기서
 * 옮겨 두기 때문이다. 그 밖의 것이 여기까지 왔다면 이 코드의 결함이므로 삼키지 않고 다시 던진다.
 */
private fun Throwable.asTerminalSessionFailure(): TerminalSessionFailure =
    this as? TerminalSessionFailure ?: throw this
