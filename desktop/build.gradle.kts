// 앱은 세 모듈이다(core-ui-split-design.md 6.15).
//
//   protocol  화면과 코어 사이를 건너는 값 — 틀 · 명령 · 변화 · 상태 모델. 직렬화만 안다.
//   core      세션을 소유하는 헤드리스 코어 — 상태 홀더 · claude 프로세스 · 앱 소켓 · kyu 호출. Compose 를 모른다.
//   ui        Compose 화면과 거울. 설치 패키지를 내는 모듈이다.
//
// 세 모듈이 함께 쓰는 판단(버전)만 여기 둔다. 플러그인은 판만 정하고 적용은 각 모듈이 한다 — 한 빌드에서 같은 플러그인을 판이
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
}
