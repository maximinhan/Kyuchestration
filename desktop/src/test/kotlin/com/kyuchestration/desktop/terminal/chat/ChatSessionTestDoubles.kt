package com.kyuchestration.desktop.terminal.chat

import com.kyuchestration.desktop.diagnostics.DiagnosticLog
import com.kyuchestration.desktop.terminal.AssignedChildSession
import com.kyuchestration.desktop.terminal.ChildSessionCommandAnswer
import com.kyuchestration.desktop.terminal.ChildSessionCommandRequest
import com.kyuchestration.desktop.terminal.ChildSessionCommandSource
import com.kyuchestration.desktop.terminal.PersonOpenedSessionTarget
import com.kyuchestration.desktop.terminal.RecordedChildSessionIds
import com.kyuchestration.desktop.terminal.SessionCommandAnswer
import com.kyuchestration.desktop.terminal.SessionCommandSource
import com.kyuchestration.desktop.terminal.SessionConversationChoice
import com.kyuchestration.desktop.terminal.SessionEntryPlan
import com.kyuchestration.desktop.terminal.SessionTarget
import java.io.IOException
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.json.JsonObject

/*
 * 챗 상태 홀더 시험들이 함께 쓰는 대역들 — 엔진 · claude · 앱 소켓 자리에 서는 것.
 *
 * 하위 세션 시험(ChildSessionStateHolderTest)이 두 번째 사용처로 오면서 ChatSessionStateHolderTest 안에서 꺼냈다.
 */

internal val TEST_WORK_DIR_PATH: Path = Path.of("/home/me/work/WorkDir-featureX")

/**
 * 승인 소켓이 놓이는 자리 — **워크디렉토리 아래가 아니다**(설계 5.4).
 *
 * 짧은 런타임 디렉토리에 두는 것이 이 값의 전부다. 워크디렉토리 경로를 쓰면 사용자가 지은
 * 긴 이름 하나가 107 바이트 한계를 넘겨 승인 기능 전체를 조용히 죽인다(실측 3.7).
 */
internal val TEST_APPROVAL_SOCKET_DIRECTORY: Path = Path.of("/run/user/1000/kyuchestration")

/** 엔진이 답한 argv. 앱이 이 목록을 짓지 않는다는 것이 이 상수의 뜻이다. */
internal val TEST_ENGINE_ANSWERED_COMMAND = listOf("claude", "-p", "--input-format", "stream-json")

internal fun TestScope.stateHolder(
    opener: ChatSessionOpener,
    sessionCommandSource: SessionCommandSource = RecordingSessionCommandSource(),
    diagnosticLog: DiagnosticLog = DiagnosticLog.Discarding,
    permissionChannels: MutableList<FakeAppSocketChannel> = mutableListOf(),
    childSessionCommandSource: ChildSessionCommandSource = RecordingChildSessionCommandSource(),
    recordedChildSessionIds: RecordedChildSessionIds = RecordedChildSessionIds { emptySet() },
) = ChatSessionStateHolder(
    sessionCommandSource = sessionCommandSource,
    childSessionCommandSource = childSessionCommandSource,
    recordedChildSessionIds = recordedChildSessionIds,
    chatSessionOpener = opener,
    // 이 시험의 관심 밖이다. 자격 증명이 실리는지는 SessionEntryPlanTest 가 본다.
    claudeAuthToken = { null },
    coroutineScope = backgroundScope,
    diagnosticLog = diagnosticLog,
    baseEnvironment = emptyMap(),
    appSocketChannelOpener = {
        FakeAppSocketChannel(
            socketPath = TEST_APPROVAL_SOCKET_DIRECTORY.resolve("s${permissionChannels.size}.sock"),
            answeringScope = backgroundScope,
        ).also(permissionChannels::add)
    },
    // 앱에서는 엔진에게 묻고 프로세스를 띄우는 동안 화면이 멎지 않도록 IO 로 나간다.
    sessionEntryDispatcher = StandardTestDispatcher(testScheduler),
    // stdin 쓰기도 시험의 시계 위에 올린다. 앱에서는 한 번에 하나씩 도는 자리이고, 여기서도
    // 한 줄씩 차례로 도는 것은 같다 — 다른 것은 그 차례를 시험이 진행시킨다는 것뿐이다.
    standardInputDispatcher = StandardTestDispatcher(testScheduler),
    clock = TEST_CLOCK,
)

/** 이 시험들의 시계. 하위가 뜬 시각 · 끝난 시각이 이 값으로 적힌다. */
internal val TEST_CLOCK: Clock = Clock.fixed(Instant.parse("2026-10-20T10:01:12Z"), ZoneOffset.UTC)

