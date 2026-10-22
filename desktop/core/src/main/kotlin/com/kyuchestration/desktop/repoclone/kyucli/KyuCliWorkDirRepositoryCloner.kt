package com.kyuchestration.desktop.repoclone.kyucli

import com.kyuchestration.desktop.kyu.KyuCommandRunner
import com.kyuchestration.desktop.repoclone.CloneOutcome
import com.kyuchestration.desktop.repoclone.WorkDirRepositoryCloner
import java.nio.file.Path

/**
 * kyu CLI 를 엔진으로 불러 고른 레포를 워크디렉토리에 받아 온다.
 *
 * 받을 자리를 인자가 아니라 작업 디렉토리로 넘긴다. kyu clone 은 자기가 실행된 자리를 워크디렉토리로
 * 보므로(clone.go 의 currentWorkDir), 사람이 터미널에서 `cd <워크디렉토리> && kyu clone` 이라고
 * 치는 것과 같은 실행이 된다 — 앱에서 받은 레포와 손으로 받은 레포가 같은 규칙 아래 놓인다.
 */
class KyuCliWorkDirRepositoryCloner(private val kyuCommandRunner: KyuCommandRunner) : WorkDirRepositoryCloner {

    override fun cloneInto(
        workDirPath: Path,
        profileName: String,
        repositoryFullNames: List<String>,
    ): CloneOutcome {
        val arguments = listOf("clone", "--profile", profileName) +
            repositoryFullNames.flatMap { listOf("--repo", it) } +
            "--json"

        val result = kyuCommandRunner.runCloneStep(arguments, workingDirectory = workDirPath)

        // 종료 코드만으로 갈리지 않는 자리다. 실패한 레포가 하나라도 있으면 kyu 는 종료 코드 1 로
        // 끝나지만 문서는 먼저 낸다(clone_json.go) — 어느 레포가 왜 실패했는지는 그 문서에만
        // 있으므로, 0 이 아닌 것을 곧바로 실패로 넘기면 화면이 사용자에게 아무것도 설명하지 못한다.
        //
        // 시작하지도 못한 실패(프로필이 없다, 레포 목록을 받지 못했다)는 문서가 나오지 않는 것으로
        // 갈린다. 그것은 "전부 실패" 와 다른 사실이라 결과 목록으로 옮길 수 없다.
        if (result.standardOutput.isBlank()) {
            result.failIfKyuRefused()
        }

        return readCloneStepDocument(result.standardOutput, ::parseKyuCloneOutput)
    }
}
