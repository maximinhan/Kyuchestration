package com.kyuchestration.desktop

import com.kyuchestration.desktop.mirror.CoreConnection
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** WSL 에 코어가 없거나 낡았을 때 화면이 보이는 설치 안내(core-ui-split-design.md 6.17). */
class CoreInstallGuidanceTest {

    /** 화면이 복사하게 하는 한 줄과 저장소의 설치 스크립트가 갈라지면 안내를 따른 사람은 쓰는 법 오류만 본다. 시험은 desktop/ui 에서 돈다. */
    @Test
    fun `화면이 보이는 설치 명령의 스크립트와 옵션을 저장소의 install sh 가 받는다`() {
        val installScript = Path.of("../../install.sh").readText()

        assertTrue(WSL_CORE_INSTALL_COMMAND.contains("/main/install.sh"), WSL_CORE_INSTALL_COMMAND)
        assertTrue(WSL_CORE_INSTALL_COMMAND.endsWith("--with-core"), WSL_CORE_INSTALL_COMMAND)
        assertTrue("--with-core) with_core=\"yes\"" in installScript, "install.sh 가 --with-core 를 받지 않는다")
    }

    @Test
    fun `코어의 규약 판이 화면보다 낮으면 코어가 낡은 쪽이다`() {
        assertEquals(MismatchedSide.Core, olderSideOf(mismatch(screenProtocol = 2, coreProtocol = 1)))
        assertEquals(MismatchedSide.Screen, olderSideOf(mismatch(screenProtocol = 2, coreProtocol = 3)))
    }

    private fun mismatch(screenProtocol: Int, coreProtocol: Int) = CoreConnection.ProtocolMismatch(
        screenVersion = "v0.15.0",
        screenProtocol = screenProtocol,
        coreVersion = "v0.14.0",
        coreProtocol = coreProtocol,
    )
}