internal class RecordingSessionCommandSource : SessionCommandSource {

    val askedTargets = mutableListOf<PersonOpenedSessionTarget>()

    /** 다음에 열 세션이 이어갈 대화. 진짜 어댑터에서는 엔진의 답이 이 값을 정한다. */
    var resumedConversationId: String? = null

    private var respond: () -> SessionCommandAnswer = {
        SessionCommandAnswer(
            command = TEST_ENGINE_ANSWERED_COMMAND,
            workingDirectory = TEST_WORK_DIR_PATH,
            environmentToAdd = emptyMap(),
            resumedConversationId = resumedConversationId,
        )
    }

    fun respondWith(respond: () -> SessionCommandAnswer) {
        this.respond = respond
    }

    /** 엔진이 물음과 함께 받은 승인 소켓의 자리. 브리지 없이 물었으면 null 이다. */
    val askedApprovalSocketPaths = mutableListOf<Path?>()

    override fun sessionCommandFor(
        workDirPath: Path,
        target: PersonOpenedSessionTarget,
        conversationChoice: SessionConversationChoice,
        appSocketPath: Path?,
    ): SessionCommandAnswer {
        askedTargets.add(target)
        askedApprovalSocketPaths.add(appSocketPath)
        return respond()
    }
}

/**
 * 승인 통로 노릇 — 시험이 물음을 직접 밀어 넣고, 앱이 되돌린 결정을 그대로 들고 있는다.
 *
 * 진짜 소켓을 세우지 않는다. 여기서 갈리는 것은 "물음이 카드가 되는가 · 세션 규칙이 그것을
 * 자동으로 답하는가 · 세션이 끝날 때 통로가 닫히는가" 이고, 그 답에 소켓이 필요하지 않다 —
 * 소켓 자체의 왕복은 진짜 소켓으로 따로 잰다(AppSocketTest).
 */
internal class FakeAppSocketChannel(
    override val socketPath: Path,
    /** 되돌아온 결정을 받아 적는 코루틴이 사는 자리. 진짜 통로에서는 그 자리가 소켓 연결이다. */
    private val answeringScope: CoroutineScope,
) : AppSocketChannel {

    private val incoming = Channel<AskedPermission>(Channel.UNLIMITED)

    var closeCount = 0
        private set

    override val permissionRequests: Flow<AskedPermission> = incoming.receiveAsFlow()

    private val incomingSessionTreeRequests = Channel<AskedSessionTreeRequest>(Channel.UNLIMITED)

    override val sessionTreeRequests: Flow<AskedSessionTreeRequest> = incomingSessionTreeRequests.receiveAsFlow()

    /** 그 도구 호출에 대한 승인을 물어 온다. 되돌아온 결정을 들여다볼 수 있는 자리를 돌려준다. */
    fun ask(toolName: String, toolUseId: String, input: JsonObject): AskedForTest {
        val asked = AskedPermission(PermissionRequest(toolName = toolName, input = input, toolUseId = toolUseId))
        val answering = answeringScope.async { asked.awaitDecision() }
        incoming.trySend(asked)
        return AskedForTest(asked, answering)
    }

    /** 이 세션의 kyu mcp serve 노릇 — 세션 트리 요청 하나를 보내고, 앱이 되돌린 답을 들여다볼 자리를 돌려준다. */
    fun askSessionTree(request: SessionTreeRequest): AskedSessionTreeForTest {
        val asked = AskedSessionTreeRequest(request)
        val answering = answeringScope.async { asked.awaitAnswer() }
        incomingSessionTreeRequests.trySend(asked)
        return AskedSessionTreeForTest(asked, answering)
    }

    override suspend fun close() {
        closeCount++
        incoming.close()
        incomingSessionTreeRequests.close()
    }
}

/** 물어 둔 승인 하나 — 그 물음과, 지금까지 되돌아온 결정. */
internal class AskedForTest(private val asked: AskedPermission, private val answering: Deferred<PermissionDecision>) {

    val request: PermissionRequest get() = asked.request

    /** 되돌아온 결정. 아직 답하지 않았으면 null 이다 — 시험이 기다리지 않고 들여다본다. */
    fun decisionSoFar(): PermissionDecision? = if (answering.isCompleted) answering.getCompleted() else null

    /** 물어 온 쪽이 답을 받기 전에 연결을 닫는다 — 그 도구 호출이 중단된 자리다(PR 68). */
    fun closeConnection() = asked.markConnectionClosed()
}

