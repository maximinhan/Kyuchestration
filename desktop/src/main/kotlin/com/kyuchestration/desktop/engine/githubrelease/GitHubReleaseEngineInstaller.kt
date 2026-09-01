package com.kyuchestration.desktop.engine.githubrelease

import com.kyuchestration.desktop.diagnostics.DiagnosticLog
import com.kyuchestration.desktop.engine.EngineInstallationFailure
import com.kyuchestration.desktop.engine.EngineInstaller
import com.kyuchestration.desktop.engine.reasonEngineDoesNotRun
import com.kyuchestration.desktop.kyu.managedEngineDirectory
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration
import kotlin.io.path.deleteIfExists
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * GitHub 의 최신 릴리스에서 이 머신에 맞는 엔진을 받아 앱이 관리하는 자리에 놓는다.
 *
 * 인증하지 않는다. 저장소가 공개라 최신 릴리스 조회도 자산 내려받기도 토큰 없이 통하고, 앱이
 * 토큰을 요구하는 순간 "앱이 시작점" 이라는 말이 반쯤만 남는다 — 토큰을 받으려면 브라우저와
 * GitHub 설정 화면을 먼저 지나야 한다.
 *
 * install.sh 와 같은 일을 하지만 코드를 나눠 쓰지 않는다. 저쪽은 셸 하나로 끝나야 하는 스크립트라
 * jq 없이 awk 로 JSON 을 훑고, 이쪽은 이미 있는 직렬화기로 읽는다. 공유할 수 있는 것은 자산
 * 이름 규칙 하나뿐인데 그것은 릴리스 워크플로가 정한다.
 *
 * @param engineDirectory 받아 둘 자리. 검사가 임시 디렉토리를 겨누기 위한 이음매다.
 * @param gitHubApiBaseUrl GitHub API 의 뿌리. 검사가 로컬 스텁 서버를 겨누기 위한 이음매다.
 * @param osName · @param osArchitecture 이 머신의 철자. 검사가 다른 플랫폼인 척하기 위한 이음매다.
 */
