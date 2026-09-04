package com.kyuchestration.desktop.terminal.chat

import com.kyuchestration.desktop.diagnostics.DiagnosticLog
import com.kyuchestration.desktop.diagnostics.DiagnosticLogEntry
import com.kyuchestration.desktop.platform.childProcessEnvironment
import com.kyuchestration.desktop.terminal.SessionCommandSource
import com.kyuchestration.desktop.terminal.SessionConversationChoice
import com.kyuchestration.desktop.terminal.SessionTarget
import com.kyuchestration.desktop.terminal.TerminalSessionFailure
import com.kyuchestration.desktop.terminal.planSessionEntry
import com.kyuchestration.desktop.terminal.resumeFailureSuspected
import java.io.IOException
import java.nio.file.Path
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 앱이 보유한 챗 세션들과, 그중 화면에 보이는 대화 하나를 쥐고 있는 자리. Compose 를 모른다
 * (chat-ui-design.md 5.5).
 *
 * `EmbeddedTerminalStateHolder` 가 앉은 자리에 선다. 쥐는 것이 늘었다 — 그쪽은 통로 하나였고
 * 이쪽은 **대화 전체**다. 그 대신 화면이 없어도 잃는 것이 없다: 터미널 시절에는 보이지 않는
 * 세션의 위젯을 살려 둬야 화면을 잃지 않았지만, 챗에서는 전사가 데이터로 여기 있다.
 *
 * **화면에 없는 세션의 이벤트도 계속 받는다.** 받기를 멈추면 그 세션의 파이프가 차고, 차는 순간
 * 그 안의 `claude` 가 쓰기에서 멎는다 — 이 앱이 PTY 시절에 이미 한 번 만난 함정이다
 * (`EmbeddedTerminalStateHolder` 의 주석 · ProcessChatSession).
 *
 * @param sessionCommandSource 무엇을 띄울지 답하는 자리. **챗 모드로 묻는 것**은 이 자리를
 *   조립하는 쪽이 정한다(KyuCliSessionCommandSource 의 sessionMode).
 * @param chatSessionOpener 그 답을 파이프 셋에 물려 띄우는 자리.
 * @param baseEnvironment 자식에게 물려줄 바탕 환경. PTY 어댑터와 같은 것을 쓴다 — 엔진의 답에
 *   실행 파일 경로가 아니라 `claude` 라는 이름이 들어 있어서, 그것을 찾는 PATH 가 이 값이다.
 */
