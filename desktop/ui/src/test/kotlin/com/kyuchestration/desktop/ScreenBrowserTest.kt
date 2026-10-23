package com.kyuchestration.desktop

import com.kyuchestration.desktop.claudeauth.ClaudeAuthenticationState
import kotlin.test.Test
import java.io.IOException
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertIs

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

    @Test
    fun `브라우저가 열리면 열렸다고 답한다`() {
        val opened = mutableListOf<URI>()

        assertEquals(ScreenBrowserOpening.Opened, openInScreenBrowser("https://claude.ai/oauth/authorize?code=true", browse = { opened += it }))
        assertEquals(listOf(URI("https://claude.ai/oauth/authorize?code=true")), opened)
    }

    /**
     * 여는 일이 실패해도 로그인 중인 앱이 죽으면 안 된다 — 주소가 URI 가 아니거나, 이 데스크톱이 열기를 못 하거나(헤드리스 ·
     * 지원 안 함), 기본 브라우저가 없거나, 보안 관리자가 막을 때. 실패는 값으로 돌려준다 — 화면이 "열지 못했다, 주소를 복사하라" 를 보인다.
     */
    @Test
    fun `브라우저를 열지 못하면 던지지 않고 그 까닭을 돌려준다`() {
        val failures = listOf<(URI) -> Unit>(
            { throw UnsupportedOperationException("BROWSE 를 지원하지 않습니다") },
            { throw IOException("기본 브라우저가 없습니다") },
            { throw IllegalArgumentException("열 수 없는 주소") },
            { throw SecurityException("막혔습니다") },
        )

        failures.forEach { browse ->
            assertIs<ScreenBrowserOpening.NotOpened>(openInScreenBrowser("https://claude.ai/oauth/authorize", browse))
        }
        assertIs<ScreenBrowserOpening.NotOpened>(openInScreenBrowser("https://claude.ai/주소 에 빈칸", browse = { }))
    }
}
