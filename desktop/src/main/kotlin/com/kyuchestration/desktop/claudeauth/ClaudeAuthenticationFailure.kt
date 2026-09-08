package com.kyuchestration.desktop.claudeauth

/**
 * claude 로그인이 서지 못한 이유.
 *
 * 세션 실패(TerminalSessionFailure)와 갈라 둔다. 사용자가 있던 자리가 다르다 — 이쪽은 아직
 * 워크디렉토리도 열기 전이고, 화면이 보여줄 것도 "이 세션이 왜 안 열렸는가" 가 아니라
 * "지금 무엇을 해야 로그인이 되는가" 다.
 *
 * **claude 의 stderr 를 화면에는 올리고 기록에는 남기지 않는다.** 브라우저 로그인이 실패할 때
 * claude 가 무엇을 적는지는 실측했고(`Login failed: Request failed with status code 400`) 거기에
 * 사용자가 붙여넣은 코드는 없었다. 그러나 실패 갈래를 하나만 재고서 "이 스트림에는 절대 코드가
 * 실리지 않는다" 고 단정할 수는 없다. 화면은 사용자가 방금 자기 손으로 붙여넣은 것을 다시 보는
 * 자리라 잃을 것이 없지만, 기록 파일은 남고 옮겨진다 — 그래서 기록에는 종료 코드만 간다
 * (DiagnosticLogEntry.ClaudeBrowserLoginFailed).
 *
 * @param guidance 사람이 지금 할 수 있는 일. 인증 화면에 오류 문구와 함께 뜬다.
 */
sealed class ClaudeAuthenticationFailure(
    message: String,
    val guidance: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    /**
     * claude 를 띄우지 못했다 — PATH 에 없거나, 실행 권한이 없거나.
     *
     * 이 갈래만 화면이 통째로 달라진다. 로그인 버튼을 눌러도 같은 실패를 만나므로, 화면은
     * 버튼 대신 설치 안내를 보여준다(ClaudeAuthenticationState.ClaudeCliMissing).
     */
    class ClaudeCliCouldNotRun(cause: Throwable) : ClaudeAuthenticationFailure(
        message = "claude 를 실행하지 못했습니다.",
        guidance = "claude 가 PATH 에 있는지 확인하세요. 원인: ${cause.message}",
        cause = cause,
    )

    /**
     * claude 는 돌았는데 이 앱이 그 답을 읽지 못했다.
     *
     * "로그인되지 않았다" 로 옮기지 않는다. 그러면 이미 로그인된 사용자에게 인증 화면이 뜨고,
     * 거기서 무엇을 해도 같은 자리로 돌아온다 — 진짜 이유(claude 의 답 모양이 바뀌었다)는
     * 어디에도 뜨지 않는다.
     */
    class ClaudeAnswerUnreadable(
        val claudeCommandDescription: String,
        val exitCode: Int,
        claudeMessage: String,
        cause: Throwable,
    ) : ClaudeAuthenticationFailure(
        message = "claude 의 답을 읽지 못했습니다 ($claudeCommandDescription · 종료 코드 $exitCode).",
        guidance = claudeMessage.trim().ifBlank {
            "claude 를 올리면 답 모양이 바뀌었을 수 있습니다. 터미널에서 같은 명령을 직접 실행해 " +
                "무엇이 오는지 확인하세요. 원인: ${cause.message}"
        },
        cause = cause,
    )

    /**
     * 브라우저 로그인을 띄웠는데 URL 이 나오지 않았다.
     *
     * 앱이 사용자에게 줄 것이 없는 상태다 — 브라우저가 열렸는지도 알 수 없고, 열렸다 해도
     * 어느 주소인지 화면에 적을 수 없다.
     */
    class BrowserLoginUrlNeverArrived(val waitedSeconds: Long) : ClaudeAuthenticationFailure(
        message = "claude 가 ${waitedSeconds}초 안에 로그인 주소를 알려주지 않았습니다.",
        guidance = "망이 막혀 있거나 claude 가 다른 것을 묻고 있을 수 있습니다. " +
            "터미널에서 claude auth login 을 직접 실행해 무엇이 뜨는지 확인하세요.",
    )

    /**
     * 코드를 넣었는데 claude 가 로그인을 끝내지 못했다.
     *
     * @param claudeMessage claude 가 stderr 에 적은 것. 화면에만 간다(이 타입의 머리말).
     */
    class BrowserLoginRefused(
        val exitCode: Int,
        val claudeMessage: String,
    ) : ClaudeAuthenticationFailure(
        message = "로그인을 끝내지 못했습니다 (claude 종료 코드 $exitCode).",
        guidance = claudeMessage.trim().ifBlank {
            "코드를 다시 받아 붙여넣어 보세요. 코드는 한 번만 쓸 수 있어서, 이미 쓴 것을 다시 넣으면 거절됩니다."
        },
    )

    /**
     * 붙여넣은 토큰으로 claude 가 답하지 못했다.
     *
     * @param claudeMessage claude 가 `result` 에 적은 것. 앤트로픽이 그 토큰에 대해 한 말이라
     *   사용자의 입력이 섞이지 않는다 — 이 갈래는 기록에도 그대로 간다.
     */
    class PastedTokenRefused(val claudeMessage: String) : ClaudeAuthenticationFailure(
        message = "이 토큰으로는 claude 가 답하지 못했습니다.",
        guidance = "$claudeMessage\n\n이미 로그인된 머신에서 claude setup-token 으로 새 토큰을 받아 다시 넣으세요.",
    )

    /** 토큰은 통했는데 이 머신에 저장하지 못했다. */
    class TokenCouldNotBeStored(cause: Throwable) : ClaudeAuthenticationFailure(
        message = "확인된 토큰을 이 머신에 저장하지 못했습니다.",
        guidance = "저장하지 못하면 앱을 다시 띄울 때마다 같은 것을 묻게 됩니다. " +
            "터미널에서 kyu claude-auth status 로 저장소가 답하는지 확인하세요. 원인: ${cause.message}",
        cause = cause,
    )
}
