import javax.inject.Inject
import org.gradle.process.ExecOperations
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    // Compose 컴파일러는 Kotlin 2.0 부터 Kotlin 과 같은 버전으로 함께 배포된다. 그래서 이 플러그인은
    // composeMultiplatform 이 아니라 kotlin 버전을 따라간다.
    alias(libs.plugins.kotlinComposeCompiler)
    alias(libs.plugins.composeMultiplatform)
    // kyu 의 JSON 계약을 읽는 데이터 클래스에 직렬화기를 붙여 준다. 리플렉션 없이 컴파일
    // 시점에 만들어지므로, 필드 이름을 잘못 적으면 실행이 아니라 빌드에서 걸린다.
    alias(libs.plugins.kotlinSerialization)
}

group = "com.kyuchestration.desktop"

// 릴리스 워크플로가 태그에서 뽑은 버전을 -PdesktopVersion 으로 넣는다(환경 변수로 넣을 때는
// ORG_GRADLE_PROJECT_desktopVersion). 아무것도 넘기지 않으면 개발용 기본값이라, 로컬에서
// ./gradlew run 하는 흐름은 이 줄이 생기기 전과 같다.
//
// jpackage 는 버전의 각 자리를 숫자로만 받는다. 태그 이름("v0.8.0")을 그대로 넘기면 컴파일도
// 패키징도 다 지난 마지막 단계에서야 깨지므로, 빌드를 구성하는 이 자리에서 먼저 끊는다.
val desktopVersion = (providers.gradleProperty("desktopVersion").orNull ?: "0.1.0").trim()
require(Regex("""\d+\.\d+\.\d+""").matches(desktopVersion)) {
    "desktopVersion 은 숫자 세 자리(MAJOR.MINOR.PATCH)여야 한다 — 받은 값: \"$desktopVersion\". " +
        "태그 이름이라면 앞의 v 를 떼고 넘긴다."
}
version = desktopVersion

// 맥 설치 패키지에만 넘기는 버전. 위의 제품 버전과 일부러 다르다.
//
// jpackage 는 맥 앱을 만들 때 --app-version 을 애플의 CFBundleVersion 규약으로 검사하고, 첫
// 숫자가 0 이면 거절한다(OpenJDK jdk.jpackage.internal.MacAppBundler.doValidate → CFBundleVersion.of
// — "first number in a CFBundleVersion cannot be zero"). 애플의 규약에서 0.x 는 아직 내놓은 적
// 없는 빌드라는 뜻이라서다.
//
// 그렇다고 제품 버전을 1.0.0 으로 올리지는 않는다. 1.0.0 은 기능 · 테스트 · 지원 플랫폼이
// 기준을 충족했을 때 선언하는 것이지 jpackage 가 요구해서 붙이는 숫자가 아니다. 도구 제약에
// 제품 버전을 맞추면 그 뒤로 버전이 뜻하는 바가 사라진다. 그래서 제품 버전은 0.x 로 두고,
// jpackage 가 보는 값만 여기서 앞자리를 올려 넘긴다.
//
// 앞자리만 바꾸고 나머지는 그대로 둔다(0.8.0 → 1.8.0). 되짚어 읽을 수 있고, 0.x 를 내는 동안은
// 값이 계속 커진다. 앞자리가 이미 1 이상이면 그대로 통과하므로 진짜 1.0.0 부터는 이 매핑이
// 사라진다.
//
// 대가가 둘 있고 둘 다 감수한다.
//   - 맥 Finder 정보창에는 이 매핑된 숫자가 보인다. 제품 버전은 앱 화면(jar 매니페스트의
//     Implementation-Version)과 릴리스 자산 이름에 있다 — README 데스크톱 절에 적어 뒀다.
//   - 진짜 1.0.0 을 낼 때 이 값이 한 번 내려간다(0.10.0 → 1.10.0 다음이 1.0.0). 0.x 를 1 이상으로
//     올리는 어떤 매핑을 써도 피할 수 없는 자리인데, 이 앱에는 자동 업데이트가 없어
//     CFBundleVersion 을 견주는 쪽이 없다.
val macPackageVersion =
    if (desktopVersion.startsWith("0.")) "1." + desktopVersion.removePrefix("0.") else desktopVersion

