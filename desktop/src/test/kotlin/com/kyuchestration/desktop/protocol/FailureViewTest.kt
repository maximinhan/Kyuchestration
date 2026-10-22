package com.kyuchestration.desktop.protocol

import com.kyuchestration.desktop.engine.EngineInstallationFailure
import com.kyuchestration.desktop.terminal.TerminalSessionFailure
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 실패가 경계를 건너는 모양(core-ui-split-design.md 6.3). 예외는 원인 사슬을 쥐어 건너지 못하고, 화면이 쓰는 것은 문구와
 * 안내뿐이다 — 갈래 이름은 화면이 갈래별 문구를 고를 재료로 함께 간다.
 */
class FailureViewTest {

    @Test
    fun `갈래 이름과 문구와 안내만 남기고 원인은 남기지 않는다`() {
        val failure = EngineInstallationFailure.GitHubUnreachable(IOException("connect timed out"))

        assertEquals(
            FailureView(
                kind = "GitHubUnreachable",
                message = "GitHub 에 닿지 못했습니다.",
                guidance = "네트워크와 프록시 설정을 확인한 뒤 다시 시도하세요. 원인: connect timed out",
            ),
            failure.toFailureView(),
        )
    }

    @Test
    fun `원인이 사슬로 걸린 실패도 바깥 갈래 하나로 건넌다`() {
        val refusal = TerminalSessionFailure.SessionCommandRefused("main", 2, listOf("session-command"), "모르는 옵션")

        val view = TerminalSessionFailure.EngineTooOld("v0.13.0", "v0.14.0", refusal).toFailureView()

        assertEquals("EngineTooOld", view.kind)
        assertTrue("모르는 옵션" in view.guidance)
    }
}
