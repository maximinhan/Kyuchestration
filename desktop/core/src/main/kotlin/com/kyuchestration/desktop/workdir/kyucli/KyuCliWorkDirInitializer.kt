package com.kyuchestration.desktop.workdir.kyucli

import com.kyuchestration.desktop.kyu.KyuCommandFailure
import com.kyuchestration.desktop.kyu.KyuCommandRunner
import com.kyuchestration.desktop.workdir.WorkDirInitializationResult
import com.kyuchestration.desktop.workdir.WorkDirInitializer
import java.nio.file.Path

/**
 * kyu CLI 를 엔진으로 불러 워크디렉토리를 초기화한다.
 *
 * 앱이 .coord/plan.md 를 직접 쓰지 않는다. 조율 디렉토리를 만드는 것과 계획 템플릿을 놓는 것은
 * 나뉠 수 없는 한 가지 일이고(init.go 의 createWorkDirPlanFile), 그 지식은 엔진 한 곳에 있어야
 * 한다. 앱이 반쪽만 흉내 내면 템플릿이 바뀌는 날 앱으로 연 워크디렉토리만 조용히 다른 모양이 된다.
 */
class KyuCliWorkDirInitializer(private val kyuCommandRunner: KyuCommandRunner) : WorkDirInitializer {

    override fun initializeInPlace(workDirPath: Path): WorkDirInitializationResult {
        val result = try {
            // 인자 없는 kyu init 은 작업 디렉토리를 초기화한다. 사람이 터미널에서
            // `cd <워크디렉토리> && kyu init` 이라고 치는 것과 같은 실행이라, 자리를 경로로 푸는
            // 규칙이 CLI 와 어긋날 자리가 없다.
            kyuCommandRunner.run(listOf("init"), workDirPath)
        } catch (failure: KyuCommandFailure) {
            return WorkDirInitializationResult.NotInitialized(reasonKyuCouldNotRun(failure))
        }

        if (result.exitCode != 0) {
            // kyu 는 거절 이유를 stderr 에 사람 말로 적는다(예: "이미 계획 파일이 있습니다").
            // 그것을 그대로 올리는 편이 이쪽에서 다시 지어낸 문구보다 정확하다.
            return WorkDirInitializationResult.NotInitialized(
                result.standardError.trim().ifBlank {
                    "kyu init 이 아무 말 없이 종료 코드 ${result.exitCode} 로 끝났습니다."
                },
            )
        }

        return WorkDirInitializationResult.Initialized(workDirPath)
    }

    private fun reasonKyuCouldNotRun(failure: KyuCommandFailure): String = when (failure) {
        is KyuCommandFailure.ExecutableNotFound ->
            "${failure.message} 워크디렉토리를 초기화하는 일도 kyu 가 합니다 — 설치해 PATH 에 넣은 뒤 다시 시도하세요."

        is KyuCommandFailure.FailedToStart ->
            "${failure.message} 실행 권한이 있는지, 파일이 온전한지 확인하세요. " +
                "원인: ${failure.processStartFailure.message}"
    }
}