kotlin {
    // 툴체인을 못 박아 두면 개발자 머신의 기본 JDK 가 무엇이든 같은 바이트코드가 나온다.
    jvmToolchain(21)
}

dependencies {
    // currentOs 는 빌드하는 머신의 스키코(Skia 바인딩)만 가져온다. 배포용 패키지는 각 OS 에서
    // 만들기 때문에 개발 빌드가 세 플랫폼의 네이티브 바이너리를 모두 받을 이유가 없다.
    implementation(compose.desktop.currentOs)
    implementation(libs.composeMaterial3)
    implementation(libs.kotlinxSerializationJson)

    // Compose 가 이미 끌고 오지만, 상태 홀더가 Compose 없이 도는 순수 코틀린이라 여기서
    // 직접 적는다. 끌려온 것에 기대면 Compose 의존이 바뀔 때 이유도 모르고 깨진다.
    implementation(libs.kotlinxCoroutinesCore)

    // 앱 안의 터미널. JediTerm 이 화면과 터미널 에뮬레이션을, pty4j 가 그 아래의 진짜 PTY 를 맡는다.
    // 이 둘이 있어야 claude 가 요구하는 TTY 가 앱 안에 생긴다 — 앱이 세션을 직접 보유하는 근거다.
    implementation(libs.jeditermUi)
    implementation(libs.jeditermCore)
    implementation(libs.pty4j)
    runtimeOnly(libs.slf4jSimple)

    // kotlin("test") 는 적용된 Kotlin 플러그인의 버전을 그대로 따라간다. 버전 카탈로그에 한 줄 더
    // 적으면 Kotlin 을 올릴 때 두 곳을 맞춰야 하므로 여기서는 일부러 버전을 적지 않는다.
    testImplementation(kotlin("test"))

    // 폴링을 실제로 3 초 기다리며 시험하면 테스트가 그만큼 느려진다. 가상 시간으로 돌린다.
    testImplementation(libs.kotlinxCoroutinesTest)
}

// 엔진(kyu)을 설치 패키지 안에 함께 넣는다. 앱만 설치하면 끝나야 하기 때문이다 — 화면이
// 하는 모든 일이 엔진을 부르는 일인데, 그 엔진을 사용자가 따로 구해 와야 한다면 설치는 한
// 걸음에 끝나지 않는다.
//
// 동봉이 CLI 단독 설치를 대신하지 않는다. 터미널에서만 쓰는 사람은 install.sh 로 바이너리
// 하나를 넣으면 되고(README 설치 절), 그 길은 그대로다.
//
// build/ 아래에 둔다. 저장소에 적어 두는 것이 아니라 만들어지는 12MB 바이너리라, 소스 트리에
// 두면 .gitignore 한 줄을 더 얹어야 하고 clean 이 치우지도 못한다.
val bundledEngineRootDirectory: Provider<Directory> = layout.buildDirectory.dir("engineResources")

/**
 * 설치 패키지에 넣을 엔진의 대상.
 *
 * Compose 가 플랫폼별 리소스를 고르는 디렉토리 이름과 Go 가 대상을 부르는 철자를 한 자리에서
 * 정한다. 둘이 갈라지면 linux-x64 디렉토리에 darwin 바이너리가 놓이고, 그 사실은 받은 사람이
 * 앱을 띄워 엔진을 부를 때에야 드러난다.
 */
data class BundledEngineTarget(
    val resourceDirectoryName: String,
    val goOperatingSystem: String,
    val goArchitecture: String,
)

/**
 * 이 머신이 만들 동봉 엔진의 대상. 설치 패키지를 내지 않는 호스트에서는 안내와 함께 멈춘다.
 *
 * Provider 로 감싸 늦게 편다. 구성 단계에서 곧바로 정하면 이 값을 쓰지도 않는
 * `./gradlew build` 까지 윈도우에서 통째로 끊긴다 — 윈도우 네이티브는 설치 패키지의 대상이
 * 아니지만(msi 를 내지 않는다), 그 머신에서 컴파일과 테스트까지 막을 이유는 없다.
 */
