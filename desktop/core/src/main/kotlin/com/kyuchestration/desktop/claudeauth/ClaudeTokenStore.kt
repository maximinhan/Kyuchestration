package com.kyuchestration.desktop.claudeauth

/**
 * 앱이 맡긴 claude 토큰이 사는 자리.
 *
 * 저장을 엔진에게 맡기는 이유는 이 도구의 비밀이 이미 거기 살기 때문이다 — 키체인이냐
 * secret-service 냐 평문 파일이냐를 가르는 앎이 `internal/secretstore` 한 곳에 있고,
 * 앱이 그것을 다시 알기 시작하면 GitHub 토큰과 claude 토큰이 서로 다른 규칙으로 저장된다.
 *
 * **여기 오는 것은 폴백 하나다.** 브라우저 로그인은 claude 자신이 자격 증명을 챙기므로 이
 * 저장소를 지나지 않는다(chat-ui-design.md 7.3 가). 이 자리는 그 길이 막힌 머신을 위한 것이다.
 */
interface ClaudeTokenStore {

    /**
     * 맡겨 둔 것과, 새로 맡기면 어디에 놓이는지를 한 번에 묻는다.
     *
     * 둘을 한 물음으로 묶은 이유는 답하는 쪽이 같은 저장소를 두 번 열지 않게 하려는 것이다.
     * 화면이 이 둘을 함께 쓴다 — 하나는 감지에, 하나는 저장 전 경고에.
     *
     * @throws ClaudeTokenStoreFailure 저장소에 닿지 못했을 때.
     */
    fun storedCredential(): StoredClaudeCredential

    /**
     * 토큰을 맡긴다. 이미 맡긴 것이 있으면 덮어쓴다.
     *
     * **통하는 것만 맡긴다.** 확인은 부르는 쪽이 먼저 한다(ClaudeCliAuthentication.tokenIsAccepted) —
     * 저장소는 값을 보관할 뿐 그것이 유효한지 모른다.
     *
     * @throws ClaudeTokenStoreFailure 저장하지 못했을 때.
     */
    fun storeToken(token: String)
}

/**
 * @param token 맡겨 둔 토큰. 없으면 null.
 * @param newTokenWouldBeStoredInPlaintext 지금 맡기면 평문 파일에 저장되는가.
 *   키체인도 secret-service 도 없는 머신(WSL 이 그렇다)에서 참이다.
 */
data class StoredClaudeCredential(
    val token: String?,
    val newTokenWouldBeStoredInPlaintext: Boolean,
)

/**
 * 저장소에 닿지 못한 이유.
 *
 * 인증 실패(ClaudeAuthenticationFailure)와 갈라 둔다. 이쪽은 claude 와 아무 상관이 없다 —
 * 엔진을 부르지 못했거나 그 답을 읽지 못한 것이고, 사용자가 할 일도 다르다.
 */
class ClaudeTokenStoreFailure(message: String, cause: Throwable? = null) : Exception(message, cause)
