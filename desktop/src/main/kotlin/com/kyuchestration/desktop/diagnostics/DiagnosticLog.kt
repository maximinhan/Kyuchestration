package com.kyuchestration.desktop.diagnostics

/**
 * 앱이 겪은 일을 어딘가에 남기는 자리.
 *
 * **로깅 프레임워크를 들이지 않는다.** 이 앱의 의존성 트리에 이미 slf4j 가 있지만(JediTerm 과
 * pty4j 가 자기 진단을 그리로 흘린다) 붙어 있는 구현은 slf4j-simple 이고, 그것은 stderr 로만
 * 쓴다 — 파일로 남기려면 logback 이나 log4j 를 새로 들여야 한다. 그 대가가 남는 것에 비해 크다:
 * 설정 파일 하나와 의존성 두 개가 늘고, 그 설정이 남기는 것은 "누구든 아무 문자열이나 적을 수
 * 있는" 로그다. 이 앱이 남길 것은 여섯 갈래로 정해져 있고(DiagnosticLogEntry) 그 닫힌 갈래가
 * 곧 비밀이 새지 않는다는 보장이라, 프레임워크의 자유로운 표면은 얻는 것이 아니라 잃는 것이다.
 *
 * 남기는 자리를 인터페이스로 두는 이유는 시험이다. 시험이 진짜 홈 디렉토리에 파일을 남기지
 * 않아야 하고, 어댑터 시험들은 아무 데도 남기지 않아야 한다.
 */
interface DiagnosticLog {

    fun record(entry: DiagnosticLogEntry)

    /**
     * 아무 데도 남기지 않는다.
     *
     * 시험이 기본값으로 쓴다. 진짜 파일을 기본값으로 두면 어댑터 시험 하나를 돌릴 때마다 이
     * 머신의 홈 디렉토리에 기록이 쌓인다.
     */
    object Discarding : DiagnosticLog {
        override fun record(entry: DiagnosticLogEntry) = Unit
    }
}