val hostBundledEngineTarget: Provider<BundledEngineTarget> = providers.provider {
    val hostOperatingSystemName = System.getProperty("os.name").orEmpty()
    val hostArchitecture = System.getProperty("os.arch").orEmpty()

    val (resourceOperatingSystemName, goOperatingSystem) = when {
        hostOperatingSystemName.startsWith("Mac", ignoreCase = true) -> "macos" to "darwin"
        hostOperatingSystemName.startsWith("Linux", ignoreCase = true) -> "linux" to "linux"
        else -> throw GradleException(
            "이 호스트($hostOperatingSystemName)에서는 엔진을 동봉하지 않습니다. " +
                "설치 패키지를 내는 것은 맥과 리눅스뿐이고, 윈도우 사용자는 WSL 안에서 리눅스 패키지를 씁니다.",
        )
    }
    val (resourceArchitecture, goArchitecture) = when (hostArchitecture) {
        "amd64", "x86_64" -> "x64" to "amd64"
        "aarch64", "arm64" -> "arm64" to "arm64"
        else -> throw GradleException(
            "이 아키텍처($hostArchitecture)용 엔진은 만들지 않습니다 — x86-64 와 arm64 만 냅니다.",
        )
    }

    BundledEngineTarget(
        resourceDirectoryName = "$resourceOperatingSystemName-$resourceArchitecture",
        goOperatingSystem = goOperatingSystem,
        goArchitecture = goArchitecture,
    )
}

/** 패키징이 집어 갈 엔진 파일. 이름은 PATH 에 두는 것과 같은 `kyu` 다. */
val bundledEngineExecutableFile: Provider<RegularFile> = hostBundledEngineTarget.flatMap { target ->
    bundledEngineRootDirectory.map { it.dir(target.resourceDirectoryName).file("kyu") }
}

/**
 * 엔진을 만들 go 실행 파일. 이 머신에 Go 가 없으면 null.
 *
 * PATH 만 보지 않는 이유가 둘이다. 하나는 Go 를 tarball 로 넣으면 그 bin 을 PATH 에 넣는 것이
 * 사용자 몫이라 넣지 않은 머신이 흔해서고, 다른 하나는 IDE 나 편집기가 띄운 Gradle 이 로그인
 * 셸의 PATH 를 물려받지 못하는 자리가 있어서다. 둘 다 Go 가 설치되어 있는데 "없다" 로 읽힌다.
 *
 * 찾은 자리를 그대로 실행 파일로 넘긴다(아래 goExecutablePath). "있다고 본 자리" 와 "실제로
 * 부르는 자리" 가 갈라지면 탐지는 성공하고 빌드만 "command go 를 띄우지 못했다" 로 깨진다 —
 * 이 저장소에서 실제로 났던 일이다: go 가 `~/.local/go/bin` 에 있는 머신에서 buildBundledEngine
 * 이 "PATH 에서 찾지 못했습니다" 로 멈췄다.
 */
fun findGoExecutable(): File? {
    val goRootBinDirectory = System.getenv("GOROOT")
        ?.takeIf { it.isNotBlank() }
        ?.let { File(it, "bin") }

    val searchPathDirectories = System.getenv("PATH").orEmpty()
        .split(File.pathSeparatorChar)
        .filter { it.isNotBlank() }
        .map(::File)

    // Go 를 넣는 흔한 자리들. tarball 을 푼 자리 둘(go.dev/doc/install 이 안내하는
    // /usr/local/go 와, 관리자 권한 없이 넣을 때 쓰는 홈 아래)과 애플 실리콘 맥의 Homebrew 다.
    val commonInstallationDirectories = listOf(
        File(System.getProperty("user.home").orEmpty(), ".local/go/bin"),
        File("/usr/local/go/bin"),
        File("/opt/homebrew/bin"),
    )

    return (listOfNotNull(goRootBinDirectory) + searchPathDirectories + commonInstallationDirectories)
        .map { File(it, "go") }
        .firstOrNull { it.isFile && it.canExecute() }
}

val goExecutable: File? = findGoExecutable()

