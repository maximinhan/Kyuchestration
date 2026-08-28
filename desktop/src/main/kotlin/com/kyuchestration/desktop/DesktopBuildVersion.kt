package com.kyuchestration.desktop

/**
 * 앱이 자기 버전을 알아내는 유일한 경로.
 */
object DesktopBuildVersion {

    private const val DEVELOPMENT_BUILD_LABEL = "개발 빌드"

    // Implementation-Version 은 jar 매니페스트에만 박힌다. `./gradlew run` 과 IDE 실행은 클래스
    // 디렉터리에서 돌기 때문에 여기가 null 이다. 그때 빈 문자열을 그대로 화면에 올리면 버전 자리가
    // 사라져 어떤 빌드를 보고 있는지 알 수 없으므로, 개발 빌드임을 드러내는 쪽을 택한다.
    // (kyu CLI 가 Go 빌드 정보의 "(devel)" 을 다루는 규칙과 같다)
    val label: String = formatVersionLabel(javaClass.`package`?.implementationVersion)

    internal fun formatVersionLabel(implementationVersion: String?): String =
        if (implementationVersion.isNullOrBlank()) DEVELOPMENT_BUILD_LABEL else "v$implementationVersion"
}
