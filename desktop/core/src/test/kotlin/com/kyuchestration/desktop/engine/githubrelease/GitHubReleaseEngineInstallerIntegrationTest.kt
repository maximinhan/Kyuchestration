package com.kyuchestration.desktop.engine.githubrelease

import com.kyuchestration.desktop.engine.EngineInstallationFailure
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.abort

/**
 * 진짜 GitHub 에 무인증으로 물어, 이 기능이 서 있는 전제를 확인한다.
 *
 * 그 전제는 스텁으로는 확인할 수 없다 — 스텁은 우리가 시킨 대로 답하므로, "저장소가 공개라
 * 토큰 없이도 최신 릴리스가 보인다" 를 시험한 것이 되지 않는다. 저장소가 다시 프라이빗이 되면
 * 다른 검사는 전부 통과한 채 이 검사만 빨개져야 한다.
 *
 * 자산 이름을 목록에 못 박지 않고 포함만 본다. 릴리스에 데스크톱 설치 패키지 같은 것이 더
 * 붙어도 이 검사가 깨지지 않아야 한다 — 여기서 보는 것은 "엔진 자산이 있는가" 이지
 * "릴리스에 무엇이 몇 개 붙는가" 가 아니다.
 */
class GitHubReleaseEngineInstallerIntegrationTest {

    @Test
    fun `공개 저장소의 최신 릴리스는 토큰 없이 읽힌다`() {
        val latestRelease = fetchLatestReleaseOrSkip()

        assertTrue(latestRelease.tagName.startsWith("v"), "태그 이름: ${latestRelease.tagName}")
        assertTrue(
            latestRelease.assetDownloadUrls.keys.containsAll(RELEASED_ENGINE_ASSET_NAMES),
            "최신 릴리스 ${latestRelease.tagName} 의 자산: ${latestRelease.assetDownloadUrls.keys}",
        )
    }

    @Test
    fun `엔진 자산의 내려받을 주소는 릴리스 다운로드 주소다`() {
        val latestRelease = fetchLatestReleaseOrSkip()

        RELEASED_ENGINE_ASSET_NAMES.forEach { assetName ->
            val downloadUrl = latestRelease.assetDownloadUrls.getValue(assetName)

            // 앱은 이 주소를 인증 없이 그대로 부른다. API 자산 주소(.../releases/assets/<숫자>)로
            // 바뀌면 Accept 헤더 없이는 바이트 대신 JSON 이 온다 — 그때 받은 파일은 실행되지 않고,
            // 화면에는 "받아 온 엔진이 실행되지 않았습니다" 만 뜬다.
            assertTrue(
                downloadUrl.startsWith("https://github.com/maximinhan/Kyuchestration/releases/download/"),
                "$assetName 의 주소: $downloadUrl",
            )
        }
    }

    /**
     * 망이 막혔거나 무인증 한도에 걸린 것으로 빌드를 빨갛게 만들지 않는다 — 그것은 코드가 아니라
     * 그 순간의 사정이다. 문서를 읽지 못한 것(UnreadableReleaseDocument)은 그대로 실패로 둔다.
     * 그쪽은 GitHub 이 답한 모양이 우리가 아는 모양과 달라졌다는 뜻이라 사람이 봐야 한다.
     */
    private fun fetchLatestReleaseOrSkip(): LatestRelease = try {
        fetchLatestRelease()
    } catch (failure: EngineInstallationFailure.GitHubUnreachable) {
        abort("GitHub 에 닿지 못해 건너뜁니다 (${failure.message})")
    } catch (failure: EngineInstallationFailure.ReleaseRequestRejected) {
        abort("GitHub 이 ${failure.httpStatusCode} 로 거절해 건너뜁니다 (무인증 한도일 수 있습니다)")
    }

    private companion object {
        /** release.yml 의 크로스 컴파일 목록이 내는 것. */
        val RELEASED_ENGINE_ASSET_NAMES = setOf("kyu_linux_amd64", "kyu_darwin_amd64", "kyu_darwin_arm64")
    }
}
