package com.kyuchestration.desktop.mirror

import com.kyuchestration.desktop.core.TEST_SCREEN_VERSION_LABEL
import com.kyuchestration.desktop.core.coreProcessCommandForTests
import com.kyuchestration.desktop.core.isolatedCoreEnvironment
import com.kyuchestration.desktop.diagnostics.DiagnosticLogEntry
import com.kyuchestration.desktop.diagnostics.RecordingDiagnosticLog
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerializationException
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS

/**
 * 화면이 코어를 진짜 자식 프로세스로 띄우고 그 stdio 에 거울을 잇는다(core-ui-split-design.md 7 절 2 단계).
 *
 * 여기서는 실 claude 를 띄우지 않는다 — 코어의 엔진 자리를 없는 파일로 고정해 두어 코어가 아무 세션도 열지 않는다. 세션까지 여는
 * 시험은 RealClaudeCoreProcessIntegrationTest 다.
 */
@DisabledOnOs(OS.WINDOWS, disabledReason = "코어는 리눅스 · 맥에서만 돈다 — 윈도우의 코어는 WSL 안에 있다(core-ui-split-design.md 6.16)")
class CoreChildProcessTest {

    private val scratchDirectory: Path = createTempDirectory("kyu-core-child")

    private val readingScope = CoroutineScope(Dispatchers.Default)

    private val diagnosticLog = RecordingDiagnosticLog()

    @AfterTest
    fun cleanUp() {
        readingScope.cancel()
        scratchDirectory.toFile().deleteRecursively()
    }

    @Test
    fun `코어 프로세스가 강제로 죽으면 거울이 연결 끊김을 세우고 화면이 떠나기 전에 끝났다고 기록한다`() = runBlocking {
        val coreProcess = startCoreProcess()
        val mirror = connect(coreProcess)

        ProcessHandle.of(coreProcess.pid).orElseThrow().destroyForcibly()

        val failure = withTimeout(PROCESS_TIMEOUT) { mirror.coreConnectionFailure.filterNotNull().first() }
        assertEquals("CoreMirror.ConnectionLost", failure.kind)
        assertFalse(recordedCoreExit().screenWasLeaving)
    }

    @Test
    fun `화면이 떠나면 코어가 stdin 의 끝을 보고 스스로 끝나며 연결 끊김은 서지 않는다`() = runBlocking {
        val coreProcess = startCoreProcess()
        val mirror = connect(coreProcess)

        val exit = coreProcess.leave(mirror, timeout = PROCESS_TIMEOUT)

        assertEquals(CoreChildProcess.Exit.EndedByItself(exitCode = 0), exit)
        assertFalse(ProcessHandle.of(coreProcess.pid).map { it.isAlive }.orElse(false))
        assertEquals(null, mirror.coreConnectionFailure.value)
        assertEquals(DiagnosticLogEntry.CoreProcessExited(exitCode = 0, screenWasLeaving = true), recordedCoreExit())
    }

    @Test
    fun `거울을 세우다 실패하면 코어의 stdin 을 닫고 끝나기를 기다린 뒤 그 실패를 던진다`() = runBlocking {
        // 첫 스냅샷 전에 화면이 던지고 떠나면 띄운 코어가 주인 없이 남는다 — 코어는 stdin 의 끝을 받아야 세션을 끝낸다.
        val brokenCore = CoreChildProcess.start(
            CorePlace.SameMachine(listOf("sh", "-c", "echo 틀이-아닌-줄; cat > /dev/null")),
            diagnosticLog,
        )

        assertFailsWith<SerializationException> {
            brokenCore.connectMirror(readingScope, TEST_SCREEN_VERSION_LABEL, leaveTimeout = PROCESS_TIMEOUT)
        }

        assertFalse(ProcessHandle.of(brokenCore.pid).map { it.isAlive }.orElse(false))
        assertEquals(DiagnosticLogEntry.CoreProcessExited(exitCode = 0, screenWasLeaving = true), recordedCoreExit())
    }

    private fun startCoreProcess(): CoreChildProcess = CoreChildProcess.start(CorePlace.SameMachine(coreProcessCommandForTests()), diagnosticLog) {
        isolatedCoreEnvironment(scratchDirectory, scratchDirectory.resolve("kyu"))
    }

    private suspend fun connect(coreProcess: CoreChildProcess): CoreMirror = withTimeout(PROCESS_TIMEOUT) {
        assertIs<CoreConnection.Connected>(
            coreProcess.connectMirror(readingScope, TEST_SCREEN_VERSION_LABEL, leaveTimeout = PROCESS_TIMEOUT),
        ).mirror
    }

    /** 프로세스가 끝난 것을 알리는 기록은 다른 스레드에서 온다. 올 때까지 기다린다. */
    private suspend fun recordedCoreExit(): DiagnosticLogEntry.CoreProcessExited = withTimeout(PROCESS_TIMEOUT) {
        var coreExit: DiagnosticLogEntry.CoreProcessExited? = null
        while (coreExit == null) {
            coreExit = diagnosticLog.recordedEntries.filterIsInstance<DiagnosticLogEntry.CoreProcessExited>().singleOrNull()
            if (coreExit == null) {
                delay(POLL_INTERVAL)
            }
        }
        coreExit
    }

    private companion object {
        val PROCESS_TIMEOUT = 30.seconds
        val POLL_INTERVAL = 0.05.seconds
    }
}
