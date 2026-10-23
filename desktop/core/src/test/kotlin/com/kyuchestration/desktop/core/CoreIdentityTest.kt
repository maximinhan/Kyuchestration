package com.kyuchestration.desktop.core

import kotlin.test.Test
import kotlin.test.assertEquals

/** 코어가 인사 답에 싣는 자기 소개 중 이 머신에서 읽는 것 — OS 와 WSL 배포판(core-ui-split-design.md 6.4 · 6.9). */
class CoreIdentityTest {

    /** WSL 은 배포판 안의 모든 프로세스에 `WSL_DISTRO_NAME` 을 준다. `wsl.exe -e` 로 뜬 프로세스도 받는다. */
    @Test
    fun `WSL 안의 코어는 리눅스이고 자기 배포판 이름을 말한다`() {
        val identity = coreIdentityOf(
            versionLabel = "v0.15.0",
            diagnosticLogPath = "/home/yawn/.local/state/kyuchestration/core.log",
            osName = "Linux",
            lookUpEnvironmentVariable = { if (it == "WSL_DISTRO_NAME") "Ubuntu-24.04" else null },
        )

        assertEquals(
            CoreIdentity(
                versionLabel = "v0.15.0",
                diagnosticLogPath = "/home/yawn/.local/state/kyuchestration/core.log",
                operatingSystem = "linux",
                wslDistributionName = "Ubuntu-24.04",
            ),
            identity,
        )
    }

    @Test
    fun `WSL 밖의 코어는 배포판이 없다`() {
        val identity = coreIdentityOf(
            versionLabel = "v0.15.0",
            diagnosticLogPath = "/Users/yawn/Library/Logs/Kyuchestration/core.log",
            osName = "Mac OS X",
            lookUpEnvironmentVariable = { null },
        )

        assertEquals("macos", identity.operatingSystem)
        assertEquals(null, identity.wslDistributionName)
    }
}
