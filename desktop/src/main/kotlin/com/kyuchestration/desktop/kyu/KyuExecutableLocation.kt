package com.kyuchestration.desktop.kyu

import com.kyuchestration.desktop.platform.childProcessEnvironment
import com.kyuchestration.desktop.platform.runningOnMac
import java.io.File
import java.nio.file.Path
import kotlin.io.path.isExecutable
import kotlin.io.path.isRegularFile

/**
 * 부를 kyu 를 네 자리에서 이 순서로 찾는다. 어디에도 없으면 null.
 *
 * 1. [PINNED_KYU_EXECUTABLE_VARIABLE] 이 가리키는 파일
 * 2. 앱에 동봉된 엔진을 복사해 둔 자리([bundledEngineCopyPath])
 * 3. PATH — 사용자가 자기 손으로 설치한 것
 * 4. 앱이 스스로 받아 둔 것([managedEngineDirectory])
 *
 * 순서가 이 함수의 전부다.
 *
 * 동봉된 엔진이 PATH 보다 앞이다. 설치 패키지가 들고 온 엔진은 이 앱 판과 함께 만들어지고 함께
 * 시험된 바로 그 엔진이라, 앱이 무엇을 부리고 있는지가 받은 사람의 머신 사정과 무관하게 정해진다.
 * 뒤로 미루면 "앱만 설치하면 끝난다" 는 말이 PATH 사정에 걸린다 — 언젠가 넣어 둔 낡은 kyu 가
 * 남아 있는 머신에서, 방금 설치한 앱이 그 옛 엔진을 부르고 그 사실은 어디에도 드러나지 않는다.
 * 엔진을 고치는 중인 사람에게는 ①이 열려 있어 이 순서가 막다른 길이 되지 않는다.
 *
 * PATH 는 앱이 받아 둔 것보다 여전히 앞이다. 사용자가 자기 손으로 놓은 것이 앱이 첫 화면에서
 * 대신 받아 둔 것을 이겨야 한다 — 반대로 두면 소스에서 빌드해 PATH 에 놓고 엔진을 고치는 중인
 * 사람이 영영 앱이 받아 둔 옛 판을 부르게 되고, 그 사실이 어디에도 드러나지 않는다.
 *
 * 셸을 거치지 않는다. `sh -c "kyu ..."` 로 부르면 사용자의 셸 설정과 인용 규칙이 끼어들어,
 * 공백이 든 워크디렉토리 경로에서 인자가 쪼개진다.
 *
 * 부를 때마다 다시 뒤진다. 앱이 떠 있는 동안 kyu 가 생기면(설치 화면이 받아 오거나, 사용자가
 * 다른 터미널에서 설치하거나) 그때부터 동작해야 하는데, 시작할 때 한 번 찾아 두면 그 실행은
 * 영영 "kyu 가 없다" 로 남는다.
 *
 * 두 자리가 함께 쓴다 — 실행기(ProcessKyuCommandRunner)와 설치 화면의 "다시 찾기". 각자 찾게
 * 두면 찾는 자리가 한쪽에서만 느는 날이 온다.
 *
 * 세션 진입도 결국 이 함수에 닿는다. 앱이 PTY 에서 부르는 것은 이제 kyu 가 아니라 claude 지만,
 * 무엇을 띄울지 묻는 걸음이 실행기를 지난다.
 *
 * PATH 는 이 앱이 물려받은 것이 아니라 자식에게 물려줄 것을 본다(ChildProcessEnvironment).
 * 찾는 자리와 부르는 자리가 다른 PATH 를 보면, Finder 로 띄운 맥 앱에서 "찾을 때는 보였는데
 * 부를 때는 없는" kyu 가 생긴다.
 *
 * @param lookUpEnvironmentVariable 환경 변수를 읽는 자리. 검사가 진짜 PATH 를 건드리지 않고
 *   순서를 확인하기 위한 이음매다.
 * @param bundledEngineCopyPath 앱에 동봉된 엔진을 복사해 둔 자리.
 * @param managedEngineExecutablePath 앱이 받아 둔 엔진이 있을 자리.
 * @param isExecutableFile 그 자리에 실행 가능한 파일이 있는지 답하는 자리.
 */
internal fun findKyuExecutable(
    lookUpEnvironmentVariable: (String) -> String? = childProcessEnvironment()::get,
    bundledEngineCopyPath: Path = bundledEngineCopyPath(),
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

    return bundledEngineCopyPath.takeIf(isExecutableFile)
        ?: findOnSearchPath(lookUpEnvironmentVariable("PATH"), isExecutableFile)
        ?: managedEngineExecutablePath.takeIf(isExecutableFile)
}

/**
 * 설치 패키지가 앱 안에 함께 넣어 온 엔진. 동봉이 없는 실행에서는 null.
 *
 * jpackage 가 앱 리소스를 옮길 때 실행 권한을 떼어 내고 그 자리를 root 소유로 두므로(리눅스
 * deb 실측: `/opt/kyuchestration/lib/app/resources/kyu` 가 `-rw-r--r-- root/root`), 이 파일은
 * 읽을 수만 있는 원본이다. 실제로 부르는 것은 이것을 복사해 둔 [bundledEngineCopyPath] 다.
 *
 * null 이 되는 실행이 둘이다. IDE 에서 main 을 띄우면 시스템 프로퍼티 자체가 없고,
 * `./gradlew run` 은 자리를 알려 주지만 Go 가 없는 머신에서는 엔진을 만드는 걸음을 건너뛰어
 * 그 자리가 비어 있다(desktop/build.gradle.kts 의 run 게이트). 둘 다 "동봉이 없다" 로 답해야
 * 나머지 길(PATH · 첫 화면이 스스로 받아 오는 것)이 그대로 열린다.
 *
 * @param lookUpSystemProperty Compose 가 앱 리소스 자리를 알려 주는 프로퍼티를 읽는 자리.
 * @param isExistingFile 그 자리에 파일이 있는지 답하는 자리.
 */
