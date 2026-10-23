package com.kyuchestration.desktop.wsl

/**
 * 사람이 코어를 둘 수 있는 WSL 배포판들(core-ui-split-design.md 6.17).
 *
 * @param defaultName `wsl.exe` 의 기본 배포판. 걸러진 배포판(docker-desktop)이 기본이면 null 이다.
 */
data class WslDistributions(val names: List<String>, val defaultName: String?)

/**
 * `wsl.exe -l -v` 의 출력을 읽는다.
 *
 * 첫 줄은 머리줄이다. 그 뒤 줄마다 `[*] <이름> <상태> <판>` 이고 `*` 가 기본 배포판이다(3.4). 배포판 이름에는 빈칸이 없으므로
 * 빈칸으로 가른다. 기본 표시는 `-l -v` 에만 있다 — `-l -q` 에는 없다(3.4). 레지스트리(`Lxss\DefaultDistribution`)도 같은 답을
 * 주지만 읽는 길이 하나 더 는다.
 *
 * `docker-desktop` 으로 시작하는 배포판은 뺀다. Docker Desktop 이 제 엔진을 돌리려고 만든 배포판이라(`docker-desktop`,
 * 옛 판의 `docker-desktop-data`) 사람이 프로젝트를 두는 자리가 아니다.
 */
fun parseWslDistributionList(listOutput: ByteArray): WslDistributions {
    val rows = decodeWslOutput(listOutput).lines().drop(1).mapNotNull { line ->
        val words = line.trim().split(WHITESPACE).filter { it.isNotEmpty() }
        when {
            words.isEmpty() -> null
            words.first() == DEFAULT_MARK -> words.getOrNull(1)?.let { name -> name to true }
            else -> words.first() to false
        }
    }.filterNot { (name, _) -> name.startsWith(DOCKER_DESKTOP_PREFIX) }
    return WslDistributions(names = rows.map { (name, _) -> name }, defaultName = rows.firstOrNull { (_, isDefault) -> isDefault }?.first)
}

/**
 * `wsl.exe` 가 스스로 쓴 글을 읽는다. `WSL_UTF8=1` 을 아는 판이면 UTF-8, 모르는 옛 판이면 BOM 없는 UTF-16LE 다(3.4).
 *
 * 어느 쪽인지는 NUL 바이트로 가른다. UTF-8 글에는 NUL 이 없고, `wsl.exe` 의 글은 언제나 줄 끝(`\r\n`)을 담으므로 UTF-16LE 이면
 * `0D 00 0A 00` 이 나온다.
 */
fun decodeWslOutput(output: ByteArray): String =
    if (output.any { it == NUL_BYTE }) String(output, Charsets.UTF_16LE) else String(output, Charsets.UTF_8)

private val WHITESPACE = Regex("""\s+""")

private const val DEFAULT_MARK = "*"

private const val DOCKER_DESKTOP_PREFIX = "docker-desktop"

private const val NUL_BYTE: Byte = 0
