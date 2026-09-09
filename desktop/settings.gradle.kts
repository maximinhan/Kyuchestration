// desktop/ 은 루트의 Go 모듈과 완전히 분리된 독립 Gradle 빌드다.
// settings 파일을 저장소 루트에 두면 Gradle 이 Go 소스 트리까지 프로젝트 후보로 훑고,
// CI 에서도 Go 빌드와 데스크톱 빌드를 서로 다른 워크플로로 떼어 낼 수 없다.
rootProject.name = "kyuchestration-desktop"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        // Compose Multiplatform 의 일부 아티팩트(스키코 등)는 Maven Central 에만 있는 것이 아니라
        // JetBrains 전용 저장소에서 먼저 공개된다. 둘 다 열어 둔다.
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
        google()
    }
}
