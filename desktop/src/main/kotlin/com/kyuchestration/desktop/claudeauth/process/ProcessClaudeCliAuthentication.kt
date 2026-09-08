package com.kyuchestration.desktop.claudeauth.process

import com.kyuchestration.desktop.claudeauth.ClaudeAuthenticationFailure
import com.kyuchestration.desktop.claudeauth.ClaudeBrowserLogin
import com.kyuchestration.desktop.claudeauth.ClaudeCliAuthentication
import com.kyuchestration.desktop.claudeauth.ClaudeTokenVerdict
import com.kyuchestration.desktop.platform.childProcessEnvironment
import com.kyuchestration.desktop.platform.environmentCarryingClaudeAuthToken
import com.kyuchestration.desktop.platform.withEnvironment
import java.io.IOException
import java.io.Reader
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 진짜 `claude` 를 자식으로 띄워 자격 증명을 묻고 로그인시킨다.
 *
 * **PTY 가 필요 없다.** 로그인은 브라우저를 여는 대화형 흐름이지만, `claude auth login` 은
 * 파이프에 물려도 주소를 stdout 으로 내고 코드를 stdin 한 줄로 받는다(chat-ui-design.md 7.3 가).
 * 이 사실이 이 파일의 존재 이유이고, 터미널 뷰를 지울 수 있게 된 근거이기도 하다(7.2).
 *
 * @param baseEnvironment 자식에게 물려줄 바탕 환경. 세션 어댑터와 같은 것을 쓴다 — `claude` 라는
 *   이름을 찾는 PATH 가 이 값이고, 인증을 확인한 환경과 세션을 띄우는 환경이 갈리면 "화면은
 *   로그인됐다는데 세션은 아니라고 한다" 가 생긴다.
 */
