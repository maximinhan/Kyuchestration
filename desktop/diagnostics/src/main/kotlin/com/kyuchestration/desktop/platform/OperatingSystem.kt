package com.kyuchestration.desktop.platform

/**
 * 이 JVM 이 답하는 OS 이름(`os.name`). 기본값으로 이것을 읽는 자리가 모두 이 함수를 지난다 — 시험은 이름을 직접 넘긴다.
 */
fun currentOperatingSystemName(): String = System.getProperty("os.name").orEmpty()

/**
 * JVM 의 `os.name` 을 OS 낱말(`windows` · `macos` · `linux`)로 옮긴다. 이 셋 밖이면 null — 이 앱이 패키지를 내지 않는 OS 다.
 *
 * **os.name 의 접두를 보는 규칙은 여기 하나다.** 맥 · 윈도우 갈래(아래 둘)도, 화면과 코어가 인사에 싣는 OS 낱말(screenOs ·
 * coreOs)도 이것을 쓴다. 자리마다 문자열을 견주게 두면 한쪽만 대소문자를 놓치는 날이 온다.
 *
 * JVM 은 맥을 언제나 "Mac OS X" 로, 윈도우를 "Windows 11" · "Windows Server 2022" 처럼 판을 붙여 답한다. 앞만 본다. "Darwin" 은
 * 보지 않는다 — JVM 이 그 철자로 답하지 않는다. 릴리스 자산 이름을 옮기는 자리(EngineReleaseTarget)가 그 철자를 함께 받는 것은
 * 그쪽이 Go 의 GOOS 와 마주 보기 때문이다.
 *
 * 틀에는 열거형이 아니라 낱말로 싣는다. 새 판의 상대가 모르는 낱말을 보내도 인사 틀 전체가 읽히지 않는 일이 없게 하려는 것이다
 * (필드를 더하는 변경은 판을 올리지 않는다 — core-ui-split-design.md 6.14).
 */
fun operatingSystemWordOf(osName: String): String? = when {
    osName.startsWith("Windows", ignoreCase = true) -> WINDOWS_WORD
    osName.startsWith("Mac", ignoreCase = true) -> MAC_WORD
    osName.startsWith("Linux", ignoreCase = true) -> "linux"
    else -> null
}

/** 이 JVM 이 도는 OS 의 낱말. */
fun currentOperatingSystemWord(): String? = operatingSystemWordOf(currentOperatingSystemName())

/**
 * 이 앱이 맥에서 돌고 있는가. 맥에서만 다른 길을 가는 자리가 여럿이라(엔진을 놓는 디렉토리 · 폴더 선택창 · 로그인 셸의 기본값)
 * 그 판정을 한 곳에 둔다.
 */
fun runningOnMac(osName: String = currentOperatingSystemName()): Boolean = operatingSystemWordOf(osName) == MAC_WORD

/** 이 프로그램이 윈도우에서 돌고 있는가. 윈도우에서 뜨는 것은 화면뿐이다 — 코어는 WSL 안에 있다(core-ui-split-design.md 6.16). */
fun runningOnWindows(osName: String = currentOperatingSystemName()): Boolean = operatingSystemWordOf(osName) == WINDOWS_WORD

private const val WINDOWS_WORD = "windows"

private const val MAC_WORD = "macos"
