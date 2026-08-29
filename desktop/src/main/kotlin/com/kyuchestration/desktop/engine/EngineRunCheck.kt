package com.kyuchestration.desktop.engine

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
 * `version` 을 묻는 이유는 그것이 tmux 도 워크디렉토리도 보지 않는 유일한 명령이기 때문이다.
 * 다른 명령으로 물으면 "엔진이 아니다" 와 "이 머신에 tmux 가 없다" 가 한 답으로 섞인다.
 */
internal fun reasonEngineDoesNotRun(enginePath: Path): String? {
    val versionResult = try {
        ProcessKyuCommandRunner(enginePath).run(listOf("version"))
    } catch (failure: KyuCommandFailure) {
        return failure.message.orEmpty()
    }

    if (versionResult.exitCode == 0) {
        return null
    }
    return versionResult.standardError.trim().ifBlank { "종료 코드 ${versionResult.exitCode}" }
}
