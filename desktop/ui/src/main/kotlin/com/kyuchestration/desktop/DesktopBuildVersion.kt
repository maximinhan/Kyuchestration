package com.kyuchestration.desktop

import java.nio.file.Path
import java.util.jar.JarFile
import kotlin.io.path.isRegularFile

/**
 * 앱이 자기 버전을 알아내는 유일한 경로.
 */
object DesktopBuildVersion {

    private const val DEVELOPMENT_BUILD_LABEL = "개발 빌드"

    // 이 클래스가 실린 jar 의 매니페스트를 읽는다. java.lang.Package 로 읽지 않는 것은 패키지가 모듈 사이에 걸치기 때문이다
    // (protocol · core 에도 com.kyuchestration.desktop 아래 패키지가 있다). 패키지의 판은 그 패키지를 먼저 정의한 jar 의 것이라,
    // 판을 적지 않은 jar 가 먼저 오면 정식 릴리스가 "개발 빌드" 로 뜬다.
    //
    // `./gradlew run` 과 IDE 실행은 클래스 디렉토리에서 돌아 매니페스트가 없다. 그때 빈 문자열을 그대로 화면에 올리면 버전
    // 자리가 사라져 어떤 빌드를 보고 있는지 알 수 없으므로, 개발 빌드임을 드러내는 쪽을 택한다.
    // (kyu CLI 가 Go 빌드 정보의 "(devel)" 을 다루는 규칙과 같다)
    val label: String = formatVersionLabel(
        implementationVersionOf(Path.of(DesktopBuildVersion::class.java.protectionDomain.codeSource.location.toURI())),
    )

    internal fun formatVersionLabel(implementationVersion: String?): String =
        if (implementationVersion.isNullOrBlank()) DEVELOPMENT_BUILD_LABEL else "v$implementationVersion"

    /** [codeSourceLocation] 이 jar 면 그 매니페스트의 Implementation-Version. 클래스 디렉토리이거나 적혀 있지 않으면 null. */
    internal fun implementationVersionOf(codeSourceLocation: Path): String? {
        if (!codeSourceLocation.isRegularFile()) {
            return null
        }
        return JarFile(codeSourceLocation.toFile()).use { jar ->
            jar.manifest?.mainAttributes?.getValue("Implementation-Version")
        }
    }
}
