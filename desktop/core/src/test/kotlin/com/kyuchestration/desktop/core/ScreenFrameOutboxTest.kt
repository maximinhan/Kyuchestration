package com.kyuchestration.desktop.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest

class ScreenFrameOutboxTest {

    @Test
    fun `쓴 줄의 바이트를 줄 끝까지 세고 화면에 건넨 만큼 덜어 낸다`() = runTest {
        val outbox = ScreenFrameOutbox()

        outbox.write("가a")
        outbox.write("bc")
        // UTF-8 로 "가" 는 3 바이트, 줄마다 줄바꿈 1 바이트가 붙는다.
        assertEquals(5L + 3L, outbox.pendingBytes.value)

        outbox.close()
        val delivered = mutableListOf<String>()
        outbox.deliverEach { line ->
            delivered += line
        }

        assertEquals(listOf("가a", "bc"), delivered)
        assertEquals(0L, outbox.pendingBytes.value)
    }

    @Test
    fun `코어가 실패로 닫으면 건네는 쪽이 남은 줄을 다 건넨 뒤 그 실패를 받는다`() = runTest {
        val outbox = ScreenFrameOutbox()
        outbox.write("마지막 줄")
        outbox.close(IllegalStateException("홀더가 던졌다"))

        val delivered = mutableListOf<String>()
        val failure = assertFailsWith<IllegalStateException> { outbox.deliverEach { delivered += it } }

        assertEquals("홀더가 던졌다", failure.message)
        assertEquals(listOf("마지막 줄"), delivered)
    }
}
