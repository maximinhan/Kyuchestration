package com.kyuchestration.desktop.terminal

import java.nio.file.Path

/**
 * 세션에 들어가지 못한 이유.
 *
 * 관찰 실패(WorkDirObservationFailure)와 갈라 둔다. 같은 kyu 를 부르지만 사용자가 있던 자리가
 * 다르다 — 이쪽은 줄을 눌러 세션에 들어가려던 참이고, 화면이 보여줄 것도 목록이 아니라
 * "그 세션에 왜 못 들어갔는가" 다.
 *
 * **이름에 아직 터미널이 남아 있다.** 터미널 화면이 사라진 지금 이 타입이 담는 것은 챗 세션의
 * 진입 실패뿐이고, 이름은 `SessionEntryFailure` 가 맞다. 이 PR 에서 바꾸지 않는 이유는 그
 * 개명이 `terminal/chat/` 의 파일 열 개를 건드리는데 그 자리를 지금 다른 작업(6 단계)이 쥐고
 * 있어서다 — 삭제와 개명을 한 PR 에 섞으면 리뷰가 둘로 갈린다. 개명은 그 뒤의 PR 한 줄기다.
 */
sealed class TerminalSessionFailure(
    message: String,
    /** 사람이 지금 할 수 있는 일. 대화 자리에 오류 문구와 함께 뜬다. */
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
     * 없는 레포 이름, 워크디렉토리가 아닌 자리, 스캔 실패가 여기로 온다. 답을 못 받았으므로 띄울
     * 것이 없다 — 조용히 넘어가면 사용자는 대화가 열리다 만 것만 보게 되고, 이유는 어디에도
     * 남지 않는다.
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
     * 엔진에게 묻는 실패([SessionCommandRefused] · [KyuExecutableNotFound])와 가르는 이유는 사람이
     * 할 일이 다르기 때문이다. 저쪽은 kyu 가 답하지 못한 것이고, 이쪽은 그 답을 실행하지 못한
     * 것이다 — `claude` 가 PATH 에 없거나, 답한 자리가 사라졌거나.
     */
    class ChatProcessFailedToStart(cause: Throwable) : TerminalSessionFailure(
        message = "챗 세션의 claude 를 띄우지 못했습니다.",
        guidance = "claude 가 PATH 에 있는지, 엔진이 답한 디렉토리가 그대로 있는지 확인하세요. " +
            "원인: ${cause.message}",
        cause = cause,
    )

    /**
     * 승인 물음을 받을 소켓을 열지 못했다(chat-ui-design.md 5.4).
     *
     * **세션을 열지 않고 여기서 멈춘다.** 소켓 없이 챗을 열 수도 있지만, 그러면 승인이 필요한 일이
     * 나올 때까지는 멀쩡해 보이다가 그 순간부터 아무것도 못 하는 세션이 된다 — 화면이 먼저
     * 말하는 자리다(설계 원칙 12).
     */
    class ApprovalSocketFailedToOpen(socketPath: Path, cause: Throwable) : TerminalSessionFailure(
        message = "승인 물음을 받을 소켓을 열지 못했습니다 ($socketPath).",
        guidance = "이 소켓이 사람에게 물어볼 유일한 통로라, 없으면 승인이 필요한 일을 하나도 하지 못합니다. " +
            "런타임 디렉토리(XDG_RUNTIME_DIR · TMPDIR · /tmp)가 쓸 수 있는 자리인지 확인하세요. 원인: ${cause.message}",
        cause = cause,
    )

    /**
     * 승인 소켓의 경로가 유닉스 소켓의 한계를 넘는다.
     *
     * 실측이 실제로 만난 실패다(3.7 의 `AF_UNIX path too long`). 앱이 고르는 자리는 런타임
     * 디렉토리라 이 선을 넘는 일이 흔치 않지만, 넘었을 때 "왜 승인이 안 되지" 로 남지 않도록
     * 이유를 따로 든다.
     */
    class ApprovalSocketPathTooLong(
        socketPath: Path,
        limitBytes: Int,
    ) : TerminalSessionFailure(
        message = "승인 소켓의 경로가 유닉스 소켓의 한계($limitBytes 바이트)를 넘습니다: $socketPath",
        guidance = "런타임 디렉토리(XDG_RUNTIME_DIR · TMPDIR)를 더 짧은 자리로 두면 열립니다. " +
            "워크디렉토리 경로는 이 자리에 섞이지 않으므로 그쪽을 줄일 일은 아닙니다.",
    )
}
