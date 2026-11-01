package com.kyuchestration.desktop.wsl

import com.kyuchestration.desktop.mirror.WSL_CORE_EXECUTABLE_FROM_HOME
import com.kyuchestration.desktop.mirror.wslProcessBuilder
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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

/** 깔린 코어의 판(`~/.local/share/kyuchestration/core/VERSION`). */
sealed interface InstalledWslCoreVersion {

    data class Known(val versionLabel: String) : InstalledWslCoreVersion

    /** `VERSION` 이 없거나 한 줄이 아니다 — 4a 이전에 손으로 깐 코어다. */
    data object Unknown : InstalledWslCoreVersion

    data class DistributionFailed(val wslMessage: String) : InstalledWslCoreVersion
}

/** @param output 합쳐 읽은 stdout · stderr. 윈도우의 줄 끝은 `\n` 으로 바꾸고 앞뒤 빈칸을 걷었다. */
data class WslCommandResult(val exitCode: Int, val output: String)

/**
 * 윈도우 화면이 코어를 띄우기 전에 `wsl.exe` 너머에서 하는 일(core-ui-split-design.md 6.17) — 배포판 목록, 코어가 있는가와 그 판,
 * 묶음으로 코어 깔기의 걸음들.
 *
 * 모두 셸 없이 부르고 `WSL_UTF8` 을 켠다 — 코어를 띄우는 것과 같은 길이다(wslProcessBuilder). 리눅스 쪽 명령은 `--cd ~` 의 홈에서
 * 돌고, 인자는 따옴표가 필요 없는 낱말만 넘긴다(3.7). `wsl.exe` 의 오류는 stdout 과 stderr 중 어디로 올지 판마다 달라 둘을 합쳐 읽는다.
 */
class WslCommandLine(private val wslExecutable: Path) : WslDistributionCommands {

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
     * (`/usr/bin/test`)이다. 아무 글 없이 1 로 끝나면 `test` 의 "아니다" 다. 그 밖은 `wsl.exe` 의 실패다 — 없는 배포판은 리눅스
     * 쪽에서 255(윈도우 JVM 에서는 -1)이고, 커널 갱신 · 기본 사용자 없음 같은 것은 글을 쓰고 1 로 끝난다. `test` 는 아무것도 쓰지
     * 않으므로 글이 있으면 `wsl.exe` 의 것이다.
     */
    suspend fun checkCore(distributionName: String): WslCoreCheck {
        val (exitCode, output) = runWsl(inDistribution(distributionName, listOf("test", "-x", WSL_CORE_EXECUTABLE_FROM_HOME)))
        return when {
            exitCode == 0 -> WslCoreCheck.Installed
            exitCode == TEST_SAYS_NO && output.isEmpty() -> WslCoreCheck.NotInstalled
            else -> WslCoreCheck.DistributionFailed(wslMessageOf(output))
        }
    }

    /**
     * 코어를 본 바로 뒤에 부른다. 없는 `VERSION` 에 `cat` 은 글을 쓰고 1 로 끝나므로 [checkCore] 처럼 "글이 있으면 `wsl.exe` 의 실패" 로
     * 가르지 않는다 — 0 과 한 줄만 판으로 믿고, `wsl.exe` 자신의 실패(없는 배포판의 255, 윈도우 JVM 에서 -1)만 따로 가른다. 1 은
     * `cat` 의 것이다 — 바로 앞의 `test -x` 가 그 배포판을 띄웠으니 `wsl.exe` 가 1 로 끝내는 실패(커널 갱신 · 기본 사용자 없음)는 이미
     * 지났다.
     */
    suspend fun readInstalledCoreVersion(distributionName: String): InstalledWslCoreVersion {
        val (exitCode, output) = runWsl(inDistribution(distributionName, listOf("cat", WSL_INSTALLED_CORE_VERSION_FROM_HOME)))
        val versionLines = wslMessageOf(output).lines().filter { it.isNotBlank() }
        return when {
            exitCode == 0 && versionLines.size == 1 -> InstalledWslCoreVersion.Known(versionLines.single().trim())
            exitCode == 0 || exitCode == CAT_SAYS_UNREADABLE -> InstalledWslCoreVersion.Unknown
            else -> InstalledWslCoreVersion.DistributionFailed(wslMessageOf(output))
        }
    }

    override suspend fun run(distributionName: String, linuxCommand: List<String>): WslCommandResult {
        val (exitCode, output) = runWsl(inDistribution(distributionName, linuxCommand))
        return WslCommandResult(exitCode, wslMessageOf(output))
    }

