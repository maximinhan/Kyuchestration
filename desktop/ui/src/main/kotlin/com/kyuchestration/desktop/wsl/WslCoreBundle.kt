package com.kyuchestration.desktop.wsl

import java.nio.file.Path
import kotlin.io.path.isRegularFile

/**
 * msi 에 실린 WSL 코어 묶음(core-ui-split-design.md 6.16 의 4a 정정) — 코어 이미지 · 리눅스 kyu · `install.sh` · `VERSION`.
 *
 * @param versionLabel 묶음의 판. 묶음 안의 `VERSION` 을 읽지 않고 화면 자신의 판을 쓴다 — 같은 빌드에서 나와 한 msi 에 실린다.
 */
data class WslCoreBundle(val archive: Path, val versionLabel: String)

sealed interface WslCoreBundleLookup {

    data class Found(val bundle: WslCoreBundle) : WslCoreBundleLookup

    data class Absent(val absence: WslCoreBundleAbsence) : WslCoreBundleLookup
}

sealed interface WslCoreBundleAbsence {

    /** 진단 기록에 남길 까닭. */
    val reason: String

    /** Compose 가 앱 리소스 자리를 알리지 않았다 — 소스에서 띄운 화면이다. */
    data object ResourcesDirectoryUnknown : WslCoreBundleAbsence {
        override val reason: String
            get() = "앱 리소스 자리($APP_RESOURCES_DIRECTORY_PROPERTY)를 모른다 — 소스에서 띄운 화면"
    }

    data class ArchiveMissing(val expectedArchive: String) : WslCoreBundleAbsence {
        override val reason: String
            get() = "묶음 파일이 없다: $expectedArchive"
    }

    /**
     * 화면이 개발 빌드라 견줄 판이 없다. 묶음과 화면은 같은 빌드에서 나와 생기지 않는 짝이지만, 생기면 깔린 코어의 `VERSION` 과 무엇을
     * 견줄지 정할 수 없다 — 묶음이 없는 것과 같이 다룬다.
     */
    data object ScreenIsDevelopmentBuild : WslCoreBundleAbsence {
        override val reason: String
            get() = "화면이 개발 빌드라 묶음의 판을 정할 수 없다"
    }
}

/**
 * @param resourcesDirectory 시스템 프로퍼티 [APP_RESOURCES_DIRECTORY_PROPERTY]. jpackage 의 실행기가 `$APPDIR\resources` 로 채운다.
 * @param screenReleaseLabel 화면의 릴리스 판. 개발 빌드면 null.
 */
fun lookUpWslCoreBundle(resourcesDirectory: String?, screenReleaseLabel: String?): WslCoreBundleLookup {
    if (resourcesDirectory.isNullOrBlank()) {
        return WslCoreBundleLookup.Absent(WslCoreBundleAbsence.ResourcesDirectoryUnknown)
    }
    val archive = Path.of(resourcesDirectory).resolve(BUNDLE_DIRECTORY).resolve(BUNDLE_FILE_NAME)
    if (!archive.isRegularFile()) {
        return WslCoreBundleLookup.Absent(WslCoreBundleAbsence.ArchiveMissing(archive.toString()))
    }
    if (screenReleaseLabel == null) {
        return WslCoreBundleLookup.Absent(WslCoreBundleAbsence.ScreenIsDevelopmentBuild)
    }
    return WslCoreBundleLookup.Found(WslCoreBundle(archive, screenReleaseLabel))
}

const val APP_RESOURCES_DIRECTORY_PROPERTY = "compose.application.resources.dir"

private const val BUNDLE_DIRECTORY = "wsl-core"

private const val BUNDLE_FILE_NAME = "kyuchestration-wsl-core.tar.gz"
