package com.kyuchestration.desktop

import com.kyuchestration.desktop.claudeauth.ClaudeAuthenticationState
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 로그인 주소를 화면의 브라우저로 여는가(core-ui-split-design.md 6.10).
 *
 * WSL 의 claude 는 윈도우 브라우저를 열지 못한다 — 실측: 이 WSL 에는 wslview 가 없고 xdg-open 은 리눅스 쪽 브라우저를 찾는다.
 * 그래서 코어가 WSL 너머에 있으면 화면이 연다. 같은 머신의 코어(맥 · 리눅스)는 claude 가 이미 그 머신의 브라우저를 여므로 화면이
 * 또 열면 같은 주소가 두 번 열린다.
 */
class ScreenBrowserTest {

    private val waitingForCode = ClaudeAuthenticationState.BrowserLoginWaitingForCode("https://claude.ai/oauth/authorize?code=true")

    @Test
    fun `코어가 WSL 너머에 있으면 로그인 주소를 화면이 연다`() {
        assertEquals(
            "https://claude.ai/oauth/authorize?code=true",
            authorizationUrlTheScreenOpens(waitingForCode, coreWslDistributionAcrossScreen = "Ubuntu-24.04"),
        )
    }

    @Test
    fun `같은 머신의 코어면 claude 가 연 것을 또 열지 않는다`() {
        assertEquals(null, authorizationUrlTheScreenOpens(waitingForCode, coreWslDistributionAcrossScreen = null))
    }

    @Test
    fun `코드를 기다리는 때가 아니면 열 것이 없다`() {
        assertEquals(
            null,
            authorizationUrlTheScreenOpens(ClaudeAuthenticationState.BrowserLoginStarting, coreWslDistributionAcrossScreen = "Ubuntu-24.04"),
        )
    }
}
