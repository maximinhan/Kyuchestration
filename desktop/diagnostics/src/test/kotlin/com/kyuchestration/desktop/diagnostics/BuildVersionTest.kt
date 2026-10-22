package com.kyuchestration.desktop.diagnostics

import java.nio.file.Path
import java.util.jar.Attributes
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlin.io.path.createTempDirectory
import kotlin.io.path.outputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BuildVersionTest {

    private val temporaryDirectory: Path = createTempDirectory("desktop-build-version")

    @AfterTest
    fun removeTemporaryDirectory() {
        temporaryDirectory.toFile().deleteRecursively()
    }

    @Test
    fun `패키징된 빌드는 매니페스트 버전에 v 를 붙여 보여준다`() {
        assertEquals("v0.1.0", BuildVersion.formatVersionLabel("0.1.0"))
    }

    @Test
    fun `매니페스트가 없는 실행은 개발 빌드로 표시한다`() {
        assertEquals("개발 빌드", BuildVersion.formatVersionLabel(null))
    }

    @Test
    fun `매니페스트 값이 공백뿐이어도 개발 빌드로 떨어진다`() {
        assertEquals("개발 빌드", BuildVersion.formatVersionLabel("   "))
    }

    /**
     * 판은 이 클래스가 실린 jar 의 매니페스트에서 읽는다. 패키지(java.lang.Package)로 읽으면 같은 패키지를 다른 모듈의 jar 가
     * 먼저 정의하는 날 그 jar 의 매니페스트가 답한다 — 판이 없는 jar 라면 정식 릴리스가 "개발 빌드" 로 뜬다.
     */
    @Test
    fun `클래스가 실린 jar 의 매니페스트에서 판을 읽는다`() {
        val screenJar = jarWithImplementationVersion("0.15.0")

        assertEquals("0.15.0", BuildVersion.implementationVersionOf(screenJar))
    }

    @Test
    fun `판을 적지 않은 jar 면 판이 없다`() {
        assertNull(BuildVersion.implementationVersionOf(jarWithImplementationVersion(null)))
    }

    @Test
    fun `클래스 디렉토리에서 돌면 판이 없다`() {
        // ./gradlew run 과 IDE 실행이 이 모양이다.
        assertNull(BuildVersion.implementationVersionOf(temporaryDirectory))
    }

    private fun jarWithImplementationVersion(implementationVersion: String?): Path {
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            implementationVersion?.let { mainAttributes[Attributes.Name.IMPLEMENTATION_VERSION] = it }
        }
        val jar = temporaryDirectory.resolve("ui-${implementationVersion ?: "none"}.jar")
        JarOutputStream(jar.outputStream(), manifest).close()
        return jar
    }
}
