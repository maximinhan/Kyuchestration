package com.kyuchestration.desktop.terminal.pty

import com.jediterm.terminal.TtyConnector
import com.kyuchestration.desktop.terminal.SessionTarget
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.isExecutable
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.abort

/**
 * 앱 안의 PTY 가 정말 tmux 세션에 붙는지, 그리고 놓으면 세션이 살아남는지 본다.
 *
 * 화면 없이 이것을 확인할 수 있는 이유는 tmux 가 자기에게 붙은 클라이언트를 셀 줄 알기 때문이다.
 * `tmux list-clients` 가 한 줄을 내면 우리 PTY 가 그 세션의 화면을 실제로 받고 있다는 뜻이고,
 * 그것이 이 PR 전체가 성립했다는 증거다.
 *
 * 사용자의 tmux 서버는 건드리지 않는다. TMUX_TMPDIR 로 이 시험만의 소켓을 쓰고 끝나면 그 서버를
 * 통째로 죽인다.
 *
 * 세션은 tmux 로 직접 만든다. kyu 가 세션에서 띄우는 명령은 claude 로 못 박혀 있는데(start.go),
 * 그것이 깔려 있는지는 이 검증과 아무 상관이 없다. 그래도 kyu start 는 그대로 지난다 — 이미 떠
 * 있는 세션을 실패로 보지 않는 그 성질이 진입 계획이 start 를 조건 없이 앞에 두는 근거이므로,
 * 이 시험이 그 성질까지 함께 붙잡는다.
 */
class PtyKyuSessionTerminalAttacherIntegrationTest {

    // tmux 소켓은 유닉스 도메인 소켓이라 경로 길이 상한(약 108 바이트)이 있다. 시스템 임시
    // 디렉토리 바로 아래에 두어 상한에 걸리지 않게 한다.
    private val tmuxSocketDirectory = createTempDirectory("kyu-tmux")
    private val workDirPath = createTempDirectory("WorkDir-featureX")

    private val isolatedEnvironment: Map<String, String> =
        System.getenv() + mapOf(TMUX_TMPDIR_ENV_NAME to tmuxSocketDirectory.absolutePathString())

    private var openedConnector: TtyConnector? = null

    private lateinit var kyuExecutablePath: Path

    /**
     * 건너뛸지를 시험 본문보다 먼저 정한다.
     *
     * 도구를 찾는 일을 본문 안에서 하면, 그 전에 부른 tmux 가 없는 기계에서 "건너뜀" 이 아니라
     * "실패" 로 끝난다. 데스크톱 CI 는 Go 를 세우지 않아 kyu 가 없는 것이 정상이다.
     */
    @BeforeTest
    fun 검증에_필요한_도구를_찾거나_건너뛴다() {
        if (!isTmuxInstalled()) {
            abort<Unit>("tmux 가 없어 건너뜁니다 — 세션 진입은 tmux 백엔드 위에서만 성립합니다")
        }
        kyuExecutablePath = locateKyuOrSkip()
    }

    @AfterTest
    fun 격리한_tmux_서버와_임시_디렉토리를_지운다() {
        openedConnector?.close()
        // 건너뛴 실행은 서버를 띄운 적이 없고, tmux 자체가 없을 수도 있다.
        if (isTmuxInstalled()) {
            runTmux("kill-server")
        }
        tmuxSocketDirectory.toFile().deleteRecursively()
        workDirPath.toFile().deleteRecursively()
    }

