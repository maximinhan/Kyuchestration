import java.io.ByteArrayOutputStream
import javax.inject.Inject
import org.gradle.process.ExecOperations

// 세션을 소유하는 헤드리스 코어(core-ui-split-design.md 6.15). 상태 홀더 · claude 프로세스 · 앱 소켓 · kyu 호출이 여기 있다.
//
// Compose 를 의존하지 않는다. 클래스패스의 대부분이 화면 것이고(설계 3.6), 코어 코드가 Compose 타입을 쓰면 여기서 컴파일이
// 깨진다 — 의존 방향을 시험이 아니라 빌드가 막는다.
plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    // 코어의 시험 도구(기록하는 진단 기록 · 명령을 모두 한 번씩 부르는 자리 · 시험용 kyu 바이너리)를 화면의 시험도 쓴다.
    // 시험 소스를 서로 끌어다 쓰면 한 모듈의 시험이 다른 모듈의 시험 클래스 전부를 싣는다 — 내놓을 것만 test fixtures 로 둔다.
    `java-test-fixtures`
}

dependencies {
    api(project(":protocol"))
    api(project(":diagnostics"))
    implementation(libs.kotlinxSerializationJson)
    implementation(libs.kotlinxCoroutinesCore)

    // 폴링을 실제로 3 초 기다리며 시험하면 테스트가 그만큼 느려진다. 가상 시간으로 돌린다.
    testImplementation(libs.kotlinxCoroutinesTest)

    // 시험용 kyu 가 없을 때 시험을 건너뛰게 하는 자리(Assumptions.abort)가 JUnit 5 를 직접 쓴다. 시험 쪽의 kotlin("test") 는
    // useJUnitPlatform 을 보고 같은 junit5 변형을 고르므로 판이 갈리지 않는다.
    testFixturesImplementation(kotlin("test-junit5"))
    testFixturesImplementation(libs.kotlinxCoroutinesTest)
    testFixturesApi(testFixtures(project(":diagnostics")))
}

// 코어는 리눅스 · 맥에서만 돈다 — 윈도우의 코어는 WSL 안에 있다(core-ui-split-design.md 6.16). 시험도 셸 스크립트 · 가짜 kyu ·
// POSIX 경로 위에 서 있다. 윈도우 CI 는 컴파일까지만 보고 시험은 건너뛴다 — 건너뛴 사실은 이 이유와 함께 빌드 로그에 남는다.
val hostIsWindows: Boolean by rootProject.extra
tasks.named<Test>("test") {
    onlyIf("코어는 윈도우에서 돌지 않는다(설계 6.16) — 윈도우에서는 코어 시험을 건너뛴다") { !hostIsWindows }
}

// ── WSL 에 설치하는 코어 이미지(core-ui-split-design.md 6.16 의 3 단계 정정) ─────────────────────────────────────────────
//
// 윈도우 사용자의 코어는 WSL 안에 산다. install.sh --with-core 가 이 이미지를 ~/.local/share/kyuchestration/core 에 풀고
// ~/.local/bin/kyuchestration-core 로 링크한다. 이미지는 릴리스 자산으로도 나가고, 아래 WSL 코어 묶음에 실려 msi 안에도 들어간다.
//
//   kyuchestration-core/
//   ├─ bin/kyuchestration-core   링크를 풀어 이미지 자리를 찾는 sh 실행 파일(src/coreImage)
//   ├─ runtime/                  jlink 런타임 — WSL 에 자바를 요구하지 않는다
//   ├─ lib/*.jar                 코어와 그 의존
//   └─ VERSION                   판 라벨 한 줄(v0.16.0) — 깔린 코어의 판을 화면이 띄우지 않고 읽는 자리
//
// 런타임을 품는 까닭: WSL 의 Ubuntu 에는 기본으로 자바가 없고, 설치하려면 sudo 가 필요하다. 설치 스크립트는 sudo 없이 ~/.local
// 에만 놓는다(install.sh 와 같은 자세). 그 값이 런타임 40 MB(실측, jdeps 가 고른 모듈 + TLS 의 EC 제공자)다.
//
// 이미지는 리눅스에서만 만든다 — jlink 는 자기가 도는 OS 의 런타임을 만든다.

