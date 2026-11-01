package com.kyuchestration.desktop.wsl

import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.setPosixFilePermissions
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS

/**
 * 화면이 `wsl.exe` 에게 묻는 두 가지 — 배포판 목록, 고른 배포판에 코어가 있는가.
 *
 * 진짜 `wsl.exe` 대신 받은 인자와 환경을 적고 정해 둔 답을 내는 셸 스크립트를 세운다.
 */
@DisabledOnOs(OS.WINDOWS, disabledReason = "가짜 wsl.exe 가 셸 스크립트다 — 윈도우에서 wsl.exe 와의 실제 왕복은 PR 의 실측이 잰다")
class WslCommandLineTest {

    private lateinit var scratchDirectory: Path

    private lateinit var fakeWsl: Path

    private lateinit var receivedArguments: Path

    @BeforeTest
    fun setUp() {
        scratchDirectory = createTempDirectory("kyu-fake-wsl")
        fakeWsl = scratchDirectory.resolve("wsl.exe")
        receivedArguments = scratchDirectory.resolve("arguments.txt")
    }

    @AfterTest
    fun cleanUp() {
        if (::scratchDirectory.isInitialized) {
            scratchDirectory.toFile().deleteRecursively()
        }
    }

    private fun fakeWslAnswering(output: String, exitCode: Int) {
        fakeWslRunning(
            """
            printf '%s' '$output'
            exit $exitCode
            """.trimIndent(),
        )
    }

    /** 받은 인자를 적은 뒤 [body] 를 돈다. [body] 는 리눅스 쪽 명령인 척한다. */
    private fun fakeWslRunning(body: String) {
        fakeWsl.writeText(
            "#!/bin/sh\n" +
                "printf '%s\\n' \"${'$'}WSL_UTF8\" \"${'$'}@\" > '$receivedArguments'\n" +
                body + "\n",
        )
        fakeWsl.setPosixFilePermissions(setOf(
            java.nio.file.attribute.PosixFilePermission.OWNER_READ,
            java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
            java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE,
        ))
    }

    private fun receivedLines(): List<String> = receivedArguments.readText().lines().dropLast(1)

    @Test
    fun `배포판 목록을 -l -v 로 묻고 WSL_UTF8 을 켠다`() = runTest {
        fakeWslAnswering("  NAME   STATE   VERSION\r\n* Ubuntu-24.04   Running   2\r\n", exitCode = 0)

        val listing = WslCommandLine(fakeWsl).listDistributions()

        assertEquals(WslDistributionListing.Listed(WslDistributions(listOf("Ubuntu-24.04"), "Ubuntu-24.04")), listing)
        assertEquals(listOf("1", "-l", "-v"), receivedLines())
    }

    @Test
    fun `목록을 묻다 wsl 이 실패하면 그 말을 돌려준다`() = runTest {
        fakeWslAnswering("Linux용 Windows 하위 시스템에 설치된 배포가 없습니다.\r\n", exitCode = 255)

        assertEquals(
            WslDistributionListing.Failed("Linux용 Windows 하위 시스템에 설치된 배포가 없습니다."),
            WslCommandLine(fakeWsl).listDistributions(),
        )
    }

    /** 셸 없이 `test` 를 부른다 — 코어를 띄우는 것과 같은 `--cd ~` 와 홈 기준 상대 경로로 같은 자리를 본다. */
    @Test
    fun `코어가 있는지는 그 배포판의 홈에서 코어 실행 파일을 test -x 로 본다`() = runTest {
        fakeWslAnswering("", exitCode = 0)

        val check = WslCommandLine(fakeWsl).checkCore("Ubuntu-24.04")

        assertEquals(WslCoreCheck.Installed, check)
        assertEquals(
            listOf("1", "-d", "Ubuntu-24.04", "--cd", "~", "-e", "test", "-x", ".local/bin/kyuchestration-core"),
            receivedLines(),
        )
    }

    @Test
    fun `test 가 1 로 끝나면 코어가 없다`() = runTest {
        fakeWslAnswering("", exitCode = 1)

        assertEquals(WslCoreCheck.NotInstalled, WslCommandLine(fakeWsl).checkCore("Ubuntu-24.04"))
    }

