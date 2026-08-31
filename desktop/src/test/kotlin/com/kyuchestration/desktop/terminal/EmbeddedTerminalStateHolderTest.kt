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
        val holder = stateHolder(RecordingSessionTerminalOpener())

        assertEquals(EmbeddedTerminalState.NoTerminalOpen, holder.state.value)
    }

    @Test
    fun `진입을 요청하면 세션이 뜰 때까지 진행 중으로 둔다`() = runTest {
        val holder = stateHolder(RecordingSessionTerminalOpener())

        holder.enterSession(WORK_DIR_PATH, SessionTarget.Repo("proj-a"))

        val running = assertIs<EmbeddedTerminalState.SessionEntryRunning>(holder.state.value)
        assertEquals(SessionTarget.Repo("proj-a"), running.target)
    }

    @Test
    fun `세션이 뜨면 그 통로를 들고 있는다`() = runTest {
        val opener = RecordingSessionTerminalOpener()
        val holder = stateHolder(opener)

        holder.enterSession(WORK_DIR_PATH, SessionTarget.Main)
        runCurrent()

        val attached = assertIs<EmbeddedTerminalState.SessionOnScreen>(holder.state.value)
        assertEquals(SessionTarget.Main, attached.target)
        assertSame(opener.lastHandedOutConnector, attached.ttyConnector)
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
    }

    @Test
    fun `다른 대상을 고르면 앞서 보던 세션을 끝낸다`() = runTest {
        val opener = RecordingSessionTerminalOpener()
        val holder = stateHolder(opener)
        holder.enterSession(WORK_DIR_PATH, SessionTarget.Repo("proj-a"))
        runCurrent()
        val firstConnector = assertIs<FakeTtyConnector>(opener.lastHandedOutConnector)

        holder.enterSession(WORK_DIR_PATH, SessionTarget.Repo("proj-b"))
        runCurrent()

        // 앱이 세션을 보유하므로 통로를 닫는 것이 곧 그 세션을 끝내는 일이다. detach 가 없다.
        assertEquals(1, firstConnector.closeCount)
        val attached = assertIs<EmbeddedTerminalState.SessionOnScreen>(holder.state.value)
        assertEquals(SessionTarget.Repo("proj-b"), attached.target)
    }

    @Test
    fun `같은 대상을 다시 골라도 보고 있던 세션을 그대로 둔다`() = runTest {
        val opener = RecordingSessionTerminalOpener()
        val holder = stateHolder(opener)
        holder.enterSession(WORK_DIR_PATH, SessionTarget.Repo("proj-a"))
        runCurrent()
        val firstConnector = assertIs<FakeTtyConnector>(opener.lastHandedOutConnector)

        holder.enterSession(WORK_DIR_PATH, SessionTarget.Repo("proj-a"))
        runCurrent()

        // 이미 보고 있는 세션을 다시 고른 것은 아무것도 바꾸지 않겠다는 뜻이다. 다시 띄우면
        // 하던 작업이 끝나고 새 대화가 그 자리에 열린다 — 카드를 두 번 누른 대가로는 너무 크다.
        assertEquals(0, firstConnector.closeCount)
        assertEquals(1, opener.requests.size)
        assertSame(firstConnector, assertIs<EmbeddedTerminalState.SessionOnScreen>(holder.state.value).ttyConnector)
    }

    @Test
    fun `보고 있던 세션이 이미 끝났으면 같은 대상이어도 다시 띄운다`() = runTest {
        val opener = RecordingSessionTerminalOpener()
        val holder = stateHolder(opener)
        holder.enterSession(WORK_DIR_PATH, SessionTarget.Repo("proj-a"))
        runCurrent()
        // 사용자가 세션 안에서 빠져나온(/exit) 경우다. 대화는 엔진이 --resume 으로 이어 준다.
        assertIs<FakeTtyConnector>(opener.lastHandedOutConnector).connected = false

        holder.enterSession(WORK_DIR_PATH, SessionTarget.Repo("proj-a"))
        runCurrent()

        assertEquals(2, opener.requests.size)
    }

    @Test
    fun `끝내면 통로를 닫고 터미널이 사라진다`() = runTest {
        val opener = RecordingSessionTerminalOpener()
        val holder = stateHolder(opener)
        holder.enterSession(WORK_DIR_PATH, SessionTarget.Repo("proj-a"))
        runCurrent()
        val connector = assertIs<FakeTtyConnector>(opener.lastHandedOutConnector)

        holder.endSession()

        assertEquals(1, connector.closeCount)
        assertEquals(EmbeddedTerminalState.NoTerminalOpen, holder.state.value)
    }

    @Test
    fun `전환이 늦게 끝나 뒤늦게 도착한 이전 세션은 곧바로 끝낸다`() = runTest {
        val opener = RecordingSessionTerminalOpener()
        val holder = stateHolder(opener)

        // 첫 진입이 아직 끝나기 전에 다른 카드를 누른 상황이다.
        holder.enterSession(WORK_DIR_PATH, SessionTarget.Repo("proj-a"))
        holder.enterSession(WORK_DIR_PATH, SessionTarget.Repo("proj-b"))
        runCurrent()

        // 놓치면 아무도 보지 않는 claude 프로세스가 앱 밖에서 계속 돈다.
        assertEquals(1, opener.handedOutConnectors.first().closeCount)
        val attached = assertIs<EmbeddedTerminalState.SessionOnScreen>(holder.state.value)
        assertEquals(SessionTarget.Repo("proj-b"), attached.target)
        assertSame(opener.handedOutConnectors.last(), attached.ttyConnector)
    }

    @Test
    fun `끝낸 뒤에 도착한 세션도 곧바로 끝낸다`() = runTest {
        val opener = RecordingSessionTerminalOpener()
        val holder = stateHolder(opener)

        holder.enterSession(WORK_DIR_PATH, SessionTarget.Repo("proj-a"))
        holder.endSession()
        runCurrent()

        assertEquals(1, opener.handedOutConnectors.single().closeCount)
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
        val handedOutConnectors = mutableListOf<FakeTtyConnector>()

        val lastHandedOutConnector: TtyConnector? get() = handedOutConnectors.lastOrNull()

        private var respond: () -> TtyConnector = { FakeTtyConnector().also(handedOutConnectors::add) }

        fun respondWith(respond: () -> TtyConnector) {
            this.respond = respond
        }

        override fun openSessionIn(workDirPath: Path, target: SessionTarget): TtyConnector {
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
