package com.kyuchestration.desktop.kyu

/**
 * kyu 를 부르지 못했거나, 부른 kyu 와 끝까지 주고받지 못한 이유.
 *
 * 실행기를 쓰는 쪽이 여럿이 되면서(관찰 · 초기화 · 토큰 프로필 · 레포 조회 · 클론) 실행기가
 * 한쪽의 실패 타입으로 답할 수 없게 됐다.
 * "부를 kyu 가 없다" 는 사실은 어느 쪽에서나 같지만 사람에게 할 말은 다르다 —
 * "설치한 뒤 새로고침하세요" 와 "설치한 뒤 다시 만드세요" 는 같은 문장이 아니다.
 * 그래서 실행기는 사실만 던지고, 안내 문구는 부르는 쪽이 자기 실패 타입에서 정한다
 * (TerminalSessionFailure 가 관찰 실패와 갈라져 있는 것과 같은 선).
 */
sealed class KyuCommandFailure(message: String, cause: Throwable? = null) : Exception(message, cause) {

    class ExecutableNotFound : KyuCommandFailure("kyu 실행 파일을 찾지 못했습니다.")

    /**
     * 실행 파일은 찾았는데 프로세스를 띄우지 못했다 — 실행 권한이 없거나, 찾은 뒤 지워졌거나.
     *
     * @param processStartFailure 프로세스를 띄우려다 받은 예외. 부르는 쪽이 자기 안내 문구에
     *   원인을 실을 수 있도록 따로 들고 있는다 — Throwable.cause 로만 두면 널 가능 타입이라
     *   쓰는 자리마다 없는 경우를 다시 따져야 한다.
     */
    class FailedToStart(val processStartFailure: Throwable) :
        KyuCommandFailure("kyu 를 실행하지 못했습니다.", processStartFailure)

    /**
     * 프로세스는 떴는데 끝까지 주고받지 못했다 — stdin 을 쓰다, stdout · stderr 를 읽다 파이프가 끊겼거나, 기다리던 스레드가
     * 인터럽트됐다.
     *
     * kyu 가 스스로 끝난 것과 갈라 둔다. 쓰다 죽은 kyu 는 종료 코드를 남기므로 [KyuCommandResult] 로 답한다 — 이 갈래는 이쪽이
     * 종료 코드를 받기 전에 주고받기를 잃은 경우라, 지어낸 종료 코드 없이 받은 예외를 그대로 든다. 실행기는 이것을 던지기 전에
     * 그 프로세스를 끝내 둔다.
     *
     * @param runFailure 주고받다 받은 예외. 원인이 문구에도 실리는 것은 부르는 쪽 여럿이 message 만 옮기기 때문이다 —
     *   "끊겼다" 만으로는 파이프가 닫힌 것과 인터럽트된 것을 가를 수 없다.
     */
    class FailedWhileRunning(val runFailure: Throwable) :
        KyuCommandFailure("kyu 와 주고받던 중에 끊겼습니다 (${runFailure.message ?: runFailure::class.java.simpleName}).", runFailure)
}
