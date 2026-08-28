package com.kyuchestration.desktop.workdir.kyucli

import com.kyuchestration.desktop.workdir.WorkDirObservationFailure
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.setPosixFilePermissions
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import java.nio.file.attribute.PosixFilePermissions
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS

// 셸 스크립트를 실행 파일로 세워 프로세스 다루기만 시험한다. 윈도우에는 /bin/sh 가 없으므로
// 그쪽에서는 건너뛴다 — 이 클래스가 시험하는 것은 계약이 아니라 이 구현의 프로세스 취급이다.
@DisabledOnOs(OS.WINDOWS)
class ProcessKyuCommandRunnerTest {

    private val temporaryDirectory = createTempDirectory("kyu-runner-test")

    @AfterTest
    fun 임시_디렉토리를_지운다() {
        temporaryDirectory.toFile().deleteRecursively()
    }

    @Test
    fun `stdout 과 stderr 와 종료 코드를 갈라 담는다`() {
        val executable = executableScript(
            """
            #!/bin/sh
            echo "인자: $*"
            echo "사람에게 하는 말" >&2
            exit 3
            """.trimIndent(),
        )

        val result = ProcessKyuCommandRunner(executable).run(listOf("list", "/w", "--json"))

        assertEquals(3, result.exitCode)
        assertEquals("인자: list /w --json\n", result.standardOutput)
        assertEquals("사람에게 하는 말\n", result.standardError)
    }

    @Test
    fun `한쪽 파이프가 넘칠 만큼 길게 내보내도 멈추지 않는다`() {
        // stderr 를 다른 스레드에서 비우지 않으면 여기서 교착으로 굳는다. 파이프 버퍼(리눅스 64KB)
        // 보다 확실히 큰 양을 stderr 로 먼저 쏟아 그 상황을 만든다.
        val executable = executableScript(
            """
            #!/bin/sh
            i=0
            while [ ${'$'}i -lt 5000 ]; do
              echo "표준 오류로 흘려보내는 긴 줄 ${'$'}i" >&2
              i=${'$'}((i + 1))
            done
            echo "{}"
            """.trimIndent(),
        )

        val result = ProcessKyuCommandRunner(executable).run(emptyList())

        assertEquals(0, result.exitCode)
        assertEquals("{}\n", result.standardOutput)
        assertEquals(5000, result.standardError.trim().lines().size)
    }

    @Test
    fun `실행 권한이 없는 파일을 겨누면 실행하지 못했다고 보고한다`() {
        val notExecutable = temporaryDirectory.resolve("kyu").apply {
            writeText("#!/bin/sh\nexit 0\n")
            setPosixFilePermissions(PosixFilePermissions.fromString("rw-r--r--"))
        }

        assertFailsWith<WorkDirObservationFailure.KyuFailedToStart> {
            ProcessKyuCommandRunner(notExecutable).run(emptyList())
        }
    }

    private fun executableScript(source: String): Path =
        temporaryDirectory.resolve("kyu").apply {
            writeText(source + "\n")
            setPosixFilePermissions(PosixFilePermissions.fromString("rwxr-xr-x"))
        }
}
