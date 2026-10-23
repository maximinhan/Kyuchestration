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

    /** 판이 낮은 쪽이 낡았다. WSL 에 따로 깐 코어가 낡았으면 그 설치 한 줄, 화면이 낡았으면 새 msi 다. */
    @Test
    fun `WSL 에 따로 깐 코어와 판이 갈리면 낡은 쪽을 새로 깔게 한다`() {
        assertEquals(MismatchRemedy.UpdateWslCore, mismatchRemedyOf(mismatch(screenProtocol = 2, coreProtocol = 1), coreInstalledSeparatelyInWsl = true))
        assertEquals(MismatchRemedy.UpdateScreen, mismatchRemedyOf(mismatch(screenProtocol = 2, coreProtocol = 3), coreInstalledSeparatelyInWsl = true))
    }

    /** 맥 · 리눅스의 코어는 화면과 한 패키지다 — 어느 쪽이 낡았든 패키지 하나를 다시 깐다. */
    @Test
    fun `같은 패키지의 코어와 판이 갈리면 함께 다시 깔게 한다`() {
        assertEquals(MismatchRemedy.ReinstallTogether, mismatchRemedyOf(mismatch(screenProtocol = 2, coreProtocol = 1), coreInstalledSeparatelyInWsl = false))
    }

    private fun mismatch(screenProtocol: Int, coreProtocol: Int) = CoreConnection.ProtocolMismatch(
        screenVersion = "v0.15.0",
        screenProtocol = screenProtocol,
        coreVersion = "v0.14.0",
        coreProtocol = coreProtocol,
    )
}