/** 이미지의 jlink 런타임을 만든다. 넣을 모듈은 jdeps 가 클래스패스를 읽어 고른다 — 손으로 적으면 의존이 바뀔 때 조용히 모자란다. */
abstract class LinkCoreRuntime @Inject constructor(
    private val execOperations: ExecOperations,
) : DefaultTask() {

    @get:Classpath
    abstract val coreClasspath: ConfigurableFileCollection

    /**
     * jlink · jdeps 를 꺼내 쓸 툴체인. 입력이다 — JDK 판이 바뀌면 런타임을 다시 만든다. 경로만 Internal 로 두면 툴체인이 바뀌어도
     * 태스크가 up-to-date 로 낡은 런타임을 묶는다. JavaLauncher 는 판 · 제공자를 입력으로 내놓는 Gradle 의 타입이다.
     */
    @get:Nested
    abstract val javaLauncher: Property<JavaLauncher>

    @get:OutputDirectory
    abstract val runtimeDirectory: DirectoryProperty

    @TaskAction
    fun link() {
        val jdk = javaLauncher.get().metadata.installationPath.asFile
        val jars = coreClasspath.files.filter { it.name.endsWith(".jar") }.map { it.absolutePath }
        val moduleListing = ByteArrayOutputStream()
        execOperations.exec {
            executable = jdk.resolve("bin/jdeps").absolutePath
            args = listOf("--print-module-deps", "--ignore-missing-deps", "--multi-release", "21") + jars
            standardOutput = moduleListing
        }
        val modules = moduleListing.toString().trim().split(',').filter { it.isNotBlank() } + MODULES_JDEPS_CANNOT_SEE
        val output = runtimeDirectory.get().asFile
        output.deleteRecursively()
        execOperations.exec {
            executable = jdk.resolve("bin/jlink").absolutePath
            args = listOf(
                "--add-modules", modules.distinct().joinToString(","),
                "--strip-debug", "--no-header-files", "--no-man-pages", "--compress=zip-6",
                "--output", output.absolutePath,
            )
        }
    }

    private companion object {
        // TLS 가 쓰는 타원 곡선 제공자는 서비스로 실려 jdeps 가 보지 못한다. 코어가 엔진을 GitHub 에서 받는 HTTPS 가 그것을 쓴다
        // (실측: 빼고 만든 런타임은 api.github.com 에 handshake_failure).
        val MODULES_JDEPS_CANNOT_SEE = listOf("jdk.crypto.ec")
    }
}

/** 이 호스트에서 만든 코어 이미지의 아키텍처(Go · 릴리스 자산의 철자). 리눅스가 아니면 이미지를 만들 수 없어 멈춘다. */
fun hostCoreImageArchitecture(): String {
    val hostOperatingSystemName = System.getProperty("os.name").orEmpty()
    if (!hostOperatingSystemName.startsWith("Linux", ignoreCase = true)) {
        throw GradleException("코어 이미지는 리눅스에서만 만듭니다 — jlink 는 자기가 도는 OS 의 런타임을 만듭니다($hostOperatingSystemName).")
    }
    return when (val hostArchitecture = System.getProperty("os.arch").orEmpty()) {
        "amd64", "x86_64" -> "amd64"
        "aarch64", "arm64" -> "arm64"
        else -> throw GradleException("이 아키텍처($hostArchitecture)용 코어 이미지는 만들지 않습니다.")
    }
}

val coreImageArchitecture: Provider<String> = providers.provider { hostCoreImageArchitecture() }

