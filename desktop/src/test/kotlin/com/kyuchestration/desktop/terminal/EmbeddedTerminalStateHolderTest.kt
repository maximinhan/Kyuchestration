package com.kyuchestration.desktop.terminal

import com.jediterm.terminal.TtyConnector
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
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

        holder.enterSession(WORK_DIR_PATH, SessionTarget.Repo("proj-a"))

        val running = assertIs<EmbeddedTerminalState.SessionEntryRunning>(holder.state.value)
        assertEquals(SessionTarget.Repo("proj-a"), running.target)
    }

    @Test
    fun `세션이 뜨면 보유하고 화면에 올린다`() = runTest {
        val opener = RecordingSessionTerminalOpener()
        val holder = stateHolder(opener)

        holder.enterSession(WORK_DIR_PATH, SessionTarget.Main)
        runCurrent()

        val onScreen = assertIs<EmbeddedTerminalState.SessionOnScreen>(holder.state.value)
        assertEquals(SessionTarget.Main, onScreen.target)
        assertSame<TtyConnector>(opener.openedConnectors.single(), onScreen.ttyConnector)
        assertEquals(listOf(SessionTarget.Main), holder.heldSessions.value.map { it.target })
        assertEquals(listOf<Pair<Path, SessionTarget>>(WORK_DIR_PATH to SessionTarget.Main), opener.requests)
    }

    @Test
    fun `띄우지 못하면 그 이유를 들고 있는다`() = runTest {
        val opener = RecordingSessionTerminalOpener().apply {
            respondWith { throw TerminalSessionFailure.KyuExecutableNotFound() }
        }
        val holder = stateHolder(opener)

        holder.enterSession(WORK_DIR_PATH, SessionTarget.Repo("proj-a"))
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
        holder.enterSession(WORK_DIR_PATH, SessionTarget.Repo("proj-a"))
        runCurrent()
        val firstConnector = opener.openedConnectors.single()

        holder.enterSession(WORK_DIR_PATH, SessionTarget.Repo("proj-b"))
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
        holder.enterSession(WORK_DIR_PATH, SessionTarget.Repo("proj-a"))
        runCurrent()
        val firstConnector = opener.openedConnectors.single()
        holder.enterSession(WORK_DIR_PATH, SessionTarget.Repo("proj-b"))
        runCurrent()

        holder.enterSession(WORK_DIR_PATH, SessionTarget.Repo("proj-a"))
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
        holder.enterSession(WORK_DIR_PATH, SessionTarget.Repo("proj-a"))
        runCurrent()
        holder.enterSession(WORK_DIR_PATH, SessionTarget.Repo("proj-b"))
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
        holder.enterSession(WORK_DIR_PATH, SessionTarget.Repo("proj-a"))
        runCurrent()
        holder.enterSession(WORK_DIR_PATH, SessionTarget.Main)
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
        holder.enterSession(WORK_DIR_PATH, SessionTarget.Repo("proj-a"))
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
        holder.enterSession(WORK_DIR_PATH, SessionTarget.Repo("proj-a"))
        runCurrent()
        opener.lastSessionExit.complete(0)
        runCurrent()

        holder.enterSession(WORK_DIR_PATH, SessionTarget.Repo("proj-a"))
        runCurrent()

        // 대화는 엔진이 --resume 으로 이어 준다 — 앱이 하는 일은 다시 묻는 것뿐이다.
        assertEquals(2, opener.requests.size)
        assertEquals(listOf(SessionTarget.Repo("proj-a")), holder.heldSessions.value.map { it.target })
    }

    @Test
    fun `우리가 끝낸 세션은 끝났다는 화면을 남기지 않는다`() = runTest {
        val opener = RecordingSessionTerminalOpener()
        val holder = stateHolder(opener)
        holder.enterSession(WORK_DIR_PATH, SessionTarget.Repo("proj-a"))
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
        holder.enterSession(WORK_DIR_PATH, SessionTarget.Repo("proj-a"))
        holder.enterSession(WORK_DIR_PATH, SessionTarget.Repo("proj-b"))
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

        holder.enterSession(WORK_DIR_PATH, SessionTarget.Repo("proj-a"))
        holder.endSession()
        runCurrent()

        assertEquals(1, opener.openedConnectors.single().closeCount)
        assertEquals(emptyList(), holder.heldSessions.value.map { it.target })
        assertEquals(EmbeddedTerminalState.NoTerminalOpen, holder.state.value)
    }

    private fun TestScope.stateHolder(opener: SessionTerminalOpener) = EmbeddedTerminalStateHolder(
        sessionTerminalOpener = opener,
        coroutineScope = backgroundScope,
        // 앱에서는 PTY 를 여는 동안 화면이 멎지 않도록 IO 디스패처로 나간다.
        sessionEntryDispatcher = StandardTestDispatcher(testScheduler),
    )

    private class RecordingSessionTerminalOpener : SessionTerminalOpener {

        val requests = mutableListOf<Pair<Path, SessionTarget>>()
        val openedConnectors = mutableListOf<FakeTtyConnector>()
        val sessionExits = mutableListOf<CompletableFuture<Int>>()

        /** 세션이 스스로 끝나는 순간을 시험이 정한다. 진짜 PTY 라면 자식 프로세스가 정한다. */
        val lastSessionExit: CompletableFuture<Int> get() = sessionExits.last()

        private var respond: () -> OpenedSessionTerminal = {
            OpenedSessionTerminal(
                ttyConnector = FakeTtyConnector().also(openedConnectors::add),
                sessionExit = CompletableFuture<Int>().also(sessionExits::add),
            )
        }

        fun respondWith(respond: () -> OpenedSessionTerminal) {
            this.respond = respond
        }

        override fun openSessionIn(workDirPath: Path, target: SessionTarget): OpenedSessionTerminal {
            requests.add(workDirPath to target)
            return respond()
        }
    }

    private companion object {
        val WORK_DIR_PATH: Path = Path.of("/home/me/work/WorkDir-featureX")
    }
}
