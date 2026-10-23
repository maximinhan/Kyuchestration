package com.kyuchestration.desktop

import com.kyuchestration.desktop.claudeauth.ClaudeAuthenticationState
import java.awt.Desktop
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException

/**
 * 화면이 자기 OS 의 브라우저로 열 로그인 주소. 없으면 null(core-ui-split-design.md 6.10 — 브라우저 열기는 사람이 앉은 OS 의 것).
 *
 * 코어가 WSL 너머에 있을 때만 연다. WSL 의 `claude auth login` 은 윈도우 브라우저를 열지 못한다(3 단계 실측: wslview 가 없고
 * xdg-open 은 리눅스 쪽 브라우저를 찾는다). 같은 머신의 코어는 claude 가 그 머신의 브라우저를 이미 연다 — 화면이 또 열면 같은
 * 주소가 두 번 열린다.
 */
internal fun authorizationUrlTheScreenOpens(
    claudeAuthenticationState: ClaudeAuthenticationState,
    coreWslDistributionAcrossScreen: String?,
): String? {
    if (coreWslDistributionAcrossScreen == null) {
        return null
    }
    return (claudeAuthenticationState as? ClaudeAuthenticationState.BrowserLoginWaitingForCode)?.authorizationUrl
}

/** 화면이 브라우저를 열어 본 결과. */
internal sealed interface ScreenBrowserOpening {

    data object Opened : ScreenBrowserOpening

    /** @param reason 사람에게 보일 까닭. 로그인 화면이 이것과 함께 주소를 복사하라고 안내한다. */
    data class NotOpened(val reason: String) : ScreenBrowserOpening
}

/**
 * [url] 을 이 OS 의 기본 브라우저로 연다. 열지 못하면 던지지 않고 그 까닭을 돌려준다 — 로그인 중인 앱이 그 때문에 죽으면 안 된다.
 * 로그인 화면은 주소와 복사 단추를 늘 보이고 있어, 열지 못했다는 말과 함께 사람이 직접 연다.
 *
 * 실패의 갈래는 정해져 있다: 주소가 URI 가 아니다(URISyntaxException), 이 데스크톱이 열기를 못 한다(UnsupportedOperationException —
 * 헤드리스의 HeadlessException 도 그 하위다), 기본 브라우저가 없거나 띄우지 못했다(IOException), 열 수 없는 주소다
 * (IllegalArgumentException), 보안 관리자가 막았다(SecurityException). Desktop.browse 가 문서로 밝힌 실패 전부다.
 *
 * 프로세스를 띄우는 일이라 창을 그리는 스레드에서 부르지 않는다.
 *
 * @param browse 실제로 여는 자리. 시험이 실패 갈래를 세우는 이음매다.
 */
internal fun openInScreenBrowser(url: String, browse: (URI) -> Unit = ::browseWithDesktop): ScreenBrowserOpening =
    try {
        browse(URI(url))
        ScreenBrowserOpening.Opened
    } catch (notUri: URISyntaxException) {
        ScreenBrowserOpening.NotOpened("주소를 읽지 못했습니다: ${notUri.reason}")
    } catch (unsupported: UnsupportedOperationException) {
        ScreenBrowserOpening.NotOpened("이 데스크톱은 브라우저를 열 수 없습니다")
    } catch (noBrowser: IOException) {
        ScreenBrowserOpening.NotOpened("기본 브라우저를 띄우지 못했습니다")
    } catch (refusedUri: IllegalArgumentException) {
        ScreenBrowserOpening.NotOpened("브라우저가 이 주소를 받지 않았습니다")
    } catch (blocked: SecurityException) {
        ScreenBrowserOpening.NotOpened("브라우저 열기가 막혔습니다")
    }

private fun browseWithDesktop(uri: URI) {
    if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
        throw UnsupportedOperationException("Desktop.Action.BROWSE")
    }
    Desktop.getDesktop().browse(uri)
}
