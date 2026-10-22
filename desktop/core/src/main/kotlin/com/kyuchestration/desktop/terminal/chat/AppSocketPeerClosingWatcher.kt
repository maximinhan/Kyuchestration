package com.kyuchestration.desktop.terminal.chat

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.ClosedSelectorException
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred

/**
 * 앱 소켓 하나의 연결들이 상대 쪽에서 닫히는지를 **스레드 하나**로 지켜본다(셀렉터).
 *
 * 답을 기다리는 요청은 오래 열려 있다 — 기다림은 최대 3000 초, 승인과 `ask_parent` 는 상한이 없다(session-tree 6.7).
 * 연결마다 막힌 읽기로 지켜보면 열린 요청 수만큼 스레드가 묶이고, 그 스레드가 `Dispatchers.IO` 에서 오면 stdin 쓰기 ·
 * 스트림 읽기와 같은 풀(64)을 다 써 버려 새 요청은 읽히지도 않는다. 셀렉터는 열린 연결이 몇 개든 한 스레드로 지켜본다.
 *
 * 감시를 시작한 연결은 **비차단**이 된다. 셀렉터에 걸 수 있는 채널의 조건이 그것이다 — 그 연결에 답을 쓰는 쪽은
 * 비차단 쓰기를 다뤄야 한다(AppSocket 의 writeAnswerLine).
 */
internal class AppSocketPeerClosingWatcher private constructor(private val selector: Selector) {

    /**
     * 걸어 달라고 들어온 연결들. 셀렉터 스레드만 셀렉터에 건다 — 다른 스레드가 `register` 를 부르면 막힌 `select` 와
     * 순서가 엉키고, 판에 따라 그 호출이 `select` 가 풀릴 때까지 기다린다.
     */
    private val pendingRegistrations = ConcurrentLinkedQueue<Pair<SocketChannel, CompletableDeferred<Unit>>>()

    /**
     * 그 연결을 지켜보기 시작한다. 상대가 연결을 닫으면(EOF) 끝나는 자리를 돌려준다.
     *
     * 앱이 답을 쓰고 연결을 닫으면 이 자리는 끝나지 않는다 — 그때는 이미 답한 뒤라 아무도 이 끝을 기다리지 않는다.
     */
    fun watch(connection: SocketChannel): Deferred<Unit> {
        val peerClosed = CompletableDeferred<Unit>()
        connection.configureBlocking(false)
        pendingRegistrations.add(connection to peerClosed)
        selector.wakeup()
        return peerClosed
    }

    /**
     * 셀렉터를 한 번 깨운다. 앱이 지켜보던 연결을 닫은 뒤에 부른다.
     *
     * 셀렉터에 걸린 채널을 닫으면 파일 디스크립터는 셀렉터가 그 키를 거둘 때까지 남는다(JDK 의 SelectableChannel 규약).
     * 깨우지 않으면 다음 사건이 올 때까지 닫힌 연결이 상대에게 끝나지 않은 것으로 보인다.
     */
    fun releaseClosedConnections() {
        selector.wakeup()
    }

    /** 감시를 접는다. 지켜보던 연결의 주인(AppSocket)이 그 연결들을 닫는다. */
    fun close() {
        selector.close()
    }

    private fun watchUntilClosed() {
        val discarded = ByteBuffer.allocate(DISCARD_BUFFER_BYTES)
        while (true) {
            try {
                selector.select()
                registerPendingConnections()
                selector.selectedKeys().forEach { key -> noticePeerClosing(key, discarded) }
                selector.selectedKeys().clear()
            } catch (closed: ClosedSelectorException) {
                return
            }
        }
    }

    private fun registerPendingConnections() {
        while (true) {
            val (connection, peerClosed) = pendingRegistrations.poll() ?: return
            try {
                connection.register(selector, SelectionKey.OP_READ, peerClosed)
            } catch (alreadyClosed: ClosedChannelException) {
                // 걸기도 전에 닫혔다. 앱이 이미 답하고 닫은 것이면 아무도 기다리지 않고, 상대가 닫은 것이면 그것이 곧 끝이다.
                peerClosed.complete(Unit)
            }
        }
    }

    /**
     * 읽을 것이 생긴 연결 하나. 상대는 요청 뒤에 아무것도 보내지 않으므로(app_socket.go 의 exchangeLineWithApp) 읽을 것이
     * 생겼다는 것은 곧 EOF 다. 프로토콜에 없는 바이트가 오면 읽어 버리고 계속 지켜본다.
     */
    private fun noticePeerClosing(key: SelectionKey, discarded: ByteBuffer) {
        @Suppress("UNCHECKED_CAST")
        val peerClosed = key.attachment() as CompletableDeferred<Unit>
        val readCount = try {
            discarded.clear()
            (key.channel() as SocketChannel).read(discarded)
        } catch (connectionGone: IOException) {
            END_OF_STREAM
        }
        if (readCount == END_OF_STREAM) {
            key.cancel()
            peerClosed.complete(Unit)
        }
    }

    companion object {

        /** 감시 스레드를 띄운다. 데몬이다 — 앱이 꺼지는 것을 이 스레드가 붙잡지 않는다. */
        fun start(socketName: String): AppSocketPeerClosingWatcher {
            val watcher = AppSocketPeerClosingWatcher(Selector.open())
            thread(isDaemon = true, name = "app-socket-peer-watch-$socketName") { watcher.watchUntilClosed() }
            return watcher
        }

        private const val END_OF_STREAM = -1

        private const val DISCARD_BUFFER_BYTES = 256
    }
}
