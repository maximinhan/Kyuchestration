package com.kyuchestration.desktop

import com.kyuchestration.desktop.claudeauth.ClaudeAuthenticationState
import java.awt.Desktop
import java.io.IOException
import java.net.URI

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

/**
 * [url] 을 이 OS 의 기본 브라우저로 연다. 열지 못하면(브라우저 열기를 지원하지 않는 툴킷 · 기본 브라우저 없음) 아무것도 하지 않는다 —
 * 로그인 화면이 주소와 복사 단추를 늘 보이고 있어 사람이 직접 연다.
 */
internal fun openInScreenBrowser(url: String) {
    if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
        return
    }
    try {
        Desktop.getDesktop().browse(URI(url))
    } catch (noDefaultBrowser: IOException) {
        // 기본 브라우저가 정해져 있지 않거나 띄우지 못했다. 로그인 화면이 주소와 복사 단추를 보이고 있다.
    }
}