/**
 * 이미지 안에서 실행되는 파일. 그 밖은 모두 읽기 · 쓰기 파일이다.
 *
 * 권한을 원본에서 물려받지 않고 여기서 정한다. 실행기(src/coreImage)의 실행 비트는 작업 트리의 파일 모드에 기대면 core.fileMode=false
 * 이거나 /mnt/c 위의 체크아웃에서 0644 로 묶인다. jlink 는 legal/ 을 0444 로 두어 다음 빌드의 Sync 가 덮지 못하게 한다(실측).
 * 런타임에서 실행되는 것은 실측으로 넷이다(bin/java · bin/keytool · lib/jspawnhelper · lib/jexec) — jspawnhelper 가 빠지면 코어가
 * claude · kyu 를 띄우지 못한다.
 */
fun isExecutableInCoreImage(relativePath: String): Boolean =
    relativePath.startsWith("bin/") ||
        relativePath.startsWith("runtime/bin/") ||
        relativePath == "runtime/lib/jspawnhelper" ||
        relativePath == "runtime/lib/jexec"

val EXECUTABLE_FILE_MODE = "755".toInt(radix = 8)

val READ_WRITE_FILE_MODE = "644".toInt(radix = 8)

/**
 * 판 라벨 파일 하나(`v0.16.0` 한 줄). 화면의 판 라벨(`BuildVersion` 의 `v<판>`)과 같은 꼴이다 — 화면이 자기 라벨과 문자열로 견준다.
 * 코어 이미지와 WSL 코어 묶음이 같은 파일을 싣는다.
 */
val writeCoreVersionLabel = tasks.register("writeCoreVersionLabel") {
    val versionLabel = "v${project.version}"
    val versionFile = layout.buildDirectory.file("core-version/VERSION")
    inputs.property("versionLabel", versionLabel)
    outputs.file(versionFile)
    doLast { versionFile.get().asFile.writeText("$versionLabel\n") }
}

val coreRuntimeClasspath = files(tasks.named("jar"), configurations.named("runtimeClasspath"))

val linkCoreRuntime = tasks.register<LinkCoreRuntime>("linkCoreRuntime") {
    coreClasspath.from(coreRuntimeClasspath)
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
    runtimeDirectory.set(layout.buildDirectory.dir("core-image-runtime"))
}

val assembleCoreImage = tasks.register<Sync>("assembleCoreImage") {
    group = "distribution"
    description = "WSL 에 설치할 코어 이미지(런타임 · jar · 실행 파일)를 build/core-image 에 모은다"
    into(layout.buildDirectory.dir("core-image/kyuchestration-core"))
    eachFile {
        val mode = if (isExecutableInCoreImage(relativePath.pathString)) EXECUTABLE_FILE_MODE else READ_WRITE_FILE_MODE
        permissions { unix(mode) }
    }
    from(linkCoreRuntime) { into("runtime") }
    from(coreRuntimeClasspath) { into("lib") }
    from("src/coreImage")
    from(writeCoreVersionLabel)
}

val packageCoreArchive = tasks.register<Tar>("packageCoreArchive") {
    group = "distribution"
    description = "코어 이미지를 릴리스 자산 kyuchestration-core_<판>_linux_<아키텍처>.tar.gz 로 묶는다"
    compression = Compression.GZIP
    destinationDirectory.set(layout.buildDirectory.dir("core-archive"))
    archiveFileName.set(coreImageArchitecture.map { architecture -> "kyuchestration-core_${project.version}_linux_$architecture.tar.gz" })
    // Gradle 9 의 묶음은 기본으로 모든 파일을 0644 로 싣는다(재현 가능한 묶음). 그대로면 실행 파일 · 런타임의 java ·
    // jspawnhelper 가 풀린 자리에서 실행되지 않는다(실측: Permission denied). 이미지에 모아 둔 권한을 그대로 싣는다.
    useFileSystemPermissions()
    into("kyuchestration-core") { from(assembleCoreImage) }
}

