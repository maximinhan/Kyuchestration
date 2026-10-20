package com.kyuchestration.desktop.terminal.chat

import com.kyuchestration.desktop.diagnostics.DiagnosticLog
import com.kyuchestration.desktop.diagnostics.DiagnosticLogEntry
import com.kyuchestration.desktop.platform.childProcessEnvironment
import com.kyuchestration.desktop.terminal.AssignedChildSession
import com.kyuchestration.desktop.terminal.ChildSessionCommandAnswer
import com.kyuchestration.desktop.terminal.ChildSessionCommandRequest
import com.kyuchestration.desktop.terminal.ChildSessionCommandSource
import com.kyuchestration.desktop.terminal.ChildSessionGate
import com.kyuchestration.desktop.terminal.PersonOpenedSessionTarget
import com.kyuchestration.desktop.terminal.RecordedChildSessionIds
import com.kyuchestration.desktop.terminal.SessionCommandSource
import com.kyuchestration.desktop.terminal.SessionConversationChoice
import com.kyuchestration.desktop.terminal.SessionTarget
import com.kyuchestration.desktop.terminal.TerminalSessionFailure
import com.kyuchestration.desktop.terminal.planSessionEntry
import com.kyuchestration.desktop.terminal.resumeFailureSuspected
import java.io.IOException
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * @param claudeAuthToken 세션을 띄울 때마다 물어보는 자리. 앱이 맡아 둔 토큰이 없으면 null 이다.
 *   값이 아니라 함수인 이유는 시점이다 — 이 홀더는 앱이 뜰 때 한 번 조립되는데, 토큰은 그 뒤에
 *   사용자가 인증 화면을 지나면서 생길 수 있다.
 * @param baseEnvironment 자식에게 물려줄 바탕 환경. PTY 어댑터와 같은 것을 쓴다 — 엔진의 답에
 *   실행 파일 경로가 아니라 `claude` 라는 이름이 들어 있어서, 그것을 찾는 PATH 가 이 값이다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatSessionStateHolder(
    private val sessionCommandSource: SessionCommandSource,
    private val chatSessionOpener: ChatSessionOpener,
    /** 하위 세션이 무엇을 띄울지 답하는 자리(`--child`). 관문의 물음도 여기서 온다(6.9). */
    private val childSessionCommandSource: ChildSessionCommandSource,
    /** 기록에 이미 적힌 하위 이름들. 다음 이름을 그 뒤에서 센다(nextChildSessionId). */
    private val recordedChildSessionIds: RecordedChildSessionIds,
    private val claudeAuthToken: () -> String?,
    private val coroutineScope: CoroutineScope,
    private val diagnosticLog: DiagnosticLog = DiagnosticLog.Discarding,
    private val baseEnvironment: Map<String, String> = childProcessEnvironment(),
    /**
     * 세션마다의 승인 통로를 여는 자리(5.4).
     *
     * 기본값이 실제로 도는 것이다 — 런타임 디렉토리에 세션마다 유닉스 소켓 하나. **그 자리에
     * 워크디렉토리가 섞이지 않는 것이 요점이다**: 사용자가 지은 긴 이름 하나가 107 바이트 한계를
     * 넘겨 승인 기능 전체를 조용히 죽이는 것이 실측이 만난 실패다(3.7).
     */
    private val appSocketChannelOpener: AppSocketChannelOpener = AppSocketChannelOpener {
        AppSocket.openAt(appSocketDirectory().resolve(newAppSocketFileName()))
    },
    /** 엔진에게 묻고 프로세스를 띄우고 끝내는 동안 화면이 멎지 않도록 나가는 자리. */
    private val sessionEntryDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /**
     * stdin 에 쓰는 일이 나가는 자리. **한 번에 하나씩만 돈다.**
     *
     * 입력창이 열려 있는 지금은 쓰기 둘이 겹칠 수 있다 — 빠르게 두 번 보내거나, 보내고 곧바로
     * 끊는 자리다. 화면 스레드에서 순서대로 띄워도 그 안에서 [Dispatchers.IO] 로 나가는 순간
     * 실행 순서는 정해지지 않고, 뒤집히면 두 말이 반대 순서로 큐에 들어가거나 중단 요청이 아직
     * 나가지 않은 말보다 먼저 닿는다. `ProcessChatSession` 의 자물쇠는 줄이 섞이는 것만 막지
     * **순서는 지키지 않는다.**
     *
     * 세션 전부가 이 한 자리를 함께 쓴다. 쓰기는 파이프에 한 줄을 흘려보내는 짧은 일이라 서로를
     * 기다리게 하지 않는다 — 세션마다 자리를 두는 것은 그 기다림이 실제로 문제가 될 때 정한다.
     */
    private val standardInputDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
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
     *
     * **한 번에 한 코루틴만 이것을 만진다는 전제 위에 있다.** 이 홀더의 상태를 고치는 일은 전부
     * [coroutineScope] 안에서 일어나고, 앱이 넘기는 그 스코프는 화면을 그리는 단일 스레드다
     * (Main.kt 의 rememberCoroutineScope). 여러 스레드가 도는 스코프를 넘기면 세션 하나의 전사가
     * 조용히 샌다 — 터미널 홀더가 보유 목록에 대해 두는 전제와 같다.
     */
    private val conversations = mutableMapOf<SessionTarget, ChatConversation>()

    /**
     * 아직 답하지 않은 승인 물음들 — 화면의 버튼과 소켓 너머의 자식을 잇는 자리.
     *
     * 전사에 넣지 않는다. 전사는 화면에 그릴 값이고 이것은 기다리고 있는 연결이라, 한 자리에
     * 담으면 대화를 복사할 때마다 그 연결이 함께 복사된다.
     *
     * 세션과 도구 호출을 함께 열쇠로 쓴다. `toolUseId` 만으로도 지금은 갈리지만, 그 값이 비어
     * 오는 물음이 있으면(엔진이 채우지 못한 경우) 두 세션의 물음이 한 자리를 두고 겹친다.
     */
    private val askedPermissions = mutableMapOf<AskedPermissionKey, AskedPermission>()

    /**
     * 세션마다 "이 세션에서 계속 허용" 을 받은 도구 이름들(설계 10 절 열린 질문 4 의 v1 결정).
     *
     * **메모리에만 산다.** 워크디렉토리의 `.coord/` 에 규칙을 적는 길이 유력했지만(설계 10 절 4 의
     * (나)), 그것을 열려면 규칙의 모양 — 도구 이름까지인가, 인자의 무늬까지인가, 누가 그것을
     * 검증하는가 — 을 먼저 정해야 한다. 세션 범위는 그 물음 없이도 성립하고, 사용자가 앱을 끄면
     * 함께 사라져 잊힌 규칙이 남지 않는다. 적어 두는 자리는 사용자가 그것을 실제로 아쉬워할 때 연다.
     */
    private val toolsAllowedForSession = mutableMapOf<SessionTarget, MutableSet<String>>()

    /**
     * 지금 화면이 기다리고 있는 진입이 몇 번째인가.
     *
     * 진행 중인 코루틴을 취소하지 않고 이 번호로 가른다 — 취소해도 이미 시작된 프로세스는 멈추지
     * 않으므로, 취소는 "아무도 읽지 않는 `claude` 가 하나 남는" 상태를 만든다.
     */
    private var latestSessionEntryRequestId = 0L

    private val mutableSessionTree = MutableStateFlow(SessionTree())

    /**
     * 메인 아래 하위 세션들(session-tree-design.md 6.11). 패널이 이것으로 하위 줄을 그리고, `list_sessions` 가
     * 이것을 그대로 답한다 — 화면과 상위가 같은 사실을 본다(원칙 16).
     */
    val sessionTree: StateFlow<SessionTree> = mutableSessionTree.asStateFlow()

    /** 이번 실행에서 하위에게 준 이름의 수. 기록과 함께 다음 이름을 정한다(nextChildSessionId). */
    private var lastAssignedChildOrdinal = 0

    /**
     * spawn 을 한 번에 하나씩 돌린다.
     *
     * 살아 있는 하위의 자리를 엔진에게 넘기는 것과 띄운 하위를 트리에 세우는 것 사이가 비면, 같은 자리를
     * 고른 두 spawn 이 서로를 모른 채 둘 다 엔진의 cwd 규칙(6.8)을 지난다. 상위의 도구 호출은 어차피 차례로
     * 돌아서(3.3) 이 줄이 실제로 늦추는 것은 없다.
     */
    private val childSpawning = Mutex()

    private val mutableGateQuestions = MutableStateFlow<List<ChildSessionGateQuestion>>(emptyList())

    /**
     * 사람에게 물을 관문들(6.9). 화면이 맨 앞의 것을 대화 상자로 세운다.
     *
     * 목록인 것은 화면이 하나씩 보이더라도 상태가 하나로 줄지 않게 하려는 것이다 — spawn 은 차례로 돌아 지금은
     * 둘이 겹칠 일이 없지만, 겹친 날 뒤의 물음이 앞의 것을 덮어 영영 답을 못 받는 일이 없게 한다.
     */
    val gateQuestions: StateFlow<List<ChildSessionGateQuestion>> = mutableGateQuestions.asStateFlow()

    /** 아직 답하지 않은 관문 물음의 답 자리. 하위 이름이 열쇠다 — spawn 하나에 물음은 한 번에 하나다. */
    private val gateDecisions = mutableMapOf<String, CompletableDeferred<GateAnswer>>()

    /**
     * 상위가 `await_session` 으로 기다리는 중이면 그 기다림(6.11 의 `pendingAwait`). **원칙 18 의 갈림길이 이 값 하나다** —
     * 있으면 사건이 이 기다림의 답으로 가고, 없으면 상위에게 사용자 메시지로 간다.
     *
     * 하나뿐이다. 상위의 도구 호출은 차례로 돌아서(3.3) 기다림 둘이 함께 열려 있을 수 없다.
     */
    private var pendingAwait: PendingAwait? = null

    /**
     * 상위에게 아직 전하지 못한 사건들. 일어난 차례대로다.
     *
     * 사건이 곧바로 가지 못하는 때가 있다 — 상위가 다른 하위만 고른 기다림 중이거나, 기다림 사이에서 답을 쓰는 중이다.
     * 그 사건들은 여기 남아 다음 기다림이 한 번에 받는다(6.6 의 "쌓인 사건을 다 담는다").
     */
    private val undeliveredParentEvents = mutableListOf<SessionTreeEvent.OfChild>()

    /**
     * 하위마다 상위의 답을 기다리는 물음(6.11 의 `pendingParentQuestion`). 하위 이름이 열쇠다 — 하위의 도구 호출은 차례로
     * 돌아(3.3) 한 하위에 물음은 한 번에 하나다.
     *
     * 상위의 `send_to_session` 이 이 값으로 갈린다(6.5 의 둘째 줄) — 있으면 그 물음의 답이 되고, 없으면 하위의 stdin 으로 간다.
     */
    private val pendingParentQuestions = mutableMapOf<String, PendingParentQuestion>()

    /** 이번 실행에서 하위의 물음에 붙인 이름의 수. 같은 상위 아래 물음들이 서로 갈린다. */
    private var lastAskOrdinal = 0

    /**
     * 그 세션의 챗을 연다. 이미 보유 중이면 다시 띄우지 않고 화면만 그쪽으로 옮긴다.
     *
     * @param conversationChoice 이 세션이 쓸 대화를 어느 쪽으로 정할지. 여기를 지나 엔진에게 닿고,
     *   엔진이 그 뜻대로 명령을 조립한다(설계 원칙 11).
     */
    fun enterSession(workDirPath: Path, target: PersonOpenedSessionTarget, conversationChoice: SessionConversationChoice) {
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

        // 메인을 새로 띄우면 앞 메인의 하위들도 함께 놓는다. 트리는 그 메인 프로세스의 것이고, 새 메인은 그
        // 하위들을 띄운 적이 없다(6.15 — 하위는 이어가지 않는다).
        if (target == SessionTarget.Main) {
            forgetChildren()
        }

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
     *
     * **도는 중에도 보낼 수 있다.** `claude` 가 큐를 갖고 있고(3.11), 실측에서 큐에 든 말은
     * 중단 뒤에도 살아남아 다음 턴으로 돌았다. 다만 되돌아오는 것은 그 턴이 시작할 때라,
     * 그 사이의 공백을 [ChatConversation.pendingUserMessages] 가 메운다.
     */
    fun sendUserMessage(text: String) {
        val target = mutableState.value.target ?: return
        val written = sendUserMessageTo(target, text, UserMessageSender.Human) ?: return

        // 사람이 기다리는 상위에게 말했다(session-tree 6.5 의 셋째 줄). 기다림을 user_spoke 로 깨운다 — stdin 에 쓴
        // 뒤에 깨우는 것이 순서다. 그래야 그 말이 도구 결과 바로 뒤에 같은 턴으로 붙는다(3.1).
        if (target == SessionTarget.Main && pendingAwait != null) {
            coroutineScope.launch {
                written.join()
                wakeParentAwaitBecauseHumanSpoke()
            }
        }
    }

    /**
     * 그 세션에 말 한 줄을 보낸다. 사람의 입력창과, 상위가 하위에게 하는 말(첫 프롬프트 · `send_to_session`)이 함께 쓴다.
     *
     * @param sender 그 말의 주인. 되돌아온 말풍선을 누구의 것으로 그릴지를 uuid 와 함께 적어 둔다(session-tree 6.12).
     * @return stdin 에 쓰는 일. 그 세션을 보유하고 있지 않으면 null.
     */
    private fun sendUserMessageTo(target: SessionTarget, text: String, sender: UserMessageSender): Job? {
        val held = mutableHeldSessions.value.firstOrNull { it.target == target } ?: return null

        // uuid 를 보내기 전에 정한다. 쓰기는 다른 스레드에서 나중에 일어나는데, 되돌아온 말을 알아볼
        // 열쇠는 대기 목록에 지금 서야 한다(session-tree 3.5 (다)).
        val message = OutgoingUserMessage(text, sender)

        // 턴 갈래를 여기서 옮기지 않는다. 보낸 것은 아직 시작한 턴이 아니고, 시작은 이 말이
        // 되돌아올 때다(3.11) — 그때까지 이 말은 "기다리는 말" 이다.
        updateConversation(target) { it.copy(pendingUserMessages = it.pendingUserMessages + message) }

        return writeToSession(target, held, failureNotice = "보내지 못했습니다") { sendUserMessage(message) }
    }

    /**
     * 화면에 떠 있는 대화의 도는 턴을 끊는다. **대화도 프로세스도 죽지 않는다**(3.8).
     *
     * 실측: 긴 글을 쓰던 중에 끊고 다음 말을 걸었더니 앞 맥락을 기억한 채로 답했다. 그래서 이
     * 자리가 "세션 끝내기" 옆에 따로 설 수 있다 — 끊는 것과 끝내는 것은 다른 일이다.
     *
     * **도는 턴이 없으면 아무 말도 하지 않는다.** 제어 요청은 프로세스가 언제든 받는 줄이라,
     * 턴이 없을 때 보내면 그다음에 시작하는 턴이 첫 낱말에서 끊긴다.
     */
    fun interruptOnScreenTurn() {
        val target = mutableState.value.target ?: return
        if (conversations[target]?.turnState != TurnState.Running) {
            return
        }
        val held = mutableHeldSessions.value.firstOrNull { it.target == target } ?: return

        // 두 번 눌러도 한 번만 간다. 갈래를 옮기는 것이 그 빗장이다.
        updateConversation(target) { it.copy(turnState = TurnState.Interrupting) }

        writeToSession(target, held, failureNotice = "끊지 못했습니다") { interruptCurrentTurn() }
    }

    /**
     * 세션의 stdin 에 한 줄을 쓴다. 쓰지 못하면 그 사실을 대화에 적고 입력창을 연다.
     *
     * 사용자 메시지와 중단 요청이 이 자리를 함께 쓴다(원칙 4 의 두 번째 사용처). 둘 다 실패하는
     * 이유가 하나뿐이라 — 프로세스가 이미 끝나 stdin 이 닫혔다 — 되살아나는 길도 같다. 그 사실은
     * 곧 `SessionEnded` 로도 오지만, 잠긴 입력창을 그때까지 두면 사용자는 자기 말이 갔는지 모른
     * 채 기다린다.
     */
    private fun writeToSession(
        target: SessionTarget,
        held: HeldChatSession,
        failureNotice: String,
        write: suspend OpenedChatSession.() -> Unit,
    ): Job =
        coroutineScope.launch {
            try {
                withContext(standardInputDispatcher) { held.session.write() }
            } catch (failure: IOException) {
                updateConversation(target) {
                    it.copy(
                        turnState = TurnState.Idle,
                        // 기다리던 말도 함께 놓는다. stdin 이 닫혔다는 것은 이 세션이 끝났다는
                        // 뜻이라, 그 말들은 되돌아오지 않는다 — 남겨 두면 영영 "대기 중" 이다.
                        pendingUserMessages = emptyList(),
                        entries = it.entries + ChatEntry.EngineNotice(
                            "$failureNotice — 이 세션은 끝났습니다 (${failure.message})",
                        ),
                    )
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
        if (target is SessionTarget.Child) {
            endChildSessionByHuman(target)
            return
        }

        latestSessionEntryRequestId++
        mutableState.value = ChatScreenState.NoChatOpen
        conversations.remove(target)

        // 원칙 17 — 메인이 끝나면 하위가 끝난다. 하위 먼저다: 메인이 먼저 끝나면 하위의 물음이 답 없이 끊긴다.
        if (target == SessionTarget.Main) {
            endChildrenOfMain()
            forgetChildren()
        }

        val held = mutableHeldSessions.value.firstOrNull { it.target == target } ?: return
        // 목록에서 먼저 뺀다. 끝나는 것을 지켜보던 자리가 이 목록으로 "우리가 끝낸 것인가" 를 가른다.
        mutableHeldSessions.value = mutableHeldSessions.value - held
        endSessionProcess(held)
    }

    /**
     * 하위 하나를 화면에 세운다. 끝난 하위도 전사가 남아 있으면 선다 — 왜 끝났는지 읽을 자리다.
     *
     * 패널의 하위 줄과 상위 챗의 spawn 카드 · 승인 띠가 이 길로 온다(6.12).
     */
    fun showChildSession(childId: String) {
        val target = SessionTarget.Child(childId)
        val conversation = conversations[target] ?: return
        // 화면이 다른 곳으로 옮겼으니 기다리던 진입은 버린다 — enterSession 이 다른 세션을 고를 때와 같다.
        latestSessionEntryRequestId++
        mutableState.value = ChatScreenState.ChatOnScreen(target, conversation)
    }

    /**
     * 사람이 하위 화면에서 "세션 끝내기" 를 눌렀다(6.13). 그 하위만 끝나고 상위는 그대로다.
     *
     * 화면은 메인으로 돌아간다. 하위의 전사는 남긴다 — 하위는 패널의 줄을 눌러 다시 띄우는 길이 없어서,
     * 전사가 그 하위가 한 일의 유일한 화면이다.
     */
    private suspend fun endChildSessionByHuman(target: SessionTarget.Child) {
        mutableState.value = conversations[SessionTarget.Main]
            ?.let { ChatScreenState.ChatOnScreen(SessionTarget.Main, it) }
            ?: ChatScreenState.NoChatOpen

        val node = mutableSessionTree.value.child(target.childId) ?: return
        if (node.lifecycle == ChildLifecycle.Running) {
            endChildSession(node, ChildEndReason.EndedByHuman)
        }
    }

    /**
     * 메인이 끝나 하위를 전부 끝낸다(6.13). 사람에게 묻던 관문도 거둔다 — 띄울 상위가 없다.
     *
     * 트리의 하위들은 그대로 두고 끝났다고만 적는다. 놓을지는 부르는 쪽이 정한다 — 사람이 메인을 닫은 것이면
     * 메인의 전사와 함께 놓고, 메인이 스스로 끝난 것이면 남겨 사람이 무엇이 끝났는지 읽게 한다.
     */
    private suspend fun endChildrenOfMain() {
        withdrawGateQuestions()
        forgetParentDialogue()
        val liveChildren = mutableSessionTree.value.children.filter { it.lifecycle == ChildLifecycle.Running }
        coroutineScope {
            liveChildren.map { async { endChildSession(it, ChildEndReason.ParentEnded) } }.awaitAll()
        }
    }

    /** 트리와 하위들의 전사를 놓는다. 그 하위들은 이미 끝났다. */
    private fun forgetChildren() {
        pendingParentQuestions.clear()
        mutableSessionTree.value.children.forEach { conversations.remove(it.target) }
        mutableSessionTree.value = SessionTree()
    }

    /** 사람에게 묻던 관문을 거두고 대화 상자를 내린다. */
    private fun withdrawGateQuestions() {
        val pendingDecisions = gateDecisions.values.toList()
        gateDecisions.clear()
        mutableGateQuestions.value = emptyList()
        pendingDecisions.forEach { it.complete(GateAnswer.Withdrawn) }
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

        // 하위 먼저, 상위 나중(6.13). 하위끼리는 한꺼번에 끝낸다.
        endChildrenOfMain()
        forgetChildren()
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
        target: PersonOpenedSessionTarget,
        conversationChoice: SessionConversationChoice,
    ): HeldChatSession {
        // 소켓이 먼저다. 엔진에게 물을 때 그 자리를 함께 줘야 하고(조립은 엔진이 한다 — 원칙 11),
        // 그러려면 앱이 이미 듣고 있어야 한다. 열지 못하면 세션도 열지 않는다 — 승인이 필요한
        // 일이 나올 때까지 멀쩡해 보이다가 그때부터 아무것도 못 하는 세션이 되는 것보다,
        // 화면이 먼저 말하는 편이 낫다(원칙 12 · TerminalSessionFailure.ApprovalSocketFailedToOpen).
        //
        // 챗 세션이면 조건 없이 연다. 이 소켓은 승인만의 것이 아니라 그 세션의 앱 소켓이고
        // (session-tree-design.md 6.4), 그 소켓에 승인 서버를 붙일지는 엔진이 bypass 로 정한다 —
        // 앱이 그 조건을 따라 소켓을 열고 말고를 가르면 같은 지식이 두 곳에 놓인다.
        val appSocketChannel = appSocketChannelOpener.openAppSocketChannel()

        try {
            val answer = sessionCommandSource.sessionCommandFor(
                workDirPath = workDirPath,
                target = target,
                conversationChoice = conversationChoice,
                appSocketPath = appSocketChannel.socketPath,
            )

            val plan = planSessionEntry(
                sessionCommandAnswer = answer,
                baseEnvironment = baseEnvironment,
                workDirPath = workDirPath,
                target = target,
                claudeAuthToken = claudeAuthToken(),
            )

            return HeldChatSession(
                target = target,
                session = chatSessionOpener.openChatSession(plan),
                resumedConversationId = answer.resumedConversationId,
                workingDirectory = plan.workingDirectory,
                appSocketChannel = appSocketChannel,
                workDirPath = workDirPath,
            )
        } catch (failure: Throwable) {
            // 세션이 서지 못했으면 그 소켓에 물어올 자식도 없다. 닫지 않으면 아무도 붙지 않는
            // 소켓이 런타임 디렉토리에 하나씩 쌓인다.
            appSocketChannel.close()
            throw failure
        }
    }

    private fun holdAndShow(held: HeldChatSession) {
        hold(held)
        conversations[held.target]?.let { mutableState.value = ChatScreenState.ChatOnScreen(held.target, it) }
    }

    /**
     * 세션을 보유하고 그 세션이 말하는 것을 받기 시작한다. 화면은 옮기지 않는다.
     *
     * 하위 세션이 이 길로만 선다 — 상위가 띄운 하위가 사람이 보던 화면을 빼앗으면, 사람은 자기가 무엇을
     * 하다 이 화면에 왔는지 잃는다.
     */
    private fun hold(held: HeldChatSession) {
        conversations[held.target] = ChatConversation(
            target = held.target,
            workingDirectory = held.workingDirectory,
            resumedConversationId = held.resumedConversationId,
        )
        mutableHeldSessions.value = mutableHeldSessions.value + held

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

        // 승인 물음은 다른 통로로 온다(소켓). 같은 이유로 화면과 무관하게 받는다 — 보고 있지 않은
        // 세션의 물음에 아무도 답하지 않으면 그 세션의 claude 는 관문 앞에서 멈춘 채 산다.
        coroutineScope.launch {
            held.appSocketChannel.permissionRequests.collect { asked -> receivePermissionRequest(held.target, asked) }
        }

        // 세션 트리 요청도 같은 소켓으로 온다(6.4). 메인이 아닌 세션의 소켓에도 듣는다 — 듣지 않으면 그
        // 요청은 거절도 받지 못한 채 서 있고, 그 세션의 claude 는 도구 답을 기다리며 멎는다.
        coroutineScope.launch {
            held.appSocketChannel.sessionTreeRequests.collect { asked -> receiveSessionTreeRequest(held, asked) }
        }
    }

    /**
     * 세션 트리 요청 하나가 올라왔다.
     *
     * **앱이 소켓마다 역할을 안다**(6.3). 하위를 띄우고 거두는 것은 메인뿐이다 — 레포 세션과 하위 세션의
     * 소켓으로 온 요청은 거절한다. 하위의 kyu 서버에는 이 도구들이 없으니 정상이면 오지 않는 요청이지만,
     * 소켓은 줄을 받을 뿐 누가 보냈는지 고르지 않는다.
     */
    private fun receiveSessionTreeRequest(requester: HeldChatSession, asked: AskedSessionTreeRequest) {
        // 상위에게 묻는 것은 하위뿐이다 — 메인과 레포 세션에게는 물을 상위가 없다.
        val askParent = asked.request as? SessionTreeRequest.AskParent
        if (askParent != null) {
            val child = requester.target as? SessionTarget.Child
            if (child == null) {
                asked.answerWith(
                    SessionTreeAnswer.Refused(
                        "ask_parent 는 하위 세션만 부릅니다 — 이 세션(${requester.target.label})에게는 물을 상위가 없습니다",
                    ),
                )
                return
            }
            askParentFor(child.childId, askParent.message, asked)
            return
        }

        if (requester.target != SessionTarget.Main) {
            asked.answerWith(
                SessionTreeAnswer.Refused(
                    "하위 세션은 메인 세션만 띄우고 거둡니다 — 이 세션(${requester.target.label})의 요청은 받지 않습니다",
                ),
            )
            return
        }

        when (val request = asked.request) {
            is SessionTreeRequest.SpawnSession ->
                coroutineScope.launch { asked.answerWith(spawnChildSession(requester, request, asked)) }

            is SessionTreeRequest.AwaitSessions -> awaitChildSessions(asked, request)

            is SessionTreeRequest.SendToSession -> asked.answerWith(sendToChildSession(request.childId, request.message))

            is SessionTreeRequest.EndSession ->
                coroutineScope.launch { asked.answerWith(endChildSessionForParent(request.childId)) }

            is SessionTreeRequest.ListSessions ->
                asked.answerWith(SessionTreeAnswer.Listed(mutableSessionTree.value.children))

            // 위에서 하위의 것으로 이미 갈랐다.
            is SessionTreeRequest.AskParent -> Unit
        }
    }

    /**
     * 하위가 상위에게 묻는다(6.5 의 첫째 줄). 물음을 걸어 두고 상위에게 알린다 — 상위가 기다리고 있으면 그 기다림의 답으로,
     * 쉬고 있으면 상위의 stdin 으로 간다(원칙 18). 하위의 `ask_parent` 는 상위가 `send_to_session` 으로 답할 때까지 열려 있다.
     *
     * 상한을 두지 않는다(6.7). 답을 정하는 것은 상위의 모델이고, 상위는 사람과 이야기 중일 수 있다.
     */
    private fun askParentFor(childId: String, message: String, asked: AskedSessionTreeRequest) {
        if (mutableHeldSessions.value.none { it.target == SessionTarget.Main }) {
            asked.answerWith(SessionTreeAnswer.AskEnded(AskEndReason.ParentEnded))
            return
        }
        if (mutableSessionTree.value.child(childId)?.lifecycle != ChildLifecycle.Running) {
            asked.answerWith(SessionTreeAnswer.AskEnded(AskEndReason.SessionEnded))
            return
        }

        lastAskOrdinal++
        val question = PendingParentQuestion(askId = "ask-$lastAskOrdinal", asked = asked)
        pendingParentQuestions[childId] = question
        refreshChildWaitingFor(SessionTarget.Child(childId))

        // 사람이 그 하위의 턴을 중단하면 kyu mcp serve 가 연결을 닫는다. 물음을 거두고, 아직 상위에게 가지 않았으면 그것도
        // 지운다 — 상위가 나중에 답하면 그 말은 아무도 기다리지 않는 답이 된다.
        coroutineScope.launch {
            if (asked.awaitConnectionClosedBeforeAnswer() && pendingParentQuestions[childId] === question) {
                forgetParentQuestion(childId)
            }
        }

        tellParent(SessionTreeEvent.AskedParent(childId, message, question.askId))
    }

    /** 그 하위의 물음을 놓는다 — 상위에게 아직 가지 않은 그 물음도 함께 지운다. 답은 부르는 쪽이 한다. */
    private fun forgetParentQuestion(childId: String): PendingParentQuestion? {
        val question = pendingParentQuestions.remove(childId) ?: return null
        undeliveredParentEvents.removeAll { it is SessionTreeEvent.AskedParent && it.askId == question.askId }
        refreshChildWaitingFor(SessionTarget.Child(childId))
        return question
    }

    /**
     * 상위의 부탁대로 하위 하나를 띄우고, 첫 프롬프트를 넣은 뒤 답한다(6.2 — 하위의 턴을 기다리지 않는다).
     *
     * 순서가 판단이다: 이름 → 소켓 → 엔진 → 프로세스 → 트리 → 첫 프롬프트. 소켓이 엔진보다 먼저인 이유는
     * 사람이 연 세션과 같다 — 엔진이 그 자리를 하위의 `--mcp-config` 에 싣는다.
     */
    private suspend fun spawnChildSession(
        parent: HeldChatSession,
        request: SessionTreeRequest.SpawnSession,
        asked: AskedSessionTreeRequest,
    ): SessionTreeAnswer = childSpawning.withLock {
        val recordedChildIds = try {
            withContext(sessionEntryDispatcher) {
                recordedChildSessionIds.childSessionIdsRecordedUnderMain(parent.workDirPath)
            }
        } catch (failure: IOException) {
            return SessionTreeAnswer.Refused("대화 기록을 읽지 못해 하위의 이름을 정하지 못했습니다 (${failure.message})")
        }

        // 이름을 쓴 것으로 먼저 센다. 엔진이 거절했어도 그 이름이 기록에 적혔을 수 있다 — 같은 실행에서 다시
        // 고르면 엔진이 "이미 적혀 있다" 로 또 거절한다.
        val assignment = nextChildSessionId(recordedChildIds, lastAssignedChildOrdinal)
        lastAssignedChildOrdinal = assignment.ordinal
        val childTarget = SessionTarget.Child(assignment.childId)

        val commandRequest = ChildSessionCommandRequest(
            childId = assignment.childId,
            requestedCwd = request.requestedCwd,
            createDirectory = request.createDirectory,
            title = request.title,
            liveChildRelativeCwds = mutableSessionTree.value.liveChildRelativeCwds,
            approvedFingerprint = null,
        )

        // 관문에 걸리면 사람에게 묻고, 승인한 지문으로 다시 묻는다. 다시 걸리면 사람이 본 뒤 파일이 바뀐
        // 것이라 지금 내용으로 또 묻는다 — 엔진이 지금 내용의 지문만 적는다(childSessionApprovalNeeded).
        var approvedFingerprint: String? = null
        val opened: ChildSessionOpening.Opened
        while (true) {
            val attempt = commandRequest.copy(approvedFingerprint = approvedFingerprint)
            val opening = try {
                withContext(sessionEntryDispatcher) { openChildSession(parent.workDirPath, childTarget, attempt) }
            } catch (failure: TerminalSessionFailure) {
                return SessionTreeAnswer.Refused(refusalReasonFor(failure))
            }

            when (opening) {
                is ChildSessionOpening.Opened -> {
                    opened = opening
                    break
                }

                is ChildSessionOpening.GateRequired -> {
                    val question = ChildSessionGateQuestion(assignment.childId, request.prompt, opening.gate)
                    val gateAnswer = askPersonAboutGate(question, asked)
                    if (parent !in mutableHeldSessions.value) {
                        return SessionTreeAnswer.Refused("상위 세션이 끝나 하위를 띄우지 않았습니다")
                    }
                    when (gateAnswer) {
                        GateAnswer.Approved -> approvedFingerprint = opening.gate.fingerprint
                        GateAnswer.Declined -> return SessionTreeAnswer.Refused(
                            "사람이 이 폴더(${opening.gate.relativeCwd})의 설정을 승인하지 않았습니다",
                        )
                        // 이 답을 받을 연결은 이미 없다. 띄우지 않는 것이 요점이고 문구는 기록일 뿐이다.
                        GateAnswer.Withdrawn -> return SessionTreeAnswer.Refused(
                            "관문을 묻는 사이 spawn_session 호출이 거둬져 하위를 띄우지 않았습니다",
                        )
                    }
                }
            }
        }

        // 기다리는 동안 메인이 끝났다. 원칙 17 — 상위가 없는 하위는 서지 않는다.
        if (parent !in mutableHeldSessions.value) {
            endSessionProcess(opened.held)
            return SessionTreeAnswer.Refused("상위 세션이 끝나 하위를 띄우지 않았습니다")
        }

        val assigned = opened.assignedChildSession
        hold(opened.held)
        mutableSessionTree.value = mutableSessionTree.value.withChild(
            ChildSessionNode(
                childId = assigned.childId,
                title = assigned.title,
                relativeCwd = assigned.relativeCwd,
                workingDirectory = opened.held.workingDirectory,
                conversationId = assigned.conversationId,
            ),
        )
        sendUserMessageTo(childTarget, PARENT_MESSAGE_PREFIX + request.prompt, UserMessageSender.ParentSession)

        SessionTreeAnswer.Spawned(
            childId = assigned.childId,
            relativeCwd = assigned.relativeCwd,
            title = assigned.title,
            conversationId = assigned.conversationId,
            notice = personOpenedSessionAtTheSamePlace(opened.held.workingDirectory)?.let { repoLabel ->
                "사람이 연 레포 세션 $repoLabel 이 같은 자리에서 살아 있습니다 — 서로의 수정을 덮을 수 있으니 " +
                    "필요하면 사람에게 물으세요"
            },
        )
    }

    /**
     * 관문 대화 상자를 세우고 사람의 답을 기다린다.
     *
     * 상한을 두지 않는다. 사람이 그 파일을 읽는 데 얼마가 걸리든 그동안 상위의 도구 호출이 기다린다 — 승인
     * 카드에 상한을 두지 않은 이유와 같다(chat-ui 5.4.1). 메인이 끝나거나, 그 spawn 의 연결이 끊기면 그 자리에서
     * 거둬진다(6.13 · 6.4) — 답을 받을 쪽이 사라졌는데 사람이 승인하면 상위가 모르는 하위가 뜬다.
     */
    private suspend fun askPersonAboutGate(question: ChildSessionGateQuestion, asked: AskedSessionTreeRequest): GateAnswer {
        val decision = CompletableDeferred<GateAnswer>()
        gateDecisions[question.childId] = decision
        mutableGateQuestions.value = mutableGateQuestions.value + question

        val watchingConnection = coroutineScope.launch {
            if (asked.awaitConnectionClosedBeforeAnswer()) {
                withdrawGateQuestion(question.childId)
            }
        }
        return decision.await().also { watchingConnection.cancel() }
    }

    /** 그 하위의 관문 물음 하나를 거두고 대화 상자를 내린다. 이미 답한 물음이면 아무 일도 하지 않는다. */
    private fun withdrawGateQuestion(childId: String) {
        val decision = gateDecisions.remove(childId) ?: return
        mutableGateQuestions.value = mutableGateQuestions.value.filterNot { it.childId == childId }
        decision.complete(GateAnswer.Withdrawn)
    }

    /**
     * 관문 대화 상자에 사람이 답했다.
     *
     * 이미 답한 물음에는 아무 일도 하지 않는다 — 버튼을 두 번 누른 것이 두 답이 되지 않는다.
     */
    fun answerGateQuestion(childId: String, approved: Boolean) {
        val decision = gateDecisions.remove(childId) ?: return
        mutableGateQuestions.value = mutableGateQuestions.value.filterNot { it.childId == childId }
        decision.complete(if (approved) GateAnswer.Approved else GateAnswer.Declined)
    }

    /** 하위 하나를 엔진에게 묻고 띄운다. 띄우지 못하면 그 하위의 소켓을 닫고 실패를 그대로 던진다. */
    private suspend fun openChildSession(
        workDirPath: Path,
        childTarget: SessionTarget.Child,
        commandRequest: ChildSessionCommandRequest,
    ): ChildSessionOpening {
        val appSocketChannel = appSocketChannelOpener.openAppSocketChannel()

        try {
            val ready = when (
                val answer = childSessionCommandSource.childSessionCommandFor(
                    workDirPath,
                    commandRequest,
                    appSocketChannel.socketPath,
                )
            ) {
                is ChildSessionCommandAnswer.Ready -> answer
                is ChildSessionCommandAnswer.ApprovalRequired -> {
                    // 아무것도 띄우지 않았다. 이 소켓에 물어올 자식도 없다.
                    appSocketChannel.close()
                    return ChildSessionOpening.GateRequired(answer.gate)
                }
            }

            val plan = planSessionEntry(
                sessionCommandAnswer = ready.sessionCommandAnswer,
                baseEnvironment = baseEnvironment,
                workDirPath = workDirPath,
                target = childTarget,
                claudeAuthToken = claudeAuthToken(),
            )

            val held = HeldChatSession(
                target = childTarget,
                session = chatSessionOpener.openChatSession(plan),
                resumedConversationId = null,
                workingDirectory = plan.workingDirectory,
                appSocketChannel = appSocketChannel,
                workDirPath = workDirPath,
            )
            return ChildSessionOpening.Opened(held, ready.assignedChildSession)
        } catch (failure: Throwable) {
            appSocketChannel.close()
            throw failure
        }
    }

    /** 같은 자리에서 살아 있는 사람이 연 레포 세션의 라벨. 없으면 null(6.8). */
    private fun personOpenedSessionAtTheSamePlace(childWorkingDirectory: Path): String? =
        mutableHeldSessions.value
            .firstOrNull {
                it.target is SessionTarget.Repo && it.workingDirectory.normalize() == childWorkingDirectory.normalize()
            }
            ?.target?.label

    /**
     * 상위가 하위에게 말한다(6.5 의 둘째 줄). 하위의 stdin 에 사용자 메시지로 넣는다 — 접두가 모델에게, 보낸 이가
     * 화면에게 출처를 말한다.
     *
     * 끝난 하위에게는 쓰지 않는다. stdin 이 이미 닫혀 쓰기가 실패하고, 상위는 말이 갔다고 믿은 채 답을 기다린다(6.15).
     */
    private fun sendToChildSession(childId: String, message: String): SessionTreeAnswer {
        val node = mutableSessionTree.value.child(childId)
            ?: return SessionTreeAnswer.Refused("그런 하위 세션이 없습니다: $childId — list_sessions 로 확인하세요")

        if (node.lifecycle != ChildLifecycle.Running) {
            return SessionTreeAnswer.Refused("$childId 은 끝났습니다 — list_sessions 로 살아 있는 세션을 확인하세요")
        }

        // 하위가 묻고 기다리는 중이면 그 물음의 답이 된다(6.5). stdin 에 쓰면 하위의 턴이 ask_parent 에 묶인 채라
        // 그 말은 물음이 풀릴 때까지 닿지 않는다(3.1) — 서로를 기다리는 교착이다.
        val question = forgetParentQuestion(childId)
        if (question != null) {
            question.asked.answerWith(SessionTreeAnswer.ParentAnswered(message))
            return SessionTreeAnswer.DeliveredAsAnswer(question.askId)
        }

        sendUserMessageTo(node.target, PARENT_MESSAGE_PREFIX + message, UserMessageSender.ParentSession)
        return SessionTreeAnswer.DeliveredAsMessage
    }

    /**
     * 상위가 하위들에게 전할 일이 생길 때까지 기다린다(6.6).
     *
     * 이미 쌓인 사건이 있으면 곧바로 답한다. 기다릴 살아 있는 하위가 없으면 그것도 곧바로 답한다 — 올 것이 없는데
     * `waitSeconds` 를 다 채우면 상위의 턴이 이유 없이 그만큼 묶인다.
     */
    private fun awaitChildSessions(asked: AskedSessionTreeRequest, request: SessionTreeRequest.AwaitSessions) {
        val tree = mutableSessionTree.value
        val unknownChildIds = request.childIds.orEmpty().filter { tree.child(it) == null }
        if (unknownChildIds.isNotEmpty()) {
            asked.answerWith(
                SessionTreeAnswer.Refused(
                    "그런 하위 세션이 없습니다: ${unknownChildIds.joinToString()} — list_sessions 로 확인하세요",
                ),
            )
            return
        }

        // 생략은 "살아 있는 하위 전부" 다(6.6). 끝난 하위까지 고르는 것이 그 뜻을 지킨다 — 끝난 하위는 더 일어날 일이
        // 없지만, 끝나기 전후에 쌓인 사건(마지막 턴 · 끝남)은 아직 상위에게 가지 않았을 수 있다.
        val watchedChildIds = request.childIds?.toSet() ?: tree.children.map { it.childId }.toSet()

        // 앞선 기다림이 아직 열려 있다면 그 호출은 이미 거둬졌다 — 상위의 도구 호출은 차례로 돈다(3.3). 연결이 끊긴
        // 것을 소켓이 아직 알리지 못한 짧은 틈이다. 빈 답으로 닫고 새 기다림을 연다.
        pendingAwait?.let { answerPendingAwait(emptyList()) }

        val expiry = coroutineScope.launch {
            delay(request.waitSeconds.seconds)
            if (pendingAwait?.asked === asked) {
                answerPendingAwait(emptyList())
            }
        }
        pendingAwait = PendingAwait(asked, watchedChildIds, expiry)

        // 상위가 기다림을 중단하면 kyu mcp serve 가 연결을 닫는다(3.2 · PR 68). 그 기다림만 지운다 — 하위는 그대로 돌고,
        // 이 기다림이 가져가지 못한 사건은 쌓인 채 다음 기다림이나 상위의 턴 끝을 기다린다.
        coroutineScope.launch {
            if (asked.awaitConnectionClosedBeforeAnswer() && pendingAwait?.asked === asked) {
                pendingAwait?.expiry?.cancel()
                pendingAwait = null
            }
        }

        deliverParentEvents()

        val nothingCanHappen = watchedChildIds.none { mutableSessionTree.value.child(it)?.lifecycle == ChildLifecycle.Running }
        if (pendingAwait?.asked === asked && nothingCanHappen) {
            answerPendingAwait(emptyList())
        }
    }

    /** 열린 기다림에 답하고 닫는다. 기다린 하위 중 아직 살아 있는 것을 함께 싣는다. */
    private fun answerPendingAwait(events: List<SessionTreeEvent>) {
        val await = pendingAwait ?: return
        pendingAwait = null
        await.expiry.cancel()

        val stillRunning = mutableSessionTree.value.children
            .filter { it.childId in await.watchedChildIds && it.lifecycle == ChildLifecycle.Running }
            .map { it.childId }
        await.asked.answerWith(SessionTreeAnswer.Awaited(events, stillRunning))
    }

    /**
     * 사람이 기다리는 상위에게 말했다. 그 기다림이 고른 하위의 쌓인 사건과 함께 [SessionTreeEvent.UserSpoke] 로 답한다.
     *
     * stdin 에 쓰는 사이 다른 사건이 먼저 기다림을 닫았으면 할 일이 없다 — 사람의 말은 그 도구 결과 뒤에 붙는다.
     */
    private fun wakeParentAwaitBecauseHumanSpoke() {
        val await = pendingAwait ?: return
        val deliverable = undeliveredParentEvents.filter { it.childId in await.watchedChildIds }
        undeliveredParentEvents.removeAll(deliverable)
        answerPendingAwait(deliverable + SessionTreeEvent.UserSpoke)
    }

    /** 상위에게 전할 일이 생겼다. 원칙 18 의 길로 보낸다([deliverParentEvents]). */
    private fun tellParent(event: SessionTreeEvent.OfChild) {
        undeliveredParentEvents += event
        deliverParentEvents()
    }

    /**
     * 쌓인 사건을 지금 들을 수 있는 통로로 보낸다(원칙 18).
     *
     * | 상위 | 사건이 가는 길 |
     * |---|---|
     * | `await_session` 으로 기다리는 중 | 그 기다림이 고른 하위의 사건을 전부 담아 도구의 답으로. 고르지 않은 하위의 것은 남는다 |
     * | 쉬는 중 | 사건마다 하위의 말로 stdin 에 — 새 턴이 돈다(3.5 (가)) |
     * | 턴 중인데 기다리지 않는다 | 남겨 둔다. 이 턴 안에서 기다리면 그 답으로, 기다리지 않고 턴을 끝내면 그때 stdin 으로 간다 |
     *
     * 셋째 줄에서 곧바로 stdin 에 쓰지 않는 이유가 있다. 그 말은 다음 도구 경계에서야 닿는데(3.1), 그 도구가
     * `await_session` 이면 기다림이 끝날 때까지 묶이고, 같은 사건을 기다림의 답에도 담으면 상위는 같은 결과를 두 번 읽는다.
     */
    private fun deliverParentEvents() {
        val await = pendingAwait
        if (await != null) {
            val deliverable = undeliveredParentEvents.filter { it.childId in await.watchedChildIds }
            if (deliverable.isNotEmpty()) {
                undeliveredParentEvents.removeAll(deliverable)
                answerPendingAwait(deliverable)
            }
            return
        }

        if (conversations[SessionTarget.Main]?.turnState != TurnState.Idle) {
            return
        }
        val idleParentEvents = undeliveredParentEvents.toList()
        undeliveredParentEvents.clear()
        idleParentEvents.forEach { event ->
            sendUserMessageTo(SessionTarget.Main, parentNoticeTextFor(event), UserMessageSender.ChildSession(event.childId))
        }
    }

    /**
     * 상위가 끝났다. 기다림과 쌓인 사건을 놓는다 — 전할 상대가 없다.
     *
     * 기다림에 답하지 않는다. 상위의 소켓이 닫히면서 그 연결이 끊기고, 답을 받을 도구 호출도 그 프로세스와 함께 사라졌다.
     */
    private fun forgetParentDialogue() {
        pendingAwait?.expiry?.cancel()
        pendingAwait = null
        undeliveredParentEvents.clear()
    }

    /** 상위가 `end_session` 으로 하위를 끝낸다(6.13). 이미 끝난 하위면 그대로 끝났다고 답한다. */
    private suspend fun endChildSessionForParent(childId: String): SessionTreeAnswer {
        val node = mutableSessionTree.value.child(childId)
            ?: return SessionTreeAnswer.Refused("그런 하위 세션이 없습니다: $childId — list_sessions 로 확인하세요")

        if (node.lifecycle == ChildLifecycle.Running) {
            endChildSession(node, ChildEndReason.EndedByParent)
        }
        return SessionTreeAnswer.Ended(childId)
    }

    /**
     * 살아 있는 하위 하나를 끝내고, 트리에 그 이유를 적는다.
     *
     * 트리를 먼저 고친다. 프로세스가 끝나며 오는 `SessionEnded` 는 이미 끝난 하위의 종료 코드만 채운다 —
     * 순서가 뒤집히면 앱이 끝낸 하위가 스스로 끝난 것으로 적힌다.
     */
    private suspend fun endChildSession(node: ChildSessionNode, reason: ChildEndReason) {
        // 묻던 중이면 그 물음에 먼저 답한다(6.13) — stdin 을 닫으면 하위가 그 턴을 마치는데, 답 없이 끊긴 물음은 오류로 읽힌다.
        forgetParentQuestion(node.childId)?.asked?.answerWith(
            SessionTreeAnswer.AskEnded(
                if (reason == ChildEndReason.ParentEnded) AskEndReason.ParentEnded else AskEndReason.SessionEnded,
            ),
        )

        updateChild(node.childId) {
            it.copy(lifecycle = ChildLifecycle.Ended(reason, exitCode = null), waitingFor = ChildWaitingFor.Nothing)
        }
        // 상위가 끝나서 함께 끝난 하위는 알릴 상위가 없다.
        if (reason != ChildEndReason.ParentEnded) {
            tellParent(SessionTreeEvent.SessionEnded(node.childId, reason, exitCode = null))
        }

        val held = mutableHeldSessions.value.firstOrNull { it.target == node.target } ?: return
        // 목록에서 먼저 뺀다. 끝나는 것을 지켜보던 자리가 이 목록으로 "우리가 끝낸 것인가" 를 가른다.
        mutableHeldSessions.value = mutableHeldSessions.value - held
        endSessionProcess(held)
    }

    private fun updateChild(childId: String, change: (ChildSessionNode) -> ChildSessionNode) {
        val node = mutableSessionTree.value.child(childId) ?: return
        mutableSessionTree.value = mutableSessionTree.value.withChild(change(node))
    }

    /**
     * 승인 물음 하나가 올라왔다.
     *
     * **세션 규칙이 먼저다.** "이 세션에서 계속 허용" 을 받은 도구면 사람에게 다시 묻지 않고 그대로
     * 답하되, 카드는 그래도 세운다 — 규칙이 무엇을 통과시켰는지가 전사에 없으면 사용자는 자기가
     * 세운 규칙이 실제로 무엇을 허용했는지 볼 길이 없다.
     */
    private fun receivePermissionRequest(target: SessionTarget, asked: AskedPermission) {
        val request = asked.request

        if (request.toolName in toolsAllowedForSession[target].orEmpty()) {
            asked.answerWith(PermissionDecision.Allow(updatedInput = request.input))
            updateConversation(target) {
                it.after(ChatSessionEvent.PermissionRequested(request))
                    .withPermissionAnswered(request.toolUseId, PermissionAnswer.AllowedByThisSessionRule)
            }
            return
        }

        val key = AskedPermissionKey(target, request.toolUseId)
        askedPermissions[key] = asked
        receive(target, ChatSessionEvent.PermissionRequested(request))

        // 사람이 그 턴을 중단하면 kyu mcp ask 가 연결을 닫는다. 카드를 거둔다 — 남기면 누를 수 있는 척하는 카드가 되고,
        // 하위의 줄은 아무도 기다리지 않는 승인을 기다린다고 말한다.
        coroutineScope.launch {
            if (asked.awaitConnectionClosedBeforeAnswer() && askedPermissions[key] === asked) {
                askedPermissions.remove(key)
                updateConversation(target) { it.withPermissionAnswered(request.toolUseId, PermissionAnswer.Withdrawn) }
            }
        }
    }

    /**
     * 화면에 떠 있는 대화의 승인 카드에 답한다(6.4).
     *
     * 답을 두 곳으로 보낸다 — 소켓 너머의 자식에게는 결정을, 전사에는 그 결정이 무엇이었는지를.
     * 둘을 한 함수에서만 하는 것이 규율이다. 나눠 두면 소켓에는 갔는데 화면에는 남지 않은 승인이
     * 생기고, 그것은 사용자가 무엇을 허용했는지 확인할 수 없다는 뜻이다.
     *
     * **이미 답한 물음에는 아무 일도 하지 않는다.** 버튼을 두 번 누르거나, 세션이 끝나 물음이
     * 닫힌 뒤에 누른 자리가 그렇다.
     */
    fun answerOnScreenPermission(toolUseId: String, choice: PermissionCardChoice) {
        val target = mutableState.value.target ?: return
        val asked = askedPermissions.remove(AskedPermissionKey(target, toolUseId)) ?: return

        val decision = when (choice) {
            is PermissionCardChoice.AllowOnce -> PermissionDecision.Allow(choice.input)
            is PermissionCardChoice.AllowForThisSession -> PermissionDecision.Allow(choice.input)
            is PermissionCardChoice.Deny -> PermissionDecision.Deny(choice.reason)
        }
        asked.answerWith(decision)

        if (choice is PermissionCardChoice.AllowForThisSession) {
            toolsAllowedForSession.getOrPut(target) { mutableSetOf() }.add(asked.request.toolName)
        }

        updateConversation(target) { it.withPermissionAnswered(toolUseId, answerFor(choice, asked.request)) }
    }

    /**
     * 카드가 결정 뒤에 남을 모습을 정한다.
     *
     * 고친 인자는 원본과 다를 때만 싣는다. 같은 값을 담아 두면 화면이 "고쳐서 허용" 과 "그대로
     * 허용" 을 가르지 못하고, 그 둘은 사용자가 나중에 전사를 읽을 때 가장 먼저 묻는 것이다.
     */
    private fun answerFor(choice: PermissionCardChoice, request: PermissionRequest): PermissionAnswer = when (choice) {
        is PermissionCardChoice.AllowOnce ->
            PermissionAnswer.Allowed(editedInput = choice.input.takeIf { it != request.input })

        is PermissionCardChoice.AllowForThisSession -> PermissionAnswer.AllowedForThisSession

        is PermissionCardChoice.Deny -> PermissionAnswer.Denied(choice.reason)
    }

    private fun receive(target: SessionTarget, event: ChatSessionEvent) {
        val entriesBefore = conversations[target]?.entries.orEmpty()
        updateConversation(target) { it.after(event) }

        if (target is SessionTarget.Child && event is ChatSessionEvent.TurnFinished) {
            tellParentChildTurnFinished(target.childId, event, turnTriggerOf(entriesBefore))
        }
        // 상위의 턴이 끝났다 — 그 턴 동안 쌓인 사건을 이제 사용자 메시지로 보낼 수 있다.
        if (target == SessionTarget.Main && event is ChatSessionEvent.TurnFinished) {
            deliverParentEvents()
        }

        if (event is ChatSessionEvent.SessionEnded) {
            if (target is SessionTarget.Child) {
                recordChildExit(target.childId, event.exitCode)
            }
            markSessionEnded(target, event.exitCode)
        }
    }

    /**
     * 하위의 턴 하나가 끝났다는 것을 상위에게 알린다(6.6). 하위의 매 턴이 상위를 깨운다 — 사람이 하위 탭에서 시킨 턴도
     * 그렇다(v1 의 판단 · 8 절 2 번).
     *
     * 끝난 하위의 마지막 턴은 알리지 않는다. 앱이 끝낸 하위는 stdin 이 닫혀 진행 중이던 턴을 마치는데, 상위는 이미
     * 그 하위가 끝났다는 사건을 받았다.
     */
    private fun tellParentChildTurnFinished(childId: String, finished: ChatSessionEvent.TurnFinished, trigger: TurnTrigger) {
        if (mutableSessionTree.value.child(childId)?.lifecycle != ChildLifecycle.Running) {
            return
        }
        tellParent(
            SessionTreeEvent.TurnCompleted(
                childId = childId,
                result = finished.resultText.orEmpty(),
                isError = finished.outcome != TurnOutcome.Completed,
                triggeredBy = trigger,
                costUsd = finished.costUsd,
                durationMillis = finished.durationMillis,
            ),
        )
    }

    /**
     * 하위의 프로세스가 끝났다는 사실을 트리에 적는다.
     *
     * 살아 있던 하위면 스스로 끝난 것이다 — 0 이면 끝났고, 아니면 죽었다. 앱이 이미 끝낸 하위면 이유는 그대로
     * 두고 종료 코드만 채운다.
     */
    private fun recordChildExit(childId: String, exitCode: Int) {
        val endedByItself = mutableSessionTree.value.child(childId)?.lifecycle == ChildLifecycle.Running
        // 물음을 낸 프로세스가 사라졌다. 그 물음에 답할 자리도, 상위가 그것을 읽을 까닭도 없다.
        forgetParentQuestion(childId)

        updateChild(childId) { node ->
            when (val lifecycle = node.lifecycle) {
                is ChildLifecycle.Running -> node.copy(
                    lifecycle = ChildLifecycle.Ended(
                        reason = if (exitCode == 0) ChildEndReason.Exited else ChildEndReason.Crashed,
                        exitCode = exitCode,
                    ),
                    waitingFor = ChildWaitingFor.Nothing,
                )

                is ChildLifecycle.Ended -> node.copy(lifecycle = lifecycle.copy(exitCode = lifecycle.exitCode ?: exitCode))
            }
        }

        // 트리를 먼저 고친 뒤에 알린다. 기다림의 답이 싣는 stillRunning 이 이 하위를 아직 살아 있다고 말하지 않게.
        if (endedByItself) {
            val lifecycle = mutableSessionTree.value.child(childId)?.lifecycle as? ChildLifecycle.Ended ?: return
            tellParent(SessionTreeEvent.SessionEnded(childId, lifecycle.reason, exitCode))
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

        // 메인이 스스로 끝났다(크래시 · 대화 안에서 끝냄). 원칙 17 — 하위도 끝낸다. 트리는 남겨 무엇이
        // 끝났는지 사람이 읽게 한다.
        if (target == SessionTarget.Main) {
            coroutineScope.launch { endChildrenOfMain() }
        }

        // 스스로 끝난 세션의 소켓도 닫는다. 프로세스가 죽으면 그 자식(kyu mcp ask)도 함께 죽으므로
        // 물어올 상대는 이미 없고, 남은 것은 런타임 디렉토리의 파일 하나뿐이다.
        coroutineScope.launch { held.appSocketChannel.close() }
        forgetPermissionsOf(target)

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

        // 하위의 대화가 바뀌면 그 하위가 기다리는 것도 바뀐다 — 패널의 표시와 list_sessions 가 이 값을 본다.
        refreshChildWaitingFor(target)
    }

    /**
     * 하위가 지금 기다리는 것을 다시 읽는다. 대화가 바뀔 때와, 대화 밖의 사실(승인 물음 · 상위에게 한 물음)이 바뀔 때 부른다.
     *
     * 끝난 하위는 그대로 둔다 — 끝나기 직전의 값으로 되돌리면 끝난 하위가 아직 무언가를 기다린다고 말한다.
     */
    private fun refreshChildWaitingFor(target: SessionTarget) {
        if (target !is SessionTarget.Child) {
            return
        }
        val conversation = conversations[target] ?: return
        val approvalPending = askedPermissions.keys.any { it.target == target }
        val parentQuestionPending = target.childId in pendingParentQuestions
        updateChild(target.childId) { node ->
            if (node.lifecycle == ChildLifecycle.Running) {
                node.copy(waitingFor = childWaitingFor(conversation, approvalPending, parentQuestionPending))
            } else {
                node
            }
        }
    }

    /**
     * 끝내기는 프로세스를 기다리는 일이라 화면을 그리는 스레드에서 하지 않는다.
     *
     * 소켓을 프로세스보다 **나중에** 닫는다. 먼저 닫으면 마지막 순간에 관문을 연 도구가 물어볼
     * 자리를 잃고, 그 자식은 이유 없이 거절당한 채 끝난다 — 프로세스가 먼저 끝나면 그 물음도
     * 함께 사라진다.
     */
    private suspend fun endSessionProcess(held: HeldChatSession) = withContext(sessionEntryDispatcher) {
        held.session.endSession()
        held.appSocketChannel.close()
        forgetPermissionsOf(held.target)
    }

    /**
     * 그 세션이 들고 있던 승인 관련 상태를 놓는다 — 답하지 못한 물음과 세션 규칙.
     *
     * 규칙이 여기서 사라지는 것이 "이 세션에서 계속 허용" 의 수명 그 자체다(열린 질문 4 의 v1 결정).
     */
    private fun forgetPermissionsOf(target: SessionTarget) {
        askedPermissions.keys.removeAll { it.target == target }
        toolsAllowedForSession.remove(target)
    }
}

/**
 * 사람에게 보일 관문 하나(6.9) — 어느 하위를, 무엇을 시키려고, 어떤 설정 때문에 묻는가.
 *
 * @param prompt 상위가 그 하위에게 시키려던 일. 사람이 "이 설정으로 무엇이 돌게 되는가" 를 판단할 재료다.
 */
data class ChildSessionGateQuestion(
    val childId: String,
    val prompt: String,
    val gate: ChildSessionGate,
)

/** 관문 물음이 어떻게 끝났는가(6.9). */
private enum class GateAnswer {
    Approved,
    Declined,

    /** 사람이 답하기 전에 물음이 거둬졌다 — 메인이 끝났거나, 그 spawn 의 연결이 끊겼다. */
    Withdrawn,
}

/** 하위 하나를 띄우려 한 결과 — 띄웠거나, 관문에 걸려 아무것도 띄우지 않았다(6.9). */
private sealed interface ChildSessionOpening {

    /** 띄웠다 — 보유할 세션과, 엔진이 배정한 사실. */
    class Opened(val held: HeldChatSession, val assignedChildSession: AssignedChildSession) : ChildSessionOpening

    class GateRequired(val gate: ChildSessionGate) : ChildSessionOpening
}

/**
 * 하위를 띄우지 못한 이유를 상위 모델이 읽을 한 문장으로 옮긴다.
 *
 * 엔진의 거절이면 stderr 가 곧 이유다(cwd 규칙 — 6.8). 그 문구가 모델에게 고칠 것을 말한다.
 */
private fun refusalReasonFor(failure: TerminalSessionFailure): String =
    listOfNotNull(failure.message, failure.guidance.takeIf { it.isNotBlank() }).joinToString(" — ")

/**
 * 상위가 하위에게 넣는 말의 접두(6.5). 하위의 규약 문구가 이 접두를 상위의 말로 읽는다(session_command_child.go).
 */
private const val PARENT_MESSAGE_PREFIX = "[상위 세션] "

/**
 * 상위의 열린 기다림 하나.
 *
 * @param watchedChildIds 이 기다림이 고른 하위들. 이들의 사건만 이 기다림을 깨운다.
 * @param expiry `waitSeconds` 가 다 되면 빈 답으로 닫는 자리. 먼저 답하면 거둔다.
 */
private class PendingAwait(
    val asked: AskedSessionTreeRequest,
    val watchedChildIds: Set<String>,
    val expiry: Job,
)

/**
 * 하위가 상위에게 한 물음 하나 — 그 이름과, 답을 되돌릴 자리.
 *
 * @param askId 상위가 기다림의 사건과 `send_to_session` 의 답에서 이 물음을 가리키는 이름.
 */
private class PendingParentQuestion(val askId: String, val asked: AskedSessionTreeRequest)

/** 어느 세션의 어느 도구 호출에 대한 물음인가. */
private data class AskedPermissionKey(val target: SessionTarget, val toolUseId: String)

/**
 * 진입 실패는 전부 TerminalSessionFailure 로 온다 — 어댑터가 프로세스 경계의 예외를 거기서
 * 옮겨 두기 때문이다. 그 밖의 것이 여기까지 왔다면 이 코드의 결함이므로 삼키지 않고 다시 던진다.
 */
private fun Throwable.asTerminalSessionFailure(): TerminalSessionFailure =
    this as? TerminalSessionFailure ?: throw this
