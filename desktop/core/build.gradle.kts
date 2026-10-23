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
tasks.named<Test>("test") {
    val hostIsWindows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
    onlyIf("코어는 윈도우에서 돌지 않는다(설계 6.16) — 윈도우에서는 코어 시험을 건너뛴다") { !hostIsWindows }
}

// ── WSL 에 설치하는 코어 이미지(core-ui-split-design.md 6.16 의 3 단계 정정) ─────────────────────────────────────────────
//
// 윈도우 사용자의 코어는 WSL 안에 산다. 설치 패키지(msi)에는 화면만 들고, 코어는 이 이미지를 install.sh --with-core 가 받아
// ~/.local/share/kyuchestration/core 에 풀고 ~/.local/bin/kyuchestration-core 로 링크한다.
//
//   kyuchestration-core/
//   ├─ bin/kyuchestration-core   링크를 풀어 이미지 자리를 찾는 sh 실행 파일(src/coreImage)
//   ├─ runtime/                  jlink 런타임 — WSL 에 자바를 요구하지 않는다
//   └─ lib/*.jar                 코어와 그 의존
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

    @get:Internal
    abstract val jdkHome: DirectoryProperty

    @get:OutputDirectory
    abstract val runtimeDirectory: DirectoryProperty

    @TaskAction
    fun link() {
        val jdk = jdkHome.get().asFile
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

val coreImageArchitecture: Provider<String> = providers.provider {
    val hostOperatingSystemName = System.getProperty("os.name").orEmpty()
    if (!hostOperatingSystemName.startsWith("Linux", ignoreCase = true)) {
        throw GradleException("코어 이미지는 리눅스에서만 만듭니다 — jlink 는 자기가 도는 OS 의 런타임을 만듭니다($hostOperatingSystemName).")
    }
    when (val hostArchitecture = System.getProperty("os.arch").orEmpty()) {
        "amd64", "x86_64" -> "amd64"
        "aarch64", "arm64" -> "arm64"
        else -> throw GradleException("이 아키텍처($hostArchitecture)용 코어 이미지는 만들지 않습니다.")
    }
}

val OWNER_WRITE_BIT = "200".toInt(radix = 8)

val coreRuntimeClasspath = files(tasks.named("jar"), configurations.named("runtimeClasspath"))

val linkCoreRuntime = tasks.register<LinkCoreRuntime>("linkCoreRuntime") {
    coreClasspath.from(coreRuntimeClasspath)
    jdkHome.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }.map { it.metadata.installationPath })
    runtimeDirectory.set(layout.buildDirectory.dir("core-image-runtime"))
}

val assembleCoreImage = tasks.register<Sync>("assembleCoreImage") {
    group = "distribution"
    description = "WSL 에 설치할 코어 이미지(런타임 · jar · 실행 파일)를 build/core-image 에 모은다"
    into(layout.buildDirectory.dir("core-image/kyuchestration-core"))
    // jlink 는 legal/ 의 파일을 읽기 전용(0444)으로 둔다. 그대로 옮기면 다음 빌드의 Sync 가 그 파일을 덮지 못해 멈춘다
    // (실측: Permission denied). 주인의 쓰기 권한만 더하고 나머지(실행 비트)는 그대로 둔다.
    // permissions { user { write = true } } 는 기본값(0644)에서 시작해 실행 비트를 지운다(실측) — 원본의 모드에 쓰기 비트를 더한다.
    eachFile {
        val sourceMode = permissions.toUnixNumeric()
        permissions { unix(sourceMode or OWNER_WRITE_BIT) }
    }
    from(linkCoreRuntime) { into("runtime") }
    from(coreRuntimeClasspath) { into("lib") }
    from("src/coreImage")
}

tasks.register<Tar>("packageCoreArchive") {
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