class ChatSessionStateHolder(
    private val sessionCommandSource: SessionCommandSource,
    private val chatSessionOpener: ChatSessionOpener,
    private val coroutineScope: CoroutineScope,
    private val diagnosticLog: DiagnosticLog = DiagnosticLog.Discarding,
    private val baseEnvironment: Map<String, String> = childProcessEnvironment(),
    /** 엔진에게 묻고 프로세스를 띄우고 stdin 에 쓰는 동안 화면이 멎지 않도록 나가는 자리. */
    private val sessionEntryDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val mutableState = MutableStateFlow<ChatScreenState>(ChatScreenState.NoChatOpen)

    /** 대화 자리가 지금 보여줄 것. */
    val state: StateFlow<ChatScreenState> = mutableState.asStateFlow()

    private val mutableHeldSessions = MutableStateFlow<List<HeldChatSession>>(emptyList())

    /**
     * 지금 앱이 보유 중인 챗 세션 전부. 화면에 보이지 않는 것도 여기 있다.
     *
     * 화면에 보이는 것과 따로 내보낸다. 카드를 옮기는 것과 세션이 늘고 주는 것은 다른 사건이라,
     * 하나로 합치면 카드를 오갈 때마다 목록 전체가 다시 그려진다.
     */
    val heldSessions: StateFlow<List<HeldChatSession>> = mutableHeldSessions.asStateFlow()

    /**
     * 세션마다의 대화. 화면에 보이지 않는 것도 여기 쌓인다.
     *
     * 흐름이 아니라 보통의 맵인 것이 뜻이다 — 이 값을 구독하는 화면이 없다. 화면이 보는 것은
     * [state] 하나이고, 이 맵은 그 뒤에서 "돌아왔을 때 두고 온 전사가 그대로인" 근거일 뿐이다.
     * 고치는 자리가 [updateConversation] 하나뿐이라 흐름 둘이 어긋날 자리도 없다.
     */
    private val conversations = mutableMapOf<SessionTarget, ChatConversation>()

    /**
     * 지금 화면이 기다리고 있는 진입이 몇 번째인가.
     *
     * 진행 중인 코루틴을 취소하지 않고 이 번호로 가른다 — 취소해도 이미 시작된 프로세스는 멈추지
     * 않으므로, 취소는 "아무도 읽지 않는 `claude` 가 하나 남는" 상태를 만든다.
     */
    private var latestSessionEntryRequestId = 0L

    /**
     * 그 세션의 챗을 연다. 이미 보유 중이면 다시 띄우지 않고 화면만 그쪽으로 옮긴다.
     *
     * @param conversationChoice 이 세션이 쓸 대화를 어느 쪽으로 정할지. 여기를 지나 엔진에게 닿고,
     *   엔진이 그 뜻대로 명령을 조립한다(설계 원칙 11).
     */
    fun enterSession(workDirPath: Path, target: SessionTarget, conversationChoice: SessionConversationChoice) {
        latestSessionEntryRequestId++

        // 보유 중인 대화로 돌아가는 길이다. 다시 띄우면 돌고 있던 턴이 끊기고 전사를 잃는다.
        val alreadyHeld = mutableHeldSessions.value.firstOrNull { it.target == target }
        if (alreadyHeld != null) {
            conversations[target]?.let { mutableState.value = ChatScreenState.ChatOnScreen(target, it) }
            return
        }

        // 앞서 스스로 끝난 세션의 전사가 남아 있는 자리다. 새로 띄우는 것이므로 그 전사도 함께
        // 놓는다 — 남겨 두면 새 대화의 위에 지난 대화가 이어 붙는다.
        conversations.remove(target)

        val requestId = latestSessionEntryRequestId
        mutableState.value = ChatScreenState.ChatSessionEntryRunning(target)

        coroutineScope.launch {
            val opened = withContext(sessionEntryDispatcher) {
                runCatching { openChatSession(workDirPath, target, conversationChoice) }
            }

            // 기다리는 동안 사용자가 다른 세션을 골랐거나 챗을 닫았다.
            if (requestId != latestSessionEntryRequestId) {
                opened.getOrNull()?.let { endSessionProcess(it) }
                return@launch
            }

            opened.fold(
                onSuccess = ::holdAndShow,
                onFailure = { failure -> recordAndShowEntryFailure(target, failure.asTerminalSessionFailure()) },
            )
        }
    }

    /**
     * 화면에 떠 있는 대화에 사용자의 말 한 줄을 보낸다. 이것이 한 턴의 시작이다.
     *
     * **보낸 말을 전사에 직접 넣지 않는다.** 스트림을 한 바퀴 돌아 `UserMessageEchoed` 로
     * 돌아오고 전사는 그것으로 그린다(3.14) — 앱이 직접 넣으면 큐잉이나 중단이 끼는 순간
     * 순서가 어긋난다.
     */
    fun sendUserMessage(text: String) {
        val target = mutableState.value.target ?: return
        val held = mutableHeldSessions.value.firstOrNull { it.target == target } ?: return

        // 잠금은 보내는 즉시다. 되돌아온 말을 기다려 잠그면 그 사이에 한 번 더 보낼 수 있고,
        // 그 둘은 한 턴으로 합쳐져(3.11) 답이 하나만 온다.
        updateConversation(target) { it.copy(turnState = TurnState.Running) }

        coroutineScope.launch {
            try {
                withContext(sessionEntryDispatcher) { held.session.sendUserMessage(text) }
            } catch (failure: IOException) {
                // 프로세스가 이미 끝나 stdin 이 닫힌 자리다. 그 사실은 곧 SessionEnded 로도 오지만,
                // 잠긴 입력창을 그때까지 두면 사용자는 자기 말이 갔는지 모른 채 기다린다.
                updateConversation(target) {
                    it.copy(
                        turnState = TurnState.Idle,
                        entries = it.entries + ChatEntry.EngineNotice(
                            "보내지 못했습니다 — 이 세션은 끝났습니다 (${failure.message})",
                        ),
                    )
                }
            }
        }
    }

    /**
     * 보고 있는 챗을 끝낸다. 보유한 다른 세션은 그대로 둔다.
     *
     * 프로세스가 실제로 끝난 뒤에 돌아온다. 부르는 쪽이 그것을 기다릴 수 있어야 같은 대화를
     * 곧바로 다른 화면으로 열 수 있다 — 대화 하나를 두 프로세스가 동시에 열면 엔진이 거절한다
     * (5.6 의 `--session-id already in use`).
     */
    suspend fun endOnScreenSession() {
        val target = mutableState.value.target ?: return
        latestSessionEntryRequestId++
        mutableState.value = ChatScreenState.NoChatOpen
        conversations.remove(target)

        val held = mutableHeldSessions.value.firstOrNull { it.target == target } ?: return
        // 목록에서 먼저 뺀다. 끝나는 것을 지켜보던 자리가 이 목록으로 "우리가 끝낸 것인가" 를 가른다.
        mutableHeldSessions.value = mutableHeldSessions.value - held
        endSessionProcess(held)
    }

    /**
     * 보유한 챗을 전부 끝낸다 — 워크디렉토리를 바꾸거나 앱을 끌 때다.
     *
     * 앱이 그냥 죽으면 파이프에 물린 `claude` 는 부모가 사라진 것을 모른 채 계속 산다. 그러면
     * 어디에도 보이지 않는 프로세스가 남고 찾을 수 있는 자리가 `ps` 뿐이다(설계 8 절 1 번).
     *
     * 한꺼번에 끝낸다. 하나씩 기다리면 세션 수만큼의 종료 대기가 줄줄이 붙어 창이 늦게 닫힌다.
     */
    suspend fun endAllSessions() {
        latestSessionEntryRequestId++
        mutableState.value = ChatScreenState.NoChatOpen
        conversations.clear()

        val endingSessions = mutableHeldSessions.value
        mutableHeldSessions.value = emptyList()
        coroutineScope {
            endingSessions.map { async { endSessionProcess(it) } }.awaitAll()
        }
    }

    /**
     * 엔진에게 묻고, 그 답을 파이프 셋에 물려 띄운다.
     *
     * PTY 어댑터가 하는 것과 같은 두 걸음이다(PtySessionTerminalOpener) — 다른 것은 무엇에
     * 물리느냐뿐이라 앞 걸음의 답(argv·cwd·env)도 그것을 계획으로 옮기는 자리도 같다.
     */
    private suspend fun openChatSession(
        workDirPath: Path,
        target: SessionTarget,
        conversationChoice: SessionConversationChoice,
    ): HeldChatSession {
        val answer = sessionCommandSource.sessionCommandFor(workDirPath, target, conversationChoice)

        val plan = planSessionEntry(
            sessionCommandAnswer = answer,
            baseEnvironment = baseEnvironment,
            workDirPath = workDirPath,
            target = target,
        )

        return HeldChatSession(
            target = target,
            session = chatSessionOpener.openChatSession(plan),
            resumedConversationId = answer.resumedConversationId,
        )
    }

    private fun holdAndShow(held: HeldChatSession) {
        val conversation = ChatConversation(
            target = held.target,
            resumedConversationId = held.resumedConversationId,
        )
        conversations[held.target] = conversation
        mutableHeldSessions.value = mutableHeldSessions.value + held
        mutableState.value = ChatScreenState.ChatOnScreen(held.target, conversation)

        // 이어간 대화의 ID 자체는 남기지 않는다. 뒤에 오는 종료를 읽는 근거는 "이어갔는가" 이고,
        // ID 는 그 판단에 쓰이지 않으면서 사용자의 대화를 가리키는 값이다.
        diagnosticLog.record(
            DiagnosticLogEntry.SessionStarted(
                targetLabel = held.target.label,
                resumingConversation = held.resumedConversationId != null,
            ),
        )

        // 화면이 이 세션을 보고 있든 아니든 받는다. 멈추면 그 세션의 파이프가 차고, 차는 순간
        // 그 안의 claude 가 멎는다.
        coroutineScope.launch {
            held.session.events.collect { event -> receive(held.target, event) }
        }
    }

    private fun receive(target: SessionTarget, event: ChatSessionEvent) {
        updateConversation(target) { it.after(event) }

        if (event is ChatSessionEvent.SessionEnded) {
            markSessionEnded(target, event.exitCode)
        }
    }

    /**
     * 세션이 스스로 끝났다 — 사용자가 대화 안에서 끝냈거나, 이어갈 대화가 없어 `claude` 가
     * 곧바로 죽었거나.
     *
     * 보유 목록에서 빼되 화면에서는 내리지 않는다. 전사가 남아 있어야 사용자가 왜 끝났는지
     * (엔진이 남긴 마지막 줄까지) 읽을 수 있고, 그 사실은 이미 대화 안에 있다.
     */
    private fun markSessionEnded(target: SessionTarget, exitCode: Int) {
        val held = mutableHeldSessions.value.firstOrNull { it.target == target }
            // 우리가 끝낸 세션이다. 끝내는 자리에서 이미 목록에서 뺐다.
            ?: return
        mutableHeldSessions.value = mutableHeldSessions.value - held

        // 잘 끝난 세션은 남기지 않는다 — 사용자가 시킨 일이라 진단할 것이 없다. 화면과 달리 이
        // 자리는 보고 있지 않은 세션의 죽음도 남긴다.
        if (exitCode != 0) {
            diagnosticLog.record(
                DiagnosticLogEntry.SessionEndedUnexpectedly(
                    targetLabel = target.label,
                    exitCode = exitCode,
                    resumeFailureSuspected = resumeFailureSuspected(held.resumedConversationId, exitCode),
                ),
            )
        }
    }

    private fun recordAndShowEntryFailure(target: SessionTarget, failure: TerminalSessionFailure) {
        diagnosticLog.record(
            DiagnosticLogEntry.SessionEntryFailed(
                targetLabel = target.label,
                failureMessage = failure.message.orEmpty(),
                guidance = failure.guidance,
            ),
        )
        mutableState.value = ChatScreenState.ChatSessionEntryFailed(target, failure)
    }

    /**
     * 그 세션의 대화를 고친다. 화면이 그 세션을 보고 있으면 화면도 함께 바뀐다.
     *
     * 두 자리를 한 함수에서만 고치는 것이 이 홀더의 규율이다. 나눠 고치면 화면이 들고 있는
     * 대화와 맵에 있는 대화가 갈리고, 그 어긋남은 세션을 옮겼다 돌아왔을 때 드러난다.
     */
    private fun updateConversation(target: SessionTarget, change: (ChatConversation) -> ChatConversation) {
        val conversation = conversations[target] ?: return
        val changed = change(conversation)
        conversations[target] = changed

        val onScreen = mutableState.value
        if (onScreen is ChatScreenState.ChatOnScreen && onScreen.target == target) {
            mutableState.value = ChatScreenState.ChatOnScreen(target, changed)
        }
    }

    /** 끝내기는 프로세스를 기다리는 일이라 화면을 그리는 스레드에서 하지 않는다. */
    private suspend fun endSessionProcess(held: HeldChatSession) =
        withContext(sessionEntryDispatcher) { held.session.endSession() }
}

/**
 * 진입 실패는 전부 TerminalSessionFailure 로 온다 — 어댑터가 프로세스 경계의 예외를 거기서
 * 옮겨 두기 때문이다. 그 밖의 것이 여기까지 왔다면 이 코드의 결함이므로 삼키지 않고 다시 던진다.
 */
private fun Throwable.asTerminalSessionFailure(): TerminalSessionFailure =
    this as? TerminalSessionFailure ?: throw this
