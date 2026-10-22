package com.kyuchestration.desktop.wsl

import com.kyuchestration.desktop.mirror.WSL_CORE_EXECUTABLE_FROM_HOME
import com.kyuchestration.desktop.mirror.wslProcessBuilder
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** `wsl.exe -l -v` 의 답. */
sealed interface WslDistributionListing {

    data class Listed(val distributions: WslDistributions) : WslDistributionListing

    /** @param wslMessage `wsl.exe` 가 스스로 쓴 글. 사람이 그대로 읽는다 — 윈도우의 언어로 온다. */
    data class Failed(val wslMessage: String) : WslDistributionListing
}

/** 고른 배포판에 코어가 깔려 있는가. */
sealed interface WslCoreCheck {

    data object Installed : WslCoreCheck

    data object NotInstalled : WslCoreCheck

    /** `wsl.exe` 가 그 배포판을 띄우지 못했다(배포판이 지워졌다 · 깨졌다). */
    data class DistributionFailed(val wslMessage: String) : WslCoreCheck
}

/**
 * 윈도우 화면이 코어를 띄우기 전에 `wsl.exe` 에게 묻는 두 가지(core-ui-split-design.md 6.17).
 *
 * 둘 다 셸 없이 부르고 `WSL_UTF8` 을 켠다 — 코어를 띄우는 것과 같은 길이다(wslProcessBuilder). `wsl.exe` 의 오류는 stdout 과
 * stderr 중 어디로 올지 판마다 달라 둘을 합쳐 읽는다.
 */
class WslCommandLine(private val wslExecutable: Path) {

    suspend fun listDistributions(): WslDistributionListing {
        val (exitCode, output) = runWsl(listOf("-l", "-v"))
        return if (exitCode == 0) {
            WslDistributionListing.Listed(parseWslDistributionList(output))
        } else {
            WslDistributionListing.Failed(wslMessageOf(output))
        }
    }

    /**
     * 코어를 띄울 때와 같은 `--cd ~` 와 홈 기준 상대 경로로 코어 실행 파일을 `test -x` 로 본다. 셸이 없으니 `test` 는 실행 파일
     * (`/usr/bin/test`)이다. 1 은 `test` 의 "아니다" 이고, 그 밖의 실패는 `wsl.exe` 의 것이다 — 리눅스 쪽에서 255 로, 윈도우 JVM
     * 에서는 -1 로 보인다.
     */
    suspend fun checkCore(distributionName: String): WslCoreCheck {
        val (exitCode, output) = runWsl(
            listOf("-d", distributionName, "--cd", "~", "-e", "test", "-x", WSL_CORE_EXECUTABLE_FROM_HOME),
        )
        return when (exitCode) {
            0 -> WslCoreCheck.Installed
            TEST_SAYS_NO -> WslCoreCheck.NotInstalled
            else -> WslCoreCheck.DistributionFailed(wslMessageOf(output))
        }
    }

    private suspend fun runWsl(arguments: List<String>): Pair<Int, ByteArray> = withContext(Dispatchers.IO) {
        val process = wslProcessBuilder(wslExecutable, arguments).redirectErrorStream(true).start()
        process.outputStream.close()
        val output = process.inputStream.use { it.readBytes() }
        process.waitFor() to output
    }
}

/** 사람에게 그대로 보일 `wsl.exe` 의 글. 윈도우의 줄 끝(`\r\n`)은 화면의 글자 칸이 그리는 줄 끝으로 바꾼다. */
private fun wslMessageOf(output: ByteArray): String = decodeWslOutput(output).replace("\r\n", "\n").trim()

/**
 * 윈도우의 `wsl.exe`. 윈도우가 C: 가 아닌 드라이브에 깔려 있어도 맞게 `SystemRoot` 를 따른다. 이름으로 PATH 에서 찾지 않는 까닭은
 * CorePlace.WslDistribution 에 적었다.
 */
fun wslExecutablePath(lookUpEnvironmentVariable: (String) -> String? = System::getenv): Path {
    val systemRoot = checkNotNull(lookUpEnvironmentVariable(SYSTEM_ROOT_VARIABLE)?.takeIf { it.isNotBlank() }) {
        "윈도우가 언제나 채워 두는 $SYSTEM_ROOT_VARIABLE 가 비어 있습니다"
    }
    return Path.of(systemRoot).resolve("System32").resolve("wsl.exe")
}

private const val TEST_SAYS_NO = 1

private const val SYSTEM_ROOT_VARIABLE = "SystemRoot"
