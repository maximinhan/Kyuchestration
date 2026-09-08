package com.kyuchestration.desktop.claudeauth

/**
 * 이 머신의 claude 가 로그인돼 있는가, 아니라면 로그인시키려는 걸음이 어디까지 왔는가.
 *
 * **엔진 설치(EngineInstallationState) 다음 갈림길이다.** 워크디렉토리를 열고 세션에 들어가는
 * 걸음이 모두 claude 를 띄우는 일이라, 로그인되지 않은 채로 대시보드를 보여주면 사용자는
 * 세션을 열 때마다 "Not logged in" 한 줄과 종료 코드 1 을 만난다(chat-ui-design.md 7.1).
 * 그 자리를 아예 만들지 않기 위해 갈림길을 여기 하나 둔다.
 */
sealed interface ClaudeAuthenticationState {

    /** claude 에게 묻는 중. 앱을 띄운 직후와 걸음이 끝날 때마다 잠깐 지난다. */
    data object CheckingCredentials : ClaudeAuthenticationState

    /**
     * claude 를 띄우지 못했다 — 이 머신에 없다.
     *
     * 로그인 버튼을 보여주지 않는다. 눌러도 같은 실패를 만나고, 사용자가 할 일은 로그인이 아니라
     * 설치다. 엔진과 달리 앱이 대신 받아 놓지 않는 것은 claude 가 이 도구의 것이 아니어서다 —
     * 설치 방법도 판올림도 앤트로픽이 정한다.
     */
    data class ClaudeCliMissing(val failure: ClaudeAuthenticationFailure) : ClaudeAuthenticationState

    /**
     * claude 는 있는데 자격 증명이 없다. 사용자가 고를 자리다.
     *
     * @param lastFailure 방금 실패한 걸음이 있으면 그 이유. 처음 이 자리에 서면 null 이다.
     *   실패를 따로 상태로 두지 않는 이유는 사용자가 할 일이 같아서다 — 다시 고르는 것이다.
     * @param tokenWouldBeStoredInPlaintext 지금 토큰을 넣으면 평문 파일에 저장되는가.
     *   **토큰을 받기 전에 알려야 한다.** 받은 뒤에 알리면 사용자가 할 수 있는 일은 이미 저장된
     *   것을 지우는 것뿐이다(kyu auth add 가 같은 자세다).
     */
    data class CredentialsMissing(
        val lastFailure: ClaudeAuthenticationFailure?,
        val tokenWouldBeStoredInPlaintext: Boolean,
    ) : ClaudeAuthenticationState

    /** claude auth login 을 띄웠고 아직 주소가 오지 않았다. */
    data object BrowserLoginStarting : ClaudeAuthenticationState

    /**
     * 주소가 왔다. 사용자가 브라우저에서 받아 올 코드를 기다린다.
     *
     * **기다리는 것 말고 할 수 있는 일이 없다.** 로그인이 되돌아오는 자리가 이 머신이 아니라
     * 앤트로픽의 주소라(chat-ui-design.md 7.3 가), 앱이 열어 둘 콜백도 물어볼 자리도 없다 —
     * 완료를 아는 길은 사용자가 붙여넣는 코드 하나다.
     */
    data class BrowserLoginWaitingForCode(val authorizationUrl: String) : ClaudeAuthenticationState

    /** 코드를 넣었고 claude 가 그것을 교환하는 중이다. */
    data object BrowserLoginFinishing : ClaudeAuthenticationState

    /** 붙여넣은 토큰이 통하는지 claude 에게 한 턴을 태워 묻는 중이다. */
    data object PastedTokenVerifying : ClaudeAuthenticationState

    /**
     * 자격 증명이 있다. 앱의 나머지가 여기서부터다.
     *
     * 브라우저로 방금 로그인한 것과 원래 로그인돼 있던 것을 가르지 않는다. 화면이 하는 일이
     * 같기 때문이다 — 엔진 설치 화면이 EngineReady 하나로 두는 것과 같은 자세다.
     */
    data object CredentialsReady : ClaudeAuthenticationState
}