/**
 * 저장소 루트의 Go 모듈에서 이 머신용 kyu 를 만들어 동봉 자리에 놓는다.
 *
 * 모노레포의 이점을 그대로 쓴다. 엔진 소스가 옆에 있으므로 앱을 패키징하는 사람이 엔진 릴리스를
 * 기다리거나 어딘가에서 받아 올 이유가 없다.
 *
 * 산출물을 Gradle 의 입출력으로 선언하지 않는다. 선언하면 그 자리를 읽는 prepareAppResources 가
 * "의존을 선언하지 않고 남의 산출물을 읽는다" 로 걸리는데, 그 의존을 선언하는 순간
 * `./gradlew run` 까지 Go 를 요구하게 된다(run 이 prepareAppResources 를 거친다). 소스에서
 * 띄우는 흐름은 동봉 없이도 돌아야 하므로, 대신 이 태스크는 부를 때마다 실제로 만든다 —
 * go 자신의 빌드 캐시가 있어 두 번째부터는 몇 초다.
 */
abstract class BuildBundledEngine @Inject constructor(
    private val execOperations: ExecOperations,
) : DefaultTask() {

    /**
     * 부를 go 실행 파일의 절대 경로. 비어 있으면 이 머신에 Go 가 없다.
     *
     * 이름("go")이 아니라 경로를 받는다. 이름으로 부르면 PATH 에 있는 것만 걸려, 찾을 때 본
     * 자리와 부를 때 보는 자리가 갈라진다.
     */
    @get:Internal
    abstract val goExecutablePath: Property<String>

    @get:Internal
    abstract val goModuleDirectory: DirectoryProperty

    @get:Internal
    abstract val bundledEngineExecutable: RegularFileProperty

    @get:Internal
    abstract val goOperatingSystem: Property<String>

    @get:Internal
    abstract val goArchitecture: Property<String>

    /**
     * 링커로 엔진에 심을 버전. 비어 있으면 심지 않는다 — 그때 엔진은 빌드에 쓰인 커밋으로
     * 자기를 말한다(internal/cli.versionName).
     */
    @get:Internal
    abstract val engineVersion: Property<String>

    @TaskAction
    fun buildEngine() {
        val goExecutable = requireGoIsInstalled()

        val engineFile = bundledEngineExecutable.get().asFile
        engineFile.parentFile.mkdirs()

        execOperations.exec {
            executable = goExecutable
            workingDir = goModuleDirectory.get().asFile
            args = buildList {
                add("build")
                // -trimpath 와 CGO_ENABLED=0 은 릴리스가 내는 kyu 와 같은 조건이다
                // (release.yml 의 크로스 컴파일). 조건이 갈라지면 동봉된 엔진과 따로 받은
                // 엔진이 같은 태그에서도 다르게 동작할 수 있다.
                add("-trimpath")
                engineVersion.orNull?.takeIf { it.isNotBlank() }?.let { version ->
                    add("-ldflags")
                    add("-X $VERSION_LDFLAGS_SYMBOL_ASSIGNMENT$version")
                }
                add("-o")
                add(engineFile.absolutePath)
                add("./cmd/kyu")
            }
            environment("GOOS", goOperatingSystem.get())
            environment("GOARCH", goArchitecture.get())
            environment("CGO_ENABLED", "0")
        }
    }

    /**
     * go 가 없는 것을 먼저 끊는다.
     *
     * 그러지 않으면 Gradle 이 내는 말은 "A problem occurred starting process 'command go'" 뿐이라,
     * 이 저장소를 처음 여는 사람은 무엇을 설치해야 하는지 알 수 없다.
     */
    private fun requireGoIsInstalled(): String = goExecutablePath.orNull ?: throw GradleException(
        "엔진을 만들 Go 를 찾지 못했습니다. " +
            "저장소 루트의 go.mod 가 요구하는 판을 설치하거나(https://go.dev/dl), " +
            "이미 만들어 둔 kyu 를 ${bundledEngineExecutable.get().asFile} 에 직접 놓으세요.",
    )

    private companion object {
        /**
         * 링커가 버전을 심는 심볼. 뒤의 `=` 까지 한 문자열로 적는다.
         *
         * 링커는 없는 심볼을 조용히 무시한다 — 경로를 한 글자 틀려도 빌드는 성공하고, 동봉된
         * 엔진만 자기를 dev 라고 답한다. cmd/kyu/main_test.go 가 이 파일에서 이 모양을 찾아
         * 그 오타를 잡으므로, 경로와 `=` 를 쪼개 적으면 그 그물이 헐거워진다.
         */
        const val VERSION_LDFLAGS_SYMBOL_ASSIGNMENT =
            "github.com/maximinhan/Kyuchestration/internal/cli.injectedVersion="
    }
}

