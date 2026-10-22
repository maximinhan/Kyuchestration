package com.kyuchestration.desktop.engine

import com.kyuchestration.desktop.diagnostics.DiagnosticLogEntry
import com.kyuchestration.desktop.diagnostics.RecordingDiagnosticLog
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createTempDirectory
import kotlin.io.path.setPosixFilePermissions
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS

/**
 * 엔진이 정말 도는지 묻는 걸음과, 그 걸음이 실패했을 때 남는 것.
 *
 * **기록을 여기까지 이어야 하는 이유가 이 걸음의 자리다.** 여기서 걸린 실행은 설치 화면에 멈춰
 * 서고 그 뒤로 엔진을 부르는 일이 하나도 없다 — 남기지 않으면 그 실행에 대해 파일에 적히는
 * 것은 "앱이 시작했다" 한 줄뿐이고, 정작 원격으로 봐야 하는 실패(다른 플랫폼의 바이너리,
 * 맥이 격리해 둔 파일)가 그렇게 끝난다.
 *
 * 셸 스크립트를 엔진 삼아 시험한다. 윈도우에는 /bin/sh 가 없어 그쪽에서는 건너뛴다.
 */
@DisabledOnOs(OS.WINDOWS)
class EngineRunCheckTest {

    private val temporaryDirectory: Path = createTempDirectory("kyu-engine-run-check")

    @AfterTest
    fun 임시_디렉토리를_지운다() {
        temporaryDirectory.toFile().deleteRecursively()
    }

    @Test
    fun `도는 엔진에는 이유가 없다`() {
        val engine = executableScript(
            """
            #!/bin/sh
            echo "kyu v0.10.0"
            """.trimIndent(),
        )

        assertNull(reasonEngineDoesNotRun(engine))
    }

    @Test
    fun `돌지 않으면 엔진이 남긴 말을 이유로 든다`() {
        val engine = executableScript(
            """
            #!/bin/sh
            echo "이 바이너리는 이 플랫폼의 것이 아닙니다" >&2
            exit 1
            """.trimIndent(),
        )

        assertEquals("이 바이너리는 이 플랫폼의 것이 아닙니다", reasonEngineDoesNotRun(engine))
    }

    @Test
    fun `돌지 않는 엔진은 기록에도 남는다`() {
        val engine = executableScript(
            """
            #!/bin/sh
            echo "이 바이너리는 이 플랫폼의 것이 아닙니다" >&2
            exit 1
            """.trimIndent(),
        )
        val diagnosticLog = RecordingDiagnosticLog()

        reasonEngineDoesNotRun(engine, diagnosticLog)

        val recorded = assertIs<DiagnosticLogEntry.EngineCallFailed>(diagnosticLog.recordedEntries.single())
        assertEquals(listOf("version"), recorded.arguments)
        assertEquals(1, recorded.exitCode)
        assertTrue("이 플랫폼의 것이 아닙니다" in recorded.standardError)
    }

    @Test
    fun `도는 엔진은 기록을 남기지 않는다`() {
        val engine = executableScript(
            """
            #!/bin/sh
            echo "kyu v0.10.0"
            """.trimIndent(),
        )
        val diagnosticLog = RecordingDiagnosticLog()

        reasonEngineDoesNotRun(engine, diagnosticLog)

        // 이 확인은 앱을 띄울 때마다 도는 걸음이다(동봉 엔진을 놓는 자리). 성공까지 남기면
        // 실행할 때마다 기록에 한 줄이 는다.
        assertEquals(emptyList(), diagnosticLog.recordedEntries)
    }

    @Test
    fun `띄우지도 못한 엔진은 그 사실이 기록에 남는다`() {
        val notExecutable = temporaryDirectory.resolve("kyu").apply {
            writeText("#!/bin/sh\nexit 0\n")
            setPosixFilePermissions(PosixFilePermissions.fromString("rw-r--r--"))
        }
        val diagnosticLog = RecordingDiagnosticLog()

        assertTrue(reasonEngineDoesNotRun(notExecutable, diagnosticLog)!!.isNotBlank())

        assertIs<DiagnosticLogEntry.EngineCallCouldNotStart>(diagnosticLog.recordedEntries.single())
    }

    private fun executableScript(source: String): Path =
        temporaryDirectory.resolve("kyu").apply {
            writeText(source + "\n")
            setPosixFilePermissions(PosixFilePermissions.fromString("rwxr-xr-x"))
        }
}
