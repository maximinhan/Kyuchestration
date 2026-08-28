package com.kyuchestration.desktop.workdir.kyucli

import com.kyuchestration.desktop.workdir.WorkDirObservationFailure
import com.kyuchestration.desktop.workdir.WorkDirObserver
import com.kyuchestration.desktop.workdir.WorkDirSnapshot
import java.nio.file.Path
import kotlin.io.path.absolutePathString

/**
 * kyu CLI 를 엔진으로 불러 워크디렉토리를 관찰한다.
 *
 * 이 앱은 워크디렉토리를 스스로 훑지 않는다. 스캔·상태 추론·계획 파싱은 kyu 안의 조율 계층이
 * 이미 하고 있고, 그것을 코틀린으로 한 번 더 옮기면 두 구현이 조용히 갈라진다
 * (설계 문서 5.1 — "조율 계층을 두 번 쓰지 않는 것").
 */
class KyuCliWorkDirObserver(private val kyuCommandRunner: KyuCommandRunner) : WorkDirObserver {

    override fun observe(workDirPath: Path): WorkDirSnapshot {
        // 절대경로로 못 박아 넘긴다. 상대 경로는 kyu 프로세스의 작업 디렉토리 기준으로 풀리는데,
        // 그것은 GUI 를 어디서 띄웠는지에 달려 있어 사용자가 고른 곳과 다를 수 있다.
        val result = try {
            kyuCommandRunner.run(listOf("list", workDirPath.absolutePathString(), "--json"))
        } catch (failure: KyuCommandFailure) {
            // 실행기는 "부르지 못했다" 는 사실만 던진다. 그 사실을 관찰하던 사람에게 할 말로
            // 옮기는 것이 이 어댑터의 몫이다.
            throw when (failure) {
                is KyuCommandFailure.ExecutableNotFound -> WorkDirObservationFailure.KyuExecutableNotFound()
                is KyuCommandFailure.FailedToStart ->
                    WorkDirObservationFailure.KyuFailedToStart(failure.processStartFailure)
            }
        }

        if (result.exitCode != 0) {
            throw WorkDirObservationFailure.KyuExitedWithFailure(
                exitCode = result.exitCode,
                standardError = result.standardError.trim(),
            )
        }

        return parseKyuListOutput(result.standardOutput)
    }
}
