package com.kyuchestration.desktop.workdir.kyucli

import com.kyuchestration.desktop.workdir.WorkDirInitializationResult
import com.kyuchestration.desktop.workdir.WorkDirInitializer
import java.nio.file.Path

/**
 * kyu CLI 를 엔진으로 불러 워크디렉토리를 만들고 초기화한다.
 *
 * GUI 가 디렉토리를 먼저 만들지 않는다. 디렉토리를 만드는 것과 .coord/plan.md 템플릿을 놓는 것은
 * 나뉠 수 없는 한 가지 일이고(init.go 의 createWorkDirPlanFile), 그 지식은 엔진 한 곳에 있어야
 * 한다. 앱이 mkdir 만 흉내 내면 템플릿이 바뀌는 날 반쪽만 따라가는 초기화가 생긴다.
 */
class KyuCliWorkDirInitializer(private val kyuCommandRunner: KyuCommandRunner) : WorkDirInitializer {

    override fun createWorkDir(parentDirectory: Path, newWorkDirName: String): WorkDirInitializationResult =
        runKyuInit(
            arguments = listOf("init", newWorkDirName),
            // 이름은 부모 디렉토리를 작업 디렉토리로 삼아 그대로 넘긴다. 사람이 터미널에서
            // `cd <부모> && kyu init <이름>` 이라고 치는 것과 같은 실행이라, 이름을 경로로 푸는
            // 규칙이 CLI 와 어긋날 자리가 없다.
            workingDirectory = parentDirectory,
            initializedWorkDirPath = parentDirectory.resolve(newWorkDirName),
        )

    override fun initializeExistingWorkDir(workDirPath: Path): WorkDirInitializationResult =
        runKyuInit(
            arguments = listOf("init"),
            workingDirectory = workDirPath,
            initializedWorkDirPath = workDirPath,
        )

    private fun runKyuInit(
        arguments: List<String>,
        workingDirectory: Path,
        initializedWorkDirPath: Path,
    ): WorkDirInitializationResult {
        val result = try {
            kyuCommandRunner.run(arguments, workingDirectory)
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

        return WorkDirInitializationResult.Initialized(initializedWorkDirPath)
    }

    private fun reasonKyuCouldNotRun(failure: KyuCommandFailure): String = when (failure) {
        is KyuCommandFailure.ExecutableNotFound ->
            "${failure.message} 워크디렉토리를 만드는 일도 kyu 가 합니다 — 설치해 PATH 에 넣은 뒤 다시 시도하세요."

        is KyuCommandFailure.FailedToStart ->
            "${failure.message} 실행 권한이 있는지, 파일이 온전한지 확인하세요. " +
                "원인: ${failure.processStartFailure.message}"
    }
}
