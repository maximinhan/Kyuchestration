package com.kyuchestration.desktop.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlinx.coroutines.test.runTest

class ScreenFrameOutboxTest {

    @Test
    fun `쓴 줄을 UTF-8 로 한 번 바꿔 그 바이트와 줄바꿈까지 세고 화면에 건넨 만큼 덜어 낸다`() = runTest {
        val outbox = ScreenFrameOutbox()

        // UTF-8 로 "가" 는 3 바이트, 줄마다 줄바꿈 1 바이트가 붙는다.
        assertEquals(5L, outbox.write("가a"))
        assertEquals(3L, outbox.write("bc"))
        assertEquals(5L + 3L, outbox.pendingBytes.value)

        outbox.close()
        val delivered = mutableListOf<String>()
        val coreFailure = outbox.deliverEach { frameLine -> delivered += frameLine.bytes.toString(Charsets.UTF_8) }

        assertEquals(listOf("가a", "bc"), delivered)
        assertEquals(0L, outbox.pendingBytes.value)
        assertNull(coreFailure)
    }

    @Test
    fun `코어가 실패로 닫으면 남은 줄을 다 건넨 뒤 그 실패를 값으로 돌려준다`() = runTest {
        // 건네는 쪽의 실패(stdout 이 끊김)와 코어의 실패가 섞이지 않게 코어의 실패는 던지지 않고 돌려준다 — 코어 프로세스가 그 값으로
        // 종료 코드를 정한다(CoreMain).
        val outbox = ScreenFrameOutbox()
        outbox.write("마지막 줄")
        val holderFailure = IllegalStateException("홀더가 던졌다")
        outbox.close(holderFailure)

        val delivered = mutableListOf<String>()
        val coreFailure = outbox.deliverEach { delivered += it.bytes.toString(Charsets.UTF_8) }

        assertSame(holderFailure, coreFailure)
        assertEquals(listOf("마지막 줄"), delivered)
    }
}
