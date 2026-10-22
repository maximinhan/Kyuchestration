package com.kyuchestration.desktop.repoclone.kyucli

import com.kyuchestration.desktop.kyu.KyuCommandRunner
import com.kyuchestration.desktop.repoclone.GitHubRepositoryCatalog
import com.kyuchestration.desktop.repoclone.RemoteRepository
import com.kyuchestration.desktop.repoclone.RepositoryOwner

/**
 * kyu CLI 를 엔진으로 불러 GitHub 의 계정과 레포를 묻는다.
 *
 * 앱이 GitHub API 에 직접 붙지 않는다. 붙으려면 토큰을 이 프로세스로 꺼내 와야 하고, 그 순간
 * "앱은 토큰을 들고 있지 않다" 가 깨진다 — 페이지를 넘겨 가며 목록을 모으는 규칙이 kyu 와
 * 갈라지는 것은 그다음 문제다.
 */
class KyuCliGitHubRepositoryCatalog(private val kyuCommandRunner: KyuCommandRunner) : GitHubRepositoryCatalog {

    override fun listOwners(profileName: String): List<RepositoryOwner> {
        val result = kyuCommandRunner.runCloneStep(listOf("repos", "owners", "--profile", profileName, "--json"))
        result.failIfKyuRefused()
        return readCloneStepDocument(result.standardOutput, ::parseKyuReposOwnersOutput)
    }

    override fun listRepositories(profileName: String, ownerLogin: String): List<RemoteRepository> {
        val result = kyuCommandRunner.runCloneStep(
            listOf("repos", "list", "--profile", profileName, "--owner", ownerLogin, "--json"),
        )
        result.failIfKyuRefused()
        return readCloneStepDocument(result.standardOutput, ::parseKyuReposListOutput)
    }
}
