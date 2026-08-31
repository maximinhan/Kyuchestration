package com.kyuchestration.desktop.platform

import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * 사용자의 로그인 셸에게 PATH 를 물어본다. 묻지 못했거나 알아들을 수 없는 답이 오면 null.
 *
 * **왜 물어보는가.** Finder 에서 띄운 맥 GUI 앱은 로그인 셸을 거치지 않는다 — launchd 가 곧바로
 * 실행하므로 그 프로세스가 들고 있는 PATH 는 시스템 기본값뿐이다. brew 가 놓은 tmux
 * (`/opt/homebrew/bin`)도 install.sh 가 놓은 kyu(`~/.local/bin`)도 거기에 없어서, 터미널에서는
 * 멀쩡히 도는 것들이 앱 안에서만 "없다" 가 된다. GUI 로 뜨는 개발 도구들이 같은 해법을 쓴다
 * (VS Code 의 셸 환경 해석).
 *
 * 리눅스에서도 같은 것을 묻는다. 터미널에서 띄우면 이미 로그인 셸의 PATH 를 물려받아 답이 같고,
 * 데스크톱 런처(.desktop)로 띄우면 맥과 같은 처지가 된다 — 플랫폼마다 다른 길을 두면 한쪽은
 * 실측할 때까지 아무도 모른다.
 *
 * @param readLoginShellOutput 셸을 실제로 띄우고 stdout 을 읽는 자리. 검사가 검사 머신의 설정
 *   파일에 기대지 않고 판단만 확인하기 위한 이음매다.
 */
internal fun loginShellSearchPath(
    lookUpEnvironmentVariable: (String) -> String? = System::getenv,
    osName: String = System.getProperty("os.name").orEmpty(),
    readLoginShellOutput: (List<String>) -> String? = ::loginShellStandardOutput,
): String? {
    val command = loginShellSearchPathCommand(lookUpEnvironmentVariable(SHELL_ENV_NAME), osName)
    val shellOutput = readLoginShellOutput(command) ?: return null

    return searchPathInLoginShellOutput(shellOutput)
}

/**
 * 로그인 셸에게 PATH 를 찍게 하는 명령.
 *
 * `-l` 이 요점이다. 로그인 셸로 띄워야 `.zprofile` · `.profile` 이 읽히고, brew 가 자기 자리를
 * PATH 에 넣는 줄(`eval "$(brew shellenv)"`)이 보통 거기에 있다.
 *
 * 대화형(`-i`)으로는 묻지 않는다. `.zshrc` 까지 읽혀 더 많은 것을 알아낼 수 있지만, 그 파일은
 * 입력을 기다리거나 프롬프트를 그리는 코드를 품고 있을 수 있어 앱이 뜨는 길에서 멎을 자리를
 * 만든다. `.zshrc` 에만 brew 를 여는 사람은 아래 잘 알려진 자리가 받는다
 * ([wellKnownSearchPathDirectories]).
 *
 * 표식을 앞뒤로 두르고 찍는다. 설정 파일이 stdout 에 인사말을 찍는 머신이 있어서, 그냥 찍으면
 * 그 줄들과 PATH 를 가려낼 방법이 없다.
 */
internal fun loginShellSearchPathCommand(shellFromEnvironment: String?, osName: String): List<String> {
    val shell = shellFromEnvironment?.takeIf { it.isNotBlank() } ?: defaultLoginShell(osName)

    return listOf(shell, "-l", "-c", ECHO_MARKED_SEARCH_PATH)
}

/**
 * 셸이 찍은 것에서 표식 사이의 값만 꺼낸다. 표식이 없으면 답으로 치지 않는다.
 *
 * 종료 코드는 보지 않는다. 설정 파일 마지막 줄이 0 이 아닌 값으로 끝나는 머신이 흔한데, 그때도
 * PATH 는 이미 제대로 찍혀 있다. 답이 실렸는지를 표식이 말하므로 종료 코드는 여기에 더할 것이 없다.
 */
internal fun searchPathInLoginShellOutput(shellOutput: String): String? =
    shellOutput.split(SEARCH_PATH_MARKER)
        .takeIf { it.size >= 3 }
        ?.get(1)
        ?.trim()
        ?.takeIf { it.isNotBlank() }