    /**
     * `wsl.exe` 자신도 1 로 끝난다 — 배포판을 WSL 2 로 바꾸라 · 커널을 갱신하라 · 기본 사용자가 없다 같은 말을 쓰고. 셸 없는 `test` 는
     * 아무것도 쓰지 않으므로, 1 이라도 글이 있으면 `wsl.exe` 의 실패다. "코어를 설치하세요" 로 보이면 그 말이 버려진다.
     */
    @Test
    fun `1 로 끝나도 wsl 이 글을 썼으면 코어가 없는 것이 아니라 배포판을 띄우지 못한 것이다`() = runTest {
        fakeWslAnswering("WSL 2 를 사용하려면 커널 구성 요소를 업데이트해야 합니다.\r\n", exitCode = 1)

        assertEquals(
            WslCoreCheck.DistributionFailed("WSL 2 를 사용하려면 커널 구성 요소를 업데이트해야 합니다."),
            WslCommandLine(fakeWsl).checkCore("Ubuntu-24.04"),
        )
    }

    @Test
    fun `그 밖의 종료 코드는 wsl 이 배포판을 띄우지 못한 것이고 그 말을 돌려준다`() = runTest {
        fakeWslAnswering("제공된 이름의 배포가 없습니다.\r\n오류 코드: Wsl/Service/WSL_E_DISTRO_NOT_FOUND\r\n", exitCode = 255)

        assertEquals(
            WslCoreCheck.DistributionFailed("제공된 이름의 배포가 없습니다.\n오류 코드: Wsl/Service/WSL_E_DISTRO_NOT_FOUND"),
            WslCommandLine(fakeWsl).checkCore("NoSuchDistro"),
        )
    }

    /** 4a 이후의 설치(손으로든 화면이든)가 깔린 코어 디렉토리에 남기는 판(core-ui-split-design.md 6.16 의 4a 정정). */
    @Test
    fun `깔린 코어의 판은 그 배포판의 홈에서 VERSION 을 cat 으로 읽는다`() = runTest {
        fakeWslAnswering("v0.16.0\n", exitCode = 0)

        val version = WslCommandLine(fakeWsl).readInstalledCoreVersion("Ubuntu-24.04")

        assertEquals(InstalledWslCoreVersion.Known("v0.16.0"), version)
        assertEquals(
            listOf("1", "-d", "Ubuntu-24.04", "--cd", "~", "-e", "cat", ".local/share/kyuchestration/core/VERSION"),
            receivedLines(),
        )
    }

    /** 4a 이전에 손으로 깐 코어에는 VERSION 이 없다. `cat` 은 그때 `cat:` 으로 시작하는 글을 쓰고 1 로 끝난다. */
    @Test
    fun `VERSION 이 없으면 판을 모른다`() = runTest {
        fakeWslAnswering("cat: .local/share/kyuchestration/core/VERSION: No such file or directory\n", exitCode = 1)

        assertEquals(InstalledWslCoreVersion.Unknown, WslCommandLine(fakeWsl).readInstalledCoreVersion("Ubuntu-24.04"))
    }

    /**
     * `wsl.exe` 자신도 글을 쓰고 1 로 끝난다(커널 갱신 · 기본 사용자 없음). 바로 앞의 `test -x` 와 이 호출 사이에 배포판이 멈추거나
     * 지워질 수 있다 — `cat` 의 글이 아니면 판을 모르는 것이 아니라 배포판을 띄우지 못한 것이다.
     */
    @Test
    fun `1 로 끝나도 cat 의 글이 아니면 배포판을 띄우지 못한 것이다`() = runTest {
        fakeWslAnswering("WSL 2 를 사용하려면 커널 구성 요소를 업데이트해야 합니다.\r\n", exitCode = 1)

        assertEquals(
            InstalledWslCoreVersion.DistributionFailed("WSL 2 를 사용하려면 커널 구성 요소를 업데이트해야 합니다."),
            WslCommandLine(fakeWsl).readInstalledCoreVersion("Ubuntu-24.04"),
        )
    }

    @Test
    fun `VERSION 이 비었거나 한 줄이 아니면 판을 모른다`() = runTest {
        fakeWslAnswering("", exitCode = 0)
        assertEquals(InstalledWslCoreVersion.Unknown, WslCommandLine(fakeWsl).readInstalledCoreVersion("Ubuntu-24.04"))

        fakeWslAnswering("v0.16.0\nv0.15.0\n", exitCode = 0)
        assertEquals(InstalledWslCoreVersion.Unknown, WslCommandLine(fakeWsl).readInstalledCoreVersion("Ubuntu-24.04"))
    }