internal class RecordingChatSessionOpener : ChatSessionOpener {

    val openedPlans = mutableListOf<SessionEntryPlan>()
    /** 띄운 차례대로. 하위 세션 시험은 메인 다음 자리부터 하위를 찾는다. */
    val openedSessions = mutableListOf<FakeChatSession>()

    val lastSession: FakeChatSession get() = openedSessions.last()

    /** 끝난 차례대로. 수명 시험이 "하위가 상위보다 먼저" 를 이것으로 본다(6.13). */
    val endedSessions = mutableListOf<FakeChatSession>()

    override suspend fun openChatSession(plan: SessionEntryPlan): OpenedChatSession {
        openedPlans.add(plan)
        return FakeChatSession(onEnded = endedSessions::add).also(openedSessions::add)
    }
}

/**
 * `claude` 자리에 놓는 세션 하나. 무엇이 언제 오는지를 시험이 정한다.
 *
 * 한계 없는 채널인 것이 진짜 어댑터와 같다(ProcessChatSession) — 받는 쪽이 아직 모으기
 * 시작하지 않았어도 [emit] 한 것이 잃히지 않는다.
 */
internal class FakeChatSession(private val onEnded: (FakeChatSession) -> Unit = {}) : OpenedChatSession {

    private val incoming = Channel<ChatSessionEvent>(Channel.UNLIMITED)

    val sentMessages = mutableListOf<OutgoingUserMessage>()
    private var writesFail = false
    var endSessionCount = 0
        private set
    var interruptCount = 0
        private set

    override val events: Flow<ChatSessionEvent> = incoming.receiveAsFlow()

    fun emit(event: ChatSessionEvent) {
        incoming.trySend(event)
    }

    /** 프로세스가 이미 끝나 stdin 이 닫힌 자리를 만든다. */
    fun failWrites() {
        writesFail = true
    }

    override suspend fun sendUserMessage(message: OutgoingUserMessage) {
        if (writesFail) {
            throw IOException("Stream closed")
        }
        sentMessages.add(message)
    }

    override suspend fun interruptCurrentTurn() {
        interruptCount++
    }

    override suspend fun endSession() {
        endSessionCount++
        onEnded(this)
        incoming.close()
    }
}

/** 보내 둔 세션 트리 요청 하나 — 지금까지 되돌아온 답. */
internal class AskedSessionTreeForTest(
    private val asked: AskedSessionTreeRequest,
    private val answering: Deferred<SessionTreeAnswer>,
) {

    /** 되돌아온 답. 아직 답하지 않았으면 null 이다. */
    fun answerSoFar(): SessionTreeAnswer? = if (answering.isCompleted) answering.getCompleted() else null

    /**
     * 엔진이 답을 받기 전에 연결을 닫는다 — 상위의 도구 호출이 중단되어 `kyu mcp serve` 가 취소를 받은 자리다
     * (session-tree 3.2 · PR 68).
     */
    fun closeConnection() = asked.markConnectionClosed()
}

/**
 * 엔진의 `--child` 자리. 기본은 상위가 고른 자리 그대로 띄울 수 있다고 답한다.
 *
 * 관문 · 거절 같은 다른 답은 시험이 [respondWith] 로 정한다.
 */
internal class RecordingChildSessionCommandSource : ChildSessionCommandSource {

    val askedRequests = mutableListOf<ChildSessionCommandRequest>()
    val askedAppSocketPaths = mutableListOf<Path>()

    private var respond: (ChildSessionCommandRequest) -> ChildSessionCommandAnswer = ::readyAnswerFor

    fun respondWith(respond: (ChildSessionCommandRequest) -> ChildSessionCommandAnswer) {
        this.respond = respond
    }

    override fun childSessionCommandFor(
        workDirPath: Path,
        request: ChildSessionCommandRequest,
        appSocketPath: Path,
    ): ChildSessionCommandAnswer {
        askedRequests.add(request)
        askedAppSocketPaths.add(appSocketPath)
        return respond(request)
    }

    companion object {

        /** 엔진이 그 자리를 받아들이고 대화를 배정한 답. */
        fun readyAnswerFor(request: ChildSessionCommandRequest): ChildSessionCommandAnswer.Ready {
            val relativeCwd = request.requestedCwd ?: "."
            return ChildSessionCommandAnswer.Ready(
                sessionCommandAnswer = SessionCommandAnswer(
                    command = TEST_ENGINE_ANSWERED_COMMAND,
                    workingDirectory = TEST_WORK_DIR_PATH.resolve(relativeCwd).normalize(),
                    environmentToAdd = emptyMap(),
                    resumedConversationId = null,
                ),
                assignedChildSession = AssignedChildSession(
                    childId = request.childId,
                    relativeCwd = relativeCwd,
                    title = request.title ?: relativeCwd,
                    conversationId = "conversation-${request.childId}",
                ),
            )
        }
    }
}

