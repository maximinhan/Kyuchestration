package com.kyuchestration.desktop.core

import com.kyuchestration.desktop.diagnostics.DiagnosticLogEntry
import com.kyuchestration.desktop.diagnostics.RecordingDiagnosticLog
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest

/** 코어 프로세스가 stdin · stdout 을 다루는 규칙(CoreMain). 진짜 프로세스를 띄우는 시험은 CoreMainProcessTest 다. */
class CoreStandardStreamsTest {

    @Test
    fun `stdin 의 줄을 틀 줄로 싣고 끝에서 줄을 닫는다`() {
        val framesFromScreen = Channel<String>(Channel.UNLIMITED)

        readFramesFromScreen(ByteArrayInputStream("{\"frame\":\"hello\"}\n안녕\n".toByteArray()), framesFromScreen)

        assertEquals("{\"frame\":\"hello\"}", framesFromScreen.tryReceive().getOrThrow())
        assertEquals("안녕", framesFromScreen.tryReceive().getOrThrow())
        assertTrue(framesFromScreen.isClosedForReceive)
    }

    @Test
    fun `stdin 을 읽다 실패해도 줄을 닫아 코어가 세션을 끝내게 한다`() {
        // 닫지 않으면 코어는 화면이 아직 있다고 알아 세션을 영영 끝내지 않고, claude 들이 주인 없이 남는다.
        val framesFromScreen = Channel<String>(Channel.UNLIMITED)
        val brokenInput = object : InputStream() {
            override fun read(): Int = throw IOException("stdin 이 끊겼다")
        }

        assertFailsWith<IOException> { readFramesFromScreen(brokenInput, framesFromScreen) }

        assertTrue(framesFromScreen.isClosedForReceive)
    }

    @Test
    fun `코어가 정상으로 끝나면 틀을 줄마다 다 쓰고 실패 없음을 돌려준다`() = runTest {
        val outbox = ScreenFrameOutbox().apply {
            write("{\"frame\":\"bye\"}")
            write("가")
            close()
        }
        val screenOutput = ByteArrayOutputStream()

        val coreFailure = deliverFramesToScreen(outbox, screenOutput, RecordingDiagnosticLog())

        assertNull(coreFailure)
        assertEquals("{\"frame\":\"bye\"}\n가\n", screenOutput.toString(Charsets.UTF_8))
    }

    @Test
    fun `코어가 실패로 끝나면 남은 틀을 다 쓴 뒤 그 실패를 돌려준다 — 코어 프로세스는 그것으로 1 로 끝난다`() = runTest {
        val holderFailure = IllegalStateException("홀더가 명령 도중 던졌다")
        val outbox = ScreenFrameOutbox().apply {
            write("마지막 틀")
            close(holderFailure)
        }
        val screenOutput = ByteArrayOutputStream()

        val coreFailure = deliverFramesToScreen(outbox, screenOutput, RecordingDiagnosticLog())

        assertSame(holderFailure, coreFailure)
        assertEquals("마지막 틀\n", screenOutput.toString(Charsets.UTF_8))
    }

    @Test
    fun `stdout 이 끊기면 그 사실을 기록하고 남은 틀을 버리며 코어의 끝을 기다린다`() = runTest {
        val outbox = ScreenFrameOutbox().apply {
            write("첫 틀")
            write("둘째 틀")
            close()
        }
        val diagnosticLog = RecordingDiagnosticLog()

        val coreFailure = deliverFramesToScreen(outbox, BrokenOutput(IOException("Broken pipe")), diagnosticLog)

        assertNull(coreFailure)
        assertEquals("화면으로 가는 줄이 끊김 — Broken pipe", assertIs<DiagnosticLogEntry.ScreenOutputBroken>(diagnosticLog.recordedEntries.single()).summary)
        assertEquals(0L, outbox.pendingBytes.value)
    }

    @Test
    fun `stdout 에 쓰다 난 IOException 이 아닌 실패는 삼키지 않고 던진다`() = runTest {
        // 이 코드의 결함이다. 종료 코드 하나로 바꿔 버리면 무엇이 깨졌는지 어디에도 남지 않는다 — 던지면 미처리 예외로 기록된다.
        val outbox = ScreenFrameOutbox().apply {
            write("틀")
            close()
        }

        assertFailsWith<IllegalStateException> {
            deliverFramesToScreen(outbox, BrokenOutput(IllegalStateException("쓰는 쪽의 결함")), RecordingDiagnosticLog())
        }
    }

    private class BrokenOutput(private val failure: Exception) : OutputStream() {
        override fun write(b: Int) = throw failure

        override fun write(b: ByteArray) = throw failure
    }
}
