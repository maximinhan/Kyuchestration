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
 * 건네는 쪽은 하나다. 코어 프로세스에서는 stdout 에 쓰는 자리, 시험에서는 줄을 읽는 자리다.
 */
class ScreenFrameOutbox {

    private val lines = Channel<String>(Channel.UNLIMITED)

    private val mutablePendingBytes = MutableStateFlow(0L)

    /** 써 두었지만 아직 화면에 건네지 못한 바이트(UTF-8, 줄바꿈 포함). */
    val pendingBytes: StateFlow<Long> = mutablePendingBytes.asStateFlow()

    fun write(line: String) {
        mutablePendingBytes.update { it + byteCountOf(line) }
        lines.trySend(line).getOrThrow()
    }

    /** 더 쓰지 않는다. [cause] 가 있으면 건네는 쪽이 남은 줄을 다 건넨 뒤 그 실패를 받는다. */
    fun close(cause: Throwable? = null) {
        lines.close(cause)
    }

    /** 줄을 하나씩 [deliver] 에 건넨다. 닫히면 돌아오고, 실패로 닫혔으면 그 실패를 던진다. */
    suspend fun deliverEach(deliver: suspend (String) -> Unit) {
        for (line in lines) {
            deliver(line)
            mutablePendingBytes.update { it - byteCountOf(line) }
        }
    }

    private fun byteCountOf(line: String): Long = line.toByteArray(Charsets.UTF_8).size + LINE_SEPARATOR_BYTES

    private companion object {
        const val LINE_SEPARATOR_BYTES = 1L
    }
}