val buildBundledEngine = tasks.register<BuildBundledEngine>("buildBundledEngine") {
    group = "compose desktop"
    description = "저장소 루트의 Go 모듈에서 이 머신용 kyu 를 만들어 설치 패키지에 넣을 자리에 놓는다"

    goExecutablePath.set(goExecutable?.absolutePath)
    goModuleDirectory.set(layout.projectDirectory.dir(".."))
    bundledEngineExecutable.set(bundledEngineExecutableFile)
    goOperatingSystem.set(hostBundledEngineTarget.map { it.goOperatingSystem })
    goArchitecture.set(hostBundledEngineTarget.map { it.goArchitecture })

    // 앱 버전을 받은 빌드는 엔진에도 같은 태그를 심는다. 동봉된 엔진의 버전이 앱 버전과
    // 다를 수 있으면 `kyu version` 의 답이 "이 앱이 무엇을 부리고 있는가" 를 말하지 못한다.
    // 아무것도 넘기지 않는 로컬 빌드에서는 심지 않는다 — 개발 중인 빌드에 v0.1.0 이라고
    // 적어 두면 그 숫자가 릴리스와 같은 뜻으로 읽힌다.
    engineVersion.set(providers.gradleProperty("desktopVersion").map { "v${it.trim()}" })
}

val checkBundledEngine = tasks.register("checkBundledEngine") {
    group = "verification"
    description = "설치 패키지에 넣을 엔진이 자리에 있는지 확인한다"

    doLast {
        val engineFile = bundledEngineExecutableFile.get().asFile
        if (!engineFile.isFile) {
            throw GradleException(
                "설치 패키지에 넣을 엔진이 없습니다: $engineFile\n" +
                    "./gradlew buildBundledEngine 으로 만든 뒤 다시 패키징하세요. " +
                    "엔진 없이 만든 패키지는 설치해도 첫 화면에서 엔진부터 받아야 합니다.",
            )
        }
    }
}

// 엔진이 빠진 설치 패키지가 나가지 않게 막는다. 빠져도 빌드는 끝까지 성공하므로, 받아서
// 설치해 보기 전에는 아무도 모른다.
//
// run 은 걸지 않는다. 소스에서 띄우는 흐름은 엔진을 PATH 에 두거나 첫 화면에서 받는 예전
// 길이 그대로 열려 있어야 한다.
//
// packageRelease* 갈래는 걸지 않는다. 프로가드가 pty4j 를 깨서 이 저장소가 쓰지 않는
// 태스크들이다(위 application 블록의 주석).
//
// named 가 아니라 matching 으로 잡는다 — 이 태스크들은 Compose 플러그인이 프로젝트 평가가
// 끝난 뒤에 등록해서, 이 자리에서 이름으로 찾으면 아직 없다.
val packagingTaskNames = setOf("createDistributable", "packageDeb", "packageDmg")
tasks.matching { it.name in packagingTaskNames }.configureEach {
    dependsOn(checkBundledEngine)
}

// 한 번의 실행에서 둘 다 부를 때(./gradlew buildBundledEngine packageDeb) 리소스를 옮기는
// 쪽이 나중에 오게 한다. 의존이 아니라 순서만 건다 — 의존으로 걸면 Go 가 없는 머신에서
// packageDeb 뿐 아니라 run 까지 통째로 멈춘다. run 쪽 의존은 아래에서 Go 가 있을 때만 건다.
tasks.matching { it.name == "prepareAppResources" }.configureEach {
    mustRunAfter(buildBundledEngine)
}