    @Test
    fun `카드를 누르면 앱 안의 PTY 가 그 레포의 세션에 클라이언트로 붙는다`() {
        gitRepository(workDirPath.resolve(REPO_NAME))
        val sessionName = createSessionWithTmux(REPO_NAME)

        val connector = attacher().attachTo(workDirPath, SessionTarget.Repo(REPO_NAME))
            .also { openedConnector = it }

        val clientLines = waitForClientLines(sessionName, expectedCount = 1)
        assertEquals(1, clientLines.size, "PTY 가 세션에 클라이언트로 붙어야 한다")
        assertTrue(connector.isConnected, "kyu attach 가 살아 있어야 한다")

        // tmux 가 그리는 클라이언트 한 줄에 진입 계획이 정한 것이 그대로 실려 있다:
        // `/dev/pts/9: <세션> [80x24 xterm-256color] (attached,...)`.
        // TERM 이 저 자리까지 닿았다는 것은 환경 정리가 PTY 를 지나 tmux 에 도달했다는 뜻이다.
        assertTrue(
            "xterm-256color" in clientLines.single(),
            "클라이언트의 터미널 종류가 진입 계획이 정한 값이어야 한다. tmux 가 말한 것: ${clientLines.single()}",
        )
    }

    @Test
    fun `터미널을 닫으면 클라이언트만 떨어지고 세션은 살아 있다`() {
        gitRepository(workDirPath.resolve(REPO_NAME))
        val sessionName = createSessionWithTmux(REPO_NAME)
        val connector = attacher().attachTo(workDirPath, SessionTarget.Repo(REPO_NAME))
        waitForClientLines(sessionName, expectedCount = 1)

        connector.close()

        // 설계 원칙 5 — 도구가 죽어도 작업이 안 끊긴다. 닫기는 kill 이 아니라 detach 여야 한다.
        assertEquals(
            emptyList(),
            waitForClientLines(sessionName, expectedCount = 0),
            "닫으면 클라이언트가 떨어져야 한다",
        )
        assertTrue(isSessionAlive(sessionName), "클라이언트가 떨어져도 세션은 살아 있어야 한다")
    }

    @Test
    fun `tmux 안에서 띄운 앱이어도 세션에 붙는다`() {
        gitRepository(workDirPath.resolve(REPO_NAME))
        val sessionName = createSessionWithTmux(REPO_NAME)

        // GUI 를 tmux 세션 안의 셸에서 띄우면 이 변수가 앱에 딸려 온다. 그대로 물려주면
        // kyu attach 가 중첩으로 보고 거절하므로, 진입 계획이 이것을 덜어낸다.
        val attacherLaunchedInsideTmux = PtyKyuSessionTerminalAttacher(
            fixedKyuExecutablePath = kyuExecutablePath,
            parentEnvironment = isolatedEnvironment + mapOf("TMUX" to "/tmp/tmux-1000/default,1234,0"),
        )

        openedConnector = attacherLaunchedInsideTmux.attachTo(workDirPath, SessionTarget.Repo(REPO_NAME))

        assertEquals(
            1,
            waitForClientLines(sessionName, expectedCount = 1).size,
            "TMUX 를 덜어냈으므로 붙어야 한다",
        )
    }

    private fun attacher() = PtyKyuSessionTerminalAttacher(
        fixedKyuExecutablePath = kyuExecutablePath,
        parentEnvironment = isolatedEnvironment,
    )

    /**
     * kyu 의 이름 규칙(session/name.go)대로 세션을 만든다: `kyu-<워크디렉토리>-<레포>`.
     *
     * 이 이름이 규칙과 어긋나면 kyu attach 가 세션을 찾지 못해 시험이 그 자리에서 깨진다 —
     * 규칙을 코틀린에 옮겨 적은 것이 아니라, 옮겨 적은 것이 맞는지를 kyu 에게 물어보는 셈이다.
     */
    private fun createSessionWithTmux(repoName: String): String {
        val sessionName = "kyu-${workDirPath.fileName}-$repoName"
        runTmux(
            "new-session", "-d", "-s", sessionName,
            "-c", workDirPath.resolve(repoName).absolutePathString(),
            // 세션이 살아 있기만 하면 되는 자리다. 무엇이 도는지는 이 검증의 관심사가 아니다.
            "sh", "-c", "while :; do sleep 1; done",
        )
        return sessionName
    }

