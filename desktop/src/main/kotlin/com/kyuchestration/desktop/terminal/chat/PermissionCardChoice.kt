package com.kyuchestration.desktop.terminal.chat

import kotlinx.serialization.json.JsonObject

/**
 * 승인 카드에서 사용자가 누른 것(6.4).
 *
 * [PermissionDecision] 과 갈래가 다른 이유는 화면의 낱말과 통로의 낱말이 다르기 때문이다.
 * "이 세션에서 계속 허용" 은 소켓 너머로 보낼 것이 아니라 앱이 기억할 것이라, 그 뜻을 통로의
 * 타입에 얹으면 엔진이 알 필요 없는 개념을 알게 된다 — 옮기는 자리는 상태 홀더 하나다.
 */
sealed interface PermissionCardChoice {

    /**
     * 이번 한 번을 허용한다.
     *
     * @param input 실제로 실행될 인자. 사용자가 카드에서 고쳤으면 고친 것이다 — 고친 대로
     *   실행되는 것을 실측이 쟀다(3.5).
     */
    data class AllowOnce(val input: JsonObject) : PermissionCardChoice

    /**
     * 이번을 허용하고, 이 세션이 사는 동안 같은 도구를 다시 묻지 않는다.
     *
     * **규칙은 도구 이름 단위이고 앱의 메모리에만 산다**(설계 10 절 열린 질문 4 의 v1 결정).
     * 세션이 끝나면 함께 사라지므로 어딘가에 적히는 것이 없다.
     *
     * @param input 이번 호출에 쓸 인자. 고친 인자는 이번 한 번에만 적용된다 — 규칙이 무엇을
     *   허용하는지는 도구 이름까지이고, 다음 호출의 인자는 그때 모델이 정한다.
     */
    data class AllowForThisSession(val input: JsonObject) : PermissionCardChoice

    /**
     * 거절한다.
     *
     * @param reason 모델에게 그대로 갈 문구. 사용자가 적지 않았으면 앱이 정한 기본 문구다 —
     *   빈 문구를 보내면 모델은 왜 못 했는지 말할 것이 없다.
     */
    data class Deny(val reason: String) : PermissionCardChoice
}
