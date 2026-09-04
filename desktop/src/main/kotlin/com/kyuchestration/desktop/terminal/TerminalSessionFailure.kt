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
        message = "kyu 실행 파일을 찾지 못했습니다.",
        guidance = "세션이 무엇을 띄우는지는 kyu 가 답합니다 — 앱은 그 답을 실행할 뿐입니다. " +
            "앱을 다시 띄우면 엔진 설치 화면이 뜹니다.",
    )

    class KyuFailedToStart(cause: Throwable) : TerminalSessionFailure(
        message = "kyu 를 실행하지 못했습니다.",
        guidance = "실행 권한이 있는지, 파일이 온전한지 확인하세요. 원인: ${cause.message}",
        cause = cause,
    )

    /**
     * kyu 가 "무엇을 띄울지" 에 답하기를 거절했다.
     *
     * 없는 레포 이름, 워크디렉토리가 아닌 자리, 스캔 실패가 여기로 온다. 답을 못 받았으므로 열
     * PTY 가 없다 — 조용히 넘어가면 사용자는 빈 터미널이 곧바로 닫히는 것만 보게 되고, 이유는
     * 어디에도 남지 않는다.
     *
     * @param arguments 실제로 부른 인자. 사람이 같은 것을 손으로 다시 부를 수 있게 그대로 싣는다 —
     *   메인 세션은 레포 이름 자리가 비어 있어서, 라벨(main)로 안내 문구를 지어내면 실제로는
     *   부를 수 없는 명령을 알려 주게 된다.
     */
    class SessionCommandRefused(
        val targetLabel: String,
        val exitCode: Int,
        val arguments: List<String>,
        standardError: String,
    ) : TerminalSessionFailure(
        message = "$targetLabel 세션이 무엇을 띄울지 kyu 가 답하지 못했습니다 (종료 코드 $exitCode).",
        // kyu 는 거절 이유를 stderr 에 사람 말로 적는다. 그것을 그대로 올리는 편이 이쪽에서
        // 다시 지어낸 문구보다 정확하다.
        guidance = standardError.ifBlank {
            "워크디렉토리에서 kyu ${arguments.joinToString(" ")} 를 직접 실행해 이유를 확인하세요."
        },
    )

    /**
     * 답은 받았는데 이 앱이 읽을 줄 아는 판이 아니다.
     *
     * 관찰(WorkDirObservationFailure)과 갈라 둔다. 사실은 같지만 사용자가 있던 자리가 다르고,
     * 뒤처진 쪽을 올리라는 말이 목록이 안 뜨는 사람과 세션이 안 열리는 사람에게 같은 무게로
     * 읽히지 않는다.
     */
    class UnsupportedSchemaVersion(
        val actualSchemaVersion: Int,
        val supportedSchemaVersion: Int,
    ) : TerminalSessionFailure(
        message = "kyu 가 낸 문서는 판 $actualSchemaVersion 인데 이 앱은 판 $supportedSchemaVersion 만 읽습니다.",
        guidance = "kyu 와 데스크톱 앱 중 뒤처진 쪽을 올리세요. " +
            "이 문서는 앱이 그대로 실행할 것이라, 반쯤 알아듣고 띄우면 엉뚱한 디렉토리에서 세션이 열립니다.",
    )

    class UnreadableKyuOutput(cause: Throwable) : TerminalSessionFailure(
        message = "kyu 가 답한 문서를 읽지 못했습니다.",
        guidance = "kyu session-command --json 의 stdout 에 JSON 문서 말고 다른 것이 섞이지 않았는지 " +
            "확인하세요. 원인: ${cause.message}",
        cause = cause,
    )

    /**
     * 챗 세션의 프로세스를 띄우지 못했다.
     *
     * [PtyFailedToOpen] 과 가르는 이유는 사람이 할 일이 다르기 때문이다. 그쪽은 앱이 도는 환경의
     * 문제고(pty4j 의 네이티브·남은 PTY), 이쪽은 엔진이 답한 명령을 실행하지 못한 것이다 —
     * `claude` 가 PATH 에 없거나, 답한 자리가 사라졌거나.
     *
     * 이름에 터미널이 들어간 타입에 챗의 갈래를 두는 것을 짚어둔다. 세션에 들어가지 못한 이유를
     * 담는 자리가 하나여야 부르는 쪽이 두 벌을 잡지 않는다 — 챗 화면도 엔진에게 묻는 실패
     * ([SessionCommandRefused] · [KyuExecutableNotFound])를 똑같이 만난다. 터미널이 사라지는
     * 7 단계에 이 타입의 이름을 함께 옮긴다.
     */
    class ChatProcessFailedToStart(cause: Throwable) : TerminalSessionFailure(
        message = "챗 세션의 claude 를 띄우지 못했습니다.",
        guidance = "claude 가 PATH 에 있는지, 엔진이 답한 디렉토리가 그대로 있는지 확인하세요. " +
            "원인: ${cause.message}",
        cause = cause,
    )

    /**
     * PTY 를 열지 못했다.
     *
     * 세션 문제가 아니라 이 앱이 도는 환경의 문제다 — pty4j 가 자기 네이티브 라이브러리를
     * 풀지 못했거나, 열 수 있는 PTY 가 남아 있지 않거나.
     */
    class PtyFailedToOpen(cause: Throwable) : TerminalSessionFailure(
        message = "앱 안에 터미널을 열지 못했습니다.",
        guidance = "터미널에서 그 디렉토리로 가 claude 를 직접 띄우면 작업은 이어갈 수 있습니다. " +
            "원인: ${cause.message}",
        cause = cause,
    )
}
