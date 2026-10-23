package com.kyuchestration.desktop.wsl

/** 화면이 고른 폴더를 코어에게 건넬 경로로 옮긴 결과. */
sealed interface CoreWorkDirPath {

    data class Translated(val corePath: String) : CoreWorkDirPath

    /** 코어가 열 수 없는 자리다. [reason] 은 사람이 그대로 읽는다. */
    data class Refused(val reason: String) : CoreWorkDirPath
}

/**
 * 화면의 파일 선택기가 고른 폴더를 코어의 경로로 옮긴다(core-ui-split-design.md 6.9 의 3 단계 정정).
 *
 * 윈도우 화면이 WSL 의 코어에 붙어 있으면 받는 것은 `\\wsl.localhost\<배포판>\…` 또는 옛 형식 `\\wsl$\<배포판>\…` 다(설계 3.3 —
 * 윈도우 JVM 은 두 형식을 하나로 맞춰 주지 않는다). 배포판의 뿌리가 리눅스의 `/` 이므로 그 뒤를 `/` 로 이으면 코어의 경로다.
 *
 * **그 밖은 거절한다.** 다른 배포판의 폴더는 이 코어가 볼 수 없다(`wslpath -u` 도 풀지 못한다). `C:\…` 같은 윈도우 쪽 폴더는
 * `/mnt/c/…` 로 열 수는 있지만, 그 위에서 도는 claude · kyu · git 이 9P 너머 윈도우 파일 시스템을 쓰게 된다 — 느리고, 실행
 * 권한과 대소문자 규칙이 리눅스와 다르다. 코어는 WSL 안의 폴더만 연다.
 *
 * 화면에서 옮기는 까닭: 코어가 받는 것은 언제나 코어 경로다. 거절은 왕복 없이 고른 그 자리에서 알린다.
 *
 * @param coreWslDistributionName 화면이 윈도우이고 코어가 WSL 안에 있으면 코어가 인사 답에 실은 배포판. 아니면 null — 맥 · 리눅스,
 *   WSLg 의 화면은 코어와 같은 OS 를 보므로 화면 경로가 곧 코어 경로다.
 */
fun coreWorkDirPathOf(screenPath: String, coreWslDistributionName: String?): CoreWorkDirPath {
    if (coreWslDistributionName == null) {
        return CoreWorkDirPath.Translated(screenPath)
    }
    val segments = screenPath.split(WINDOWS_SEPARATOR)
    // `\\host\배포판\…` 을 가르면 앞의 두 칸이 비고 셋째가 호스트, 넷째가 배포판이다.
    val isWslShare = segments.size >= WSL_SHARE_LEADING_SEGMENTS &&
        segments[0].isEmpty() && segments[1].isEmpty() &&
        WSL_SHARE_HOSTS.any { host -> host.equals(segments[2], ignoreCase = true) }
    if (!isWslShare) {
        return CoreWorkDirPath.Refused(
            "코어는 WSL($coreWslDistributionName) 안의 폴더만 엽니다. 고른 자리($screenPath)는 윈도우 쪽입니다 — " +
                wslFolderGuidance(coreWslDistributionName),
        )
    }
    val chosenDistribution = segments[3]
    if (!chosenDistribution.equals(coreWslDistributionName, ignoreCase = true)) {
        return CoreWorkDirPath.Refused(
            "이 코어는 WSL 의 $coreWslDistributionName 에 있습니다. 다른 배포판($chosenDistribution)의 폴더는 열 수 없습니다 — " +
                wslFolderGuidance(coreWslDistributionName),
        )
    }
    val linuxNames = segments.drop(WSL_SHARE_LEADING_SEGMENTS).filter { it.isNotEmpty() }.map(::linuxNameOf)
    return CoreWorkDirPath.Translated(linuxNames.joinToString(separator = "/", prefix = "/"))
}

/**
 * 거절 뒤 사람이 따라 할 길. 선택 창은 JFileChooser 라 탐색기의 "Linux" 항목이 없다 — 선택 창이 처음 여는 자리와, 파일 이름 칸에
 * 적을 수 있는 경로를 알린다.
 */
private fun wslFolderGuidance(coreWslDistributionName: String): String {
    val distributionRoot = "\\\\${WSL_SHARE_HOSTS.first()}\\$coreWslDistributionName\\"
    return "선택 창이 처음 여는 ${distributionRoot}home 아래에서 고르거나, 파일 이름 칸에 ${distributionRoot}… 경로를 적으세요."
}

/**
 * 코어의 경로를 윈도우 화면이 여는 경로로 옮긴다 — 화면이 코어의 진단 기록 자리를 알릴 때 쓴다(6.2 (다)). 사람이 그 경로를 탐색기에
 * 붙여 넣으면 그 파일이 열려야 한다.
 *
 * @param coreWslDistributionName [coreWorkDirPathOf] 와 같다. null 이면 그대로 돌려준다.
 */
fun screenPathOfCorePath(corePath: String, coreWslDistributionName: String?): String {
    if (coreWslDistributionName == null) {
        return corePath
    }
    val windowsNames = corePath.split('/').filter { it.isNotEmpty() }.map(::windowsNameOf)
    return (listOf("\\\\${WSL_SHARE_HOSTS.first()}", coreWslDistributionName) + windowsNames).joinToString(WINDOWS_SEPARATOR)
}

/**
 * 윈도우가 이름에 쓰지 못하는 글자를 담은 리눅스 이름은 윈도우 쪽에 그 글자 + U+F000 으로 보인다. 실측(2026-10-22): `a:b` →
 * `a\uF03Ab`, `ctl\u0001x` → `ctl\uF001x`, 나머지 `\ * ? " < > |` 와 탭도 같다. `wslpath -u` 는 이것을 되돌린다 — 코어에게 건넬 때도
 * 되돌려야 코어가 없는 폴더를 열지 않는다.
 */
private fun linuxNameOf(windowsName: String): String = buildString {
    windowsName.forEach { character ->
        val original = character - WINDOWS_ENCODED_CHARACTER_OFFSET
        append(if (character.code in WINDOWS_ENCODED_RANGE && original in WINDOWS_FORBIDDEN_CHARACTERS) original else character)
    }
}

private fun windowsNameOf(linuxName: String): String = buildString {
    linuxName.forEach { character ->
        append(if (character in WINDOWS_FORBIDDEN_CHARACTERS) character + WINDOWS_ENCODED_CHARACTER_OFFSET else character)
    }
}

/** 윈도우 JVM 이 준 `\\wsl.localhost\Ubuntu-24.04` 를 가르면 빈 칸 둘 · 호스트 · 배포판이다. */
private const val WSL_SHARE_LEADING_SEGMENTS = 4

/** 지금의 이름이 앞이다 — 코어 경로를 윈도우 경로로 옮길 때 그것을 쓴다. */
private val WSL_SHARE_HOSTS = listOf("wsl.localhost", "wsl$")

private const val WINDOWS_SEPARATOR = "\\"

private const val WINDOWS_ENCODED_CHARACTER_OFFSET = 0xF000

private val WINDOWS_ENCODED_RANGE = 0xF001..0xF07F

private val WINDOWS_FORBIDDEN_CHARACTERS: Set<Char> = ((1..0x1F).map(Int::toChar) + "\\:*?\"<>|".toList()).toSet()
