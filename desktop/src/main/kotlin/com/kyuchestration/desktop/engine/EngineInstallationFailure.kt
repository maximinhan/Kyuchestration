package com.kyuchestration.desktop.engine

import java.io.IOException
import java.nio.file.Path

/**
 * 엔진을 놓지 못한 이유.
 *
 * 이유마다 사람이 할 수 있는 일이 다르다. 이 화면은 앱의 첫 화면이라 여기서 뭉뚱그리면 사용자는
 * 앱이 고장난 것과 자기 망이 막힌 것과 자기 플랫폼이 대상이 아닌 것을 구분할 수 없다 —
 * 셋 다 "설치 실패" 로 보인다. 그래서 종류를 타입으로 갈라 두고 각자가 자기 안내를 들고 있는다
 * (WorkDirObservationFailure 와 같은 선).
 */
sealed class EngineInstallationFailure(
    message: String,
    /** 사람이 지금 할 수 있는 일. 화면이 오류 문구 아래에 그대로 보여준다. */
    val guidance: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    /**
     * 윈도우 네이티브 JVM 에서 눌렀다.
     *
     * 바이너리를 받아 놓아도 소용이 없다. 엔진은 세션을 tmux 위에서 돌리고, tmux 는 윈도우
     * 네이티브에 없다. 받기 전에 여기서 끊어야 사용자가 다운로드를 의심하지 않는다.
     */
    class WindowsNeedsWsl : EngineInstallationFailure(
        message = "윈도우 네이티브에서는 엔진이 돌지 않습니다.",
        guidance = "WSL 안에서 앱을 띄우세요. 엔진은 세션을 tmux 위에서 돌리는데 " +
            "tmux 는 윈도우 네이티브에 없습니다.",
    )

    class PlatformNotReleased(osName: String, osArchitecture: String) : EngineInstallationFailure(
        message = "이 플랫폼용 엔진은 아직 내지 않습니다: $osName / $osArchitecture",
        guidance = "소스에서 직접 만들 수 있습니다: " +
            "go install github.com/maximinhan/Kyuchestration/cmd/kyu@latest",
    )

    class GitHubUnreachable(cause: Throwable) : EngineInstallationFailure(
        message = "GitHub 에 닿지 못했습니다.",
        guidance = "네트워크와 프록시 설정을 확인한 뒤 다시 시도하세요. 원인: ${cause.message}",
        cause = cause,
    )

    /**
     * GitHub 이 답은 했는데 200 이 아니었다.
     *
     * 상태 코드를 그대로 싣는다. 이 저장소는 공개라 여기 오는 것은 대개 레이트리밋(403)이나
     * 아직 릴리스가 없는 경우(404)인데, 둘은 기다리는 시간이 다르다.
     */
    class ReleaseRequestRejected(val httpStatusCode: Int) : EngineInstallationFailure(
        message = "GitHub 이 요청을 거절했습니다 ($httpStatusCode).",
        guidance = "403 이면 무인증 호출 한도(시간당 60 회)에 걸린 것이라 잠시 뒤 다시 됩니다. " +
            "404 면 아직 릴리스가 없는 것입니다.",
    )

    class AssetMissingFromLatestRelease(
        val assetName: String,
        val releaseTagName: String,
    ) : EngineInstallationFailure(
        message = "최신 릴리스 $releaseTagName 에 $assetName 이 없습니다.",
        guidance = "이 플랫폼 바이너리를 아직 함께 올리지 않는 릴리스입니다. " +
            "소스에서 직접 만들 수 있습니다: " +
            "go install github.com/maximinhan/Kyuchestration/cmd/kyu@latest",
    )

    class UnreadableReleaseDocument(cause: Throwable) : EngineInstallationFailure(
        message = "GitHub 이 낸 릴리스 문서를 읽지 못했습니다.",
        guidance = "잠시 뒤 다시 시도하세요. 원인: ${cause.message}",
        cause = cause,
    )

    /**
     * 받아 온 것이 `kyu version` 에 답하지 못했다.
     *
     * 받다 끊긴 파일이거나 다른 플랫폼의 바이너리다. 이 실패가 났다는 것은 그 파일이 엔진의
     * 이름을 차지하지 않았다는 뜻이기도 하다 — 검증은 자리를 바꾸기 전에 한다.
     */
    class DownloadedEngineDidNotRun(reason: String) : EngineInstallationFailure(
        message = "받아 온 엔진이 실행되지 않았습니다.",
        guidance = "받다 끊겼거나 이 머신의 것이 아닌 바이너리입니다. 다시 시도하세요. 원인: $reason",
    )

    /**
     * 앱에 동봉된 엔진을 부를 수 있는 자리로 옮기지 못했다.
     *
     * 앱이 자기 데이터 디렉토리에 파일 하나를 놓는 일이라 여기 오는 것은 대개 디스크가 찼거나 그
     * 자리에 쓸 수 없는 경우다. 화면을 세우지는 않는다 — [엔진 설치] 로 받아 오는 길이 그대로
     * 열려 있고, 그 길은 다른 디렉토리를 쓴다.
     */
    class BundledEngineNotPlaced(copyPath: Path, cause: IOException) : EngineInstallationFailure(
        message = "앱에 동봉된 엔진을 쓸 수 있는 자리에 놓지 못했습니다.",
        guidance = "$copyPath 에 쓸 수 있는지 확인하세요 (디스크 여유 · 권한). " +
            "그때까지는 [엔진 설치] 로 최신 릴리스에서 받아 쓸 수 있습니다. 원인: ${cause.message}",
        cause = cause,
    )

    /**
     * 앱에 동봉된 엔진이 `kyu version` 에 답하지 못했다.
     *
     * 이 머신의 것이 아닌 바이너리가 패키지에 들어간 경우다 — 받은 사람이 할 수 있는 일은 없고,
     * 릴리스를 만드는 쪽이 고칠 일이다. 그동안은 [엔진 설치] 로 받아 쓸 수 있다.
     */
    class BundledEngineDidNotRun(reason: String) : EngineInstallationFailure(
        message = "앱에 동봉된 엔진이 실행되지 않았습니다.",
        guidance = "이 머신의 것이 아닌 바이너리가 들어 있는 설치 패키지입니다. " +
            "[엔진 설치] 로 최신 릴리스에서 받으면 그대로 쓸 수 있습니다. 원인: $reason",
    )
}
