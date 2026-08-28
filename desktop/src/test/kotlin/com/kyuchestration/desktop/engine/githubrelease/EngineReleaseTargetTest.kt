package com.kyuchestration.desktop.engine.githubrelease

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * JVM 이 답하는 os.name · os.arch 는 플랫폼마다 철자가 다르다. 릴리스 자산 이름은 Go 의
 * GOOS/GOARCH 철자를 쓴다. 그 사이를 옮기는 표가 여기 전부다.
 */
class EngineReleaseTargetTest {

    @Test
    fun `JVM 이 답하는 철자를 릴리스 자산 이름으로 옮긴다`() {
        val expectedAssetNames = mapOf(
            ("Linux" to "amd64") to "kyu_linux_amd64",
            ("Linux" to "x86_64") to "kyu_linux_amd64",
            ("Linux" to "aarch64") to "kyu_linux_arm64",
            ("Linux" to "arm64") to "kyu_linux_arm64",
            ("Mac OS X" to "x86_64") to "kyu_darwin_amd64",
            ("Mac OS X" to "aarch64") to "kyu_darwin_arm64",
            ("Darwin" to "aarch64") to "kyu_darwin_arm64",
        )

        expectedAssetNames.forEach { (platform, assetName) ->
            val (osName, osArchitecture) = platform

            assertEquals(
                EngineReleaseTarget.ReleaseAsset(assetName),
                engineReleaseTargetFor(osName, osArchitecture),
                "$osName / $osArchitecture",
            )
        }
    }

    @Test
    fun `윈도우 네이티브는 자산이 아니라 WSL 로 가는 갈래다`() {
        // 엔진은 tmux 위에서만 세션을 돌린다. 바이너리를 받아 놓아도 그 자리에서는 돌지 않으므로
        // 받기 전에 갈라야 한다 — 받고 나서 실패하면 사용자는 다운로드를 의심하게 된다.
        assertEquals(
            EngineReleaseTarget.WindowsNeedsWsl,
            engineReleaseTargetFor("Windows 11", "amd64"),
        )
    }

    @Test
    fun `릴리스가 내지 않는 조합은 그 사실을 그대로 들고 있는다`() {
        assertEquals(
            EngineReleaseTarget.PlatformNotReleased("SunOS", "sparcv9"),
            engineReleaseTargetFor("SunOS", "sparcv9"),
        )
        assertEquals(
            EngineReleaseTarget.PlatformNotReleased("Linux", "riscv64"),
            engineReleaseTargetFor("Linux", "riscv64"),
        )
    }
}