// 엔진을 새로 만들지 않고 앱만 띄우고 싶을 때 끈다: ./gradlew run -PbundledEngineForRun=false
//
// 화면만 손보는 중이면 go 빌드가 매번 앞에 얹힌다. 그 몇 초를 아끼려고 사람들이 각자 다른
// 우회를 찾아내게 두느니 끄는 자리를 하나 열어 둔다.
//
// toBooleanStrict 라 오타(-PbundledEngineForRun=yes)는 조용히 참으로 읽히지 않고 여기서 걸린다.
val bundledEngineForRun = providers.gradleProperty("bundledEngineForRun")
    .map { it.trim().toBooleanStrict() }
    .getOrElse(true)

/**
 * 소스에서 띄우는 흐름도 엔진을 스스로 갖춘다.
 *
 * 모노레포에 엔진 소스가 옆에 있는데도 `./gradlew run` 이 그것을 쓰지 않으면, 소스를 받아 처음
 * 띄운 사람은 앱이 아니라 "엔진이 없다" 는 화면부터 만난다(실제로 맥에서 그렇게 됐다). 여기서
 * 함께 만들어 두면 그 사람이 부리는 것은 **자기가 지금 가진 소스와 정확히 같은 판의 엔진** 이다 —
 * 릴리스에서 받아 온 엔진으로 시험하면 앱 쪽 변경과 엔진 쪽 변경이 서로 다른 판에서 만나고,
 * 그때 나는 어긋남은 어느 쪽 것인지 가려지지 않는다.
 *
 * Go 가 없는 머신에는 걸지 않는다. 화면만 고치려는 사람에게 Go 설치를 요구하는 것은 지나치고,
 * 그때는 앱이 첫 화면에서 최신 릴리스를 받아 온다.
 *
 * `build` · `test` 는 그대로 Go 와 무관하다 — 여기서 거는 것은 run 하나뿐이다.
 *
 * 윈도우 네이티브에서 Go 를 가진 채로 run 하면 buildBundledEngine 이 "이 호스트에서는 엔진을
 * 동봉하지 않습니다 — WSL 안에서 쓰세요" 로 멈춘다. 감수하는 쪽을 골랐다: 릴리스가 윈도우 엔진
 * 바이너리를 내지 않아 그 머신에서는 앱이 떠도 부를 kyu 가 없으므로(EngineInstallationFailure 의
 * WindowsNeedsWsl), 조용히 뜨는 것보다 그 자리에서 이유를 듣는 편이 낫다.
 */
tasks.matching { it.name == "run" }.configureEach {
    when {
        !bundledEngineForRun -> doFirst {
            logger.lifecycle(
                "동봉 엔진 없이 실행 — -PbundledEngineForRun=false 로 껐습니다. " +
                    "부를 kyu 가 없으면 앱이 첫 화면에서 최신 릴리스를 받아 옵니다.",
            )
        }

        goExecutable == null -> doFirst {
            logger.lifecycle(
                "동봉 엔진 없이 실행 — Go 를 찾지 못했습니다. " +
                    "부를 kyu 가 없으면 앱이 첫 화면에서 최신 릴리스를 받아 옵니다.",
            )
        }

        else -> dependsOn(buildBundledEngine)
    }
}

