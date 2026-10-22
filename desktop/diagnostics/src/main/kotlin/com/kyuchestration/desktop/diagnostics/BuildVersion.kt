package com.kyuchestration.desktop.diagnostics

import java.nio.file.Path
import java.util.jar.JarFile
import kotlin.io.path.isRegularFile

/**
 * 프로그램이 자기 판을 알아내는 유일한 경로. 화면과 코어가 각자 자기 jar 로 묻는다 — 둘은 따로 깔리는 날이 오고(설계 6.14),
 * 그날 어느 쪽이 낡았는지 사람에게 보이려면 둘의 판이 각자의 것이어야 한다.
 */
object BuildVersion {

    private const val DEVELOPMENT_BUILD_LABEL = "개발 빌드"

    /**
     * [anchor] 가 실린 jar 의 판 라벨("v0.15.0"). 매니페스트가 없으면 "개발 빌드".
     *
     * 그 클래스가 실린 jar 의 매니페스트를 읽는다. java.lang.Package 로 읽지 않는 것은 패키지가 모듈 사이에 걸치기 때문이다
     * (protocol · core · ui 모두 com.kyuchestration.desktop 아래에 있다). 패키지의 판은 그 패키지를 먼저 정의한 jar 의 것이라,
     * 판을 적지 않은 jar 가 먼저 오면 정식 릴리스가 "개발 빌드" 로 뜬다.
     *
     * `./gradlew run` 과 IDE 실행은 클래스 디렉토리에서 돌아 매니페스트가 없다. 그때 빈 문자열을 그대로 화면에 올리면 버전
     * 자리가 사라져 어떤 빌드를 보고 있는지 알 수 없으므로, 개발 빌드임을 드러내는 쪽을 택한다.
     * (kyu CLI 가 Go 빌드 정보의 "(devel)" 을 다루는 규칙과 같다)
     */
    fun labelOf(anchor: Class<*>): String =
        formatVersionLabel(implementationVersionOf(Path.of(anchor.protectionDomain.codeSource.location.toURI())))

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
