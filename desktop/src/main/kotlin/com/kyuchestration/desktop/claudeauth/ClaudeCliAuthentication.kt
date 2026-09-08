package com.kyuchestration.desktop.claudeauth

/**
 * claude CLI 에게 자격 증명에 대해 묻고 시키는 자리.
 *
 * **세 물음이 한 인터페이스에 있는 이유**는 상대가 하나여서다 — 같은 프로그램에게 "있느냐",
 * "로그인시켜라", "이 토큰이 통하느냐" 를 묻는다. 셋을 갈라 두면 조립하는 자리에서 같은
 * 어댑터를 세 번 만들거나, 어느 것이 어느 프로그램에게 가는지가 흐려진다.
 *
 * **엔진(kyu)을 지나지 않는다.** 설계 원칙 11 은 세션이 무엇을 띄울지를 엔진이 답하라는 것이고,
 * 이것은 세션이 아니다 — 앱이 자기 온보딩을 위해 claude 에게 직접 묻는 자리이고, 엔진에는
 * claude 에게 물을 클라이언트가 없다. 엔진이 맡는 것은 저장 하나다([ClaudeTokenStore]).
 */
interface ClaudeCliAuthentication {

    /**
     * 이 머신의 claude 가 자격 증명을 들고 있는가.
     *
     * **있는지만 답한다. 통하는지는 답하지 않는다.** 실측에서 claude 는 가짜 토큰에도
     * "로그인됨" 이라고 답했다(chat-ui-design.md 7.3 나). 통하는지는 [tokenIsAccepted] 가 가른다.
     *
     * @param tokenToInject 앱이 맡아 둔 토큰. 세션에 실을 것과 **같은 값**을 실어서 묻는다 —
     *   다른 환경으로 물으면 화면이 "로그인됨" 이라고 말한 뒤 세션이 로그인되지 않은 채로 뜬다.
     *   맡아 둔 것이 없으면 null 이고, 그때 claude 는 자기 자격 증명을 본다.
     * @throws ClaudeAuthenticationFailure claude 를 띄우지 못했거나 그 답을 읽지 못했을 때.
     */
    suspend fun credentialsArePresent(tokenToInject: String?): Boolean

    /**
     * 브라우저 로그인을 시작하고, 주소가 나올 때까지 기다렸다가 그 통로를 돌려준다.
     *
     * 주소를 기다린 뒤에 돌려주는 이유: 주소 없는 통로는 화면이 그릴 것이 없다. "시작했다" 만
     * 돌려주면 주소를 기다리는 일이 화면 쪽으로 넘어가고, 그 기다림에는 한도와 실패가 붙는다.
     *
     * @throws ClaudeAuthenticationFailure 띄우지 못했거나 주소가 오지 않았을 때.
     */
    suspend fun startBrowserLogin(): ClaudeBrowserLogin

    /**
     * 이 토큰으로 claude 가 실제로 답하는지 한 턴을 태워 확인한다.
     *
     * **부르면 값을 쓴다.** 토큰이 유효하면 그 한 턴이 실제로 청구된다. 그보다 싼 길은 없었다 —
     * claude 에 "이 토큰이 통하나" 만 묻는 하위 명령이 없다(chat-ui-design.md 7.3 라).
     *
     * @throws ClaudeAuthenticationFailure claude 를 띄우지 못했거나 그 답을 읽지 못했을 때.
     */
    suspend fun tokenIsAccepted(token: String): ClaudeTokenVerdict
}

/**
 * 브라우저 로그인 하나. 살아 있는 claude 프로세스를 쥐고 있다.
 *
 * 이 타입이 있어야 "주소를 보여주는 것" 과 "코드를 넣는 것" 이 같은 프로세스에 대한 일이 된다.
 * 두 번의 호출로 나누면 앱이 그 사이에 어느 프로세스였는지를 따로 들고 있어야 한다.
 */
interface ClaudeBrowserLogin {

    /** 사용자가 브라우저에서 열어야 하는 주소. claude 가 스스로 열기도 하지만 그것이 된다는 보장은 없다. */
    val authorizationUrl: String

    /**
     * 브라우저가 준 코드를 넣고 로그인이 끝나기를 기다린다.
     *
     * 성공하면 자격 증명은 **claude 자신이** 챙긴다 — 앱이 저장할 것이 없다(7.3 가).
     *
     * @throws ClaudeAuthenticationFailure 거절당했거나 claude 가 끝나지 않았을 때.
     */
    suspend fun completeWithCode(code: String)

    /** 사용자가 그만둔다. 기다리던 claude 를 끝낸다. */
    fun cancel()
}

/** [ClaudeCliAuthentication.tokenIsAccepted] 의 답. */
sealed interface ClaudeTokenVerdict {

    data object Accepted : ClaudeTokenVerdict

    /**
     * @param claudeMessage claude 가 거절 이유로 적은 것. 앤트로픽이 그 토큰에 대해 한 말이라
     *   사용자가 친 글자가 섞이지 않는다.
     */
    data class Refused(val claudeMessage: String) : ClaudeTokenVerdict
}
