package com.kyuchestration.desktop.platform

/**
 * 앱이 맡아 둔 claude 토큰을 자식에게 물려주는 환경변수의 이름.
 *
 * **이 이름이 앱에 있는 것은 설계 원칙 11 의 예외다.** 세션이 무엇을 띄울지는 엔진이 답하고
 * (`kyu session-command`), 그 답의 `env` 에 더할 것이 실려 온다 — 그러니 이 변수도 거기 실리는
 * 것이 원칙에 맞다. 그렇게 하지 않은 이유는 그 답이 **문서**이기 때문이다. 사람이 터미널에서
 * `kyu session-command --json` 을 치면 그 문서가 화면에 통째로 찍히고, 앱의 기록으로도 넘어간다.
 * 토큰을 거기 실으면 상태를 확인하는 것만으로 비밀이 새고, 그 값은 폐기 전까지 계속 유효하다.
 *
 * 그래서 이름만 앱이 든다. 그리고 **한 자리에만 둔다** — 세션에 실을 때와, 인증을 확인하려고
 * claude 를 부를 때가 같은 이름을 써야 "화면은 인증됐다는데 세션은 아니라고 한다" 가 생기지 않는다.
 *
 * 값을 고르는 근거는 실측이다(chat-ui-design.md 7.3). 이 이름으로 준 값을 claude 가 실제로
 * 자격 증명으로 쓰고, 거절당하면 401 로 답한다.
 */
internal const val CLAUDE_AUTH_TOKEN_ENV_NAME = "CLAUDE_CODE_OAUTH_TOKEN"

/**
 * 토큰이 있으면 그것을 실은 환경을, 없으면 받은 환경을 그대로 답한다.
 *
 * 없을 때 빈 값을 싣지 않는 것이 이 함수의 요점이다. 빈 문자열을 실으면 claude 는 "자격 증명이
 * 있는데 거절당했다"(401)로 끝나는데, 실제 사실은 "앱이 맡아 둔 것이 없다" 이고 그때 claude 는
 * 자기 자격 증명(브라우저 로그인이 남긴 것)을 써야 한다.
 */
internal fun environmentCarryingClaudeAuthToken(
    baseEnvironment: Map<String, String>,
    claudeAuthToken: String?,
): Map<String, String> = when (claudeAuthToken) {
    null -> baseEnvironment
    else -> baseEnvironment + (CLAUDE_AUTH_TOKEN_ENV_NAME to claudeAuthToken)
}
