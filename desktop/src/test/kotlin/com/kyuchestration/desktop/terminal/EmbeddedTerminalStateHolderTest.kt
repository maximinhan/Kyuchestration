package com.kyuchestration.desktop.terminal

import com.jediterm.terminal.TtyConnector
import java.nio.file.Path
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
        val holder = stateHolder(RecordingSessionTerminalAttacher())

        assertEquals(EmbeddedTerminalState.NoTerminalOpen, holder.state.value)
    }

    @Test
    fun `진입을 요청하면 붙기 전까지 진행 중으로 둔다`() = runTest {
        val holder = stateHolder(RecordingSessionTerminalAttacher())

        holder.enterSession(WORK_DIR_PATH, SessionTarget.Repo("proj-a"))

        val running = assertIs<EmbeddedTerminalState.SessionEntryRunning>(holder.state.value)
        assertEquals(SessionTarget.Repo("proj-a"), running.target)
    }

    @Test
    fun `붙으면 그 통로를 들고 있는다`() = runTest {
        val attacher = RecordingSessionTerminalAttacher()
        val holder = stateHolder(attacher)

        holder.enterSession(WORK_DIR_PATH, SessionTarget.Main)
        runCurrent()

        val attached = assertIs<EmbeddedTerminalState.TerminalAttached>(holder.state.value)
        assertEquals(SessionTarget.Main, attached.target)
        assertSame(attacher.lastHandedOutConnector, attached.ttyConnector)
        assertEquals(listOf<Pair<Path, SessionTarget>>(WORK_DIR_PATH to SessionTarget.Main), attacher.requests)
    }

    @Test
    fun `붙지 못하면 그 이유를 들고 있는다`() = runTest {
        val attacher = RecordingSessionTerminalAttacher().apply {
            respondWith { throw TerminalSessionFailure.KyuExecutableNotFound() }
        }
        val holder = stateHolder(attacher)

        holder.enterSession(WORK_DIR_PATH, SessionTarget.Repo("proj-a"))
        runCurrent()

        val failed = assertIs<EmbeddedTerminalState.SessionEntryFailed>(holder.state.value)
        assertEquals(SessionTarget.Repo("proj-a"), failed.target)
        assertIs<TerminalSessionFailure.KyuExecutableNotFound>(failed.failure)
    }

    @Test
    fun `다른 대상을 고르면 앞서 붙어 있던 통로를 닫는다`() = runTest {
        val attacher = RecordingSessionTerminalAttacher()
        val holder = stateHolder(attacher)
        holder.enterSession(WORK_DIR_PATH, SessionTarget.Repo("proj-a"))
        runCurrent()
        val firstConnector = assertIs<FakeTtyConnector>(attacher.lastHandedOutConnector)

        holder.enterSession(WORK_DIR_PATH, SessionTarget.Repo("proj-b"))
        runCurrent()

        // 닫기는 detach 다 — 세션은 tmux 위에 그대로 있고 화면만 다른 세션으로 옮겨간다.
        assertEquals(1, firstConnector.closeCount)
        val attached = assertIs<EmbeddedTerminalState.TerminalAttached>(holder.state.value)
        assertEquals(SessionTarget.Repo("proj-b"), attached.target)
    }

    @Test
    fun `같은 대상을 다시 골라도 붙어 있는 터미널을 놓지 않는다`() = runTest {
        val attacher = RecordingSessionTerminalAttacher()
        val holder = stateHolder(attacher)
        holder.enterSession(WORK_DIR_PATH, SessionTarget.Repo("proj-a"))
        runCurrent()
        val firstConnector = assertIs<FakeTtyConnector>(attacher.lastHandedOutConnector)

        holder.enterSession(WORK_DIR_PATH, SessionTarget.Repo("proj-a"))
        runCurrent()

        // 다시 붙으면 화면이 한 번 깜빡이고 스크롤백을 처음부터 다시 받는다. 이미 보고 있는
        // 세션을 다시 고른 것은 아무것도 바꾸지 않겠다는 뜻이다.
        assertEquals(0, firstConnector.closeCount)
        assertEquals(1, attacher.requests.size)
        assertSame(firstConnector, assertIs<EmbeddedTerminalState.TerminalAttached>(holder.state.value).ttyConnector)
    }

    @Test
    fun `붙어 있던 통로가 이미 끊겼으면 같은 대상이어도 다시 붙는다`() = runTest {
        val attacher = RecordingSessionTerminalAttacher()
        val holder = stateHolder(attacher)
        holder.enterSession(WORK_DIR_PATH, SessionTarget.Repo("proj-a"))
        runCurrent()
        // 세션이 밖에서 죽었거나(kyu kill) 사용자가 세션 안에서 빠져나온 경우다.
        assertIs<FakeTtyConnector>(attacher.lastHandedOutConnector).connected = false

        holder.enterSession(WORK_DIR_PATH, SessionTarget.Repo("proj-a"))
        runCurrent()

        assertEquals(2, attacher.requests.size)
    }

    @Test
    fun `닫으면 통로를 닫고 터미널이 사라진다`() = runTest {
        val attacher = RecordingSessionTerminalAttacher()
        val holder = stateHolder(attacher)
        holder.enterSession(WORK_DIR_PATH, SessionTarget.Repo("proj-a"))
        runCurrent()
        val connector = assertIs<FakeTtyConnector>(attacher.lastHandedOutConnector)

        holder.closeTerminal()

        assertEquals(1, connector.closeCount)
        assertEquals(EmbeddedTerminalState.NoTerminalOpen, holder.state.value)
    }

    @Test
    fun `전환이 늦게 끝나 뒤늦게 도착한 이전 터미널은 곧바로 닫는다`() = runTest {
        val attacher = RecordingSessionTerminalAttacher()
        val holder = stateHolder(attacher)

        // 첫 진입이 아직 끝나기 전에 다른 카드를 누른 상황이다.
        holder.enterSession(WORK_DIR_PATH, SessionTarget.Repo("proj-a"))
        holder.enterSession(WORK_DIR_PATH, SessionTarget.Repo("proj-b"))
        runCurrent()

        // 놓치면 아무도 보지 않는 tmux 클라이언트가 세션에 붙은 채로 남는다.
        assertEquals(1, attacher.handedOutConnectors.first().closeCount)
        val attached = assertIs<EmbeddedTerminalState.TerminalAttached>(holder.state.value)
        assertEquals(SessionTarget.Repo("proj-b"), attached.target)
        assertSame(attacher.handedOutConnectors.last(), attached.ttyConnector)
    }

    @Test
    fun `닫은 뒤에 도착한 터미널도 곧바로 닫는다`() = runTest {
        val attacher = RecordingSessionTerminalAttacher()
        val holder = stateHolder(attacher)

        holder.enterSession(WORK_DIR_PATH, SessionTarget.Repo("proj-a"))
        holder.closeTerminal()
        runCurrent()

        assertEquals(1, attacher.handedOutConnectors.single().closeCount)
        assertEquals(EmbeddedTerminalState.NoTerminalOpen, holder.state.value)
    }

    private fun TestScope.stateHolder(attacher: SessionTerminalAttacher) = EmbeddedTerminalStateHolder(
        sessionTerminalAttacher = attacher,
        coroutineScope = backgroundScope,
        // 앱에서는 PTY 를 여는 동안 화면이 멎지 않도록 IO 디스패처로 나간다.
        sessionEntryDispatcher = StandardTestDispatcher(testScheduler),
    )

    private class RecordingSessionTerminalAttacher : SessionTerminalAttacher {

        val requests = mutableListOf<Pair<Path, SessionTarget>>()
        val handedOutConnectors = mutableListOf<FakeTtyConnector>()

        val lastHandedOutConnector: TtyConnector? get() = handedOutConnectors.lastOrNull()

        private var respond: () -> TtyConnector = { FakeTtyConnector().also(handedOutConnectors::add) }

        fun respondWith(respond: () -> TtyConnector) {
            this.respond = respond
        }

        override fun attachTo(workDirPath: Path, target: SessionTarget): TtyConnector {
            requests.add(workDirPath to target)
            return respond()
        }
    }

    /**
     * TtyConnector 를 손으로 채운다.
     *
     * 여덟 개뿐이고 전부 한 줄이라 가짜를 만들어 주는 라이브러리를 들일 이유가 없다. 이 시험이
     * 실제로 보는 것은 닫혔는지와 살아 있는지 둘뿐이다.
     */
    private class FakeTtyConnector(var connected: Boolean = true) : TtyConnector {

        var closeCount = 0

        override fun read(buf: CharArray, offset: Int, length: Int): Int = -1
        override fun write(bytes: ByteArray) = Unit
        override fun write(string: String) = Unit
        override fun isConnected(): Boolean = connected
        override fun waitFor(): Int = 0
        override fun ready(): Boolean = false
        override fun getName(): String = "가짜 통로"

        override fun close() {
            closeCount++
            connected = false
        }
    }

    private companion object {
        val WORK_DIR_PATH: Path = Path.of("/home/me/work/WorkDir-featureX")
    }
}