// ── msi 에 싣는 WSL 코어 묶음(core-ui-split-design.md 6.16 · 6.17 의 4a 정정) ───────────────────────────────────────────────
//
// 윈도우 화면이 첫 실행 때 WSL 에 밀어 넣을 것 전부. 화면은 자기 리소스에서 이 파일을 고정 이름으로 찾아 wsl.exe 로 WSL 에 풀고
// install.sh 를 돌린다(4b). 최상위 디렉토리 없이:
//
//   core.tar.gz   위 packageCoreArchive 의 산출물 그대로(이름만 고정) — install.sh 의 KYU_CORE_ARCHIVE 가 받는 바로 그 꼴
//   kyu           리눅스 amd64 엔진(-PwslCoreBundleKyu) — install.sh 의 KYU_BINARY_FILE
//   install.sh    저장소 루트의 것 그대로
//   VERSION       코어 이미지 안의 것과 같은 판 라벨 한 줄
//
// 코어를 풀어 둔 디렉토리가 아니라 tar.gz 그대로 싣는다. install.sh 가 받는 입력 형식을 두 벌(묶음 · 디렉토리) 두지 않으려고다 —
// 손으로 깔든 화면이 깔든 같은 함수(install_core)가 같은 묶음을 풀고 띄워 보고 자리를 바꾼다.
//
// 묶음 이름에 판을 넣지 않는다. 화면은 같은 빌드에서 나온 자기 리소스에서 고정 이름으로 찾고, 판은 화면 자신의 판과 같다.

/**
 * 묶음에 넣을 리눅스 amd64 kyu. 프로퍼티가 없으면 이 값을 묻는 순간(묶음 태스크가 실행 계획에 들 때) 어디서 구하는지를 말하며 멈춘다 —
 * 묶음을 부르지 않는 빌드(build · packageCoreArchive)는 묻지 않으므로 kyu 를 요구하지 않는다.
 */
val wslCoreBundleEngine: Provider<File> = providers.gradleProperty("wslCoreBundleKyu").map(::File).orElse(
    providers.provider {
        throw GradleException(
            "리눅스 amd64 kyu 가 필요합니다 — -PwslCoreBundleKyu=<절대경로> 로 주세요. 릴리스 잡은 kyu-binaries 산출물을, " +
                "로컬은 저장소 루트에서 GOOS=linux GOARCH=amd64 CGO_ENABLED=0 go build -trimpath -o <경로> ./cmd/kyu 로 만든 것을 주세요.",
        )
    },
)

/**
 * 묶음을 만들기 전에, 무거운 일(jlink)을 시작하기 전에 끊는다.
 *
 * - 호스트: 묶음의 코어 이미지는 이 호스트의 jlink 런타임을 품는다. 윈도우 · 맥에서 만들면 runtime/bin/java 가 그 OS 의 것이라 WSL 에서
 *   뜨지 않고, 그 사실은 사용자의 첫 실행에서야 드러난다. WSL 코어는 x86-64 만 낸다(install.sh 의 install_core 와 같은 선).
 * - 엔진: 리눅스 amd64 ELF 인지 머리 바이트로 본다. 윈도우의 kyu.exe · 맥 바이너리를 넣어도 묶음은 만들어지고 install.sh 의 kyu version
 *   에서야 깨진다.
 */