class ProcessClaudeCliAuthentication(
    private val baseEnvironment: Map<String, String> = childProcessEnvironment(),
    /** 프로세스를 띄우고 기다리는 일이라 화면을 그리는 스레드에서 하면 창이 멎는다. */
    private val processDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ClaudeCliAuthentication {

    override suspend fun credentialsArePresent(tokenToInject: String?): Boolean {
        val finished = runToCompletion(
            arguments = CREDENTIAL_STATUS_ARGUMENTS,
            claudeAuthToken = tokenToInject,
            timeout = CREDENTIAL_STATUS_TIMEOUT,
        )

        // 종료 코드로 가르지 않는다. 로그인되지 않은 claude 는 문서를 제대로 내고도 1 로 끝난다
        // (실측 7.3 나) — 코드로 갈랐다면 "로그인 안 됨" 과 "claude 가 고장났다" 가 한 답이 된다.
        return try {
            claudeJson.decodeFromString(ClaudeAuthStatusDocument.serializer(), finished.standardOutput).loggedIn
        } catch (failure: SerializationException) {
            throw finished.unreadable("claude auth status --json", failure)
        }
    }

    override suspend fun tokenIsAccepted(token: String): ClaudeTokenVerdict {
        val finished = runToCompletion(
            arguments = TOKEN_VERIFICATION_ARGUMENTS,
            claudeAuthToken = token,
            timeout = TOKEN_VERIFICATION_TIMEOUT,
        )

        val answer = try {
            claudeJson.decodeFromString(ClaudeResultDocument.serializer(), finished.standardOutput)
        } catch (failure: SerializationException) {
            throw finished.unreadable("claude -p --output-format json", failure)
        }

        if (!answer.isError) {
            return ClaudeTokenVerdict.Accepted
        }
        // claude 가 그 토큰에 대해 한 말을 그대로 올린다. 거절(401)과 다른 실패(망·한도)를 여기서
        // 지어낸 문구로 뭉뚱그리면, 사용자는 멀쩡한 토큰을 버리고 새로 받으러 간다.
        return ClaudeTokenVerdict.Refused(answer.result.ifBlank { "claude 가 이유를 적지 않았습니다." })
    }

    override suspend fun startBrowserLogin(): ClaudeBrowserLogin = withContext(processDispatcher) {
        // 맡아 둔 토큰을 싣지 않는다. 이 걸음이 만들려는 것은 claude 자신의 자격 증명이고,
        // 남의 토큰을 실어 주면 claude 는 이미 로그인된 것처럼 굴면서 브라우저 흐름만 돈다.
        val process = startClaude(BROWSER_LOGIN_ARGUMENTS, claudeAuthToken = null)
        val streams = DrainedProcessStreams(process)

        val authorizationUrl = waitForAuthorizationUrl(streams)
        if (authorizationUrl == null) {
            process.destroy()
            throw ClaudeAuthenticationFailure.BrowserLoginUrlNeverArrived(BROWSER_LOGIN_URL_TIMEOUT.inWholeSeconds)
        }

        ProcessClaudeBrowserLogin(process, streams, authorizationUrl, processDispatcher)
    }

    /**
     * 주소가 찍히기를 기다린다. 한도 안에 오지 않으면 null.
     *
     * 읽기에서 멈추지 않고 **모인 것을 되풀이해 본다**. `claude auth login` 은 주소를 찍은 뒤
     * 개행 없는 물음("Paste code here if prompted > ")을 찍고 그 자리에 멎으므로(실측 7.3 가),
     * 줄 단위로 읽으려 들면 주소를 이미 받아 놓고도 그 줄이 끝나기를 기다리게 된다.
     */
    private suspend fun waitForAuthorizationUrl(streams: DrainedProcessStreams): String? {
        val deadline = System.nanoTime() + BROWSER_LOGIN_URL_TIMEOUT.inWholeNanoseconds

        while (System.nanoTime() < deadline) {
            authorizationUrlIn(streams.standardOutputSoFar())?.let { return it }

            // 프로세스가 먼저 끝났으면 더 기다릴 것이 없다. 마지막으로 한 번 더 본다 —
            // 죽는 순간까지 흘러온 것이 아직 버퍼에 남아 있을 수 있다.
            if (!streams.isRunning()) {
                return authorizationUrlIn(streams.standardOutputSoFar())
            }
            delay(URL_POLLING_INTERVAL)
        }
        return null
    }

    /**
     * claude 를 띄우고 끝날 때까지 기다린다. 두 스트림을 비우면서 기다린다.
     *
     * stdin 을 곧바로 닫는다. 여기서 부르는 것들은 물을 것이 없는 명령인데, 열어 둔 채로 두면
     * 무언가를 묻기 시작한 판에서 이쪽이 한도까지 기다린다.
     */
    private suspend fun runToCompletion(
        arguments: List<String>,
        claudeAuthToken: String?,
        timeout: Duration,
    ): FinishedClaudeProcess = withContext(processDispatcher) {
        val process = startClaude(arguments, claudeAuthToken)
        val streams = DrainedProcessStreams(process)

        try {
            process.outputStream.close()
        } catch (_: IOException) {
            // 이미 끝난 프로세스의 stdin 을 닫는 것은 실패가 아니다 — 닫으려던 목적은 이뤄져 있다.
        }

        if (!process.waitFor(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)) {
            process.destroy()
            throw ClaudeAuthenticationFailure.ClaudeCliCouldNotRun(
                IOException("claude ${arguments.first()} 가 ${timeout.inWholeSeconds}초 안에 끝나지 않았습니다"),
            )
        }

        FinishedClaudeProcess(
            exitCode = process.exitValue(),
            standardOutput = streams.standardOutputSoFar(),
            standardError = streams.standardErrorSoFar(),
        )
    }

    private fun startClaude(arguments: List<String>, claudeAuthToken: String?): Process = try {
        ProcessBuilder(listOf(CLAUDE_EXECUTABLE_NAME) + arguments)
            .withEnvironment(environmentCarryingClaudeAuthToken(baseEnvironment, claudeAuthToken))
            .start()
    } catch (failure: IOException) {
        throw ClaudeAuthenticationFailure.ClaudeCliCouldNotRun(failure)
    }
}

/** 끝난 claude 하나가 남긴 것. */
private class FinishedClaudeProcess(
    val exitCode: Int,
    val standardOutput: String,
    val standardError: String,
) {

    /**
     * 답을 읽지 못한 실패로 옮긴다. claude 가 stderr 에 적은 것을 함께 싣는다.
     *
     * 그 스트림은 화면까지만 간다 — 기록에는 종료 코드만 남는다(ClaudeAuthenticationFailure 머리말).
     */
    fun unreadable(commandDescription: String, cause: SerializationException) =
        ClaudeAuthenticationFailure.ClaudeAnswerUnreadable(
            claudeCommandDescription = commandDescription,
            exitCode = exitCode,
            claudeMessage = standardError,
            cause = cause,
        )
}

/**
 * 살아 있는 브라우저 로그인 하나.
 *
 * 프로세스를 쥐고 있다. 주소를 보여주는 것과 코드를 넣는 것이 **같은 프로세스**에 대한 일이라야
 * 하기 때문이다 — 새로 띄우면 그 프로세스의 code_challenge 가 달라져, 사용자가 방금 받아 온
 * 코드는 어디에도 맞지 않는다.
 */
private class ProcessClaudeBrowserLogin(
    private val process: Process,
    private val streams: DrainedProcessStreams,
    override val authorizationUrl: String,
    private val processDispatcher: CoroutineDispatcher,
) : ClaudeBrowserLogin {

    override suspend fun completeWithCode(code: String): Unit = withContext(processDispatcher) {
        // 개행까지 함께 보내고 닫는다. claude 는 줄 하나를 기다리고 있고, 닫지 않으면 그 뒤에
        // 무언가를 더 물을 때 이쪽이 한도까지 기다린다.
        try {
            process.outputStream.write((code + "\n").toByteArray(Charsets.UTF_8))
            process.outputStream.flush()
            process.outputStream.close()
        } catch (failure: IOException) {
            throw ClaudeAuthenticationFailure.ClaudeCliCouldNotRun(failure)
        }

        if (!process.waitFor(BROWSER_LOGIN_EXCHANGE_TIMEOUT.inWholeMilliseconds, TimeUnit.MILLISECONDS)) {
            process.destroy()
            throw ClaudeAuthenticationFailure.BrowserLoginRefused(
                exitCode = TIMED_OUT_EXIT_CODE,
                claudeMessage = "claude 가 ${BROWSER_LOGIN_EXCHANGE_TIMEOUT.inWholeSeconds}초 안에 끝나지 않아 끊었습니다.",
            )
        }

        val exitCode = process.exitValue()
        if (exitCode != 0) {
            throw ClaudeAuthenticationFailure.BrowserLoginRefused(exitCode, streams.standardErrorSoFar())
        }
    }

    override fun cancel() {
        process.destroy()
    }
}

/**
 * 자식의 두 스트림을 스레드 둘로 계속 비우고, 지금까지 모인 것을 언제든 답한다.
 *
 * **비우기를 멈추면 자식이 멎는다.** 파이프 버퍼가 차는 순간 그 안의 claude 는 쓰기에서 멈추고,
 * 이쪽은 그것이 답하지 않는다고 여긴다 — 이 앱이 세션 쪽에서 이미 만난 함정이다
 * (ProcessChatSession · ProcessKyuCommandRunner).
 *
 * 스레드를 쓰는 이유는 막힌 읽기가 취소되지 않기 때문이다. 코루틴 안에서 블로킹 읽기를 돌리면
 * 한도를 넘겨 취소해도 그 읽기는 그대로 남는다.
 */
private class DrainedProcessStreams(private val process: Process) {

    private val standardOutput = StringBuilder()
    private val standardError = StringBuilder()

    init {
        drain(process.inputStream.bufferedReader(Charsets.UTF_8), standardOutput)
        drain(process.errorStream.bufferedReader(Charsets.UTF_8), standardError)
    }

    fun isRunning(): Boolean = process.isAlive

    fun standardOutputSoFar(): String = synchronized(standardOutput) { standardOutput.toString() }

    fun standardErrorSoFar(): String = synchronized(standardError) { standardError.toString() }

    /**
     * 글자 단위로 읽어 쌓는다.
     *
     * 줄 단위로 읽지 않는 것이 요점이다. 개행 없이 멎는 물음이 이 흐름에 실제로 있고, 그때
     * 줄을 기다리면 이미 받아 놓은 주소를 보지 못한다.
     */
    private fun drain(reader: Reader, into: StringBuilder) {
        Thread {
            reader.use {
                val buffer = CharArray(READ_BUFFER_SIZE)
                while (true) {
                    val readCount = try {
                        it.read(buffer)
                    } catch (_: IOException) {
                        break
                    }
                    if (readCount < 0) {
                        break
                    }
                    synchronized(into) { into.appendRange(buffer, 0, readCount) }
                }
            }
        }.apply {
            // 데몬으로 둔다. 창을 닫을 때 살아남은 읽기 스레드가 JVM 을 붙잡으면, 화면은
            // 사라졌는데 프로세스만 남는다.
            isDaemon = true
            name = "claude-auth-stream-reader"
            start()
        }
    }
}

private const val CLAUDE_EXECUTABLE_NAME = "claude"

/**
 * 자격 증명이 있는지 묻는 명령.
 *
 * `--json` 을 붙여 사람용 문장이 아니라 문서를 받는다. 문장으로 받으면 그 문구가 바뀌는 날
 * 앱이 조용히 "로그인 안 됨" 으로 답하고, 이미 로그인한 사용자에게 인증 화면이 뜬다.
 */
private val CREDENTIAL_STATUS_ARGUMENTS = listOf("auth", "status", "--json")

/** 브라우저 로그인을 시작하는 명령. */
private val BROWSER_LOGIN_ARGUMENTS = listOf("auth", "login")

/**
 * 토큰이 통하는지 재는 명령. 한 턴을 태우되 이 머신의 설정을 하나도 끌어들이지 않는다.
 *
 * 플래그마다 이유가 있다(chat-ui-design.md 7.3 라가 이 조합으로 쟀다).
 *
 *   - `--setting-sources ""` : 사용자·프로젝트·로컬 설정을 걷어낸다. 두면 토큰 하나를 확인하려고
 *     그 머신의 훅이 함께 도는데, 그것은 이 물음의 답과 아무 상관이 없다.
 *   - `--strict-mcp-config` : 같은 이유로 MCP 서버를 붙이지 않는다.
 *   - `--tools ""` : 도구를 다 뗀다. 확인하는 턴이 파일을 만지거나 명령을 돌릴 이유가 없다.
 *   - `--no-session-persistence` : 전사를 남기지 않는다. 확인은 사용자의 대화가 아니다.
 *
 * 프롬프트는 한 글자다. 이 턴은 토큰이 유효하면 실제로 청구되므로, 물을 것이 없는 물음을
 * 가장 짧게 둔다.
 */
private val TOKEN_VERIFICATION_ARGUMENTS = listOf(
    "-p",
    "--output-format", "json",
    "--setting-sources", "",
    "--strict-mcp-config",
    "--tools", "",
    "--no-session-persistence",
    "1",
)

private val CREDENTIAL_STATUS_TIMEOUT = 30.seconds

/** 망을 한 번 지나는 일이라 상태 확인보다 넉넉히 둔다. */
private val TOKEN_VERIFICATION_TIMEOUT = 120.seconds

/** 주소는 프로세스가 뜨고 곧 찍힌다. 실측에서는 1 초 안이었다. */
private val BROWSER_LOGIN_URL_TIMEOUT = 60.seconds

/** 코드를 넣고 앤트로픽과 한 번 왕복하는 시간. */
private val BROWSER_LOGIN_EXCHANGE_TIMEOUT = 120.seconds

private val URL_POLLING_INTERVAL = 50.milliseconds

/**
 * 한도를 넘겨 끊었을 때 실패에 싣는 코드.
 *
 * 실제 종료 코드가 아니라는 것을 드러내는 값이다 — 프로세스는 코드를 남기지 못하고 죽었고,
 * 0 이나 1 을 지어내면 그것이 claude 의 답인 것처럼 읽힌다.
 */
private const val TIMED_OUT_EXIT_CODE = -1

private const val READ_BUFFER_SIZE = 4096

/**
 * claude 가 내는 문서를 읽는 자리.
 *
 * 모르는 필드에서 멈추지 않는다. `result` 문서에만 서른 개 넘는 필드가 실려 오고(부록 A),
 * 판올림마다 는다 — 거기서 멈추면 claude 를 올리는 날 인증 화면이 통째로 죽는다.
 */
private val claudeJson = Json { ignoreUnknownKeys = true }

@Serializable
private data class ClaudeAuthStatusDocument(val loggedIn: Boolean)

/**
 * `claude -p --output-format json` 의 답에서 이 화면이 쓰는 것만.
 *
 * `is_error` 로 가른다. 종료 코드는 자격 증명이 없을 때도 거절당했을 때도 1 이라 둘을 가르지
 * 못하고, 그 구분은 `result` 문구가 한다(7.3 라).
 */
@Serializable
private data class ClaudeResultDocument(
    @SerialName("is_error") val isError: Boolean = false,
    val result: String = "",
)
