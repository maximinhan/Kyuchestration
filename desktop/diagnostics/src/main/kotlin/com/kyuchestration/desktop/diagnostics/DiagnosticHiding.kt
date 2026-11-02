package com.kyuchestration.desktop.diagnostics

/**
 * 진단 기록에 남기지 않을 값을 가린다 — 사용자의 대화를 가리키는 대화 id 같은 것.
 *
 * 인자 하나가 그 값과 같을 때만이 아니라 글 안에 들어 있어도 가린다 — kyu 의 거절 문구가 받은 id 를 되읊는다.
 *
 * 기록 쪽 모듈에 두는 것은 쓰는 자리가 둘이라서다. kyu 실행기가 자기 실패 기록에서 가리고, 세션 명령의 거절
 * (TerminalSessionFailure.SessionCommandRefused)이 세션 진입 실패의 기록에 실릴 문구를 스스로 가린다. 실행기 패키지에 두면
 * 실패 타입이 어댑터를 들여다보게 된다.
 */
class DiagnosticHiding(private val hiddenValues: Set<String>) {
    fun hide(text: String): String =
        hiddenValues.filter { it.isNotEmpty() }.fold(text) { hidden, value -> hidden.replace(value, HIDDEN_VALUE_MARK) }
}

private const val HIDDEN_VALUE_MARK = "(가린 값)"