    /**
     * [standardInput] 파일의 바이트를 리눅스 명령의 stdin 으로 그대로 흘려 넣는다(3.10 — 0x00 · 0x0D0A · 0x1A 도 바뀌지 않는다).
     *
     * 쓰기와 읽기를 다른 스레드에서 한다. 리눅스 쪽이 stdin 을 읽기 전에 파이프 버퍼보다 많이 쓰면, 같은 스레드가 stdin 을 다 쓴 뒤에
     * 출력을 읽는 길에서는 둘이 서로를 기다린다.
     */
    override suspend fun runFeedingStandardInput(distributionName: String, linuxCommand: List<String>, standardInput: Path): WslCommandResult =
        withContext(Dispatchers.IO) {
            val process = wslProcessBuilder(wslExecutable, inDistribution(distributionName, linuxCommand)).redirectErrorStream(true).start()
            val feeding = async { feedStandardInput(process, standardInput) }
            val output = process.inputStream.use { it.readBytes() }
            val exitCode = process.waitFor()
            val pipeFailure = feeding.await()
            // 리눅스 쪽이 stdin 을 다 읽지 않고 끝나면 남은 쓰기는 끊긴 파이프에 닿는다. 그 끊김은 결과이고 까닭은 리눅스 쪽의 종료 코드와
            // 글이라 그것을 돌려준다. 리눅스 쪽은 0 으로 끝났는데 다 넘기지 못했다면 그 자체가 실패다 — 덜 받은 묶음으로 잘 끝났다고 볼 수 없다.
            if (pipeFailure != null && exitCode == 0) {
                throw pipeFailure
            }
            WslCommandResult(exitCode, wslMessageOf(output))
        }

    /**
     * 리눅스 명령의 출력을 줄마다 끝나기 전에 [onOutputLine] 으로 알리고 종료 코드를 돌려준다(3.10 — 버퍼링 없이 줄마다 온다).
     *
     * stdout · stderr 를 합쳐 읽는다. 설치 스크립트의 진행은 stderr, 마지막 `kyu version` 은 stdout 이고, `wsl.exe` 자신의 오류는
     * 판마다 어느 쪽인지 다르다 — 어느 것도 버리지 않는다. 문자셋을 UTF-8 로 정해 읽는다. 윈도우 JVM 의 기본 문자셋은 MS949 라(3.10)
     * 기대면 한국어가 깨진다. 리눅스 쪽 글은 바이트 그대로 오고(3.1) `wsl.exe` 의 글은 `WSL_UTF8` 로 UTF-8 이다.
     */
    override suspend fun runReportingEachLine(
        distributionName: String,
        linuxCommand: List<String>,
        onOutputLine: (String) -> Unit,
    ): Int = withContext(Dispatchers.IO) {
        val process = wslProcessBuilder(wslExecutable, inDistribution(distributionName, linuxCommand)).redirectErrorStream(true).start()
        process.outputStream.close()
        process.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines -> lines.forEach(onOutputLine) }
        process.waitFor()
    }

    private suspend fun runWsl(arguments: List<String>): Pair<Int, ByteArray> = withContext(Dispatchers.IO) {
        val process = wslProcessBuilder(wslExecutable, arguments).redirectErrorStream(true).start()
        process.outputStream.close()
        val output = process.inputStream.use { it.readBytes() }
        process.waitFor() to output
    }
}

private fun inDistribution(distributionName: String, linuxCommand: List<String>): List<String> =
    listOf("-d", distributionName, "--cd", "~", "-e") + linuxCommand

/**
 * 끊긴 파이프는 돌려주고([WslCommandLine.runFeedingStandardInput] 이 리눅스 쪽 결과와 함께 판단한다), 파일을 읽다 난 실패는 던진다 —
 * 그것은 리눅스 쪽과 무관한 화면 쪽 사실이다. 어느 쪽이든 stdin 은 닫는다. 닫지 않으면 리눅스 쪽이 끝을 기다려 출력을 읽는 쪽도
 * 끝나지 않는다.
 */
private fun feedStandardInput(process: Process, standardInput: Path): IOException? {
    val linuxStandardInput = process.outputStream
    var pipeFailure: IOException? = null
    try {
        Files.newInputStream(standardInput).use { source ->
            val buffer = ByteArray(STANDARD_INPUT_CHUNK_BYTES)
            while (pipeFailure == null) {
                val read = source.read(buffer)
                if (read < 0) {
                    break
                }
                pipeFailure = pipeFailureOf { linuxStandardInput.write(buffer, 0, read) }
            }
        }
    } finally {
        val closeFailure = pipeFailureOf { linuxStandardInput.close() }
        pipeFailure = pipeFailure ?: closeFailure
    }
    return pipeFailure
}

private fun pipeFailureOf(pipeWrite: () -> Unit): IOException? = try {
    pipeWrite()
    null
} catch (brokenPipe: IOException) {
    brokenPipe
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

private const val CAT_SAYS_UNREADABLE = 1

private const val STANDARD_INPUT_CHUNK_BYTES = 64 * 1024

/** 깔린 코어의 판. 손으로든 화면이든 `install.sh --with-core` 가 남긴다(core-ui-split-design.md 6.16 의 4a 정정). */
internal const val WSL_INSTALLED_CORE_VERSION_FROM_HOME = ".local/share/kyuchestration/core/VERSION"

private const val SYSTEM_ROOT_VARIABLE = "SystemRoot"
