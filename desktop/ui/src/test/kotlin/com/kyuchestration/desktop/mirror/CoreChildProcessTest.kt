package com.kyuchestration.desktop.mirror

import com.kyuchestration.desktop.core.TEST_SCREEN_VERSION_LABEL
import com.kyuchestration.desktop.core.coreProcessCommandForTests
import com.kyuchestration.desktop.core.isolatedCoreEnvironment
import com.kyuchestration.desktop.diagnostics.DiagnosticLog
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * 화면이 코어를 진짜 자식 프로세스로 띄우고 그 stdio 에 거울을 잇는다(core-ui-split-design.md 7 절 2 단계).
 *
 * 여기서는 실 claude 를 띄우지 않는다 — 코어의 엔진 자리를 없는 파일로 고정해 두어 코어가 아무 세션도 열지 않는다. 세션까지 여는
 * 시험은 RealClaudeCoreProcessIntegrationTest 다.
 */
class CoreChildProcessTest {

    private val scratchDirectory: Path = createTempDirectory("kyu-core-child")

    private val readingScope = CoroutineScope(Dispatchers.Default)

    @AfterTest
    fun cleanUp() {
        readingScope.cancel()
        scratchDirectory.toFile().deleteRecursively()
    }

    @Test
    fun `코어 프로세스가 강제로 죽으면 거울이 연결 끊김을 세운다`() = runBlocking {
        val coreProcess = startCoreProcess()
        val mirror = connect(coreProcess)

        ProcessHandle.of(coreProcess.pid).orElseThrow().destroyForcibly()

        val failure = withTimeout(PROCESS_TIMEOUT) { mirror.coreConnectionFailure.filterNotNull().first() }
        assertEquals("CoreMirror.ConnectionLost", failure.kind)
    }

    @Test
    fun `화면이 떠나면 코어가 stdin 의 끝을 보고 스스로 끝나며 연결 끊김은 서지 않는다`() = runBlocking {
        val coreProcess = startCoreProcess()
        val mirror = connect(coreProcess)

        val exit = coreProcess.leave(mirror, timeout = PROCESS_TIMEOUT)

        assertEquals(CoreChildProcess.Exit.EndedByItself(exitCode = 0), exit)
        assertFalse(ProcessHandle.of(coreProcess.pid).map { it.isAlive }.orElse(false))
        assertEquals(null, mirror.coreConnectionFailure.value)
    }

    private fun startCoreProcess(): CoreChildProcess = CoreChildProcess.start(coreProcessCommandForTests()) {
        isolatedCoreEnvironment(scratchDirectory, scratchDirectory.resolve("kyu"))
    }

    private suspend fun connect(coreProcess: CoreChildProcess): CoreMirror = withTimeout(PROCESS_TIMEOUT) {
        assertIs<CoreConnection.Connected>(
            CoreMirror.connect(
                framesFromCore = coreProcess.framesFromCore,
                framesToCore = coreProcess.framesToCore,
                readingScope = readingScope,
                diagnosticLog = DiagnosticLog.Discarding,
                screenVersionLabel = TEST_SCREEN_VERSION_LABEL,
            ),
        ).mirror
    }

    private companion object {
        val PROCESS_TIMEOUT = 30.seconds
    }
}
