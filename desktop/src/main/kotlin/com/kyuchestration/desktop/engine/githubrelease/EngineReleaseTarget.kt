package com.kyuchestration.desktop.engine.githubrelease

/**
 * 이 머신이 릴리스에서 받아야 할 것.
 *
 * 받을 수 없는 두 갈래를 하나로 뭉치지 않는다. 윈도우 네이티브에서 없는 것은 바이너리가 아니라
 * 엔진이 목록을 낼 때 묻는 tmux 이므로, 사람이 할 일이 "다른 아키텍처를 기다린다" 가 아니라
 * "WSL 안에서 앱을 띄운다" 다.
 * 한 갈래로 묶으면 그 사람에게 기다리라고 말하게 된다.
 */
sealed interface EngineReleaseTarget {

    /** 릴리스 워크플로가 붙이는 자산 이름 그대로(kyu_<os>_<arch>). */
    data class ReleaseAsset(val assetName: String) : EngineReleaseTarget

    data object WindowsNeedsWsl : EngineReleaseTarget

    data class PlatformNotReleased(val osName: String, val osArchitecture: String) : EngineReleaseTarget
}

/**
 * JVM 이 답하는 철자(os.name · os.arch)를 Go 가 붙인 자산 이름의 철자로 옮긴다.
 *
 * 자산 이름은 GOOS/GOARCH 를 그대로 쓴다(.github/workflows/release.yml 의 크로스 컴파일 목록).
 * JVM 은 같은 것을 다른 이름으로 부르므로 — 맥은 "Mac OS X", x86-64 는 리눅스에서 amd64 이고
 * 맥에서 x86_64 다 — 그 사이를 옮기는 표가 필요하다.
 *
 * 릴리스가 실제로 내는 조합을 여기에 다시 적지 않는다. 지금 릴리스는 linux/amd64 ·
 * darwin/amd64 · darwin/arm64 셋만 내지만, 그 목록은 release.yml 의 것이고 이 앱은 최신 릴리스가
 * 답한 자산 목록에서 이름을 찾는다. 여기에 옮겨 적으면 릴리스에 대상이 하나 늘 때 앱이 그것을
 * 받을 수 있는데도 못 박힌 목록에 걸려 거절한다.
 */
internal fun engineReleaseTargetFor(osName: String, osArchitecture: String): EngineReleaseTarget {
    if (osName.startsWith("Windows", ignoreCase = true)) {
        return EngineReleaseTarget.WindowsNeedsWsl
    }

    val releaseOsName = when {
        osName.startsWith("Mac", ignoreCase = true) -> "darwin"
        osName.startsWith("Darwin", ignoreCase = true) -> "darwin"
        osName.startsWith("Linux", ignoreCase = true) -> "linux"
        else -> return EngineReleaseTarget.PlatformNotReleased(osName, osArchitecture)
    }

    val releaseArchitecture = when (osArchitecture) {
        "amd64", "x86_64" -> "amd64"
        "aarch64", "arm64" -> "arm64"
        else -> return EngineReleaseTarget.PlatformNotReleased(osName, osArchitecture)
    }

    return EngineReleaseTarget.ReleaseAsset("kyu_${releaseOsName}_$releaseArchitecture")
}
