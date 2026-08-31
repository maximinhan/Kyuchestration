package com.kyuchestration.desktop.kyu

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.setPosixFilePermissions
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    private fun executableScript(source: String): Path =
        temporaryDirectory.resolve("kyu").apply {
            writeText(source + "\n")
            setPosixFilePermissions(PosixFilePermissions.fromString("rwxr-xr-x"))
        }
}
