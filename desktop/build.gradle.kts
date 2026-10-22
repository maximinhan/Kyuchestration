// 앱은 네 모듈이다(core-ui-split-design.md 6.15 — 진단 기록 모듈은 2 단계에서 더했다).
//
//   protocol     화면과 코어 사이를 건너는 값 — 틀 · 명령 · 변화 · 상태 모델과 경계 인터페이스.
//   diagnostics  화면과 코어가 각자의 파일에 적는 진단 기록. 남길 수 있는 사건의 갈래는 하나로 닫혀 있다.
//   core         세션을 소유하는 헤드리스 코어 — 상태 홀더 · claude 프로세스 · 앱 소켓 · kyu 호출. Compose 를 모른다.
//   ui           Compose 화면과 거울. 설치 패키지를 내는 모듈이다.
//
// 모듈이 함께 쓰는 판단(버전)만 여기 둔다. 플러그인은 판만 정하고 적용은 각 모듈이 한다 — 한 빌드에서 같은 플러그인을 판이
// 다르게 싣지 않으려면 판을 정하는 자리가 하나여야 한다.
plugins {
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinComposeCompiler) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.kotlinSerialization) apply false
}

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

// 화면과 코어는 같은 태그에서 같은 판으로 나간다(설계 6.14). 판을 모듈마다 따로 정하는 자리가 생기면 둘이 갈릴 수 있다.
subprojects {
    group = "com.kyuchestration.desktop"
    version = desktopVersion

    plugins.withId("org.jetbrains.kotlin.jvm") {
        // 툴체인을 고정해 두면 개발자 머신의 기본 JDK 가 무엇이든 같은 바이트코드가 나온다.
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
            jvmToolchain(21)
        }
        dependencies {
            // kotlin("test") 는 적용된 Kotlin 플러그인의 버전을 그대로 따라간다. 버전 카탈로그에 한 줄 더 적으면 Kotlin 을
            // 올릴 때 두 곳을 맞춰야 하므로 일부러 버전을 적지 않는다.
            "testImplementation"(kotlin("test"))
        }
    }

    tasks.withType<Jar>().configureEach {
        manifest {
            // 화면과 코어가 각자 자기 jar 에서 판을 읽는다(BuildVersion). 이 한 줄이 빠진 jar 의 프로그램은 정식으로
            // 패키징해도 영영 "개발 빌드" 로 뜨고, 판이 갈렸을 때 어느 쪽이 낡았는지 보이지 않는다.
            attributes("Implementation-Version" to project.version)
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()

        // 통합 검증이 읽는 환경 변수를 시험 JVM 까지 실어 보낸다. 모듈마다 적어 두면 한 곳에서 빠지는 날 그 모듈의 실 claude
        // 시험이 조용히 건너뛴다 — 그래서 모든 모듈의 시험이 이 한 자리를 지난다.
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
}
