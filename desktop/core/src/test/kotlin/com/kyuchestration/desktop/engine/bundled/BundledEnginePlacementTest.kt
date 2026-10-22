package com.kyuchestration.desktop.engine.bundled

import com.kyuchestration.desktop.engine.EngineInstallationFailure
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isExecutable
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.setPosixFilePermissions
import kotlin.io.path.writeText
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS

/**
 * 진짜 파일로 시험한다. 여기서 지키려는 것이 "실행할 수 없는 원본을 실행할 수 있게 만든다" 라,
 * 권한 비트와 파일의 실제 내용을 흉내로 바꾸면 시험할 것이 남지 않는다.
 *
 * 엔진 자리에는 셸 스크립트를 세운다. 이 함수가 원본을 실행하지는 않고 복사본에게 한 번
 * `version` 을 물을 뿐이라, 그 물음에 답하는 실행 파일이면 된다. 윈도우에는 /bin/sh 도 POSIX
 * 권한도 없으므로 그쪽에서는 건너뛴다 — 동봉 자체가 맥과 리눅스의 일이다.
 */
@DisabledOnOs(OS.WINDOWS)
class BundledEnginePlacementTest {

    private val temporaryDirectory = createTempDirectory("bundled-engine-test")
    private val copyPath = temporaryDirectory.resolve("engine/bundled/kyu")

    @AfterTest
    fun 임시_디렉토리를_지운다() {
        temporaryDirectory.toFile().deleteRecursively()
    }

    @Test
    fun `동봉된 엔진을 실행할 수 있는 복사본으로 놓는다`() {
        val bundledEnginePath = engineThatAnswersVersion("v0.9.0")

        placeBundledEngineWhereItCanRun(bundledEnginePath, copyPath)

        assertTrue(copyPath.isExecutable(), "복사본을 실행할 수 없습니다: $copyPath")
        assertEquals(bundledEnginePath.readText(), copyPath.readText())
    }

    @Test
    fun `읽기만 되는 원본에서도 실행할 수 있는 복사본이 나온다`() {
        // 설치된 앱의 리소스가 이 모양이다 — jpackage 가 실행 권한을 떼어 낸다.
        val bundledEnginePath = engineThatAnswersVersion("v0.9.0")
        bundledEnginePath.setPosixFilePermissions(PosixFilePermissions.fromString("r--r--r--"))

        placeBundledEngineWhereItCanRun(bundledEnginePath, copyPath)

        assertTrue(copyPath.isExecutable(), "복사본을 실행할 수 없습니다: $copyPath")
    }

    @Test
    fun `같은 엔진이 이미 놓여 있으면 다시 복사하지 않는다`() {
        val bundledEnginePath = engineThatAnswersVersion("v0.9.0")
        placeBundledEngineWhereItCanRun(bundledEnginePath, copyPath)
        val firstPlacementTime = copyPath.getLastModifiedTime()

        placeBundledEngineWhereItCanRun(bundledEnginePath, copyPath)

        // 앱을 띄울 때마다 12MB 를 다시 쓰고 kyu 를 한 번 더 띄울 이유가 없다.
        assertEquals(firstPlacementTime, copyPath.getLastModifiedTime())
    }

    @Test
    fun `앱이 새 판으로 바뀌면 복사본도 새 엔진이 된다`() {
        placeBundledEngineWhereItCanRun(engineThatAnswersVersion("v0.9.0"), copyPath)

        placeBundledEngineWhereItCanRun(engineThatAnswersVersion("v0.10.0"), copyPath)

        // 판이 갈릴 때 갱신되지 않으면, 새 앱이 옛 엔진을 부르면서 그 사실을 아무 데도 알리지 않는다.
        assertContains(copyPath.readText(), "v0.10.0")
    }

    @Test
    fun `권한을 잃은 복사본은 다시 놓는다`() {
        val bundledEnginePath = engineThatAnswersVersion("v0.9.0")
        placeBundledEngineWhereItCanRun(bundledEnginePath, copyPath)
        copyPath.setPosixFilePermissions(PosixFilePermissions.fromString("rw-r--r--"))

        placeBundledEngineWhereItCanRun(bundledEnginePath, copyPath)

        // 바이트가 같아도 실행할 수 없으면 탐색이 그 자리를 건너뛰어, 엔진을 들고 있는 앱이
        // "엔진이 없다" 는 화면을 띄운다.
        assertTrue(copyPath.isExecutable(), "복사본을 실행할 수 없습니다: $copyPath")
    }

    @Test
    fun `동봉이 없는 실행에서는 지난 판이 남긴 복사본을 치운다`() {
        placeBundledEngineWhereItCanRun(engineThatAnswersVersion("v0.9.0"), copyPath)

        placeBundledEngineWhereItCanRun(bundledEnginePath = null, copyPath = copyPath)

        // 그대로 두면 탐색에서 PATH 보다 앞에 선다 — 앱을 지우고 소스로 돌아온 사람이 자기가
        // 방금 빌드한 엔진 대신 옛 앱이 남긴 판을 부르게 된다.
        assertFalse(copyPath.exists(), "지난 판의 복사본이 남았습니다: $copyPath")
    }

    @Test
    fun `동봉도 복사본도 없으면 아무 일도 일어나지 않는다`() {
        placeBundledEngineWhereItCanRun(bundledEnginePath = null, copyPath = copyPath)

        assertFalse(copyPath.exists())
    }

    @Test
    fun `돌지 않는 엔진에게는 자리를 내주지 않는다`() {
        val bundledEnginePath = temporaryDirectory.resolve("resources/kyu")
        bundledEnginePath.parent.createDirectories()
        // 이 머신의 것이 아닌 바이너리가 패키지에 들어간 경우를 여기서 흉내 낸다 — 실행 가능한
        // 파일이긴 하지만 version 에 답하지 못한다.
        bundledEnginePath.writeText("#!/bin/sh\nexit 42\n")
        bundledEnginePath.setPosixFilePermissions(PosixFilePermissions.fromString("rwxr-xr-x"))

        assertFailsWith<EngineInstallationFailure.BundledEngineDidNotRun> {
            placeBundledEngineWhereItCanRun(bundledEnginePath, copyPath)
        }

        assertFalse(copyPath.exists(), "돌지 않는 엔진이 kyu 라는 이름을 차지했습니다")
        assertEquals(emptyList(), copyPath.parent.listDirectoryEntries(), "복사하다 만 것이 남았습니다")
    }

    /**
     * `version` 을 물으면 그 판을 답하는 실행 파일. 판마다 내용이 달라 바이트로도 갈린다.
     */
    private fun engineThatAnswersVersion(version: String): Path {
        val enginePath = temporaryDirectory.resolve("resources-$version/kyu")
        enginePath.parent.createDirectories()
        enginePath.writeText("#!/bin/sh\necho \"kyu $version\"\n")
        enginePath.setPosixFilePermissions(PosixFilePermissions.fromString("rwxr-xr-x"))
        return enginePath
    }
}
