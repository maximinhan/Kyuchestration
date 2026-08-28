package com.kyuchestration.desktop.repoclone

/**
 * 레포를 골라 받아 오는 걸음 하나가 성립하지 않은 이유.
 *
 * 관찰 실패(WorkDirObservationFailure)와 갈라 둔다. 실패의 모양은 닮았지만 사람이 할 수 있는
 * 일이 다르다 — 목록이 갱신되지 않은 사람에게는 "새로고침하세요" 가, 레포를 고르던 사람에게는
 * "다시 시도하세요" 가 할 말이다. 안내 문구를 공용으로 두면 어느 한쪽이 남의 말을 하게 된다.
 *
 * 걸음마다 타입을 가르지 않는다. 프로필을 묻다 실패한 것과 레포 목록을 받다 실패한 것은 화면이
 * 하는 일이 같고(이유를 보여주고 그 걸음을 다시 시킨다), 어느 걸음이었는지는 상태 홀더가
 * CloneStep 으로 따로 들고 있다.
 *
 * 토큰은 이 타입 어디에도 담기지 않는다. 여기 실리는 것은 kyu 가 stderr 에 적은 말과 이 앱이
 * 지어낸 안내뿐이고, 둘 중 어느 것도 토큰을 알지 못한다 — 실패 문구는 로그와 화면 캡처에
 * 가장 잘 남는 자리다.
 */
sealed class CloneStepFailure(
    message: String,
    /** 사람이 지금 할 수 있는 일. 화면이 오류 문구 아래에 그대로 보여준다. */
    val guidance: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    class KyuExecutableNotFound : CloneStepFailure(
        message = "PATH 에서 kyu 실행 파일을 찾지 못했습니다.",
        guidance = "레포를 받아 오는 일도 kyu 가 합니다 — 설치해 PATH 에 넣은 뒤 다시 시도하세요.",
    )

    class KyuFailedToStart(cause: Throwable) : CloneStepFailure(
        message = "kyu 를 실행하지 못했습니다.",
        guidance = "실행 권한이 있는지, 파일이 온전한지 확인하세요. 원인: ${cause.message}",
        cause = cause,
    )

    /**
     * kyu 가 이 걸음을 거절했다 — 토큰이 거절당했거나, 프로필이 없거나, GitHub 에 닿지 못했거나.
     *
     * 이유를 이 앱이 다시 나누지 않는다. 401 과 네트워크 끊김과 권한 부족은 kyu 가 이미 사람 말로
     * 갈라 적어 두었고, 종료 코드만으로는 그 구분이 서지 않는다 — 셋 다 1 이다.
     */
    class KyuExitedWithFailure(
        val exitCode: Int,
        val standardError: String,
    ) : CloneStepFailure(
        message = "kyu 가 종료 코드 $exitCode 로 끝났습니다.",
        guidance = standardError.ifBlank {
            "터미널에서 같은 명령을 직접 실행해 이유를 확인하세요."
        },
    )

    class UnsupportedSchemaVersion(
        val actualSchemaVersion: Int,
        val supportedSchemaVersion: Int,
    ) : CloneStepFailure(
        message = "kyu 가 낸 문서는 판 $actualSchemaVersion 인데 이 앱은 판 $supportedSchemaVersion 만 읽습니다.",
        guidance = "kyu 와 데스크톱 앱 중 뒤처진 쪽을 올리세요. 판은 필드가 사라지거나 뜻이 바뀔 때만 오릅니다.",
    )

    class UnreadableKyuOutput(cause: Throwable) : CloneStepFailure(
        message = "kyu 의 출력을 읽지 못했습니다.",
        guidance = "stdout 에 JSON 문서 말고 다른 것이 섞이지 않았는지 확인하세요. 원인: ${cause.message}",
        cause = cause,
    )
}
