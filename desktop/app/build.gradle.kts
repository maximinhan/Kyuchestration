import javax.inject.Inject
import org.gradle.process.ExecOperations
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

// 앱의 실행기와 설치 패키지(core-ui-split-design.md 6.16).
//
// 설치 패키지에는 실행 파일이 하나다 — 화면은 그 실행기를 `core serve --stdio` 로 다시 띄워 코어를 자식으로 둔다. 코어만의
// 실행기를 따로 두지 못하는 까닭(2 단계 실측):
//   - Compose 플러그인은 jpackage 의 --add-launcher 를 열지 않는다(AbstractJPackageTask 에 그 인자가 없다).
//   - Compose 가 런타임을 만들 때 --strip-native-commands 를 고정으로 넘겨 런타임에 bin/java 가 없다 — 같은 런타임으로
//     `java -cp` 할 수 없다.
//   - 앱 리소스로 넣은 파일은 설치된 패키지에서 실행 권한을 잃는다(deb 실측 -rw-r--r--) — 코어의 앱 이미지를 리소스로 담아도
//     그 실행기와 jspawnhelper 를 부를 수 없다.
plugins {
    alias(libs.plugins.kotlinJvm)
    // Compose 컴파일러는 Kotlin 2.0 부터 Kotlin 과 같은 버전으로 함께 배포된다. 그래서 이 플러그인은
    // composeMultiplatform 이 아니라 kotlin 버전을 따라간다.
    alias(libs.plugins.kotlinComposeCompiler)
    alias(libs.plugins.composeMultiplatform)
}

// 버전은 세 모듈이 함께 쓰는 판단이라 루트 build.gradle.kts 가 정한다(화면과 코어가 같은 판으로 나간다).
val desktopVersion = project.version.toString()

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

