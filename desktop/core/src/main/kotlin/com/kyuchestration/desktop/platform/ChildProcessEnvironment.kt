package com.kyuchestration.desktop.platform

import java.io.File
import java.nio.file.Path
import kotlin.io.path.isDirectory

/**
 * 이 앱이 자식 프로세스에게 물려줄 환경. 앱이 도는 동안 한 번만 정해진다.
 *
 * **왜 물려받은 환경을 그대로 쓰지 않는가.** Finder 에서 띄운 맥 앱의 PATH 는 시스템 기본값뿐이다
 * (launchd 가 로그인 셸을 거치지 않고 실행한다). 그 PATH 에는 install.sh 가 놓은 kyu 도 사용자가
 * 자기 손으로 넣은 claude 도 없어서, 터미널에서 멀쩡히 도는 사람이 앱 안에서만 "없습니다" 를 본다.
 * 그래서 로그인 셸에게 PATH 를 물어([loginShellSearchPath]) 물려받은 것과 합친 뒤, 그 하나를
 * 모든 자식에게 물려준다.
 *
 * **한 자리에 모아 두는 이유.** kyu 를 부르는 실행기 · PTY 안의 세션 · PATH 에서 kyu 를 찾는
 * 탐색이 모두 같은 PATH 를 봐야 한다. 각자 만들게 두면 "찾을 때는 보이는데 부를 때는 없는"
 * 자리가 생기고, 로그인 셸을 세 번 띄우게 된다.
 *
 * 처음 쓰일 때 한 번 정해진다(`by lazy`). 그 시점은 앱이 첫 자식 프로세스를 띄우기 직전이고,
 * 셸에 묻는 데에는 시간 제한이 걸려 있다. 앱이 도는 동안 사용자가 `.zprofile` 을 고쳐도 다시
 * 묻지 않는다 — 물을 때마다 셸을 띄우면 3 초 폴링마다 프로세스가 하나씩 더 뜬다.
 */
internal fun childProcessEnvironment(): Map<String, String> = environmentResolvedOnce

private val environmentResolvedOnce: Map<String, String> by lazy {
    System.getenv() + mapOf(
        SEARCH_PATH_ENV_NAME to searchPathForChildProcesses(
            loginShellSearchPath = loginShellSearchPath(),
            inheritedSearchPath = System.getenv(SEARCH_PATH_ENV_NAME),
            wellKnownDirectories = wellKnownSearchPathDirectories(),
        ),
    )
}

/**
 * 자식에게 물려줄 PATH 를 만든다. 앞에서부터 로그인 셸 · 물려받은 것 · 잘 알려진 자리 순이고,
 * 같은 자리는 처음 나온 것만 남는다.
 *
 * 로그인 셸이 앞이다. 터미널에서 kyu 와 claude 를 부를 때 실제로 걸리는 것이 그 순서라, 앱 안에서
 * 부르는 것도 같은 파일이어야 "터미널에서는 되는데" 가 없어진다.
 *
 * 잘 알려진 자리는 못 물어봤을 때의 대비만이 아니라 언제나 맨 뒤에 붙는다. brew 를 `.zshrc`
 * 에서만 여는 사람은 로그인 셸에게 물어도 그 자리가 나오지 않는데(로그인 셸은 그 파일을 읽지
 * 않는다), 맨 뒤에 붙는 자리는 앞에서 이미 찾히는 것의 순서를 바꾸지 않으므로 그 사람에게만
 * 그 자리를 찾아 준다.
 */
internal fun searchPathForChildProcesses(
    loginShellSearchPath: String?,
    inheritedSearchPath: String?,
    wellKnownDirectories: List<String>,
): String = (
    searchPathEntries(loginShellSearchPath) +
        searchPathEntries(inheritedSearchPath) +
        wellKnownDirectories
    )
    .filter { it.isNotBlank() }
    .distinct()
    .joinToString(File.pathSeparator)

/**
 * PATH 에 없더라도 맨 뒤에 붙여 둘 자리. 실제로 있는 것만 답한다.
 *
 * 셋 다 이 앱이 부리는 것들이 실제로 사는 자리다 — 애플 실리콘의 brew · 인텔 맥과 리눅스의 brew
 * (그리고 손으로 넣은 것들) · install.sh 가 kyu 를 놓는 자리.
 *
 * 없는 자리를 넣어도 해롭지는 않지만 넣지 않는다. 앱이 무엇을 보고 있는지 말해야 하는 날
 * (PATH 를 화면에 적거나 로그에 남기는 날) 실제로 있는 것만 적혀 있어야 한다.
 */
internal fun wellKnownSearchPathDirectories(
    userHomeDirectory: Path = Path.of(System.getProperty("user.home").orEmpty()),
    isExistingDirectory: (Path) -> Boolean = Path::isDirectory,
): List<String> = listOf(
    Path.of("/opt/homebrew/bin"),
    Path.of("/usr/local/bin"),
    userHomeDirectory.resolve(".local/bin"),
)
    .filter(isExistingDirectory)
    .map(Path::toString)

/**
 * 환경을 통째로 갈아 끼운다.
 *
 * ProcessBuilder 는 기본으로 부모 환경을 물려주는데, 여기서 넘기는 환경은 그 부모 환경을 손본
 * 것이다(PATH 를 보강하고, 세션 진입 계획은 TMUX 를 덜어낸다). 덧씌우기만 하면 덜어낸 것이
 * 도로 살아난다.
 */
internal fun ProcessBuilder.withEnvironment(replacement: Map<String, String>): ProcessBuilder = apply {
    environment().clear()
    environment().putAll(replacement)
}

private fun searchPathEntries(searchPath: String?): List<String> =
    searchPath.orEmpty().split(File.pathSeparatorChar)

private const val SEARCH_PATH_ENV_NAME = "PATH"
