package com.kyuchestration.desktop.wsl

import com.kyuchestration.desktop.mirror.WSL_CORE_EXECUTABLE_FROM_HOME
import com.kyuchestration.desktop.mirror.wslProcessBuilder
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
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

/**
 * @param output 합쳐 읽은 stdout · stderr. 윈도우의 줄 끝은 `\n` 으로 바꾸고 앞뒤 빈칸을 걷었다.
 * @param standardInputFailure stdin 으로 파일을 다 넘기지 못한 까닭(화면 쪽 사실 — 파일을 못 읽음 · 끊긴 파이프). 리눅스 쪽 결과는
 *   그래도 끝까지 거둔다. stdin 을 넘기지 않는 호출에서는 늘 null.
 */
data class WslCommandResult(val exitCode: Int, val output: String, val standardInputFailure: String? = null)

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
     * 0 과 한 줄만 판으로 믿는다. 없는 `VERSION` 에 `cat` 은 `cat:` 으로 시작하는 글을 쓰고 1 로 끝난다 — 그것만 "판 모름" 이다.
     * [checkCore] 처럼 "1 + 글" 을 통째로 `wsl.exe` 의 실패로 보면 4a 이전에 깐 코어가 배포판 실패로 보이고, 통째로 판 모름으로 보면
     * `wsl.exe` 가 1 로 끝내는 실패(커널 갱신 · 기본 사용자 없음)가 "다시 깐다" 로 묻힌다. 그래서 글의 주인을 본다.
     */
    suspend fun readInstalledCoreVersion(distributionName: String): InstalledWslCoreVersion {
        val (exitCode, output) = runWsl(inDistribution(distributionName, listOf("cat", WSL_INSTALLED_CORE_VERSION_FROM_HOME)))
        val message = wslMessageOf(output)
        val versionLines = message.lines().filter { it.isNotBlank() }
        return when {
            exitCode == 0 && versionLines.size == 1 -> InstalledWslCoreVersion.Known(versionLines.single().trim())
            exitCode == 0 -> InstalledWslCoreVersion.Unknown
            exitCode == CAT_SAYS_UNREADABLE && message.startsWith(CAT_MESSAGE_PREFIX) -> InstalledWslCoreVersion.Unknown
            else -> InstalledWslCoreVersion.DistributionFailed(message)
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
     *
     * 넘기다 실패해도 던지지 않고 리눅스 쪽의 종료 코드와 글을 끝까지 거두어 함께 돌려준다. 파일을 못 읽어 중간에 stdin 을 닫으면
     * `tar` 는 `gzip: unexpected end of file` 을 쓰고, 리눅스 쪽이 먼저 실패해 생긴 끊긴 파이프는 그 실패(`not in gzip format`)의
     * 결과다 — 어느 한쪽만으로는 무엇이 먼저였는지 알 수 없다. 리눅스 쪽이 0 이어도 다 넘기지 못했으면 실패다(덜 받은 묶음).
     */
    override suspend fun runFeedingStandardInput(distributionName: String, linuxCommand: List<String>, standardInput: Path): WslCommandResult =
        runWslProcess(inDistribution(distributionName, linuxCommand)) { process ->
            coroutineScope {
                val feeding = async { feedStandardInput(process, standardInput) }
                val output = process.inputStream.use { it.readBytes() }
                val exitCode = process.waitFor()
                WslCommandResult(exitCode, wslMessageOf(output), standardInputFailure = feeding.await()?.toString())
            }
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
    ): Int = runWslProcess(inDistribution(distributionName, linuxCommand)) { process ->
        process.outputStream.close()
        process.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines -> lines.forEach(onOutputLine) }
        process.waitFor()
    }

    private suspend fun runWsl(arguments: List<String>): Pair<Int, ByteArray> = runWslProcess(arguments) { process ->
        process.outputStream.close()
        val output = process.inputStream.use { it.readBytes() }
        process.waitFor() to output
    }

    /**
     * `wsl.exe` 를 띄워 [useProcess] 에 맡긴다. 부른 쪽이 취소되면(준비 창을 닫았다) 그 `wsl.exe` 를 끝내고 끝날 때까지 기다린 뒤에
     * 취소를 마친다. [useProcess] 의 막힌 읽기는 코루틴 취소를 모른다 — 프로세스가 끝나야 끝을 보고 돌아온다. 끝내지 않으면 그 자식이
     * 화면 뒤에 남아 다음 실행의 걸음(임시 자리 `rm -rf`)과 겹친다.
     *
     * `wsl.exe` 를 끝내도 리눅스 쪽에는 신호가 가지 않는다(3.2 B) — 리눅스 쪽 명령은 다음에 출력을 쓸 때 끊긴 파이프를 만난다. 설치가
     * 그렇게 끊겨도 쓰던 코어가 남는 까닭은 6.17 의 4b 정정에 적었다.
     */
    private suspend fun <T> runWslProcess(arguments: List<String>, useProcess: suspend (Process) -> T): T = withContext(Dispatchers.IO) {
        val process = wslProcessBuilder(wslExecutable, arguments).redirectErrorStream(true).start()
        coroutineScope {
            val endOnCancellation = launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) { process.endAndWait() }
                }
            }
            try {
                useProcess(process)
            } finally {
                endOnCancellation.cancel()
            }
        }
    }
}

/** 이미 끝났으면 아무것도 하지 않는다. 윈도우의 `destroy` 는 곧바로 끝내고, 그 밖에서는 정중히 끝낸 뒤 기다려도 안 끝나면 강제로. */
private fun Process.endAndWait() {
    if (!isAlive) {
        return
    }
    destroy()
    if (!waitFor(PROCESS_END_GRACE_SECONDS, TimeUnit.SECONDS)) {
        destroyForcibly().waitFor()
    }
}

private fun inDistribution(distributionName: String, linuxCommand: List<String>): List<String> =
    listOf("-d", distributionName, "--cd", "~", "-e") + linuxCommand

/**
 * 다 넘기지 못한 까닭(파일 읽기 · 파이프 쓰기의 [IOException])을 돌려준다. 던지지 않는 것은 그 뒤에 리눅스 쪽 결과를 거두어 함께
 * 보이기 위해서다([WslCommandLine.runFeedingStandardInput]). 어느 쪽이든 stdin 은 닫는다 — 닫지 않으면 리눅스 쪽이 끝을 기다려 출력을
 * 읽는 쪽도 끝나지 않는다.
 */
private fun feedStandardInput(process: Process, standardInput: Path): IOException? {
    val linuxStandardInput = process.outputStream
    val feedFailure = try {
        Files.newInputStream(standardInput).use { source -> source.transferTo(linuxStandardInput) }
        null
    } catch (couldNotFeed: IOException) {
        couldNotFeed
    }
    val closeFailure = try {
        linuxStandardInput.close()
        null
    } catch (couldNotClose: IOException) {
        couldNotClose
    }
    return feedFailure ?: closeFailure
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

private const val CAT_MESSAGE_PREFIX = "cat:"

private const val PROCESS_END_GRACE_SECONDS = 5L

/** 깔린 코어의 판. 손으로든 화면이든 `install.sh --with-core` 가 남긴다(core-ui-split-design.md 6.16 의 4a 정정). */
internal const val WSL_INSTALLED_CORE_VERSION_FROM_HOME = ".local/share/kyuchestration/core/VERSION"

private const val SYSTEM_ROOT_VARIABLE = "SystemRoot"