internal fun bundledEngineResourcePath(
    lookUpSystemProperty: (String) -> String? = System::getProperty,
    isExistingFile: (Path) -> Boolean = ::isExistingRegularFile,
): Path? = lookUpSystemProperty(APPLICATION_RESOURCES_DIRECTORY_PROPERTY)
    ?.takeIf { it.isNotBlank() }
    ?.let { Path.of(it, "kyu") }
    ?.takeIf(isExistingFile)

/**
 * 동봉된 엔진을 실행할 수 있게 복사해 두는 자리.
 *
 * 앱이 받아 둔 엔진과 자리를 나눈다. 한 자리를 함께 쓰면 앱을 새 판으로 설치할 때마다 설치 화면이
 * 받아 둔 엔진이 말없이 덮이고, 무엇이 지금 그 자리에 있는지 아무도 모른다. 두 자리는 각자 자기
 * 주인이 있다 — 이 자리의 주인은 지금 설치된 앱이고, 옆자리의 주인은 그 앱과 무관하게 설치 화면이
 * 받아 둔 것이라 앱이 바뀌어도 그대로 남는다.
 */
internal fun bundledEngineCopyPath(engineDirectory: Path = managedEngineDirectory()): Path =
    engineDirectory.resolve("bundled").resolve("kyu")

/**
 * Compose 가 앱 리소스 자리를 알려 주는 시스템 프로퍼티.
 *
 * 설치한 앱에서는 실행기가 `$APPDIR/resources` 를 넣어 주고(app 이미지의 .cfg),
 * `./gradlew run` 에서는 Gradle 이 `build/compose/tmp/prepareAppResources` 를 넣어 준다.
 * 그 자리에 무엇이 들어가는지는 desktop/build.gradle.kts 의 appResourcesRootDir 이 정한다.
 */
private const val APPLICATION_RESOURCES_DIRECTORY_PROPERTY = "compose.application.resources.dir"

/**
 * 앱이 자기 손으로 받아 둔 엔진이 사는 자리.
 *
 * 플랫폼 관례를 따르되 XDG_DATA_HOME 은 보지 않는다. 이 디렉토리는 앱이 쓰고 앱이 읽는 곳이라
 * 지켜야 할 것은 "관례에 맞는 자리인가" 가 아니라 "쓴 자리와 읽는 자리가 같은가" 하나다.
 * 환경 변수를 보면 설치할 때와 다음에 띄울 때의 셸 설정이 다른 것만으로 받아 둔 엔진이 통째로
 * 안 보이게 되고, 그 엔진은 지워지지도 않은 채 어디에도 뜨지 않는다.
 *
 * 윈도우 갈래를 따로 두지 않는다. 릴리스가 윈도우 엔진 바이너리를 내지 않아 설치 화면이 받기 전에
 * 거절하고(EngineReleaseTarget), 이 자리는 그쪽에서 쓰이지 않는다. 쓰지도 않을 AppData 갈래를
 * 미리 두면 시험할 수 없는 코드가 하나 생긴다.
 */
internal fun managedEngineDirectory(
    osName: String = System.getProperty("os.name").orEmpty(),
    userHomeDirectory: Path = Path.of(System.getProperty("user.home").orEmpty()),
): Path = if (runningOnMac(osName)) {
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

/**
 * 확장자가 붙은 이름(kyu.exe)은 찾지 않는다.
 *
 * 릴리스가 윈도우 바이너리를 내지 않고(release.yml 의 크로스 컴파일 목록), 설치 패키지에서도
 * msi 가 빠졌다(build.gradle.kts 의 targetFormats). 그 둘이 그대로인 한 윈도우 네이티브는 대상이
 * 아니므로, 그 이름으로 찾아질 파일이 이 프로젝트에는 없다. 찾는 시늉만 하면 윈도우에서
 * 띄운 사람이 받게 될 답이 "WSL 에서 띄우세요" 가 아니라 침묵이 된다.
 */
private fun findOnSearchPath(searchPath: String?, isExecutableFile: (Path) -> Boolean): Path? =
    searchPath.orEmpty()
        .split(File.pathSeparatorChar)
        .filter { it.isNotBlank() }
        .map { directory -> Path.of(directory, "kyu") }
        .firstOrNull(isExecutableFile)

private fun isExecutableRegularFile(path: Path): Boolean = path.isRegularFile() && path.isExecutable()

/**
 * 동봉된 원본을 볼 때는 실행 권한을 따지지 않는다. jpackage 가 그 권한을 떼어 내는 것이 정상이고,
 * 그래서 앱이 이 파일을 복사해 자기 자리에 놓는다.
 */
private fun isExistingRegularFile(path: Path): Boolean = path.isRegularFile()
