package com.kyuchestration.desktop.engine

import com.kyuchestration.desktop.diagnostics.DiagnosticLog
import com.kyuchestration.desktop.kyu.KyuCommandFailure
import com.kyuchestration.desktop.kyu.ProcessKyuCommandRunner
import java.nio.file.Path

/**
 * 이 파일이 정말 부를 수 있는 엔진인지 그 자리에서 물어본다. 돌면 null, 돌지 않으면 그 이유.
 *
 * 엔진을 놓는 두 자리가 함께 쓴다 — 릴리스에서 받아 오는 쪽과 앱 패키지에서 꺼내 놓는 쪽.
 * "정말 도는가" 의 뜻이 두 곳에서 갈라지면, 한쪽만 확인 없이 엔진의 이름을 내주는 날이 온다.
 *
 * 실행기를 그대로 쓴다. kyu 를 어떻게 띄우고 두 스트림을 어떻게 읽는지는 이미 한 곳에 있고,
 * 여기서 ProcessBuilder 를 다시 쓰면 그 앎이 둘로 갈라진다.
 *
 * `version` 을 묻는 이유는 그것이 이 바이너리 자신에 대한 질문이라 바깥을 하나도 보지 않기
 * 때문이다 — 워크디렉토리도, git 도, 세션 백엔드도. 다른 명령으로 물으면 "엔진이 아니다" 와
 * "여기가 워크디렉토리가 아니다" 가 한 답으로 섞인다.
 *
 * @param diagnosticLog 이 확인이 실패하면 남길 자리.
 *
 *   **이 자리야말로 기록이 필요한 곳이다.** 여기서 걸리면 앱은 설치 화면에 멈춰 서고 그 뒤로
 *   엔진을 부르는 일이 하나도 일어나지 않는다 — 기록을 남기지 않으면 그 실행에 대해 파일에
 *   적히는 것은 "앱이 시작했다" 한 줄뿐이다. 받은 바이너리가 다른 플랫폼의 것이거나 맥이
 *   격리(quarantine)해 둔 경우가 그렇게 끝나는데, 그것이 바로 원격으로 봐야 하는 실패다.
 */
internal fun reasonEngineDoesNotRun(
    enginePath: Path,
    diagnosticLog: DiagnosticLog = DiagnosticLog.Discarding,
): String? {
    val versionResult = try {
        ProcessKyuCommandRunner(enginePath, diagnosticLog = diagnosticLog).run(listOf("version"))
    } catch (failure: KyuCommandFailure) {
        return failure.message.orEmpty()
    }

    if (versionResult.exitCode == 0) {
        return null
    }
    return versionResult.standardError.trim().ifBlank { "종료 코드 ${versionResult.exitCode}" }
}
