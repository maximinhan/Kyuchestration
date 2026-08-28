package com.kyuchestration.desktop.engine.githubrelease

import com.kyuchestration.desktop.engine.EngineInstallationFailure
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.isExecutable
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 진짜 HTTP 왕복과 진짜 파일과 진짜 프로세스로 설치 한 판을 지난다. 바깥 세계는 JDK 에 들어 있는
 * 스텁 서버 하나뿐이다 — 네트워크가 없는 자리에서도 도는데, 자바의 HTTP 클라이언트를 mock 으로
 * 갈아 끼우면 리다이렉트도 상태 코드도 파일 쓰기도 시험한 것이 없어진다.
 *
 * "받아 온 엔진" 자리에는 sh 스크립트를 놓는다. 검증이 하는 일은 그것을 실행해 `version` 에
 * 답하는지 보는 것뿐이라, 진짜 Go 바이너리 없이도 성공과 실패를 둘 다 만들 수 있다.
 */
class GitHubReleaseEngineInstallerTest {

    private lateinit var gitHubStub: HttpServer
    private lateinit var engineDirectory: Path
    private lateinit var verificationArgumentsPath: Path

    /** 스텁이 /repos/.../releases/latest 에 낼 것. 검사마다 바꿔 끼운다. */
    private var latestReleaseResponse: StubResponse = StubResponse(200, "")

    /** 스텁이 자산 주소에 낼 것. */
    private var assetResponse: StubResponse = StubResponse(200, "")

