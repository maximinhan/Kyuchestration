package com.kyuchestration.desktop.mirror

import java.nio.file.Path

/**
 * 화면이 코어를 어디서 띄우는가(core-ui-split-design.md 6.12).
 *
 * 화면이 코어에 닿는 길에서 OS 가 갈리는 것은 이 타입 하나다(원칙 4 · 20). 띄운 뒤의 stdio · 틀 · 거울([CoreChildProcess] ·
 * [CoreMirror])은 코어가 같은 머신에 있는지 WSL 너머에 있는지 모른다.
 */
sealed interface CorePlace {

    /**
     * 맥 · 리눅스 — 같은 머신의 자식 프로세스.
     *
     * @param coreLaunchCommand 코어를 `serve --stdio` 로 띄우는 명령. 무엇으로 띄우는지는 이 프로그램을 띄운 쪽(app 모듈)이 안다 —
     *   설치 패키지에서는 앱의 실행기, 소스에서는 같은 클래스패스의 java 다.
     */
    data class SameMachine(val coreLaunchCommand: List<String>) : CorePlace

    /**
     * 윈도우 — 코어는 WSL 배포판 안에 설치되어 있다(6.16). 윈도우의 화면은 `wsl.exe` 의 stdio 로 그 코어에 닿는다.
     *
     * @param wslExecutable `wsl.exe` 의 절대 경로. 사용자의 PATH 에 다른 `wsl.exe`(옛 `bash.exe` 시절 래퍼 등)가 앞에 있어도
     *   갈리지 않게 이름으로 찾지 않는다.
     */
    data class WslDistribution(val wslExecutable: Path, val distributionName: String) : CorePlace
}

/**
 * 화면의 경로를 코어의 경로로 옮길 때 쓸 WSL 배포판. 코어를 WSL 너머에 띄웠을 때만 있다(core-ui-split-design.md 6.9 의 3 단계 정정).
 *
 * 코어가 인사 답에 말한 배포판이 먼저다 — 코어가 사는 자리의 사실이다. 말하지 않으면(그 배포판에 `WSL_DISTRO_NAME` 이 없다) 화면이
 * `-d` 로 고른 배포판이다. 비워 두면 고른 `\\wsl.localhost\…` 가 옮겨지지도 거절되지도 않고 그대로 코어에 간다. 같은 머신의
 * 코어(맥 · 리눅스 · WSLg 화면)는 화면과 같은 OS 를 보므로 옮길 것이 없다 — WSLg 화면도 코어에게서 배포판 이름을 듣지만 그렇다.
 */
fun CorePlace.wslDistributionAcrossScreen(coreSaidWslDistribution: String?): String? = when (this) {
    is CorePlace.SameMachine -> null
    is CorePlace.WslDistribution -> coreSaidWslDistribution ?: distributionName
}

/**
 * [CorePlace] 의 코어를 띄울 프로세스.
 *
 * 셸을 거치지 않는다. PowerShell · `cmd.exe` 는 그 사이에서 인코딩과 줄 끝을 고친다(설계 3.1 (나) — 한국어가 `?` 로, 줄 끝에 `\r`).
 */
internal fun CorePlace.coreProcessBuilder(): ProcessBuilder = when (this) {
    is CorePlace.SameMachine -> ProcessBuilder(coreLaunchCommand)
    is CorePlace.WslDistribution -> wslProcessBuilder(
        wslExecutable,
        // 화면은 리눅스 홈을 모르고 `wsl.exe -e` 의 PATH 에는 `~/.local/bin` 이 없다(3.7). 셸 없이 홈의 실행 파일에 닿는 길이
        // `--cd ~` 와 홈 기준 상대 경로다. 인자는 따옴표가 필요 없는 낱말만 둔다 — 윈도우 명령줄에서 큰따옴표가 사라진다(3.7).
        listOf("-d", distributionName, "--cd", "~", "-e", WSL_CORE_EXECUTABLE_FROM_HOME, "serve", "--stdio"),
    )
}

/**
 * `wsl.exe` 를 띄울 프로세스. 코어를 띄울 때도, 배포판 목록을 물을 때도 이 길을 지난다.
 *
 * [WSL_UTF8_VARIABLE] 를 켠다. 켜지 않으면 `wsl.exe` 가 스스로 쓰는 글(배포판 목록 · "배포판이 없습니다" 같은 오류)이 BOM 없는
 * UTF-16LE 로 온다(3.4). 리눅스 쪽 프로그램의 stdio 는 이 값과 무관하게 바이트 그대로 간다(3.1).
 */
internal fun wslProcessBuilder(wslExecutable: Path, arguments: List<String>): ProcessBuilder =
    ProcessBuilder(listOf(wslExecutable.toString()) + arguments).apply {
        environment()[WSL_UTF8_VARIABLE] = "1"
    }

/** WSL 의 코어 실행 파일. 사용자 홈 기준이다(6.16 — `~/.local/bin/kyuchestration-core`). */
internal const val WSL_CORE_EXECUTABLE_FROM_HOME = ".local/bin/kyuchestration-core"

internal const val WSL_UTF8_VARIABLE = "WSL_UTF8"
