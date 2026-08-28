package com.kyuchestration.desktop.kyu

import java.io.File
import java.nio.file.Path
import kotlin.io.path.isExecutable
import kotlin.io.path.isRegularFile

/**
 * PATH 를 앞에서부터 훑어 처음 만나는 실행 가능한 kyu 를 돌려준다. 없으면 null.
 *
 * 셸을 거치지 않는다. `sh -c "kyu ..."` 로 부르면 사용자의 셸 설정과 인용 규칙이 끼어들어,
 * 공백이 든 워크디렉토리 경로에서 인자가 쪼개진다.
 *
 * 부를 때마다 다시 뒤진다. 앱이 떠 있는 동안 사용자가 kyu 를 설치하면 그때부터 동작해야 하는데,
 * 시작할 때 한 번 찾아 두면 그 실행은 영영 "kyu 가 없다" 로 남는다.
 *
 * 두 어댑터가 함께 쓴다 — 관찰(kyu list --json)과 세션 진입(kyu attach) 둘 다 부를 파일을 알아야
 * 한다. 각자 찾게 두면 윈도우 확장자 규칙이 한쪽에서만 고쳐지는 날이 온다.
 */
internal fun findKyuExecutableOnSystemPath(): Path? {
    val searchPath = System.getenv("PATH") ?: return null

    return searchPath.split(File.pathSeparatorChar)
        .filter { it.isNotBlank() }
        .flatMap { directory -> KYU_EXECUTABLE_NAMES.map { Path.of(directory, it) } }
        .firstOrNull { it.isRegularFile() && it.isExecutable() }
}

/**
 * 윈도우에서는 확장자가 붙은 이름으로만 실행 파일이 잡힌다. 설치 패키지에 Msi 가 들어 있으므로
 * (build.gradle.kts 의 targetFormats) 그쪽에서 도는 것도 이 앱이 감당해야 할 자리다.
 */
private val KYU_EXECUTABLE_NAMES: List<String> =
    if (System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) {
        listOf("kyu.exe", "kyu")
    } else {
        listOf("kyu")
    }