class GitHubReleaseEngineInstaller(
    private val engineDirectory: Path = managedEngineDirectory(),
    private val gitHubApiBaseUrl: String = GITHUB_API_BASE_URL,
    private val osName: String = System.getProperty("os.name").orEmpty(),
    private val osArchitecture: String = System.getProperty("os.arch").orEmpty(),
    /**
     * 받아 온 엔진이 돌지 않을 때 그 사실을 남길 자리.
     *
     * 여기서 걸린 실행은 설치 화면에 멈춰 서고 그 뒤로 엔진을 부르는 일이 하나도 없다 —
     * 남기지 않으면 그 실행에 대해 파일에 적히는 것은 "앱이 시작했다" 한 줄뿐이다.
     */
    private val diagnosticLog: DiagnosticLog = DiagnosticLog.Discarding,
) : EngineInstaller {

    override fun installEngine() {
        val assetName = when (val target = engineReleaseTargetFor(osName, osArchitecture)) {
            is EngineReleaseTarget.ReleaseAsset -> target.assetName
            EngineReleaseTarget.WindowsNeedsWsl -> throw EngineInstallationFailure.WindowsNeedsWsl()
            is EngineReleaseTarget.PlatformNotReleased ->
                throw EngineInstallationFailure.PlatformNotReleased(target.osName, target.osArchitecture)
        }

        val latestRelease = fetchLatestRelease(gitHubApiBaseUrl)
        val assetDownloadUrl = latestRelease.assetDownloadUrls[assetName]
            ?: throw EngineInstallationFailure.AssetMissingFromLatestRelease(assetName, latestRelease.tagName)

        installDownloadedEngine(assetDownloadUrl)
    }

    /**
     * 받아서 · 실행 권한을 주고 · 정말 도는지 확인한 뒤에야 엔진의 이름으로 옮긴다.
     *
     * 순서가 이 함수의 전부다.
     *
     * 옆자리에 받는 이유: 엔진 이름에 곧바로 받으면 망이 끊긴 순간의 반쪽짜리 파일이 kyu 라는
     * 이름을 차지한다. 탐색은 "실행 가능한 파일이 있는가" 만 보므로 그것을 엔진으로 여기고,
     * 설치 화면은 다시 뜨지 않는다. 같은 디렉토리 안에서 옮기면 이름만 바꾸는 원자적 연산이라
     * 그 중간 상태가 아예 생기지 않는다.
     *
     * 옮기기 전에 검증하는 이유도 같다. 다른 플랫폼의 바이너리는 실행 가능한 파일이긴 하므로,
     * 자리를 먼저 내주면 돌지 않는 엔진이 설치된 것으로 남는다.
     */
    private fun installDownloadedEngine(assetDownloadUrl: String) {
        Files.createDirectories(engineDirectory)

        // 임시 파일도 관리 디렉토리 안에 만든다. /tmp 에 받으면 파일 시스템이 달라 옮기기가
        // 이름 바꾸기가 아닌 복사가 되고, 원자성이 사라진다.
        val downloadPath = Files.createTempFile(engineDirectory, DOWNLOAD_FILE_PREFIX, "")
        try {
            downloadInto(assetDownloadUrl, downloadPath)
            Files.setPosixFilePermissions(downloadPath, PosixFilePermissions.fromString("rwxr-xr-x"))
            // 옮기기 전에 확인한다. 다른 플랫폼의 바이너리도 실행 가능한 파일이긴 하므로, 자리를
            // 먼저 내주면 돌지 않는 엔진이 설치된 것으로 남는다.
            reasonEngineDoesNotRun(downloadPath, diagnosticLog)?.let {
                throw EngineInstallationFailure.DownloadedEngineDidNotRun(it)
            }

            Files.move(
                downloadPath,
                engineDirectory.resolve(ENGINE_EXECUTABLE_NAME),
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (failure: Throwable) {
            // 실패로 끝나는 길에서는 받다 만 것을 남기지 않는다. 남기면 관리 디렉토리가 시도할
            // 때마다 커지고, 그 파일들은 아무도 다시 보지 않는다.
            try {
                downloadPath.deleteIfExists()
            } catch (cleanupFailure: IOException) {
                // 치우다 실패한 것으로 진짜 이유를 덮지 않는다. 화면에 떠야 하는 것은 "왜 설치가
                // 안 됐는가" 이지 "임시 파일을 왜 못 지웠는가" 가 아니다.
                failure.addSuppressed(cleanupFailure)
            }
            throw failure
        }
    }

    private fun downloadInto(assetDownloadUrl: String, downloadPath: Path) {
        val response = sendGetRequest(
            url = assetDownloadUrl,
            acceptHeader = "application/octet-stream",
            // 12 MB 남짓을 받는 일이라 문서 조회보다 넉넉하게 둔다. 시간 제한을 아예 두지 않으면
            // 응답이 끊긴 연결에서 설치 화면이 영영 "받는 중" 으로 남는다.
            timeout = ASSET_DOWNLOAD_TIMEOUT,
            bodyHandler = HttpResponse.BodyHandlers.ofFile(downloadPath),
        )

        if (response.statusCode() != HTTP_OK) {
            throw EngineInstallationFailure.ReleaseRequestRejected(response.statusCode())
        }
    }
}

/**
 * 최신 릴리스의 태그와 자산 목록.
 *
 * 자산은 이름 → 내려받을 주소로 접어 둔다. 부르는 쪽이 아는 것은 찾는 이름 하나뿐이라, 목록을
 * 그대로 넘기면 그 쪽에서 다시 이름으로 훑게 된다.
 */
internal data class LatestRelease(
    val tagName: String,
    val assetDownloadUrls: Map<String, String>,
)

/**
 * 최신 릴리스 문서를 무인증으로 읽는다.
 *
 * 클래스 밖에 둔 이유는 이것만 따로 시험하기 위해서다 — 공개 저장소가 정말 토큰 없이 자산
 * 목록을 답하는지는 진짜 GitHub 에 물어야만 알 수 있고, 그 검증이 12 MB 를 받을 이유는 없다.
 *
 * @throws EngineInstallationFailure GitHub 에 닿지 못했거나, 거절당했거나, 문서를 읽지 못했을 때.
 */
internal fun fetchLatestRelease(gitHubApiBaseUrl: String = GITHUB_API_BASE_URL): LatestRelease {
    val response = sendGetRequest(
        url = "$gitHubApiBaseUrl/repos/$ENGINE_REPOSITORY/releases/latest",
        acceptHeader = "application/vnd.github+json",
        timeout = RELEASE_DOCUMENT_TIMEOUT,
        bodyHandler = HttpResponse.BodyHandlers.ofString(),
    )

    if (response.statusCode() != HTTP_OK) {
        throw EngineInstallationFailure.ReleaseRequestRejected(response.statusCode())
    }

    val document = try {
        gitHubJson.decodeFromString(GitHubReleaseDocument.serializer(), response.body())
    } catch (failure: SerializationException) {
        throw EngineInstallationFailure.UnreadableReleaseDocument(failure)
    }

    return LatestRelease(
        tagName = document.tagName,
        assetDownloadUrls = document.assets.associate { it.name to it.browserDownloadUrl },
    )
}

/**
 * GET 한 번. 리다이렉트를 따라간다 — 자산의 browser_download_url 은 실제 바이트가 있는
 * 저장소로 한 번 더 넘긴다.
 */
private fun <T> sendGetRequest(
    url: String,
    acceptHeader: String,
    timeout: Duration,
    bodyHandler: HttpResponse.BodyHandler<T>,
): HttpResponse<T> {
    val request = HttpRequest.newBuilder(URI.create(url))
        .header("Accept", acceptHeader)
        // 판을 못 박는다. 적지 않으면 GitHub 이 그때그때의 기본 판으로 답하므로, 문서 모양이
        // 바뀌는 날이 우리가 고르는 날이 아니라 GitHub 이 정하는 날이 된다.
        .header("X-GitHub-Api-Version", "2022-11-28")
        .timeout(timeout)
        .GET()
        .build()

    return HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(CONNECT_TIMEOUT)
        .build()
        .use { httpClient ->
            try {
                httpClient.send(request, bodyHandler)
            } catch (failure: IOException) {
                throw EngineInstallationFailure.GitHubUnreachable(failure)
            } catch (failure: InterruptedException) {
                // 기다리는 스레드가 끊겼다. 끊긴 사실을 스레드에 돌려놓지 않으면 그 위에서 도는
                // 코루틴이 취소된 줄 모른 채 계속 간다.
                Thread.currentThread().interrupt()
                throw EngineInstallationFailure.GitHubUnreachable(failure)
            }
        }
}

@Serializable
private data class GitHubReleaseDocument(
    @SerialName("tag_name") val tagName: String,
    val assets: List<GitHubReleaseAssetDocument>,
)

@Serializable
private data class GitHubReleaseAssetDocument(
    val name: String,
    @SerialName("browser_download_url") val browserDownloadUrl: String,
)

private val gitHubJson = Json {
    // 릴리스 문서에서 우리가 쓰는 것은 태그 이름과 자산의 이름·주소뿐이다. 나머지 수십 개
    // 필드에서 멈추면 GitHub 이 한 줄 더 내는 날 설치가 통째로 막힌다.
    ignoreUnknownKeys = true
}

internal const val GITHUB_API_BASE_URL = "https://api.github.com"

private const val ENGINE_REPOSITORY = "maximinhan/Kyuchestration"
private const val ENGINE_EXECUTABLE_NAME = "kyu"
private const val DOWNLOAD_FILE_PREFIX = "kyu.download"
private const val HTTP_OK = 200
private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(15)
private val RELEASE_DOCUMENT_TIMEOUT: Duration = Duration.ofSeconds(30)
private val ASSET_DOWNLOAD_TIMEOUT: Duration = Duration.ofMinutes(5)
