package com.kyuchestration.desktop.terminal

import com.jediterm.terminal.TtyConnector
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class EmbeddedTerminalStateHolderTest {

    @Test
    fun `아무 터미널도 열지 않은 채로 시작한다`() = runTest {
        val holder = stateHolder(RecordingSessionTerminalOpener())

        assertEquals(EmbeddedTerminalState.NoTerminalOpen, holder.state.value)
        assertEquals(emptyList(), holder.heldSessions.value.map { it.target })
    }

    @Test
    fun `진입을 요청하면 세션이 뜰 때까지 진행 중으로 둔다`() = runTest {
        val holder = stateHolder(RecordingSessionTerminalOpener())

        holder.enterSessionContinuingConversation(SessionTarget.Repo("proj-a"))

        val running = assertIs<EmbeddedTerminalState.SessionEntryRunning>(holder.state.value)
        assertEquals(SessionTarget.Repo("proj-a"), running.target)
    }

    @Test
    fun `세션이 뜨면 보유하고 화면에 올린다`() = runTest {
        val opener = RecordingSessionTerminalOpener()
        val holder = stateHolder(opener)

        holder.enterSessionContinuingConversation(SessionTarget.Main)
        runCurrent()

        val onScreen = assertIs<EmbeddedTerminalState.SessionOnScreen>(holder.state.value)
        assertEquals(SessionTarget.Main, onScreen.target)
        assertSame<TtyConnector>(opener.openedConnectors.single(), onScreen.ttyConnector)
        assertEquals(listOf(SessionTarget.Main), holder.heldSessions.value.map { it.target })
        assertEquals(
            listOf(
                SessionEntryRequest(
                    WORK_DIR_PATH,
                    SessionTarget.Main,
                    SessionConversationChoice.ContinueRecordedConversation,
                ),
            ),
            opener.requests,
        )
    }

    @Test
    fun `띄우지 못하면 그 이유를 들고 있는다`() = runTest {
        val opener = RecordingSessionTerminalOpener().apply {
            respondWith { throw TerminalSessionFailure.KyuExecutableNotFound() }
        }
        val holder = stateHolder(opener)

        holder.enterSessionContinuingConversation(SessionTarget.Repo("proj-a"))
        runCurrent()

        val failed = assertIs<EmbeddedTerminalState.SessionEntryFailed>(holder.state.value)
        assertEquals(SessionTarget.Repo("proj-a"), failed.target)
        assertIs<TerminalSessionFailure.KyuExecutableNotFound>(failed.failure)
        assertEquals(emptyList(), holder.heldSessions.value.map { it.target })
    }

    @Test
    fun `다른 카드로 옮겨도 앞 세션은 끝나지 않는다`() = runTest {
        val opener = RecordingSessionTerminalOpener()
        val holder = stateHolder(opener)
        holder.enterSessionContinuingConversation(SessionTarget.Repo("proj-a"))
        runCurrent()
        val firstConnector = opener.openedConnectors.single()

        holder.enterSessionContinuingConversation(SessionTarget.Repo("proj-b"))
        runCurrent()

        // 이 전환의 존재 이유가 이 줄이다. 옮길 때마다 끝내면 사용자는 카드를 한 번 잘못 눌러
        // 하던 작업을 잃고, 그것을 tmux 가 막아 주던 시절은 끝났다(설계 문서 8 절 3 번).
        assertEquals(0, firstConnector.closeCount)
        assertEquals(
            listOf(SessionTarget.Repo("proj-a"), SessionTarget.Repo("proj-b")),
            holder.heldSessions.value.map { it.target },
        )
        assertEquals(SessionTarget.Repo("proj-b"), holder.state.value.target)
    }

    @Test
    fun `보유 중인 카드로 돌아가면 다시 띄우지 않고 그 통로를 그대로 보여준다`() = runTest {
        val opener = RecordingSessionTerminalOpener()
        val holder = stateHolder(opener)
        holder.enterSessionContinuingConversation(SessionTarget.Repo("proj-a"))
        runCurrent()
        val firstConnector = opener.openedConnectors.single()
        holder.enterSessionContinuingConversation(SessionTarget.Repo("proj-b"))
        runCurrent()

        holder.enterSessionContinuingConversation(SessionTarget.Repo("proj-a"))
        runCurrent()

        // 다시 띄우면 하던 작업이 끝나고 새 대화가 그 자리에 열린다. 돌아온 사람이 바라는 것은
        // 두고 온 화면이고, 그것을 쥐고 있는 것이 이 통로에 붙은 위젯이다.
        assertEquals(2, opener.requests.size)
        assertSame(firstConnector, assertIs<EmbeddedTerminalState.SessionOnScreen>(holder.state.value).ttyConnector)
    }

    @Test
    fun `보고 있는 세션만 끝낸다`() = runTest {
        val opener = RecordingSessionTerminalOpener()
        val holder = stateHolder(opener)
        holder.enterSessionContinuingConversation(SessionTarget.Repo("proj-a"))
        runCurrent()
        holder.enterSessionContinuingConversation(SessionTarget.Repo("proj-b"))
        runCurrent()
        val (firstConnector, secondConnector) = opener.openedConnectors

        holder.endSession()

        assertEquals(0, firstConnector.closeCount, "보고 있지 않던 세션은 그대로 돌아야 한다")
        assertEquals(1, secondConnector.closeCount, "보고 있던 세션은 끝나야 한다")
        assertEquals(listOf(SessionTarget.Repo("proj-a")), holder.heldSessions.value.map { it.target })
        assertEquals(EmbeddedTerminalState.NoTerminalOpen, holder.state.value)
    }

    @Test
    fun `전부 끝내면 보유한 세션이 하나도 남지 않는다`() = runTest {
        val opener = RecordingSessionTerminalOpener()
        val holder = stateHolder(opener)
        holder.enterSessionContinuingConversation(SessionTarget.Repo("proj-a"))
        runCurrent()
        holder.enterSessionContinuingConversation(SessionTarget.Main)
        runCurrent()

        holder.endAllSessions()

        // 워크디렉토리를 바꾸거나 앱을 끌 때다. 남기면 kyu list 에도 앱에도 보이지 않는
        // 프로세스가 되고, 찾을 수 있는 자리가 ps 뿐이다(설계 문서 8 절 1 번).
        assertEquals(listOf(1, 1), opener.openedConnectors.map { it.closeCount })
        assertEquals(emptyList(), holder.heldSessions.value.map { it.target })
        assertEquals(EmbeddedTerminalState.NoTerminalOpen, holder.state.value)
    }

    @Test
    fun `세션이 스스로 끝나면 보유에서 빠지고 마지막 화면은 남는다`() = runTest {
        val opener = RecordingSessionTerminalOpener()
        val holder = stateHolder(opener)
        holder.enterSessionContinuingConversation(SessionTarget.Repo("proj-a"))
        runCurrent()

        // 사용자가 세션 안에서 /exit 했거나, 이어갈 대화가 없어 claude 가 곧바로 죽었다.
        opener.lastSessionExit.complete(1)
        runCurrent()

        assertEquals(emptyList(), holder.heldSessions.value.map { it.target }, "돌지 않는 세션을 카드가 RUNNING 이라 말하면 안 된다")
        val ended = assertIs<EmbeddedTerminalState.SessionEndedOnScreen>(holder.state.value)
        assertEquals(1, ended.exitCode)
        assertSame(opener.openedConnectors.single(), ended.ttyConnector)
    }

    @Test
    fun `끝난 세션의 카드를 다시 누르면 새로 띄운다`() = runTest {
        val opener = RecordingSessionTerminalOpener()
        val holder = stateHolder(opener)
        holder.enterSessionContinuingConversation(SessionTarget.Repo("proj-a"))
        runCurrent()
        opener.lastSessionExit.complete(0)
        runCurrent()

        holder.enterSessionContinuingConversation(SessionTarget.Repo("proj-a"))
        runCurrent()

        // 대화는 엔진이 --resume 으로 이어 준다 — 앱이 하는 일은 다시 묻는 것뿐이다.
        assertEquals(2, opener.requests.size)
        assertEquals(listOf(SessionTarget.Repo("proj-a")), holder.heldSessions.value.map { it.target })
    }

    @Test
    fun `우리가 끝낸 세션은 끝났다는 화면을 남기지 않는다`() = runTest {
        val opener = RecordingSessionTerminalOpener()
        val holder = stateHolder(opener)
        holder.enterSessionContinuingConversation(SessionTarget.Repo("proj-a"))
        runCurrent()

        holder.endSession()
        // 진짜 PTY 라면 끝내는 순간 이 미래도 완료된다.
        opener.lastSessionExit.complete(143)
        runCurrent()

        // 치우려고 누른 자리에 "끝났습니다" 가 다시 떠오르면 터미널이 닫히지 않는 것처럼 보인다.
        assertEquals(EmbeddedTerminalState.NoTerminalOpen, holder.state.value)
    }

    @Test
    fun `전환이 늦게 끝나 뒤늦게 도착한 세션은 보유하지 않고 곧바로 끝낸다`() = runTest {
        val opener = RecordingSessionTerminalOpener()
        val holder = stateHolder(opener)

        // 첫 진입이 아직 끝나기 전에 다른 카드를 누른 상황이다.
        holder.enterSessionContinuingConversation(SessionTarget.Repo("proj-a"))
        holder.enterSessionContinuingConversation(SessionTarget.Repo("proj-b"))
        runCurrent()

        // 보유해 두면 아무 위젯도 그 통로를 읽지 않는 세션이 하나 생기고, PTY 버퍼가 차는 순간
        // 그 안의 claude 가 쓰기에서 멎는다.
        assertEquals(1, opener.openedConnectors.first().closeCount)
        assertEquals(listOf(SessionTarget.Repo("proj-b")), holder.heldSessions.value.map { it.target })
        assertSame(
            opener.openedConnectors.last(),
            assertIs<EmbeddedTerminalState.SessionOnScreen>(holder.state.value).ttyConnector,
        )
    }

    @Test
    fun `끝낸 뒤에 도착한 세션도 곧바로 끝낸다`() = runTest {
        val opener = RecordingSessionTerminalOpener()
        val holder = stateHolder(opener)

        holder.enterSessionContinuingConversation(SessionTarget.Repo("proj-a"))
        holder.endSession()
        runCurrent()

        assertEquals(1, opener.openedConnectors.single().closeCount)
        assertEquals(emptyList(), holder.heldSessions.value.map { it.target })
        assertEquals(EmbeddedTerminalState.NoTerminalOpen, holder.state.value)
    }

    @Test
    fun `새로 시작을 고르면 그 뜻이 세션을 여는 자리까지 간다`() = runTest {
        val opener = RecordingSessionTerminalOpener()
        val holder = stateHolder(opener)

        holder.enterSession(
            WORK_DIR_PATH,
            SessionTarget.Repo("proj-a"),
            SessionConversationChoice.StartNewConversation,
        )
        runCurrent()

        // 앱은 대화 ID 를 들고 있지 않다. 고르는 것은 갈래 하나뿐이고, 적혀 있던 대화를 버리고
        // 새 ID 를 만드는 일은 엔진이 한다(설계 원칙 11).
        assertEquals(
            listOf(SessionConversationChoice.StartNewConversation),
            opener.requests.map { it.conversationChoice },
        )
    }

    @Test
    fun `보유 중인 대상에는 새로 시작이 돌고 있는 세션을 끝내지 않는다`() = runTest {
        val opener = RecordingSessionTerminalOpener()
        val holder = stateHolder(opener)
        holder.enterSessionContinuingConversation(SessionTarget.Repo("proj-a"))
        runCurrent()
        val heldConnector = opener.openedConnectors.single()

        holder.enterSession(
            WORK_DIR_PATH,
            SessionTarget.Repo("proj-a"),
            SessionConversationChoice.StartNewConversation,
        )
        runCurrent()

        // 화면은 보유 중인 대상에 새로 시작하는 길을 내지 않는다(카드의 버튼은 앱 세션이 없을 때만
        // 뜬다). 그래도 여기서 세션을 갈아치우지 않는 이유는, 그렇게 두면 화면 쪽 조건 하나가
        // 바뀌는 날 사용자가 돌고 있는 작업을 잃기 때문이다.
        assertEquals(0, heldConnector.closeCount)
        assertEquals(1, opener.requests.size)
        assertSame(heldConnector, assertIs<EmbeddedTerminalState.SessionOnScreen>(holder.state.value).ttyConnector)
    }

    @Test
    fun `이어가기로 연 세션이 실패한 코드로 끝나면 화면이 그 사실을 들고 있는다`() = runTest {
        val opener = RecordingSessionTerminalOpener().apply { resumedConversationId = RECORDED_CONVERSATION_ID }
        val holder = stateHolder(opener)
        holder.enterSessionContinuingConversation(SessionTarget.Repo("proj-a"))
        runCurrent()

        // 전사가 사라진 대화를 이어가려 하면 claude 는 즉시 종료 코드 1 로 끝난다(설계 문서 5.5.1).
        opener.lastSessionExit.complete(1)
        runCurrent()

        val ended = assertIs<EmbeddedTerminalState.SessionEndedOnScreen>(holder.state.value)
        assertEquals(RECORDED_CONVERSATION_ID, ended.resumedConversationId)
        assertTrue(ended.resumeFailureSuspected, "이 사실이 없으면 화면은 사용자의 /exit 과 구분하지 못한다")
    }

    @Test
    fun `이어가기로 연 세션이 정상으로 끝난 것은 실패가 아니다`() = runTest {
        val opener = RecordingSessionTerminalOpener().apply { resumedConversationId = RECORDED_CONVERSATION_ID }
        val holder = stateHolder(opener)
        holder.enterSessionContinuingConversation(SessionTarget.Repo("proj-a"))
        runCurrent()

        // 사용자가 세션 안에서 /exit 한 자리다.
        opener.lastSessionExit.complete(0)
        runCurrent()

        val ended = assertIs<EmbeddedTerminalState.SessionEndedOnScreen>(holder.state.value)
        assertFalse(ended.resumeFailureSuspected, "일하고 나온 사람에게 실패를 알리면 안 된다")
    }

    @Test
    fun `새 대화로 연 세션이 끝난 것은 이어가기 실패가 아니다`() = runTest {
        val opener = RecordingSessionTerminalOpener()
        val holder = stateHolder(opener)
        holder.enterSessionContinuingConversation(SessionTarget.Repo("proj-a"))
        runCurrent()

        opener.lastSessionExit.complete(1)
        runCurrent()

        // 이어간 적이 없으므로 이어가기가 실패했을 리 없다. 여기서 실패라고 말하면 화면이
        // 일어나지 않은 일을 알리고, 사용자는 방금 연 대화를 또 버리게 된다.
        val ended = assertIs<EmbeddedTerminalState.SessionEndedOnScreen>(holder.state.value)
        assertNull(ended.resumedConversationId)
        assertFalse(ended.resumeFailureSuspected)
    }

    /**
     * 카드를 그냥 누른 자리. 이 시험 대부분이 재는 것이 그 흐름이라 갈래를 매번 적지 않는다 —
     * 새로 시작을 재는 시험만 그것을 직접 적는다.
     */
    private fun EmbeddedTerminalStateHolder.enterSessionContinuingConversation(target: SessionTarget) =
        enterSession(WORK_DIR_PATH, target, SessionConversationChoice.ContinueRecordedConversation)

    private fun TestScope.stateHolder(opener: SessionTerminalOpener) = EmbeddedTerminalStateHolder(
        sessionTerminalOpener = opener,
        coroutineScope = backgroundScope,
        // 앱에서는 PTY 를 여는 동안 화면이 멎지 않도록 IO 디스패처로 나간다.
        sessionEntryDispatcher = StandardTestDispatcher(testScheduler),
    )

    private class RecordingSessionTerminalOpener : SessionTerminalOpener {

        val requests = mutableListOf<SessionEntryRequest>()
        val openedConnectors = mutableListOf<FakeTtyConnector>()
        val sessionExits = mutableListOf<CompletableFuture<Int>>()

        /** 세션이 스스로 끝나는 순간을 시험이 정한다. 진짜 PTY 라면 자식 프로세스가 정한다. */
        val lastSessionExit: CompletableFuture<Int> get() = sessionExits.last()

        /** 다음에 열 세션이 이어갈 대화. 진짜 어댑터에서는 엔진의 답이 이 값을 정한다. */
        var resumedConversationId: String? = null

        private var respond: () -> OpenedSessionTerminal = {
            OpenedSessionTerminal(
                ttyConnector = FakeTtyConnector().also(openedConnectors::add),
                sessionExit = CompletableFuture<Int>().also(sessionExits::add),
                resumedConversationId = resumedConversationId,
            )
        }

        fun respondWith(respond: () -> OpenedSessionTerminal) {
            this.respond = respond
        }

        override fun openSessionIn(
            workDirPath: Path,
            target: SessionTarget,
            conversationChoice: SessionConversationChoice,
        ): OpenedSessionTerminal {
            requests.add(SessionEntryRequest(workDirPath, target, conversationChoice))
            return respond()
        }
    }

    private data class SessionEntryRequest(
        val workDirPath: Path,
        val target: SessionTarget,
        val conversationChoice: SessionConversationChoice,
    )

    private companion object {
        val WORK_DIR_PATH: Path = Path.of("/home/me/work/WorkDir-featureX")

        /** 엔진이 "이 대화를 이어갔다" 고 답한 값. 앱은 이 값을 만들지도 해석하지도 않는다. */
        const val RECORDED_CONVERSATION_ID = "211f6974-88a8-4453-9248-a02b0d6febae"
    }
}