    @BeforeTest
    fun startStubAndPrepareDirectory() {
        engineDirectory = createTempDirectory("engine-directory")
        verificationArgumentsPath = engineDirectory.resolve("verification-arguments.txt")

        gitHubStub = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        gitHubStub.createContext("/repos/$ENGINE_REPOSITORY/releases/latest") { it.respondWith(latestReleaseResponse) }
        gitHubStub.createContext(ASSET_PATH) { it.respondWith(assetResponse) }
        // 진짜 browser_download_url 은 실제 바이트가 있는 저장소로 한 번 더 넘긴다. 스텁이 곧바로
        // 답해 버리면 그 한 걸음을 시험한 적이 없어진다.
        gitHubStub.createContext(REDIRECTING_ASSET_PATH) { exchange ->
            exchange.responseHeaders.add("Location", assetUrl())
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        gitHubStub.start()
    }

    @AfterTest
    fun stopStub() {
        gitHubStub.stop(0)
    }

    @Test
    fun `최신 릴리스에서 받아 실행 가능한 kyu 로 놓는다`() {
        latestReleaseResponse = releaseDocument("v9.9.9", "kyu_linux_amd64" to assetUrl())
        assetResponse = StubResponse(200, engineScriptAnsweringVersion())

        installer().installEngine()

        val installedPath = engineDirectory.resolve("kyu")
        assertTrue(installedPath.isExecutable(), "받아 둔 엔진은 실행 가능해야 한다")
        assertEquals(engineScriptAnsweringVersion(), installedPath.readText())
    }

    @Test
    fun `놓기 전에 kyu version 으로 정말 도는지 물어본다`() {
        latestReleaseResponse = releaseDocument("v9.9.9", "kyu_linux_amd64" to assetUrl())
        assetResponse = StubResponse(200, engineScriptAnsweringVersion())

        installer().installEngine()

        assertEquals("version", verificationArgumentsPath.readText().trim())
    }

    @Test
    fun `받다 만 것은 엔진의 이름을 차지하지 못한다`() {
        latestReleaseResponse = releaseDocument("v9.9.9", "kyu_linux_amd64" to assetUrl())
        assetResponse = StubResponse(200, engineScriptFailing())

        assertFailsWith<EngineInstallationFailure.DownloadedEngineDidNotRun> { installer().installEngine() }

        // 자리를 먼저 내주면 탐색이 그것을 엔진으로 여기고, 설치 화면은 다시 뜨지 않는다.
        assertFalse(engineDirectory.resolve("kyu").exists(), "돌지 않는 바이너리가 kyu 자리를 차지했다")
        assertNoDownloadLeftovers()
    }

    @Test
    fun `자산 주소가 다른 곳으로 넘기면 따라간다`() {
        latestReleaseResponse = releaseDocument(
            "v9.9.9",
            "kyu_linux_amd64" to "http://127.0.0.1:${gitHubStub.address.port}$REDIRECTING_ASSET_PATH",
        )
        assetResponse = StubResponse(200, engineScriptAnsweringVersion())

        installer().installEngine()

        assertEquals(engineScriptAnsweringVersion(), engineDirectory.resolve("kyu").readText())
    }

    @Test
    fun `이미 놓여 있던 엔진은 새로 받은 것으로 바뀐다`() {
        engineDirectory.resolve("kyu").writeText("옛 엔진")
        latestReleaseResponse = releaseDocument("v9.9.9", "kyu_linux_amd64" to assetUrl())
        assetResponse = StubResponse(200, engineScriptAnsweringVersion())

        installer().installEngine()

        assertEquals(engineScriptAnsweringVersion(), engineDirectory.resolve("kyu").readText())
        assertNoDownloadLeftovers()
    }

    @Test
    fun `최신 릴리스에 이 플랫폼 자산이 없으면 그 사실을 그대로 말한다`() {
        latestReleaseResponse = releaseDocument("v9.9.9", "kyu_darwin_arm64" to assetUrl())

        val failure = assertFailsWith<EngineInstallationFailure.AssetMissingFromLatestRelease> {
            installer().installEngine()
        }

        assertEquals("kyu_linux_amd64", failure.assetName)
        assertEquals("v9.9.9", failure.releaseTagName)
    }

    @Test
    fun `GitHub 이 거절하면 상태 코드를 들고 있는다`() {
        latestReleaseResponse = StubResponse(403, """{"message":"API rate limit exceeded"}""")

        val failure = assertFailsWith<EngineInstallationFailure.ReleaseRequestRejected> {
            installer().installEngine()
        }

        // 403 과 404 는 사람이 기다릴 시간이 다르다. 하나로 뭉치면 안내가 둘 중 하나에는 거짓이 된다.
        assertEquals(403, failure.httpStatusCode)
    }

    @Test
    fun `자산을 받다 거절당하면 받다 만 것을 남기지 않는다`() {
        latestReleaseResponse = releaseDocument("v9.9.9", "kyu_linux_amd64" to assetUrl())
        assetResponse = StubResponse(404, "not found")

        assertFailsWith<EngineInstallationFailure.ReleaseRequestRejected> { installer().installEngine() }

        assertNoDownloadLeftovers()
    }

    @Test
    fun `릴리스 문서를 읽지 못하면 네트워크 실패와 갈라 말한다`() {
        latestReleaseResponse = StubResponse(200, "이것은 JSON 이 아니다")

        assertFailsWith<EngineInstallationFailure.UnreadableReleaseDocument> { installer().installEngine() }
    }

    @Test
    fun `GitHub 에 닿지 못하면 네트워크 실패다`() {
        val closedPortBaseUrl = "http://127.0.0.1:${findClosedPort()}"

        assertFailsWith<EngineInstallationFailure.GitHubUnreachable> {
            installer(gitHubApiBaseUrl = closedPortBaseUrl).installEngine()
        }
    }

    @Test
    fun `윈도우 네이티브에서는 받기 전에 거절한다`() {
        latestReleaseResponse = releaseDocument("v9.9.9", "kyu_windows_amd64" to assetUrl())

        assertFailsWith<EngineInstallationFailure.WindowsNeedsWsl> {
            installer(osName = "Windows 11").installEngine()
        }

        // 자산을 하나도 받지 않았다는 것이 요점이다. tmux 가 없는 자리에서는 바이너리가 있어도
        // 세션이 뜨지 않으므로, 받고 나서 실패하면 사용자는 다운로드를 의심하게 된다.
        assertNoDownloadLeftovers()
    }

    @Test
    fun `릴리스가 내지 않는 플랫폼도 받기 전에 거절한다`() {
        assertFailsWith<EngineInstallationFailure.PlatformNotReleased> {
            installer(osArchitecture = "riscv64").installEngine()
        }
    }

    private fun installer(
        gitHubApiBaseUrl: String = "http://127.0.0.1:${gitHubStub.address.port}",
        osName: String = "Linux",
        osArchitecture: String = "amd64",
    ) = GitHubReleaseEngineInstaller(
        engineDirectory = engineDirectory,
        gitHubApiBaseUrl = gitHubApiBaseUrl,
        osName = osName,
        osArchitecture = osArchitecture,
    )

    private fun assetUrl(): String = "http://127.0.0.1:${gitHubStub.address.port}$ASSET_PATH"

    private fun releaseDocument(tagName: String, vararg assets: Pair<String, String>): StubResponse {
        val assetsJson = assets.joinToString(",") { (name, downloadUrl) ->
            // GitHub 이 내는 것보다 필드가 적다. 모르는 필드를 흘려보내는 것은 진짜 문서로 확인할
            // 일이고(GitHubReleaseEngineInstallerIntegrationTest), 여기서 보는 것은 고르는 규칙이다.
            """{"name":"$name","browser_download_url":"$downloadUrl"}"""
        }
        return StubResponse(200, """{"tag_name":"$tagName","assets":[$assetsJson]}""")
    }

    /**
     * `version` 을 물으면 0 으로 끝나면서, 무엇을 물었는지 파일에 적어 두는 가짜 엔진.
     *
     * 인자를 절대 경로에 적는다. 검증은 작업 디렉토리를 정해 주지 않아 이 앱의 자리를 그대로
     * 물려받으므로, 상대 경로로 적으면 Gradle 프로젝트 안에 파일이 떨어진다.
     */
    private fun engineScriptAnsweringVersion(): String = """
        #!/bin/sh
        printf '%s\n' "${'$'}@" > $verificationArgumentsPath
        echo "kyu v9.9.9"
    """.trimIndent()

    private fun engineScriptFailing(): String = """
        #!/bin/sh
        echo "이 바이너리는 이 머신의 것이 아닙니다" >&2
        exit 1
    """.trimIndent()

    private fun assertNoDownloadLeftovers() {
        val leftovers = engineDirectory.listDirectoryEntries("kyu.download*")

        assertEquals(emptyList(), leftovers, "받다 만 파일이 관리 디렉토리에 남았다")
    }

    /**
     * 아무도 듣고 있지 않은 포트. 열었다 곧바로 닫아 그 번호를 얻는다 — 상수로 못 박으면 그 포트를
     * 쓰는 머신에서 이 검사가 이유 없이 다른 것을 시험하게 된다.
     */
    private fun findClosedPort(): Int = ServerSocket(0).use { it.localPort }

    private fun HttpExchange.respondWith(stubResponse: StubResponse) {
        val body = stubResponse.body.toByteArray(Charsets.UTF_8)
        // 길이 0 을 그대로 넘기면 HttpServer 는 "길이를 모르는 본문" 으로 읽어 청크 응답을 연다.
        // 본문이 없다는 뜻은 -1 이다.
        sendResponseHeaders(stubResponse.statusCode, if (body.isEmpty()) -1L else body.size.toLong())
        responseBody.use { it.write(body) }
    }

    private data class StubResponse(val statusCode: Int, val body: String)

    private companion object {
        const val ENGINE_REPOSITORY = "maximinhan/Kyuchestration"
        const val ASSET_PATH = "/download/kyu_linux_amd64"
        const val REDIRECTING_ASSET_PATH = "/releases/download/kyu_linux_amd64"
    }
}
