package com.kyuchestration.desktop.wsl

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeBytes
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** msi 에 실린 WSL 코어 묶음을 화면이 찾는다(core-ui-split-design.md 6.16 의 4a 정정 · 6.17 의 4b 정정). */
class WslCoreBundleLookupTest {

    private val resourcesDirectory: Path = createTempDirectory("kyu-app-resources")

    @AfterTest
    fun cleanUp() {
        resourcesDirectory.toFile().deleteRecursively()
    }

    private fun placeBundle(): Path =
        resourcesDirectory.resolve("wsl-core").createDirectories().resolve("kyuchestration-wsl-core.tar.gz").also { it.writeBytes(byteArrayOf(1)) }

    @Test
    fun `앱 리소스 자리의 wsl-core 아래 묶음을 화면의 판으로 쓴다`() {
        val archive = placeBundle()

        assertEquals(
            WslCoreBundleLookup.Found(WslCoreBundle(archive, versionLabel = "v0.16.0")),
            lookUpWslCoreBundle(resourcesDirectory.toString(), screenReleaseLabel = "v0.16.0"),
        )
    }

    /** 소스에서 띄운 화면(`./gradlew :app:run`)은 Compose 가 리소스 자리를 알리지 않는다 — 지금의 설치 명령 안내로 떨어진다. */
    @Test
    fun `리소스 자리를 모르면 묶음이 없다`() {
        assertEquals(
            WslCoreBundleLookup.Absent(WslCoreBundleAbsence.ResourcesDirectoryUnknown),
            lookUpWslCoreBundle(resourcesDirectory = null, screenReleaseLabel = "v0.16.0"),
        )
    }

    @Test
    fun `리소스 자리에 묶음 파일이 없으면 묶음이 없다`() {
        val expectedArchive = resourcesDirectory.resolve("wsl-core").resolve("kyuchestration-wsl-core.tar.gz")

        assertEquals(
            WslCoreBundleLookup.Absent(WslCoreBundleAbsence.ArchiveMissing(expectedArchive.toString())),
            lookUpWslCoreBundle(resourcesDirectory.toString(), screenReleaseLabel = "v0.16.0"),
        )
    }

    /**
     * 묶음과 화면은 같은 빌드에서 나오므로 생기지 않는 짝이다. 생기면 깔린 코어의 VERSION 과 견줄 판이 없어, 쓰면 뜰 때마다 다시 깔거나
     * 판을 모른 채 두게 된다 — 묶음이 없는 것과 같이 다룬다.
     */
    @Test
    fun `화면이 개발 빌드면 묶음이 있어도 쓰지 않는다`() {
        placeBundle()

        assertEquals(
            WslCoreBundleLookup.Absent(WslCoreBundleAbsence.ScreenIsDevelopmentBuild),
            lookUpWslCoreBundle(resourcesDirectory.toString(), screenReleaseLabel = null),
        )
    }
}