/** 상위가 하위 하나를 띄우는 요청. 시킨 일은 시험의 관심 밖이다. */
internal fun spawnRequest(cwd: String, prompt: String = "일해") =
    SessionTreeRequest.SpawnSession(prompt = prompt, requestedCwd = cwd, createDirectory = false, title = null)

/**
 * 메인 하나와 그 아래 하위 [childCount] 개를 띄워 둔다. 화면은 메인에 있다. 하위의 cwd 는 `proj-0` 부터다.
 *
 * 수명 시험이 쓰던 것을 말이 가는 길 시험(ChildSessionDialogueStateHolderTest)이 두 번째로 쓰면서 여기로 꺼냈다.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal fun TestScope.mainWithChildrenTestBed(childCount: Int): MainWithChildrenTestBed {
    val opener = RecordingChatSessionOpener()
    val childSource = RecordingChildSessionCommandSource()
    val channels = mutableListOf<FakeAppSocketChannel>()
    val holder = stateHolder(opener = opener, permissionChannels = channels, childSessionCommandSource = childSource)
    holder.enterSession(TEST_WORK_DIR_PATH, SessionTarget.Main, SessionConversationChoice.ContinueRecordedConversation)
    runCurrent()
    repeat(childCount) { index ->
        channels.first().askSessionTree(spawnRequest("proj-$index"))
        runCurrent()
    }
    return MainWithChildrenTestBed(holder, opener, childSource, channels)
}

internal class MainWithChildrenTestBed(
    val holder: ChatSessionStateHolder,
    val opener: RecordingChatSessionOpener,
    val childSource: RecordingChildSessionCommandSource,
    /** 연 차례대로 — 맨 앞이 메인의 소켓이고, n 번째가 n 번째 하위의 것이다. */
    val channels: List<FakeAppSocketChannel>,
) {
    val main: FakeChatSession get() = opener.openedSessions.first()
    val mainChannel: FakeAppSocketChannel get() = channels.first()

    /** n 번째로 띄운 하위의 세션(1 부터). */
    fun child(ordinal: Int): FakeChatSession = opener.openedSessions[ordinal]

    /** n 번째로 띄운 하위의 소켓(1 부터) — 하위의 kyu mcp serve 가 묻는 자리다. */
    fun childChannel(ordinal: Int): FakeAppSocketChannel = channels[ordinal]
}

/**
 * 메인의 턴 하나를 연다 — 사람이 메인에게 말하고 그 말이 되돌아왔다.
 *
 * 상위가 `await_session` 을 부르는 것은 언제나 자기 턴 안이다. 쉬는 상위에게는 사건이 사용자 메시지로 가므로(6.6),
 * 도구 답의 갈래를 보는 시험은 이 자리에서 시작한다.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal fun TestScope.startMainTurn(bed: MainWithChildrenTestBed) {
    check(bed.holder.state.value.target == SessionTarget.Main) { "화면이 메인에 있어야 사람이 메인에게 말한다" }
    bed.holder.sendUserMessage("하위들에게 일을 시켜")
    runCurrent()
    bed.main.echoLastSentMessage()
    runCurrent()
}

/** 마지막으로 받은 말이 되돌아온다 — 그 말의 턴이 시작한다. */
internal fun FakeChatSession.echoLastSentMessage() {
    val message = sentMessages.last()
    emit(ChatSessionEvent.UserMessageEchoed(text = message.text, parentToolUseId = null, messageUuid = message.uuid))
}

/** 마지막으로 받은 말로 턴 하나를 돌고 끝낸다. */
internal fun FakeChatSession.finishTurnOnLastSentMessage(result: String, outcome: TurnOutcome = TurnOutcome.Completed) {
    echoLastSentMessage()
    emit(turnFinishedWith(result, outcome))
}

internal fun turnFinishedWith(result: String?, outcome: TurnOutcome = TurnOutcome.Completed) = ChatSessionEvent.TurnFinished(
    outcome = outcome,
    costUsd = 0.25,
    usage = TurnUsage(inputTokens = 3, outputTokens = 41, cacheReadInputTokens = 0, cacheCreationInputTokens = 0),
    permissionDenials = emptyList(),
    durationMillis = 1_200,
    resultText = result,
)