    @Test
    fun `판을 읽다 wsl 이 배포판을 띄우지 못하면 그 말을 돌려준다`() = runTest {
        fakeWslAnswering("제공된 이름의 배포가 없습니다.\r\n", exitCode = 255)

        assertEquals(
            InstalledWslCoreVersion.DistributionFailed("제공된 이름의 배포가 없습니다."),
            WslCommandLine(fakeWsl).readInstalledCoreVersion("Ubuntu-24.04"),
        )
    }

    @Test
    fun `리눅스 명령을 셸 없이 홈에서 돌리고 종료 코드와 글을 돌려준다`() = runTest {
        fakeWslAnswering("/home/yawn\n", exitCode = 0)

        val result = WslCommandLine(fakeWsl).run("Ubuntu-24.04", listOf("pwd"))

        assertEquals(WslCommandResult(exitCode = 0, output = "/home/yawn"), result)
        assertEquals(listOf("1", "-d", "Ubuntu-24.04", "--cd", "~", "-e", "pwd"), receivedLines())
    }

    /**
     * 묶음 tar.gz 의 바이트가 하나도 바뀌지 않고 간다. 리눅스 쪽이 stdin 을 읽기 전에 파이프 버퍼(64 KiB)보다 많이 써도 막히지 않는다 —
     * 같은 스레드가 stdin 을 다 쓴 뒤에 출력을 읽으면 둘이 서로를 기다린다.
     */
    @Test
    fun `stdin 으로 파일을 흘려 넣는 동안 출력을 따로 읽어 서로 막히지 않는다`() = runTest {
        val received = scratchDirectory.resolve("received.bin")
        fakeWslRunning(
            """
            head -c 200000 /dev/zero | tr '\000' 'x'
            cat > '$received'
            exit 0
            """.trimIndent(),
        )
        val sent = scratchDirectory.resolve("bundle.tar.gz")
        sent.writeBytes(ByteArray(3 * 1024 * 1024) { index -> (index * 31 % 256).toByte() })

        val result = WslCommandLine(fakeWsl).runFeedingStandardInput(
            "Ubuntu-24.04",
            listOf("tar", "-xzf", "-", "-C", ".local/share/kyuchestration/.screen-install"),
            standardInput = sent,
        )

        assertEquals(0, result.exitCode)
        assertEquals(200_000, result.output.length)
        assertContentEquals(sent.readBytes(), received.readBytes())
        assertEquals(
            listOf("1", "-d", "Ubuntu-24.04", "--cd", "~", "-e", "tar", "-xzf", "-", "-C", ".local/share/kyuchestration/.screen-install"),
            receivedLines(),
        )
    }

    /**
     * 리눅스 쪽이 stdin 을 다 읽지 않고 끝나면 남은 쓰기는 끊긴 파이프에 닿는다. 그 끊김은 결과이고 까닭은 리눅스 쪽의 종료 코드와 글이다 —
     * 둘을 함께 돌려준다.
     */
    @Test
    fun `리눅스 쪽이 stdin 을 다 읽기 전에 실패하면 그 실패와 끊긴 파이프를 함께 돌려준다`() = runTest {
        fakeWslRunning(
            """
            echo 'gzip: stdin: not in gzip format' >&2
            exit 2
            """.trimIndent(),
        )
        val sent = scratchDirectory.resolve("bundle.tar.gz")
        sent.writeBytes(ByteArray(8 * 1024 * 1024))

        val result = WslCommandLine(fakeWsl).runFeedingStandardInput("Ubuntu-24.04", listOf("tar", "-xzf", "-"), standardInput = sent)

        assertEquals(2, result.exitCode)
        assertEquals("gzip: stdin: not in gzip format", result.output)
        assertTrue(result.standardInputFailure != null, "8 MiB 를 다 넘기지 못했는데 까닭이 없다")
    }