    /**
     * 붙은 클라이언트가 기대한 수가 될 때까지 기다렸다가 tmux 가 그린 줄을 그대로 돌려준다.
     *
     * 고정 시간을 자지 않는다. 클라이언트가 붙는 데는 PTY 생성·kyu 실행·attach 왕복이 걸리는데,
     * 그 시간을 상수로 박으면 느린 기계에서 헛되이 깨지고 빠른 기계에서는 그만큼 기다린다.
     */
    private fun waitForClientLines(sessionName: String, expectedCount: Int): List<String> {
        repeat(CLIENT_POLL_ATTEMPTS) {
            val clientLines = clientLines(sessionName)
            if (clientLines.size == expectedCount) {
                return clientLines
            }
            Thread.sleep(CLIENT_POLL_INTERVAL_MILLIS)
        }
        return clientLines(sessionName)
    }

    private fun clientLines(sessionName: String): List<String> =
        runTmux("list-clients", "-t", sessionName).lines().filter { it.isNotBlank() }

    private fun isSessionAlive(sessionName: String): Boolean =
        runTmux("list-sessions", "-F", "#{session_name}").lines().any { it.trim() == sessionName }

    /**
     * 격리한 서버에 대고 tmux 를 부른다. 실패는 그대로 빈 문자열이다 — 붙은 클라이언트가 없거나
     * 세션이 없을 때 tmux 는 종료 코드로 답하고, 이 시험이 묻는 것이 바로 그 "없음" 이다.
     */
    private fun runTmux(vararg arguments: String): String {
        val process = ProcessBuilder(listOf("tmux") + arguments)
            .apply {
                environment().clear()
                environment().putAll(isolatedEnvironment)
            }
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        return if (process.waitFor() == 0) output else ""
    }

    private fun gitRepository(repositoryPath: Path): Path {
        repositoryPath.createDirectories()
        git(repositoryPath, "init", "--quiet", "--initial-branch=main")
        git(repositoryPath, "commit", "--quiet", "--allow-empty", "--message=첫 커밋")
        return repositoryPath
    }

    private fun git(repositoryPath: Path, vararg arguments: String) {
        val process = ProcessBuilder(listOf("git") + arguments)
            .directory(repositoryPath.toFile())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        check(process.waitFor() == 0) { "git ${arguments.joinToString(" ")} 실패: $output" }
    }

    /**
     * 소스에서 빌드한 바이너리를 겨누려면 KYU_BINARY_PATH 를 준다. 없으면 PATH 에 설치된 kyu 를
     * 쓰고, 그것도 없으면 이 검증을 건너뛴다 — 데스크톱 CI 는 Go 를 세우지 않는다.
     */
    private fun locateKyuOrSkip(): Path {
        val pinnedPath = System.getenv(KYU_BINARY_PATH_VARIABLE)?.let(Path::of)
        if (pinnedPath != null) {
            if (!pinnedPath.isExecutable()) {
                abort<Unit>("$KYU_BINARY_PATH_VARIABLE 가 가리키는 $pinnedPath 를 실행할 수 없습니다")
            }
            return pinnedPath
        }

        return findOnSystemPath("kyu")
            ?: abort("PATH 에도 $KYU_BINARY_PATH_VARIABLE 에도 kyu 가 없어 건너뜁니다")
    }

    private fun isTmuxInstalled(): Boolean = findOnSystemPath("tmux") != null

    private fun findOnSystemPath(executableName: String): Path? =
        System.getenv("PATH").orEmpty()
            .split(':')
            .filter { it.isNotBlank() }
            .map { Path.of(it, executableName) }
            .firstOrNull { it.isExecutable() }

    private companion object {
        const val REPO_NAME = "proj-a"
        const val KYU_BINARY_PATH_VARIABLE = "KYU_BINARY_PATH"
        const val TMUX_TMPDIR_ENV_NAME = "TMUX_TMPDIR"
        const val CLIENT_POLL_ATTEMPTS = 100
        const val CLIENT_POLL_INTERVAL_MILLIS = 50L
    }
}
