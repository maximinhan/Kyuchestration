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
    // 이 둘이 있어야 kyu attach 가 요구하는 TTY 가 앱 안에 생긴다.
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
            // Msi 는 뺀다. 이 앱은 화면일 뿐이고 실제 일은 엔진인 kyu 가 tmux 위에서 한다 —
            // 윈도우 네이티브에는 그 tmux 가 없으므로(설계 문서 10 절 로드맵 v4) 설치는 되고
            // 아무것도 되지 않는 패키지가 나온다. 윈도우 사용자는 WSL 안에서 리눅스 패키지를 쓴다.
            targetFormats(TargetFormat.Dmg, TargetFormat.Deb)

            // 설치 패키지 이름은 파일 이름과 설치 경로(/opt/kyuchestration · /Applications)에
            // 그대로 쓰인다. 한글이 섞이면 그 경로들이 깨지므로, 창 제목("뀨케스트레이션")과
            // 분리해 ASCII 로 둔다.
            packageName = "Kyuchestration"
            packageVersion = project.version.toString()
            description = "뀨케스트레이션 — 멀티레포 워크디렉토리 오케스트레이터"
            vendor = "maximinhan"

            macOS {
                bundleID = "com.kyuchestration.desktop"
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

    // 임시 진단(PR 28) — PTY 시험이 맥에서만 깨지는데, 기본 콘솔 출력은 예외의 위치만 찍고
    // "expected: <0> but was: <?>" 를 감춘다. 원인을 확정하면 되돌린다.
    testLogging {
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