/**
 * 셸을 띄우고 stdout 을 읽는다. 띄우지 못했거나 [LOGIN_SHELL_TIMEOUT_SECONDS] 안에 끝나지 않으면 null.
 *
 * 시간 제한이 있는 이유는 이 일이 앱이 뜨는 길 위에 있기 때문이다. 설정 파일이 망을 타는 머신
 * (nvm · rbenv 초기화)에서는 셸 한 번이 몇 초씩 걸리고, 그것을 그대로 기다리면 창이 그만큼 늦게
 * 뜬다. 못 물어본 실행은 잘 알려진 자리로 채워지므로([wellKnownSearchPathDirectories]) 여기서
 * 포기해도 tmux 와 kyu 를 찾는 길은 남는다.
 *
 * 실패를 전부 null 로 옮긴다. 이 탐색은 없어도 앱이 도는 보강이라, 셸이 없는 머신에서 예외를
 * 올리면 앱 자체가 뜨지 않는다.
 */
private fun loginShellStandardOutput(command: List<String>): String? {
    val shellProcess = try {
        ProcessBuilder(command)
            // 로그인 셸이 stderr 로 내는 것(인사말 · 설정 파일의 경고)은 읽을 것이 아니다.
            // 읽지 않은 채 파이프로 두면 그 버퍼가 차는 순간 셸이 쓰기에서 멈춰, 답을 이미 찍어
            // 놓고도 시간 제한에 걸린다.
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
    } catch (failure: IOException) {
        // SHELL 이 가리키는 파일이 없거나 실행할 수 없는 머신이다.
        return null
    }

    // 읽기를 다른 스레드에 맡겨야 시간 제한이 걸린다. 이 스레드에서 직접 읽으면 셸이 영영 끝나지
    // 않는 머신에서 그대로 함께 멎는다.
    val shellOutputReading = CompletableFuture.supplyAsync {
        // 줄 것이 없다고 곧바로 알린다. 열어 둔 채로 두면 입력을 읽는 설정 파일 앞에서 셸이 멎는다.
        shellProcess.outputStream.close()
        shellProcess.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    return try {
        shellOutputReading.get(LOGIN_SHELL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    } catch (failure: TimeoutException) {
        null
    } catch (failure: ExecutionException) {
        // 읽다가 IOException 으로 끝난 경우가 이리로 온다.
        null
    } catch (failure: InterruptedException) {
        // 인터럽트를 삼키면 이 스레드를 멈추려는 쪽이 그 사실을 잃는다.
        Thread.currentThread().interrupt()
        null
    } finally {
        // 시간 제한에 걸려 나가는 길에서 셸을 남기지 않는다. 이미 끝난 프로세스에는 아무 일도
        // 일어나지 않고, 살아 있으면 여기서 죽으며 읽던 스레드도 EOF 를 받고 풀린다.
        shellProcess.destroyForcibly()
    }
}

/**
 * SHELL 을 물려받지 못한 실행에서 물어볼 셸.
 *
 * 맥은 카탈리나부터 zsh 가 기본이라 사용자의 설정도 `.zprofile` 에 있다. 그 밖에서는 어느
 * 배포판에나 있는 `/bin/sh` 하나만 확실하다 — 없는 셸을 부르면 물어보지도 못하고 끝난다.
 */
private fun defaultLoginShell(osName: String): String =
    if (runningOnMac(osName)) "/bin/zsh" else "/bin/sh"

/**
 * PATH 를 감싸는 표식. 검사도 이 값을 보므로 파일 안에 숨기지 않는다.
 */
internal const val SEARCH_PATH_MARKER = "__KYU_SEARCH_PATH__"

/**
 * 셸에게 넘길 한 줄.
 *
 * 변수를 중괄호로 감싸는 이유는 표식이 곧바로 뒤에 붙어서다. 감싸지 않으면 셸이 "PATH" 가 아니라
 * "PATH__KYU_SEARCH_PATH__" 를 변수 이름으로 읽고, 그런 변수는 없으니 빈 값이 찍힌다.
 */
private const val ECHO_MARKED_SEARCH_PATH =
    "echo \"$SEARCH_PATH_MARKER\${PATH}$SEARCH_PATH_MARKER\""

private const val SHELL_ENV_NAME = "SHELL"

private const val LOGIN_SHELL_TIMEOUT_SECONDS = 3L
