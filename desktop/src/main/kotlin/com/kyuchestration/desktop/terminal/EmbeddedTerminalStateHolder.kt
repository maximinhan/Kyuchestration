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
 * 어느 세션에 붙어 있는지, 그리고 그 통로를 언제 놓는지를 쥐고 있는 자리. Compose 를 모른다.
 *
 * 한 번에 하나만 붙는다. 탭은 다음 후보이고, 지금은 카드를 옮겨 다니는 것으로 충분하다 —
 * 전환이 상태를 잃지 않는 것은 tmux 가 보장하므로(세션은 붙어 있지 않아도 계속 돈다)
 * 여러 개를 동시에 띄울 이유가 아직 없다.
 */
class EmbeddedTerminalStateHolder(
    private val sessionTerminalAttacher: SessionTerminalAttacher,
    private val coroutineScope: CoroutineScope,
    // 세션을 띄우고 PTY 를 여는 동안 프로세스를 기다린다. 화면을 그리는 스레드에서 하면 창이 멎는다.
    private val sessionEntryDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val mutableState = MutableStateFlow<EmbeddedTerminalState>(EmbeddedTerminalState.NoTerminalOpen)

    val state: StateFlow<EmbeddedTerminalState> = mutableState.asStateFlow()

    /**
     * 지금 화면이 기다리고 있는 진입이 몇 번째인가.
     *
     * 진행 중인 코루틴을 취소하지 않고 이 번호로 가른다. 취소해도 이미 시작된 PTY 생성은 멈추지
     * 않으므로, 취소는 "열린 PTY 를 아무도 닫지 않는" 상태를 만든다. 번호가 어긋난 결과를 받아서
     * 닫는 편이 짧고 확실하다.
     */
    private var latestSessionEntryRequestId = 0L

    fun enterSession(workDirPath: Path, target: SessionTarget) {
        if (isAlreadyAttachedTo(target)) {
            return
        }

        detachCurrentTerminal()
        val requestId = ++latestSessionEntryRequestId
        mutableState.value = EmbeddedTerminalState.SessionEntryRunning(target)

        coroutineScope.launch {
            val attached = withContext(sessionEntryDispatcher) {
                runCatching { sessionTerminalAttacher.attachTo(workDirPath, target) }
            }

            // 기다리는 동안 사용자가 다른 카드를 눌렀거나 터미널을 닫았다.
            if (requestId != latestSessionEntryRequestId) {
                attached.getOrNull()?.close()
                return@launch
            }

            mutableState.value = attached.fold(
                onSuccess = { EmbeddedTerminalState.TerminalAttached(target, it) },
                onFailure = { EmbeddedTerminalState.SessionEntryFailed(target, it.asTerminalSessionFailure()) },
            )
        }
    }

    /**
     * 터미널을 놓는다. 세션은 그대로 둔다.
     *
     * 이 자리에 kill 이 없는 것은 빠뜨린 것이 아니다. 세션을 없애는 일은 되돌릴 수 없고, 지금
     * 보고 있는 것을 치우려는 손짓과 같은 버튼에 둘 수 없다 — 그쪽은 kyu kill 이 맡는다.
     */
    fun closeTerminal() {
        detachCurrentTerminal()
        // 아직 돌아오지 않은 진입이 있으면 그것이 들고 올 통로도 이제 주인이 없다.
        latestSessionEntryRequestId++
        mutableState.value = EmbeddedTerminalState.NoTerminalOpen
    }

    /**
     * 이미 그 세션을 보고 있는가.
     *
     * 통로가 끊겼는지까지 본다. 세션이 밖에서 죽었거나 사용자가 세션 안에서 빠져나왔으면 화면에는
     * 죽은 터미널이 남아 있는데, 그때 같은 카드를 누르는 것은 "다시 붙여 달라" 는 뜻이다.
     */
    private fun isAlreadyAttachedTo(target: SessionTarget): Boolean {
        val attached = mutableState.value as? EmbeddedTerminalState.TerminalAttached ?: return false
        return attached.target == target && attached.ttyConnector.isConnected
    }

    private fun detachCurrentTerminal() {
        (mutableState.value as? EmbeddedTerminalState.TerminalAttached)?.ttyConnector?.close()
    }
}

/**
 * 진입 실패는 전부 TerminalSessionFailure 로 온다 — 어댑터가 프로세스 경계의 예외를 거기서
 * 옮겨 두기 때문이다. 그 밖의 것이 여기까지 왔다면 이 코드의 결함이므로 삼키지 않고 다시 던진다.
 */
private fun Throwable.asTerminalSessionFailure(): TerminalSessionFailure =
    this as? TerminalSessionFailure ?: throw this
