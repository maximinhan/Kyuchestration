package com.kyuchestration.desktop.platform

/**
 * 이 앱이 맥에서 돌고 있는가.
 *
 * 맥에서만 다른 길을 가는 자리가 여럿이라(엔진을 놓는 디렉토리 · 폴더 선택창 · 로그인 셸의
 * 기본값) 그 판정을 한 곳에 둔다. 각자 문자열을 견주게 두면 한쪽만 대소문자를 놓치는 날이 온다.
 *
 * "Darwin" 은 보지 않는다. JVM 이 `os.name` 으로 답하는 것은 언제나 "Mac OS X" 이고, 릴리스 자산
 * 이름을 옮기는 자리(EngineReleaseTarget)가 두 철자를 함께 받는 것은 그쪽이 Go 의 GOOS 철자와
 * 마주 보기 때문이다. 여기서 쓰지도 않을 갈래를 늘리면 시험할 수 없는 분기가 하나 생긴다.
 */
internal fun runningOnMac(osName: String = System.getProperty("os.name").orEmpty()): Boolean =
    osName.startsWith("Mac", ignoreCase = true)
