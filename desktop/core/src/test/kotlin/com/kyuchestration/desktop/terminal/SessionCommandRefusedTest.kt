package com.kyuchestration.desktop.terminal

import com.kyuchestration.desktop.diagnostics.DiagnosticHiding
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * 거절은 진단용 문구를 스스로 가린다. 부르는 자리가 가린 문자열을 따로 지어 넘기는 구조면, 거절을 새로 만드는 자리 하나가 안 가린
 * 값을 넘겨도 컴파일이 막지 못한다 — 가리기를 받아 타입 안에서 짓는 것이 그 불변식이다.
 */
class SessionCommandRefusedTest {

    @Test
    fun `엔진의 거절 문구는 화면에 그대로, 진단용으로는 가려서 든다`() {
        val refusal = refusalWith(standardError = "이 자리의 대화 중에 $ADOPTED_ID 가 없습니다")

        assertEquals("이 자리의 대화 중에 $ADOPTED_ID 가 없습니다", refusal.guidance)
        assertEquals("이 자리의 대화 중에 (가린 값) 가 없습니다", refusal.guidanceForDiagnostics)
    }

    @Test
    fun `거절 이유가 비어 지어낸 명령 문구도 진단용으로는 가린다`() {
        val refusal = refusalWith(standardError = "")

        assertEquals(
            "워크디렉토리에서 kyu session-command --adopt-conversation (가린 값) --chat --json 를 직접 실행해 이유를 확인하세요.",
            refusal.guidanceForDiagnostics,
        )
    }

    @Test
    fun `낡은 엔진의 진단용 문구는 거절의 가린 문구를 싣는다`() {
        val engineTooOld = TerminalSessionFailure.EngineTooOld("v0.12.0", "v0.13.0", refusalWith(standardError = "모르는 옵션 $ADOPTED_ID"))

        assertFalse(ADOPTED_ID in engineTooOld.guidanceForDiagnostics, engineTooOld.guidanceForDiagnostics)
    }

    private fun refusalWith(standardError: String) = TerminalSessionFailure.SessionCommandRefused(
        targetLabel = "main",
        exitCode = 1,
        arguments = listOf("session-command", "--adopt-conversation", ADOPTED_ID, "--chat", "--json"),
        standardError = standardError,
        diagnosticHiding = DiagnosticHiding(setOf(ADOPTED_ID)),
    )

    private companion object {
        const val ADOPTED_ID = "654ce605-b030-40e8-8448-2e9003ad32b1"
    }
}
