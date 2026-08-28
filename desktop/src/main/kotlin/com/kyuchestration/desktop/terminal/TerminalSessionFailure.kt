package com.kyuchestration.desktop.terminal

/**
 * 세션에 들어가지 못한 이유.
 *
 * 관찰 실패(WorkDirObservationFailure)와 갈라 둔다. 같은 kyu 를 부르지만 사용자가 있던 자리가
 * 다르다 — 이쪽은 카드를 눌러 세션에 들어가려던 참이고, 화면이 보여줄 것도 목록이 아니라
 * "그 세션에 왜 못 들어갔는가" 다.
 */
sealed class TerminalSessionFailure(
    message: String,
    /** 사람이 지금 할 수 있는 일. 터미널 자리에 오류 문구와 함께 뜬다. */
    val guidance: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    class KyuExecutableNotFound : TerminalSessionFailure(
        message = "PATH 에서 kyu 실행 파일을 찾지 못했습니다.",
        guidance = "세션을 띄우고 진입하는 일은 전부 kyu 가 합니다. " +
            "설치해 PATH 에 넣은 뒤 다시 눌러 주세요.",
    )

    class KyuFailedToStart(cause: Throwable) : TerminalSessionFailure(
        message = "kyu 를 실행하지 못했습니다.",
        guidance = "실행 권한이 있는지, 파일이 온전한지 확인하세요. 원인: ${cause.message}",
        cause = cause,
    )

    /**
     * kyu start 가 세션을 띄우지 못했다.
     *
     * 붙기 전에 걸린 실패라 터미널을 열 이유가 없다. 조용히 넘어가면 사용자는 빈 터미널이
     * 곧바로 닫히는 것만 보게 되고, 이유(tmux 미설치·없는 레포)는 어디에도 남지 않는다.
     */
    class SessionStartRejected(
        val targetLabel: String,
        val exitCode: Int,
        standardError: String,
    ) : TerminalSessionFailure(
        message = "$targetLabel 세션을 띄우지 못했습니다 (kyu start 가 종료 코드 $exitCode 로 끝남).",
        // kyu 는 거절 이유를 stderr 에 사람 말로 적는다. 그것을 그대로 올리는 편이 이쪽에서
        // 다시 지어낸 문구보다 정확하다.
        guidance = standardError.ifBlank {
            "워크디렉토리에서 kyu start $targetLabel 을 직접 실행해 이유를 확인하세요."
        },
    )

    /**
     * PTY 를 열지 못했다.
     *
     * 세션 문제가 아니라 이 앱이 도는 환경의 문제다 — pty4j 가 자기 네이티브 라이브러리를
     * 풀지 못했거나, 열 수 있는 PTY 가 남아 있지 않거나.
     */
    class PtyFailedToOpen(cause: Throwable) : TerminalSessionFailure(
        message = "앱 안에 터미널을 열지 못했습니다.",
        guidance = "터미널에서 kyu attach 를 직접 실행하면 세션에는 들어갈 수 있습니다. " +
            "원인: ${cause.message}",
        cause = cause,
    )
}