dependencies {
    // 이 모듈은 실행기 하나다. 화면과 코어는 서로를 모른다 — 둘을 함께 아는 것은 같은 실행기로 둘 중 무엇을 띄울지 가르는
    // 이 자리뿐이다(DesktopEntry).
    implementation(project(":ui"))
    implementation(project(":core"))

    // 패키징할 이 머신의 스키코(Skia 바인딩)를 싣는다. ui 가 컴파일에 쓰는 것과 같은 것이다.
    implementation(compose.desktop.currentOs)
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
 * `./gradlew build` 까지 윈도우에서 통째로 끊긴다. 윈도우의 설치 패키지(msi)는 화면만 담고 엔진을
 * 담지 않는다 — 엔진은 WSL 의 코어 옆에 있다(core-ui-split-design.md 6.16). 그래서 윈도우의 패키징은
 * 이 값을 묻지 않는다(아래 hostPackagesBundledEngine).
 */
val hostBundledEngineTarget: Provider<BundledEngineTarget> = providers.provider {
    val hostOperatingSystemName = System.getProperty("os.name").orEmpty()
    val hostArchitecture = System.getProperty("os.arch").orEmpty()

    val (resourceOperatingSystemName, goOperatingSystem) = when {
        hostOperatingSystemName.startsWith("Mac", ignoreCase = true) -> "macos" to "darwin"
        hostOperatingSystemName.startsWith("Linux", ignoreCase = true) -> "linux" to "linux"
        else -> throw GradleException(
            "이 호스트($hostOperatingSystemName)의 설치 패키지는 엔진을 담지 않습니다. " +
                "윈도우의 msi 는 화면만 담고, 엔진은 WSL 에 코어와 함께 놓습니다(install.sh --with-core).",
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
    // 이 모듈은 desktop/ui 다. 저장소 루트의 Go 모듈은 두 칸 위에 있다.
    goModuleDirectory.set(layout.projectDirectory.dir("../.."))
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
// packageRelease* 갈래는 걸지 않는다. 이 저장소가 쓰지 않는 태스크들이다(위 application 블록의 주석).
//
// named 가 아니라 matching 으로 잡는다 — 이 태스크들은 Compose 플러그인이 프로젝트 평가가
// 끝난 뒤에 등록해서, 이 자리에서 이름으로 찾으면 아직 없다.
//
// 윈도우는 걸지 않는다. msi 는 화면만 담는다 — 코어와 엔진은 WSL 에 따로 놓는다(core-ui-split-design.md 6.16).
val hostIsWindows: Boolean by rootProject.extra
val hostPackagesBundledEngine = !hostIsWindows
val packagingTaskNames = setOf("createDistributable", "packageDeb", "packageDmg")
if (hostPackagesBundledEngine) {
    tasks.matching { it.name in packagingTaskNames }.configureEach {
        dependsOn(checkBundledEngine)
    }
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
 * 윈도우에서는 엔진을 만들지 않는다. 윈도우의 화면은 WSL 의 코어에 붙고, 엔진은 그 코어 옆에 있다.
 */
tasks.matching { it.name == "run" }.configureEach {
    when {
        !hostPackagesBundledEngine -> doFirst {
            logger.lifecycle("윈도우 화면 — 엔진은 WSL 의 코어 옆에 있습니다(install.sh --with-core).")
        }

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
        // Kotlin 최상위 함수는 파일 이름에 Kt 가 붙은 클래스로 컴파일된다. DesktopEntry.kt 의 main() 이라
        // DesktopEntryKt 다. 화면도 코어도 이 main 으로 뜬다.
        mainClass = "com.kyuchestration.desktop.app.DesktopEntryKt"

        // 배포 패키지는 packageDeb·packageDmg 로 만든다 — packageRelease* 가 아니다.
        //
        // 처음의 이유는 pty4j 였다. release 변형이 프로가드로 코드를 줄이면서 JNA 가 네이티브
        // 쪽에서 이름으로 찾아 쓰는 com.sun.jna.Native.dispose 까지 지웠고, 그러면 PTY 를 여는
        // 첫 순간 UnsatisfiedLinkError 로 죽었다(PR 26). **그 이유는 pty4j 와 함께 사라졌다.**
        //
        // 그래도 옮기지 않는다. 최소화가 지금의 클래스패스에서 무엇을 깨는지 아무도 재지 않았고,
        // 그 확인은 이 자리에서 짐작으로 대신할 것이 아니다 — 빌드는 끝까지 성공하고 받아서
        // 띄워 봐야 드러나는 종류의 실패다. 재 보는 것은 별도 작업이다.

        nativeDistributions {
            // 세 포맷 모두 jpackage 가 만들고, jpackage 는 자기가 도는 OS 용 패키지만 만들 수 있다.
            // 여기에 적어 두는 것은 "어디로 배포할 것인가" 의 선언이고, 실제 생성은 각 OS 러너에서
            // 일어난다(.github/workflows/release.yml).
            //
            // Msi 는 화면만 담는다. 윈도우의 코어와 엔진은 WSL 안에 install.sh --with-core 로 놓고,
            // 화면이 wsl.exe 로 그 코어를 띄운다(core-ui-split-design.md 6.12 · 6.16).
            targetFormats(TargetFormat.Dmg, TargetFormat.Deb, TargetFormat.Msi)

            // 설치 패키지 이름은 파일 이름과 설치 경로(/opt/kyuchestration · /Applications)에
            // 그대로 쓰인다. 한글이 섞이면 그 경로들이 깨지므로, 창 제목("뀨케스트레이션")과
            // 분리해 ASCII 로 둔다.
            packageName = "Kyuchestration"
            packageVersion = project.version.toString()
            // msi 는 한국어 설명을 실을 수 없다. jpackage 가 쓰는 WiX 3 는 MSI 데이터베이스를 코드 페이지 1252 로 짓고, 그 밖의
            // 글자를 만나면 light.exe 가 LGHT0311 로 멈춘다(윈도우 러너 실측: exit 311). 설명은 윈도우의 "앱 및 기능" 에만
            // 보이는 값이라 윈도우에서 만드는 패키지에만 로마자로 둔다 — deb · dmg 는 그대로 한국어다.
            description = if (hostIsWindows) {
                "Kyuchestration - multi-repo workdir orchestrator"
            } else {
                "뀨케스트레이션 — 멀티레포 워크디렉토리 오케스트레이터"
            }
            vendor = "maximinhan"

            // 엔진(kyu)을 앱 패키지 안에 함께 넣는 자리. Compose 는 이 디렉토리 아래에서 이
            // 플랫폼의 하위 디렉토리(linux-x64 · macos-arm64 · macos-x64)를 골라 앱 이미지의
            // resources 로 옮기고, 앱은 실행 시점에 compose.application.resources.dir 시스템
            // 프로퍼티로 그 자리를 받는다.
            appResourcesRootDir.set(bundledEngineRootDirectory)

            windows {
                // 관리자 권한을 묻지 않는다(%LOCALAPPDATA% 아래에 깔린다). 이 앱이 만지는 것은 사용자의 것뿐이다.
                perUserInstall = true
                // 판을 올려 덮어 깔 수 있게 고정한다. 바뀌면 윈도우가 같은 앱의 새 판을 다른 앱으로 보고 둘 다 남긴다.
                upgradeUuid = "A1051134-71F2-40BE-9BFF-F18CF43D87B7"
                menu = true
                menuGroup = "Kyuchestration"
                shortcut = true
            }

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

