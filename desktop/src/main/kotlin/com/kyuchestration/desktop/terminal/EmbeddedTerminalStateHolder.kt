package com.kyuchestration.desktop.terminal

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
 * 어느 세션을 보고 있는지, 그리고 그 세션을 언제 끝내는지를 쥐고 있는 자리. Compose 를 모른다.
 *
 * 한 번에 하나만 보유한다. **카드를 옮기면 앞 세션이 끝난다** — 예전에 여러 개를 띄울 이유가
 * 없었던 근거는 "전환이 상태를 잃지 않는 것을 tmux 가 보장한다" 였는데, 앱이 세션을 직접 보유하게
 * 되면서 그 보장이 통째로 사라졌다(설계 문서 8 절 3 번).
 */
class EmbeddedTerminalStateHolder(
    private val sessionTerminalOpener: SessionTerminalOpener,
    private val coroutineScope: CoroutineScope,
    // 무엇을 띄울지 묻고 PTY 를 여는 동안 프로세스를 기다린다. 화면을 그리는 스레드에서 하면 창이 멎는다.
    private val sessionEntryDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val mutableState = MutableStateFlow<EmbeddedTerminalState>(EmbeddedTerminalState.NoTerminalOpen)

    val state: StateFlow<EmbeddedTerminalState> = mutableState.asStateFlow()

    /**
     * 지금 화면이 기다리고 있는 진입이 몇 번째인가.
     *
     * 진행 중인 코루틴을 취소하지 않고 이 번호로 가른다. 취소해도 이미 시작된 PTY 생성은 멈추지
     * 않으므로, 취소는 "열린 PTY 를 아무도 닫지 않는" 상태를 만든다. 번호가 어긋난 결과를 받아서
     * 닫는 편이 짧고 확실하다 — 그리고 이제 그 닫기가 곧 방금 띄운 세션을 끝내는 일이다.
     */
    private var latestSessionEntryRequestId = 0L

    fun enterSession(workDirPath: Path, target: SessionTarget) {
        if (isAlreadyShowing(target)) {
            return
        }

        endCurrentSession()
        val requestId = ++latestSessionEntryRequestId
        mutableState.value = EmbeddedTerminalState.SessionEntryRunning(target)

        coroutineScope.launch {
            val opened = withContext(sessionEntryDispatcher) {
                runCatching { sessionTerminalOpener.openSessionIn(workDirPath, target) }
            }

            // 기다리는 동안 사용자가 다른 카드를 눌렀거나 터미널을 닫았다.
            if (requestId != latestSessionEntryRequestId) {
                opened.getOrNull()?.close()
                return@launch
            }

            mutableState.value = opened.fold(
                onSuccess = { EmbeddedTerminalState.SessionOnScreen(target, it) },
                onFailure = { EmbeddedTerminalState.SessionEntryFailed(target, it.asTerminalSessionFailure()) },
            )
        }
    }

    /**
     * 보고 있는 세션을 끝낸다.
     *
     * **"이 자리에 kill 이 없는 것은 빠뜨린 것이 아니다" 라던 주석이 반대가 됐다.** 앱이 세션을
     * 보유하므로 통로를 닫는 것이 곧 PTY 안의 `claude` 를 끝내는 일이고(SessionTtyConnector),
     * 앱에는 detach 라는 개념 자체가 없다. 사용자가 그 사실을 버튼을 누르기 전에 알아야 하므로
     * 터미널 머리말이 먼저 말한다.
     */
    fun endSession() {
        endCurrentSession()
        // 아직 돌아오지 않은 진입이 있으면 그것이 들고 올 세션도 이제 주인이 없다.
        latestSessionEntryRequestId++
        mutableState.value = EmbeddedTerminalState.NoTerminalOpen
    }

    /**
     * 이미 그 세션을 보고 있는가.
     *
     * 통로가 끊겼는지까지 본다. 세션 안에서 사용자가 빠져나왔으면(`/exit`) 화면에는 죽은 터미널이
     * 남아 있는데, 그때 같은 카드를 누르는 것은 "다시 열어 달라" 는 뜻이다. 그러면 새 세션이
     * 열리고, 대화는 엔진이 `--resume` 으로 이어 준다.
     */
    private fun isAlreadyShowing(target: SessionTarget): Boolean {
        val onScreen = mutableState.value as? EmbeddedTerminalState.SessionOnScreen ?: return false
        return onScreen.target == target && onScreen.ttyConnector.isConnected
    }

    private fun endCurrentSession() {
        (mutableState.value as? EmbeddedTerminalState.SessionOnScreen)?.ttyConnector?.close()
    }
}

/**
 * 진입 실패는 전부 TerminalSessionFailure 로 온다 — 어댑터가 프로세스 경계의 예외를 거기서
 * 옮겨 두기 때문이다. 그 밖의 것이 여기까지 왔다면 이 코드의 결함이므로 삼키지 않고 다시 던진다.
 */
private fun Throwable.asTerminalSessionFailure(): TerminalSessionFailure =
    this as? TerminalSessionFailure ?: throw this
