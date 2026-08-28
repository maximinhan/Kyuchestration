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
        // JediTerm(org.jetbrains.jediterm) 은 Maven Central 에 올라오지 않는다. 2026-08 기준으로
        // 그 그룹은 Central 색인에 아예 없고(검색 결과 0건), IntelliJ 가 자기 의존성을 받는 이 저장소에만
        // jediterm-core·jediterm-ui 가 있다. pty4j 는 Central 에도 있지만 같은 곳에서 함께 받는다.
        maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies")
        google()
    }
}