val checkWslCoreBundleInputs = tasks.register("checkWslCoreBundleInputs") {
    group = "verification"
    description = "WSL 코어 묶음을 만들 수 있는 호스트인지, 넣을 kyu 가 리눅스 amd64 인지 확인한다"
    val suppliedEngine = wslCoreBundleEngine
    doLast {
        val hostOperatingSystemName = System.getProperty("os.name").orEmpty()
        if (!hostOperatingSystemName.startsWith("Linux", ignoreCase = true) || hostCoreImageArchitecture() != "amd64") {
            throw GradleException(
                "WSL 코어 묶음은 리눅스 x86-64 에서만 만듭니다 — 묶음의 코어 이미지는 이 호스트의 jlink 런타임을 품고, " +
                    "윈도우 · 맥에서 만든 runtime/bin/java 는 WSL 에서 뜨지 않습니다" +
                    "(이 호스트: $hostOperatingSystemName ${System.getProperty("os.arch")}).",
            )
        }
        val engine = suppliedEngine.get()
        if (!engine.isAbsolute || !engine.isFile) {
            throw GradleException("-PwslCoreBundleKyu 는 있는 파일의 절대경로여야 합니다: $engine")
        }
        if (!isLinuxAmd64Executable(engine)) {
            throw GradleException(
                "-PwslCoreBundleKyu 가 리눅스 amd64 실행 파일(ELF x86-64)이 아닙니다: $engine — " +
                    "GOOS=linux GOARCH=amd64 로 만든 것을 주세요.",
            )
        }
    }
}

/** ELF 머리의 매직 · 64비트 등급 · 기계 번호(EM_X86_64, 리틀 엔디언 두 바이트)를 본다. */
fun isLinuxAmd64Executable(candidate: File): Boolean {
    val header = candidate.inputStream().use { it.readNBytes(20) }
    if (header.size < 20) return false
    val elfMagic = header[0] == 0x7f.toByte() && header[1] == 'E'.code.toByte() &&
        header[2] == 'L'.code.toByte() && header[3] == 'F'.code.toByte()
    val sixtyFourBitClass = header[4] == 2.toByte()
    val machine = (header[18].toInt() and 0xff) or ((header[19].toInt() and 0xff) shl 8)
    val elfMachineX86_64 = 62
    return elfMagic && sixtyFourBitClass && machine == elfMachineX86_64
}

// 확인이 jlink 보다 먼저 돈다. 의존이 아니라 순서만 건다 — packageCoreArchive 만 부르는 흐름(릴리스 자산 · CI)에 kyu 를 요구하지 않는다.
linkCoreRuntime.configure { mustRunAfter(checkWslCoreBundleInputs) }

tasks.register<Tar>("packageWslCoreBundle") {
    group = "distribution"
    description = "msi 에 실을 WSL 코어 묶음(코어 이미지 · 리눅스 kyu · install.sh · VERSION)을 build/wsl-core-bundle 에 만든다"
    dependsOn(checkWslCoreBundleInputs)
    compression = Compression.GZIP
    destinationDirectory.set(layout.buildDirectory.dir("wsl-core-bundle"))
    archiveFileName.set("kyuchestration-wsl-core.tar.gz")
    // 권한은 코어 이미지와 같은 규칙이다 — 실행되는 것(화면이 부르는 install.sh, install.sh 가 version 으로 불러 보는 kyu)은 0755, 그 밖
    // 0644. 원본에서 물려받지 않는다: CI 아티팩트로 건너온 kyu 는 실행 비트를 잃는다. 이미지처럼 경로로 가르는 eachFile 을 쓰지 않고
    // 갈래마다 정한다 — 부모의 eachFile 은 갈래의 rename 보다 먼저 돌아 kyu 를 원래 이름(kyu_linux_amd64)으로 본다(실측: 0644 로 실렸다).
    from(packageCoreArchive) {
        rename { "core.tar.gz" }
        filePermissions { unix(READ_WRITE_FILE_MODE) }
    }
    from(wslCoreBundleEngine) {
        rename { "kyu" }
        filePermissions { unix(EXECUTABLE_FILE_MODE) }
    }
    from(layout.projectDirectory.file("../../install.sh")) { filePermissions { unix(EXECUTABLE_FILE_MODE) } }
    from(writeCoreVersionLabel) { filePermissions { unix(READ_WRITE_FILE_MODE) } }
}
