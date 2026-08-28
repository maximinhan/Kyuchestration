package com.kyuchestration.desktop.workdir

/**
 * 관찰이 성립하지 않은 이유.
 *
 * 이유마다 사람이 할 수 있는 조치가 다르다. "관찰에 실패했습니다" 한 줄로 뭉치면 화면은 무엇을
 * 고치라고 말할 수 없고, 사용자는 앱이 고장난 것과 자기 환경이 덜 갖춰진 것을 구분하지 못한다.
 * 그래서 종류를 타입으로 갈라 두고 각자가 자기 안내 문구를 들고 있는다.
 *
 * 빈 워크디렉토리는 여기 없다. 레포가 하나도 없는 것은 관찰이 성립한 결과이지 실패가 아니다
 * (list_json.go 의 "실패는 이 문서에 담지 않는다" 와 같은 선).
 */
sealed class WorkDirObservationFailure(
    message: String,
    /** 사람이 지금 할 수 있는 일. 화면이 오류 문구 아래에 그대로 보여준다. */
    val guidance: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    class KyuExecutableNotFound : WorkDirObservationFailure(
        message = "PATH 에서 kyu 실행 파일을 찾지 못했습니다.",
        guidance = "kyu 를 설치해 PATH 에 넣은 뒤 새로고침하세요. " +
            "저장소에서 바로 만들려면 go build -o ~/.local/bin/kyu ./cmd/kyu 입니다.",
    )

    /**
     * 실행 파일은 찾았는데 프로세스를 띄우지 못했다.
     *
     * "찾지 못했다" 와 갈라 둔다. 사용자가 할 일이 다르기 때문이다 — 이쪽은 설치가 아니라 권한이나
     * 깨진 파일을 봐야 한다. 여기서 뭉뚱그리면 이미 설치한 사람에게 "설치하세요" 라고 말하게 된다.
     */
    class KyuFailedToStart(cause: Throwable) : WorkDirObservationFailure(
        message = "kyu 를 실행하지 못했습니다.",
        guidance = "실행 권한이 있는지, 파일이 온전한지 확인하세요. 원인: ${cause.message}",
        cause = cause,
    )

    class KyuExitedWithFailure(
        val exitCode: Int,
        val standardError: String,
    ) : WorkDirObservationFailure(
        message = "kyu 가 종료 코드 $exitCode 로 끝났습니다.",
        // kyu 는 실패 이유를 stderr 에 사람 말로 적는다. 그것을 그대로 올리는 편이 이쪽에서
        // 다시 지어낸 문구보다 정확하다.
        guidance = standardError.ifBlank {
            "터미널에서 같은 경로로 kyu list --json 을 직접 실행해 이유를 확인하세요."
        },
    )

    class UnsupportedSchemaVersion(
        val actualSchemaVersion: Int,
        val supportedSchemaVersion: Int,
    ) : WorkDirObservationFailure(
        message = "kyu 가 낸 문서는 판 $actualSchemaVersion 인데 이 앱은 판 $supportedSchemaVersion 만 읽습니다.",
        guidance = "kyu 와 데스크톱 앱 중 뒤처진 쪽을 올리세요. 판은 필드가 사라지거나 뜻이 바뀔 때만 오릅니다.",
    )

    class UnreadableKyuOutput(cause: Throwable) : WorkDirObservationFailure(
        message = "kyu 의 출력을 읽지 못했습니다.",
        guidance = "kyu list --json 의 stdout 에 JSON 문서 말고 다른 것이 섞이지 않았는지 확인하세요. " +
            "원인: ${cause.message}",
        cause = cause,
    )
}
