package com.kyuchestration.desktop.diagnostics

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame

/**
 * 아무도 받지 않은 예외가 기록에 닿는지.
 *
 * 이 갈래가 있어야 기록이 "앱이 이미 알고 있는 실패" 를 넘어선다. 나머지 갈래들은 이 앱이
 * 예상하고 타입으로 적어 둔 실패이고, 원격으로 진단해야 하는 것은 대개 예상하지 못한 쪽이다.
 */
class UncaughtFailureRecordingTest {

    private val handlerBeforeThisTest: Thread.UncaughtExceptionHandler? =
        Thread.getDefaultUncaughtExceptionHandler()

    @AfterTest
    fun 원래_핸들러로_되돌린다() {
        // JVM 전역 상태라 되돌리지 않으면 이 시험이 뒤따르는 시험들의 실패 보고를 가로챈다.
        Thread.setDefaultUncaughtExceptionHandler(handlerBeforeThisTest)
    }

    @Test
    fun `미처리 예외를 스레드 이름과 함께 남긴다`() {
        val diagnosticLog = RecordingDiagnosticLog()
        val handler = uncaughtFailureRecordingHandler(diagnosticLog, previousHandler = null)
        val failure = IllegalStateException("화면을 그리다 죽었다")

        handler.uncaughtException(Thread.currentThread(), failure)

        val recorded = assertIs<DiagnosticLogEntry.UnhandledFailure>(diagnosticLog.recordedEntries.single())
        assertEquals(Thread.currentThread().name, recorded.threadName)
        assertSame(failure, recorded.failure)
    }

    /**
     * 앞서 있던 핸들러를 밀어내지 않는다.
     *
     * JVM 이 기본으로 두는 핸들러가 스택을 stderr 로 찍는데, 그것을 가로채 기록만 남기면
     * 터미널에서 앱을 띄워 지켜보던 사람이 방금까지 보이던 것을 잃는다. 기록은 더하는 것이지
     * 대신하는 것이 아니다.
     */
    @Test
    fun `기록한 뒤 앞서 있던 핸들러에게 그대로 넘긴다`() {
        val diagnosticLog = RecordingDiagnosticLog()
        val handedOver = mutableListOf<Throwable>()
        val handler = uncaughtFailureRecordingHandler(
            diagnosticLog = diagnosticLog,
            previousHandler = { _, thrown -> handedOver += thrown },
        )
        val failure = IllegalStateException("화면을 그리다 죽었다")

        handler.uncaughtException(Thread.currentThread(), failure)

        assertEquals(listOf<Throwable>(failure), handedOver)
    }

    /**
     * 기록하다 죽지 않는다.
     *
     * 이 핸들러가 도는 시점은 이미 무언가 잘못된 뒤다. 여기서 또 던지면 그 예외는 받을 곳이
     * 없고, 원래 예외까지 함께 사라진다 — 앞서 있던 핸들러에게 넘기는 걸음도 못 밟는다.
     */
    @Test
    fun `기록이 실패해도 앞서 있던 핸들러에게는 넘어간다`() {
        val handedOver = mutableListOf<Throwable>()
        val handler = uncaughtFailureRecordingHandler(
            diagnosticLog = object : DiagnosticLog {
                override fun record(entry: DiagnosticLogEntry) = throw OutOfMemoryError("기록할 메모리도 없다")
            },
            previousHandler = { _, thrown -> handedOver += thrown },
        )
        val failure = IllegalStateException("화면을 그리다 죽었다")

        handler.uncaughtException(Thread.currentThread(), failure)

        assertEquals(listOf<Throwable>(failure), handedOver)
    }

    @Test
    fun `등록하면 이 JVM 의 기본 핸들러가 된다`() {
        val diagnosticLog = RecordingDiagnosticLog()

        recordUncaughtFailuresIn(diagnosticLog)

        val installedHandler = assertNotNull(
            Thread.getDefaultUncaughtExceptionHandler(),
            "기본 핸들러가 등록되지 않았다 — 미처리 예외는 그대로 사라진다",
        )
        installedHandler.uncaughtException(Thread.currentThread(), IllegalStateException("죽었다"))
        assertIs<DiagnosticLogEntry.UnhandledFailure>(diagnosticLog.recordedEntries.single())
    }
}
