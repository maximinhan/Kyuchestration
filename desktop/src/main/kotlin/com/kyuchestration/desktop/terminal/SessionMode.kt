package com.kyuchestration.desktop.terminal

/**
 * 이 앱이 세션을 무엇으로 부리는가(chat-ui-design.md 부록 D).
 *
 * 엔진에게 무엇을 물을지가 이 값으로 갈린다. **같은 세션·같은 대화를 두 모드로 열 수 있다** —
 * 대화는 워크디렉토리에 적혀 있고(`.coord/conversations.json`) 화면 모델과 무관하다.
 *
 * 참·거짓 하나로 두지 않는다. `sessionCommandFor(path, target, choice, true)` 를 읽는 사람은 그
 * 참이 무엇인지 알 수 없고, 두 모드는 답으로 오는 argv 가 통째로 다르다.
 */
enum class SessionMode {

    /**
     * PTY 를 열고 `claude` 가 그린 격자를 그대로 보인다. 지금까지의 것이다.
     *
     * 7 단계에 이 갈래가 사라진다 — 조건이 차면(설계 문서 7 절).
     */
    Terminal,

    /**
     * 파이프 셋에 물리고 이벤트에서 화면을 직접 그린다.
     *
     * 엔진이 답하는 argv 에 스트림 플래그가 앞에 붙는다(`kyu session-command --chat`).
     */
    Chat,
}
