package com.kyuchestration.desktop.wsl

import com.kyuchestration.desktop.platform.runningOnWindows
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.io.path.setPosixFilePermissions
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assumptions.assumeFalse

/**
 * 화면이 `wsl.exe` 에게 묻는 두 가지 — 배포판 목록, 고른 배포판에 코어가 있는가.
 *
 * 진짜 `wsl.exe` 대신 받은 인자와 환경을 적고 정해 둔 답을 내는 셸 스크립트를 세운다. 그래서 윈도우에서는 건너뛴다 — 윈도우에서
 * `wsl.exe` 와의 실제 왕복은 PR 의 실측(윈도우 JVM 하네스)이 잰다.
 */
class WslCommandLineTest {

    private lateinit var scratchDirectory: Path

    private lateinit var fakeWsl: Path

    private lateinit var receivedArguments: Path

    @BeforeTest
    fun setUp() {
        assumeFalse(runningOnWindows(), "가짜 wsl.exe 가 셸 스크립트라 윈도우에서는 돌지 않는다")
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
        fakeWsl.writeText(
            """
            #!/bin/sh
            printf '%s\n' "${'$'}WSL_UTF8" "${'$'}@" > '$receivedArguments'
            printf '%s' '$output'
            exit $exitCode
            """.trimIndent(),
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
}
