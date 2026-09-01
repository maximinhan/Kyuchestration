package com.kyuchestration.desktop.diagnostics

/**
 * 남긴 기록을 들고만 있는 자리. 파일을 건드리지 않는다.
 *
 * 모의 라이브러리를 쓰지 않는 이유는 다른 시험 대역들과 같다 — 여기서 확인하는 것은 "무엇이
 * 남았는가" 하나뿐이고, 그것은 목록 하나면 눈으로 읽힌다.
 */
internal class RecordingDiagnosticLog : DiagnosticLog {

    val recordedEntries = mutableListOf<DiagnosticLogEntry>()

    override fun record(entry: DiagnosticLogEntry) {
        recordedEntries += entry
    }

    /**
     * 남은 것을 파일에 적힐 모양 그대로 이어 붙인다.
     *
     * 비밀이 새지 않는지 보는 시험이 이것을 쓴다. 필드를 하나씩 뒤지면 "그 필드는 안 봤다" 는
     * 구멍이 남지만, 실제로 파일에 적히는 문자열 전체를 훑으면 그 구멍이 없다.
     */
    fun recordedText(): String = recordedEntries.joinToString("\n") { entry ->
        entry.summary + entry.detail.orEmpty()
    }
}
