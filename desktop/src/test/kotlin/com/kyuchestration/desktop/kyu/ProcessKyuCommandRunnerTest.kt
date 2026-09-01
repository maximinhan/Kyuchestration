package com.kyuchestration.desktop.kyu

import com.kyuchestration.desktop.diagnostics.DiagnosticLogEntry
import com.kyuchestration.desktop.diagnostics.RecordingDiagnosticLog
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.setPosixFilePermissions
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import java.nio.file.attribute.PosixFilePermissions
import org.junit.jupiter.api.Timeout
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

        assertFailsWith<KyuCommandFailure.FailedToStart> {
            ProcessKyuCommandRunner(notExecutable).run(emptyList())
        }
    }

    @Test
    fun `작업 디렉토리를 주면 그 자리에서 kyu 를 띄운다`() {
        // kyu init 은 이름을 작업 디렉토리 기준으로 푼다. 그 자리를 정해 주는 것이 실행기의 일이라
        // 여기서 실제 프로세스의 작업 디렉토리로 전달되는지 확인한다.
        val executable = executableScript(
            """
            #!/bin/sh
            pwd
            """.trimIndent(),
        )
        val workingDirectory = temporaryDirectory.resolve("여기서-실행").apply { createDirectories() }

        val result = ProcessKyuCommandRunner(executable).run(emptyList(), workingDirectory)

        assertEquals(workingDirectory.toRealPath().toString(), result.standardOutput.trim())
    }

    @Test
    fun `표준 입력으로 준 값은 자식 프로세스가 그대로 읽는다`() {
        // kyu auth add 는 토큰을 인자가 아니라 stdin 으로 받는다(auth_add.go) — 인자는 같은 머신의
        // 다른 사용자가 ps 로 읽는다. 그 통로가 실제로 이어지는지가 이 시험의 전부다.
        val executable = executableScript(
            """
            #!/bin/sh
            echo "받은 것: ${'$'}(cat)"
            """.trimIndent(),
        )

        val result = ProcessKyuCommandRunner(executable)
            .run(listOf("auth", "add", "개인", "--json"), standardInput = "ghp_예시토큰")

        assertEquals("받은 것: ghp_예시토큰\n", result.standardOutput)
    }

    @Test
    @Timeout(30)
    fun `줄 것이 없으면 표준 입력을 곧바로 닫아 자식이 기다리지 않게 한다`() {
        // 닫지 않으면 stdin 을 읽는 kyu 는 영영 끝나지 않고, 화면은 스피너를 돌린 채로 멎는다.
        val executable = executableScript(
            """
            #!/bin/sh
            cat > /dev/null
            echo "입력이 끝났다"
            """.trimIndent(),
        )

        val result = ProcessKyuCommandRunner(executable).run(emptyList())

        assertEquals("입력이 끝났다\n", result.standardOutput)
    }

    @Test
    fun `자식은 앱이 정한 환경만 물려받는다`() {
        // kyu 는 자기 자식으로 tmux 와 git 을 부른다. 그 둘을 찾는 PATH 가 이 자리에서 정해지므로
        // (Finder 로 띄운 맥 앱은 로그인 셸의 PATH 를 물려받지 못한다) 실제로 실려 가는지 본다.
        val executable = executableScript(
            """
            #!/bin/sh
            echo "PATH=${'$'}PATH"
            echo "HOME=${'$'}{HOME-물려받지 않음}"
            """.trimIndent(),
        )

        val result = ProcessKyuCommandRunner(
            executable,
            childProcessEnvironment = mapOf("PATH" to "/opt/homebrew/bin:/usr/bin"),
        ).run(emptyList())

        // 덧씌우는 것이 아니라 갈아 끼운다. HOME 은 이 검사를 돌리는 JVM 에 늘 있는 값이라,
        // 그것이 빈 채로 오는 것이 "부모 환경이 딸려 오지 않았다" 의 증거다.
        assertEquals("PATH=/opt/homebrew/bin:/usr/bin\nHOME=물려받지 않음\n", result.standardOutput)
    }

    @Test
    fun `0 이 아닌 코드로 끝나면 인자와 종료 코드와 stderr 를 기록에 남긴다`() {
        val executable = executableScript(
            """
            #!/bin/sh
            echo "없는 프로필입니다: 개인" >&2
            exit 1
            """.trimIndent(),
        )
        val diagnosticLog = RecordingDiagnosticLog()

        ProcessKyuCommandRunner(executable, diagnosticLog = diagnosticLog)
            .run(listOf("auth", "remove", "개인"))

        val recorded = diagnosticLog.recordedEntries.single()
        assertEquals(
            DiagnosticLogEntry.EngineCallFailed(
                arguments = listOf("auth", "remove", "개인"),
                exitCode = 1,
                standardError = "없는 프로필입니다: 개인\n",
            ),
            recorded,
        )
    }

    @Test
    fun `성공한 호출은 기록에 남기지 않는다`() {
        // 성공까지 남기면 기록이 정상적인 사용으로 찬다 — 대시보드가 3 초마다 kyu list 를 부르므로
        // 하루만 켜 두어도 봐야 할 줄이 그 아래 묻힌다.
        val executable = executableScript(
            """
            #!/bin/sh
            echo "{}"
            """.trimIndent(),
        )
        val diagnosticLog = RecordingDiagnosticLog()

        ProcessKyuCommandRunner(executable, diagnosticLog = diagnosticLog).run(listOf("list", "/w", "--json"))

        assertEquals(emptyList(), diagnosticLog.recordedEntries)
    }

    @Test
    fun `띄우지도 못한 호출은 종료 코드 없이 이유만 남긴다`() {
        val notExecutable = temporaryDirectory.resolve("kyu").apply {
            writeText("#!/bin/sh\nexit 0\n")
            setPosixFilePermissions(PosixFilePermissions.fromString("rw-r--r--"))
        }
        val diagnosticLog = RecordingDiagnosticLog()

        assertFailsWith<KyuCommandFailure.FailedToStart> {
            ProcessKyuCommandRunner(notExecutable, diagnosticLog = diagnosticLog).run(listOf("version"))
        }

        val recorded = assertIs<DiagnosticLogEntry.EngineCallCouldNotStart>(diagnosticLog.recordedEntries.single())
        assertEquals(listOf("version"), recorded.arguments)
        assertTrue(recorded.reason.isNotBlank(), "왜 띄우지 못했는지가 비어 있으면 기록이 사실을 잃는다")
    }

    /**
     * 토큰은 기록 어디에도 나타나지 않는다.
     *
     * 이 앱에서 비밀이 지나는 통로는 stdin 하나다(kyu auth add). 그 통로를 지나는 호출이
     * 실패하는 자리를 실제로 만들어, 남은 기록 전체를 훑어 토큰이 없음을 고정한다 — 기록 타입에
     * stdin 필드가 없다는 사실만으로도 지켜지지만, 그 결정이 뒷날 조용히 뒤집히지 않게 한다.
     */
    @Test
    fun `실패한 토큰 등록을 기록해도 토큰은 남지 않는다`() {
        val executable = executableScript(
            """
            #!/bin/sh
            cat > /dev/null
            echo "토큰이 거절당했습니다 (401). 아무것도 저장하지 않았습니다." >&2
            exit 1
            """.trimIndent(),
        )
        val diagnosticLog = RecordingDiagnosticLog()

        ProcessKyuCommandRunner(executable, diagnosticLog = diagnosticLog).run(
            arguments = listOf("auth", "add", "개인", "--json"),
            standardInput = SECRET_LOOKING_TOKEN,
        )

        assertFalse(
            diagnosticLog.recordedText().contains(SECRET_LOOKING_TOKEN),
            "기록에 토큰이 실렸다: ${diagnosticLog.recordedText()}",
        )
    }

    private fun executableScript(source: String): Path =
        temporaryDirectory.resolve("kyu").apply {
            writeText(source + "\n")
            setPosixFilePermissions(PosixFilePermissions.fromString("rwxr-xr-x"))
        }

    private companion object {
        const val SECRET_LOOKING_TOKEN = "ghp_이것이기록에나타나면안된다1234567890"
    }
}