    /** 화면 쪽에서 파일을 못 읽어도 던지지 않는다 — stdin 을 닫고 리눅스 쪽의 종료 코드와 글을 끝까지 거둔다. */
    @Test
    fun `넘길 파일을 못 읽어도 리눅스 쪽의 결과를 거두어 그 까닭과 함께 돌려준다`() = runTest {
        fakeWslRunning(
            """
            cat > /dev/null
            echo 'gzip: stdin: unexpected end of file' >&2
            exit 2
            """.trimIndent(),
        )

        val result = WslCommandLine(fakeWsl).runFeedingStandardInput(
            "Ubuntu-24.04",
            listOf("tar", "-xzf", "-"),
            standardInput = scratchDirectory.resolve("no-such-bundle.tar.gz"),
        )

        assertEquals(2, result.exitCode)
        assertEquals("gzip: stdin: unexpected end of file", result.output)
        assertTrue(result.standardInputFailure.orEmpty().contains("no-such-bundle.tar.gz"), result.standardInputFailure)
    }

    /**
     * 설치 스크립트의 진행 줄을 끝나기 전에 받는다 — 가짜는 첫 줄을 받은 쪽이 표시 파일을 만들 때까지 끝나지 않는다. stdout · stderr 를
     * 함께 받고, 한국어는 UTF-8 로 읽는다(윈도우 JVM 의 기본 문자셋은 MS949 다 — 3.10).
     */
    @Test
    fun `리눅스 명령의 출력을 끝나기 전에 줄마다 알리고 종료 코드를 돌려준다`() = runTest {
        val firstLineSeen = scratchDirectory.resolve("first-line-seen")
        fakeWslRunning(
            """
            echo '대상: linux_amd64' >&2
            while [ ! -f '$firstLineSeen' ]; do sleep 0.05; done
            echo 'kyu v0.16.0 (abc)'
            exit 3
            """.trimIndent(),
        )
        val reportedLines = mutableListOf<String>()

        val exitCode = WslCommandLine(fakeWsl).runReportingEachLine("Ubuntu-24.04", listOf("env", "A=b", "bash", "install.sh")) { line ->
            reportedLines += line
            firstLineSeen.toFile().createNewFile()
        }

        assertEquals(3, exitCode)
        assertEquals(listOf("대상: linux_amd64", "kyu v0.16.0 (abc)"), reportedLines)
        assertTrue(firstLineSeen.exists())
        assertEquals(listOf("1", "-d", "Ubuntu-24.04", "--cd", "~", "-e", "env", "A=b", "bash", "install.sh"), receivedLines())
    }

    /**
     * 설치 중에 준비 창을 닫으면 준비 코루틴이 취소된다. 막힌 읽기는 취소를 모르므로 `wsl.exe` 를 끝내야 돌아온다 — 끝내지 않으면 그
     * 자식이 화면 뒤에 남아 다음 실행의 걸음과 겹친다. 끝날 때까지 기다린 뒤에 취소가 끝난다.
     */
    @Test
    fun `부른 쪽이 취소되면 돌고 있는 wsl 을 끝내고 끝날 때까지 기다린다`() = runBlocking {
        val pidFile = scratchDirectory.resolve("fake-wsl.pid")
        fakeWslRunning(
            """
            echo ${'$'}${'$'} > '$pidFile'
            exec sleep 30
            """.trimIndent(),
        )
        val commandLine = WslCommandLine(fakeWsl)
        val runs = listOf<suspend () -> Unit>(
            { commandLine.run("Ubuntu-24.04", listOf("sleep")) },
            { commandLine.runFeedingStandardInput("Ubuntu-24.04", listOf("sleep"), standardInput = fakeWsl) },
            { commandLine.runReportingEachLine("Ubuntu-24.04", listOf("sleep")) {} },
        )
        for (runOne in runs) {
            pidFile.toFile().delete()
            val running = launch(Dispatchers.Default) { runOne() }
            withTimeout(10.seconds) {
                while (!pidFile.exists() || pidFile.readText().isBlank()) {
                    kotlinx.coroutines.delay(20)
                }
            }
            val pid = pidFile.readText().trim().toLong()

            withTimeout(10.seconds) { running.cancelAndJoin() }

            assertTrue(ProcessHandle.of(pid).map { !it.isAlive }.orElse(true), "취소한 뒤에도 가짜 wsl.exe(pid $pid)가 살아 있다")
        }
    }
}
