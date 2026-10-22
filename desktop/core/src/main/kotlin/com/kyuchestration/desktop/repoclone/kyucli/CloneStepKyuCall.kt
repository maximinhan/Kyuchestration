package com.kyuchestration.desktop.repoclone.kyucli

import com.kyuchestration.desktop.kyu.KyuCommandFailure
import com.kyuchestration.desktop.kyu.KyuCommandResult
import com.kyuchestration.desktop.kyu.KyuCommandRunner
import com.kyuchestration.desktop.kyu.KyuDocumentFailure
import com.kyuchestration.desktop.repoclone.CloneStepFailure
import java.nio.file.Path

/**
 * 클론 흐름의 어댑터들이 kyu 를 부르고 그 답을 읽는 공통의 두 걸음.
 *
 * 실행기와 문서 읽기는 사실만 던진다(KyuCommandFailure · KyuDocumentFailure). 그것을 사람에게
 * 할 말이 붙은 실패로 옮기는 일이 어댑터의 몫인데, 이 흐름의 어댑터 셋(토큰 프로필 · 레포 조회 ·
 * 클론)이 옮기는 방식은 똑같다 — 세 곳에 같은 when 을 적어 두면 안내 문구가 한 곳에서만
 * 고쳐지는 날이 온다.
 *
 * 종료 코드를 어떻게 볼지는 여기서 정하지 않는다. 그것만은 명령마다 다르다 — kyu clone 은
 * 실패한 레포가 섞여 있어도 문서를 내고 종료 코드 1 로 끝나므로(clone_json.go), 0 이 아닌 것을
 * 곧바로 실패로 보면 어느 레포가 왜 실패했는지를 통째로 잃는다.
 */
internal fun KyuCommandRunner.runCloneStep(
    arguments: List<String>,
    workingDirectory: Path? = null,
    standardInput: String? = null,
): KyuCommandResult = try {
    run(arguments, workingDirectory, standardInput)
} catch (failure: KyuCommandFailure) {
    throw when (failure) {
        is KyuCommandFailure.ExecutableNotFound -> CloneStepFailure.KyuExecutableNotFound()
        is KyuCommandFailure.FailedToStart -> CloneStepFailure.KyuFailedToStart(failure.processStartFailure)
    }
}

/**
 * kyu 가 낸 문서를 읽고, 읽지 못한 이유를 이 흐름의 실패로 옮긴다.
 */
internal fun <T> readCloneStepDocument(rawJson: String, parse: (String) -> T): T = try {
    parse(rawJson)
} catch (failure: KyuDocumentFailure) {
    throw when (failure) {
        is KyuDocumentFailure.UnsupportedSchemaVersion -> CloneStepFailure.UnsupportedSchemaVersion(
            actualSchemaVersion = failure.actualSchemaVersion,
            supportedSchemaVersion = failure.supportedSchemaVersion,
        )

        is KyuDocumentFailure.Unreadable -> CloneStepFailure.UnreadableKyuOutput(failure.parseFailure)
    }
}

/**
 * 시작하지도 못한 실행을 실패로 올린다.
 *
 * kyu 는 시작조차 못 한 실패에서는 stdout 에 아무것도 내지 않고 이유를 stderr 에 사람 말로
 * 적는다(json_output.go 는 성공한 문서만 내보낸다). 그 말을 그대로 올리는 편이 이쪽에서 다시
 * 지어낸 문구보다 정확하다.
 */
internal fun KyuCommandResult.failIfKyuRefused() {
    if (exitCode != 0) {
        throw CloneStepFailure.KyuExitedWithFailure(exitCode = exitCode, standardError = standardError.trim())
    }
}
