package com.kyuchestration.desktop.diagnostics

import kotlin.test.Test
import kotlin.test.assertEquals

class DiagnosticHidingTest {

    @Test
    fun `가릴 값이 글 안에 들어 있어도 가린다`() {
        // 엔진의 거절 문구는 받은 id 를 되읊는다 — 인자와 같을 때만 가리면 그 문구로 샌다.
        val hiding = DiagnosticHiding(setOf(CONVERSATION_ID))

        assertEquals("대화 중에 (가린 값) 가 없습니다", hiding.hide("대화 중에 $CONVERSATION_ID 가 없습니다"))
    }

    @Test
    fun `빈 값은 가리지 않는다`() {
        // 빈 문자열을 바꾸면 글자 사이마다 표시가 끼어 기록 전체가 읽히지 않는다.
        assertEquals("그대로", DiagnosticHiding(setOf("")).hide("그대로"))
    }

    private companion object {
        const val CONVERSATION_ID = "654ce605-b030-40e8-8448-2e9003ad32b1"
    }
}
