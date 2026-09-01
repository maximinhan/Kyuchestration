package com.kyuchestration.desktop.diagnostics

/**
 * 아무도 받지 않은 예외를 기록에 남기고, 앞서 있던 핸들러에게 그대로 넘긴다.
 *
 * **더하는 것이지 대신하는 것이 아니다.** JVM 이 기본으로 두는 핸들러가 스택을 stderr 로 찍는데,
 * 그것을 가로채 기록만 남기면 터미널에서 앱을 띄워 지켜보던 사람이 방금까지 보이던 것을 잃는다.
 *
 * @param previousHandler 이 핸들러를 세우기 전에 있던 것. 없으면 null.
 */
internal fun uncaughtFailureRecordingHandler(
    diagnosticLog: DiagnosticLog,
    previousHandler: Thread.UncaughtExceptionHandler?,
): Thread.UncaughtExceptionHandler = Thread.UncaughtExceptionHandler { thread, failure ->
    try {
        diagnosticLog.record(DiagnosticLogEntry.UnhandledFailure(thread.name, failure))
    } catch (recordingFailure: Throwable) {
        // 여기서 던지면 이 핸들러 자신이 예외로 끝나고, 원래 예외는 넘겨지지도 찍히지도 못한 채
        // 사라진다. 이 자리가 도는 시점은 이미 무언가 잘못된 뒤라 — 기록을 남기지 못하는 이유가
        // OutOfMemoryError 인 경우가 대표적이다 — 남기기보다 넘기는 쪽이 먼저다.
        //
        // 파일 쓰기 실패는 여기까지 오지 않는다(FileDiagnosticLog 가 자기 안에서 삼킨다).
        // 그래도 잡는 것은 이 함수가 어떤 DiagnosticLog 를 받을지 모르기 때문이다.
        recordingFailure.addSuppressed(failure)
    }

    previousHandler?.uncaughtException(thread, failure)
}

/**
 * 이 JVM 의 기본 미처리 예외 핸들러로 세운다.
 *
 * 코루틴이 도는 스레드(엔진 호출·PTY 열기가 나가는 IO 디스패처)와 앱의 메인 스레드가 이 핸들러를
 * 지난다. Compose 의 합성에서 빠져나온 예외는 여기가 아니라 `application` 을 부르는 자리에서
 * 잡히므로, 그쪽은 Main.kt 가 따로 받는다 — 두 자리를 합칠 수 없어 둘 다 둔다.
 */
internal fun recordUncaughtFailuresIn(diagnosticLog: DiagnosticLog) {
    val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler(uncaughtFailureRecordingHandler(diagnosticLog, previousHandler))
}
