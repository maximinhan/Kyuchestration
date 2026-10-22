package com.kyuchestration.desktop.core

import com.kyuchestration.desktop.protocol.FrameToScreen
import com.kyuchestration.desktop.protocol.SCREEN_PROTOCOL_VERSION
import com.kyuchestration.desktop.protocol.decodeFrameToScreen
import com.kyuchestration.desktop.protocol.encodeFrameToCore
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `kyuchestration-core serve --stdio` 를 진짜 프로세스로 띄운다(core-ui-split-design.md 6.12 · 6.13 의 2~4 단계).
 *
 * 화면이 그 stdio 에 거울을 잇는 것은 화면 모듈의 시험이 본다. 여기서는 프로세스의 약속만 본다 — stdout 에는 틀만 나가고, stdin 이
 * 닫히면 스스로 끝난다.
 */
class CoreMainProcessTest {

    private val scratchDirectory: Path = createTempDirectory("kyu-core-main")

    @AfterTest
    fun removeScratchDirectory() {
        scratchDirectory.toFile().deleteRecursively()
    }

    @Test
    fun `인사하면 인사 답과 스냅샷을 stdout 에 틀로만 쓰고, stdin 이 닫히면 스스로 끝난다`() {
        val core = startCore()

        core.outputStream.bufferedWriter(Charsets.UTF_8).use { toCore ->
            toCore.write(encodeFrameToCore(TEST_SCREEN_HELLO))
            toCore.newLine()
            toCore.flush()
            val fromCore = core.inputStream.bufferedReader(Charsets.UTF_8)
            val welcome = assertIs<FrameToScreen.Welcome>(decodeFrameToScreen(fromCore.readLine()))
            assertEquals(SCREEN_PROTOCOL_VERSION, welcome.protocol)
            assertEquals(scratchDirectory.resolve("state/kyuchestration/core.log").toString(), welcome.coreDiagnosticLog)
            do {
                val snapshot = assertIs<FrameToScreen.Snapshot>(decodeFrameToScreen(fromCore.readLine()))
            } while (!snapshot.last)
        }

        assertTrue(core.waitFor(PROCESS_EXIT_TIMEOUT_SECONDS, TimeUnit.SECONDS), "stdin 이 닫혔는데 코어가 끝나지 않았다")
        assertEquals(0, core.exitValue())
        // 코어가 끝나기 전에 쓴 것이 남아 있으면 그것도 틀이어야 한다 — stdout 에는 틀 말고 아무것도 쓰지 않는다(6.4).
        core.inputStream.bufferedReader(Charsets.UTF_8).readLines().forEach { decodeFrameToScreen(it) }
    }

    @Test
    fun `serve --stdio 가 아닌 인자로 띄우면 쓰는 법을 stderr 에 적고 2 로 끝난다`() {
        val process = ProcessBuilder(coreProcessCommandForTests().dropLast(2) + "attach")
            .isolatedCoreEnvironment(scratchDirectory, scratchDirectory.resolve("kyu"))
            .start()

        assertTrue(process.waitFor(PROCESS_EXIT_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertEquals(2, process.exitValue())
        assertEquals("", process.inputStream.bufferedReader().readText())
        assertTrue("serve --stdio" in process.errorStream.bufferedReader().readText())
    }

    private fun startCore(): Process = ProcessBuilder(coreProcessCommandForTests())
        .isolatedCoreEnvironment(scratchDirectory, scratchDirectory.resolve("kyu"))
        .redirectError(ProcessBuilder.Redirect.INHERIT)
        .start()

    private companion object {
        const val PROCESS_EXIT_TIMEOUT_SECONDS = 30L
    }
}
