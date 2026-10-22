package com.kyuchestration.desktop.platform

import java.io.File
import java.nio.file.Path
import kotlin.io.path.isExecutable
import kotlin.io.path.isRegularFile

/**
 * [command] 의 실행 파일이 이름뿐이면 [childEnvironment] 의 PATH 에서 찾은 절대 경로로 바꾼다. 찾지 못하거나 이미 경로면 그대로다.
 *
 * **`ProcessBuilder` 는 이름을 자식에게 줄 환경이 아니라 이 JVM 이 받은 PATH 로 찾는다**(3 단계 실측: JVM 의 PATH 에 없고 자식
 * 환경의 PATH 에만 있는 claude 는 "No such file or directory"). 이 앱은 자식에게 로그인 셸과 잘 알려진 자리로 채운 PATH 를
 * 물려주는데(childProcessEnvironment), 그 PATH 로 찾지 않으면 물려준 것과 부른 것이 갈린다. `wsl.exe -e` 로 뜬 코어의 JVM PATH
 * 에는 `~/.local/bin` 이 없어(설계 3.7) claude 를 아예 찾지 못했고, Finder 로 띄운 맥 앱도 같은 처지다.
 *
 * 찾지 못하면 그대로 두는 것은 띄우는 자리가 "실행하지 못했다" 를 이미 사람의 말로 알리기 때문이다.
 */
internal fun commandResolvedOnChildSearchPath(
    command: List<String>,
    childEnvironment: Map<String, String>,
    isExecutableFile: (Path) -> Boolean = ::isExecutableRegularFile,
): List<String> {
    val executableName = command.firstOrNull() ?: return command
    if (File.separatorChar in executableName || '/' in executableName) {
        return command
    }
    val found = findExecutableOnSearchPath(executableName, childEnvironment[SEARCH_PATH_VARIABLE], isExecutableFile)
        ?: return command
    return listOf(found.toString()) + command.drop(1)
}

/** [searchPath] 의 앞자리부터 [executableName] 이라는 실행 파일을 찾는다. */
internal fun findExecutableOnSearchPath(
    executableName: String,
    searchPath: String?,
    isExecutableFile: (Path) -> Boolean = ::isExecutableRegularFile,
): Path? =
    searchPath.orEmpty()
        .split(File.pathSeparatorChar)
        .filter { it.isNotBlank() }
        .map { directory -> Path.of(directory, executableName) }
        .firstOrNull(isExecutableFile)

internal fun isExecutableRegularFile(path: Path): Boolean = path.isRegularFile() && path.isExecutable()

private const val SEARCH_PATH_VARIABLE = "PATH"
