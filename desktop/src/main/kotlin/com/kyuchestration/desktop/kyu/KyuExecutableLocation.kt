package com.kyuchestration.desktop.kyu

import java.io.File
import java.nio.file.Path
import kotlin.io.path.isExecutable
import kotlin.io.path.isRegularFile

/**
 * 부를 kyu 를 세 자리에서 이 순서로 찾는다. 어디에도 없으면 null.
 *
 * 1. [PINNED_KYU_EXECUTABLE_VARIABLE] 이 못 박은 파일
 * 2. PATH — 사용자가 자기 손으로 설치한 것
 * 3. 앱이 스스로 받아 둔 것([managedEngineDirectory])
 *
 * 순서가 이 함수의 전부다. 사용자가 놓은 것이 앱이 받아 둔 것을 이겨야 한다 — 반대로 두면
 * 소스에서 빌드해 PATH 에 놓고 엔진을 고치는 중인 사람이 영영 앱이 받아 둔 옛 판을 부르게 되고,
 * 그 사실이 어디에도 드러나지 않는다.
 *
 * 셸을 거치지 않는다. `sh -c "kyu ..."` 로 부르면 사용자의 셸 설정과 인용 규칙이 끼어들어,
 * 공백이 든 워크디렉토리 경로에서 인자가 쪼개진다.
 *
 * 부를 때마다 다시 뒤진다. 앱이 떠 있는 동안 kyu 가 생기면(설치 화면이 받아 오거나, 사용자가
 * 다른 터미널에서 설치하거나) 그때부터 동작해야 하는데, 시작할 때 한 번 찾아 두면 그 실행은
 * 영영 "kyu 가 없다" 로 남는다.
 *
 * 세 어댑터가 함께 쓴다 — 관찰(kyu list --json) · 세션 진입(kyu attach) · 설치 화면의 재확인.
 * 각자 찾게 두면 찾는 자리가 한쪽에서만 느는 날이 온다.
 *
 * @param lookUpEnvironmentVariable 환경 변수를 읽는 자리. 검사가 진짜 PATH 를 건드리지 않고
 *   순서를 확인하기 위한 이음매다.
 * @param managedEngineExecutablePath 앱이 받아 둔 엔진이 있을 자리.
 * @param isExecutableFile 그 자리에 실행 가능한 파일이 있는지 답하는 자리.
 */
internal fun findKyuExecutable(
    lookUpEnvironmentVariable: (String) -> String? = System::getenv,
    managedEngineExecutablePath: Path = managedEngineExecutablePath(),
    isExecutableFile: (Path) -> Boolean = ::isExecutableRegularFile,
): Path? {
    val pinnedPath = lookUpEnvironmentVariable(PINNED_KYU_EXECUTABLE_VARIABLE)?.takeIf { it.isNotBlank() }
    if (pinnedPath != null) {
        // 못 박은 자리는 실행 가능한지 따지지 않고 그대로 답한다. 여기서 걸러 다음 자리로
        // 넘어가면 경로 오타 하나가 "엔진이 없다" 로 뜨고, 사용자는 자기가 못 박은 값이 무시된
        // 줄을 모른다. 실행되지 않는 이유는 실제로 부르는 자리에서 KyuCommandFailure 가 말한다.
        return Path.of(pinnedPath)
    }

    return findOnSearchPath(lookUpEnvironmentVariable("PATH"), isExecutableFile)
        ?: managedEngineExecutablePath.takeIf(isExecutableFile)
}

/**
 * 앱이 자기 손으로 받아 둔 엔진이 사는 자리.
 *
 * 플랫폼 관례를 따르되 XDG_DATA_HOME 은 보지 않는다. 이 디렉토리는 앱이 쓰고 앱이 읽는 곳이라
 * 지켜야 할 것은 "관례에 맞는 자리인가" 가 아니라 "쓴 자리와 읽는 자리가 같은가" 하나다.
 * 환경 변수를 보면 설치할 때와 다음에 띄울 때의 셸 설정이 다른 것만으로 받아 둔 엔진이 통째로
 * 안 보이게 되고, 그 엔진은 지워지지도 않은 채 어디에도 뜨지 않는다.
 *
 * 윈도우 갈래를 따로 두지 않는다. 엔진은 tmux 위에서만 돌아 윈도우 네이티브에서는 설치 화면이
 * 받기 전에 거절하므로(EngineReleaseTarget), 이 자리는 그쪽에서 쓰이지 않는다. 쓰지도 않을
 * AppData 갈래를 미리 두면 시험할 수 없는 코드가 하나 생긴다.
 */
internal fun managedEngineDirectory(
    osName: String = System.getProperty("os.name").orEmpty(),
    userHomeDirectory: Path = Path.of(System.getProperty("user.home").orEmpty()),
): Path = if (osName.startsWith("Mac", ignoreCase = true)) {
    userHomeDirectory.resolve("Library/Application Support/Kyuchestration/engine")
} else {
    userHomeDirectory.resolve(".local/share/kyuchestration/engine")
}

/**
 * 관리 디렉토리 안에서 부를 파일. 이름은 PATH 에 두는 것과 같은 `kyu` 다 — 받아 둔 자리가
 * 달라졌다고 부르는 이름까지 달라지면, 사용자가 그 자리를 직접 PATH 에 넣을 때 걸린다.
 */
internal fun managedEngineExecutablePath(engineDirectory: Path = managedEngineDirectory()): Path =
    engineDirectory.resolve("kyu")

/**
 * 통합 검증과 엔진 개발이 부를 파일을 못 박는 환경 변수.
 *
 * 검사용 훅으로 시작했지만 앱도 같은 것을 본다. 엔진을 고치는 중인 사람이 자기가 방금 빌드한
 * 바이너리로 앱을 띄우는 것은 검사와 같은 요구이고, 그 길을 검사에만 열어 두면 앱을 띄울 때마다
 * PATH 를 손대야 한다.
 */
internal const val PINNED_KYU_EXECUTABLE_VARIABLE = "KYU_BINARY_PATH"

private fun findOnSearchPath(searchPath: String?, isExecutableFile: (Path) -> Boolean): Path? =
    searchPath.orEmpty()
        .split(File.pathSeparatorChar)
        .filter { it.isNotBlank() }
        .flatMap { directory -> KYU_EXECUTABLE_NAMES.map { Path.of(directory, it) } }
        .firstOrNull(isExecutableFile)

private fun isExecutableRegularFile(path: Path): Boolean = path.isRegularFile() && path.isExecutable()

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
