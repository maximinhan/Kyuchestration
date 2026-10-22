package com.kyuchestration.desktop.core

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 코어가 화면으로 내보낼 틀 줄이 화면에 닿기 전까지 머무는 자리.
 *
 * 쓰기는 멈추지 않는다. 코어는 틀을 쓰다 멈추면 안 된다 — 쓰는 도중에 멈추면 그사이 다른 코루틴이 견준 변화가 앞 변화보다 먼저
 * 나가 `seq` 가 뒤섞인다(serveScreen). 대신 아직 화면에 건네지 못한 바이트를 센다. 화면이 읽지 못해 쌓이면 serveScreen 이 그
 * 수를 보고 변화를 버리고 스냅샷부터 다시 보낸다(core-ui-split-design.md 6.5).
 *
 * 줄은 쓸 때 UTF-8 로 한 번 바꿔 그 바이트를 싣는다. 세는 일과 stdout 에 쓰는 일이 같은 바이트를 쓴다 — 큰 대화의 스냅샷에서
 * 같은 문자열을 자리마다 다시 인코딩하면 그 비용이 자리 수만큼 는다.
 *
 * 건네는 쪽은 하나다. 코어 프로세스에서는 stdout 에 쓰는 자리, 시험에서는 줄을 읽는 자리다.
 */
class ScreenFrameOutbox {

    /** 화면에 건넬 틀 한 줄의 UTF-8 바이트. 줄바꿈은 들어 있지 않다. */
    class FrameLine(val bytes: ByteArray) {
        val pendingByteCount: Long get() = bytes.size + LINE_SEPARATOR_BYTES
    }

    private val lines = Channel<FrameLine>(Channel.UNLIMITED)

    private val mutablePendingBytes = MutableStateFlow(0L)

    /** 써 두었지만 아직 화면에 건네지 못한 바이트(UTF-8, 줄바꿈 포함). */
    val pendingBytes: StateFlow<Long> = mutablePendingBytes.asStateFlow()

    /** [line] 을 내보낼 줄로 싣고, 그 줄이 차지하는 바이트(줄바꿈 포함)를 돌려준다. */
    fun write(line: String): Long {
        val frameLine = FrameLine(line.toByteArray(Charsets.UTF_8))
        mutablePendingBytes.update { it + frameLine.pendingByteCount }
        lines.trySend(frameLine).getOrThrow()
        return frameLine.pendingByteCount
    }

    /**
     * 더 쓰지 않는다. 코어가 실패로 끝났으면 [coreFailure] 를 싣는다 — 건네는 쪽이 남은 줄을 다 건넨 뒤 그 값을 받아 코어 프로세스의
     * 종료 코드를 정한다.
     */
    fun close(coreFailure: Throwable? = null) {
        lines.close(coreFailure)
    }

    /**
     * 줄을 하나씩 [deliver] 에 건넨다. 닫히면 코어의 실패(정상으로 닫혔으면 null)를 돌려준다.
     *
     * 코어의 실패는 던지지 않고 값으로 돌려준다. [deliver] 자신이 던진 것(stdout 이 끊김 같은)은 그대로 던진다 — 둘을 한 갈래로 받으면
     * 건네는 쪽은 화면이 사라진 것과 코어가 무너진 것을 가르지 못한다.
     */
    suspend fun deliverEach(deliver: suspend (FrameLine) -> Unit): Throwable? {
        while (true) {
            val received = lines.receiveCatching()
            val frameLine = received.getOrNull() ?: return received.exceptionOrNull()
            // 건네다 실패해도 그 줄은 이 자리를 떠났다. 남은 것으로 세면 밀린 바이트가 영영 0 이 되지 않는다.
            try {
                deliver(frameLine)
            } finally {
                mutablePendingBytes.update { it - frameLine.pendingByteCount }
            }
        }
    }

    private companion object {
        const val LINE_SEPARATOR_BYTES = 1L
    }
}