compose.desktop {
    application {
        // Kotlin 최상위 함수는 파일 이름에 Kt 가 붙은 클래스로 컴파일된다. Main.kt 의 main() 이라
        // MainKt 다.
        mainClass = "com.kyuchestration.desktop.MainKt"

        // 배포 패키지는 packageDeb·packageDmg 로 만든다 — packageRelease* 가 아니다.
        // release 변형은 프로가드로 코드를 줄이는데, JNA 가 네이티브 쪽에서 이름으로 찾아 쓰는
        // com.sun.jna.Native.dispose 까지 "아무도 부르지 않는다" 며 지운다. 그러면 pty4j 가
        // PTY 를 여는 첫 순간 UnsatisfiedLinkError 로 죽는다 — 앱의 핵심인 임베디드 터미널이
        // 통째로 못 쓰게 되는데, 빌드는 끝까지 성공하므로 받아서 눌러 보기 전에는 알 수 없다.
        // (같은 클래스패스로 PTY 를 여는 시험: 최소화본은 위 오류, 최소화 없는 쪽은 정상 — PR 26)
        // 줄여서 얻는 것은 46MB 대 62MB 뿐이라, 검증할 수 없는 keep 규칙을 손으로 떠안느니
        // CI 가 실제로 시험한 바이트코드를 그대로 배포한다.

        nativeDistributions {
            // 두 포맷 모두 jpackage 가 만들고, jpackage 는 자기가 도는 OS 용 패키지만 만들 수 있다.
            // 여기에 적어 두는 것은 "어디로 배포할 것인가" 의 선언이고, 실제 생성은 각 OS 러너에서
            // 일어난다(.github/workflows/release.yml).
            //
            // Msi 는 뺀다. 이 앱은 화면일 뿐이고 실제 일은 엔진인 kyu 가 하는데, 릴리스가 윈도우
            // 엔진 바이너리를 내지 않아(release.yml 의 크로스 컴파일 목록) 설치는 되고 부를 것이
            // 없는 패키지가 나온다. 윈도우 사용자는 WSL 안에서 리눅스 패키지를 쓴다.
            targetFormats(TargetFormat.Dmg, TargetFormat.Deb)

            // 설치 패키지 이름은 파일 이름과 설치 경로(/opt/kyuchestration · /Applications)에
            // 그대로 쓰인다. 한글이 섞이면 그 경로들이 깨지므로, 창 제목("뀨케스트레이션")과
            // 분리해 ASCII 로 둔다.
            packageName = "Kyuchestration"
            packageVersion = project.version.toString()
            description = "뀨케스트레이션 — 멀티레포 워크디렉토리 오케스트레이터"
            vendor = "maximinhan"

            // 엔진(kyu)을 앱 패키지 안에 함께 넣는 자리. Compose 는 이 디렉토리 아래에서 이
            // 플랫폼의 하위 디렉토리(linux-x64 · macos-arm64 · macos-x64)를 골라 앱 이미지의
            // resources 로 옮기고, 앱은 실행 시점에 compose.application.resources.dir 시스템
            // 프로퍼티로 그 자리를 받는다.
            appResourcesRootDir.set(bundledEngineRootDirectory)

            macOS {
                bundleID = "com.kyuchestration.desktop"

                // 맥 형식(app-image · dmg)만 이 값을 쓴다 — deb 는 위의 packageVersion 그대로
                // 제품 버전이다. Compose 플러그인은 이것을 jpackage 의 --app-version 으로 넘기고
                // Info.plist 의 CFBundleShortVersionString 에 적으며, CFBundleVersion 은 따로
                // 주지 않으면 같은 값을 따라간다(packageVersionFor·packageBuildVersionFor).
                // 두 값이 같아도 되는 자리라 packageBuildVersion 은 두지 않는다.
                packageVersion = macPackageVersion
            }
        }
    }
}

tasks.jar {
    manifest {
        // DesktopBuildVersion 이 화면에 올릴 버전을 여기서 읽는다. 이 한 줄이 빠지면 정식으로
        // 패키징한 앱도 영영 "개발 빌드" 로 뜬다.
        attributes("Implementation-Version" to project.version)
    }
}

tasks.test {
    useJUnitPlatform()

    // 통합 검증이 읽는 환경 변수를 시험 JVM 까지 실어 보낸다.
    //
    // 그러지 않으면 조용히 건너뛴다. Gradle 데몬은 한 번 뜨면 그때의 환경을 들고 계속 사는데,
    // 시험 JVM 은 그 데몬의 환경을 물려받는다 — 그래서 KYU_BINARY_PATH 를 주고 불러도 데몬이
    // 먼저 떠 있었으면 시험은 그 값을 못 본다. 검증이 실패로 끝나는 것이 아니라 **건너뛴 채로
    // 초록**이 되므로, 준 사람은 자기가 검증했다고 믿는다.
    //
    // providers.environmentVariable 은 이 빌드를 부른 쪽의 환경을 읽는다(데몬의 것이 아니다).
    // 그 값을 여기서 시험 JVM 에 다시 실어 주면 부를 때마다 준 값이 그대로 닿는다.
    listOf("KYU_BINARY_PATH", "KYU_CLAUDE_CHAT_INTEGRATION").forEach { variableName ->
        providers.environmentVariable(variableName).orNull?.let { value -> environment(variableName, value) }
    }
}
