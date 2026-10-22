package com.kyuchestration.desktop

/**
 * 끊은 턴을 `claude` 가 되돌려 보내는 문구인가(설계 3.8 · 부록 A.9).
 *
 * **이 앎이 화면에 있는 것이 뜻이다.** 중단 표시는 사용자 메시지와 **같은 모양으로** 온다 —
 * `user` 이벤트의 텍스트 블록 하나다. 어댑터가 그 문구를 알아보고 다른 갈래로 옮기면, 문구가
 * 바뀌는 날 그것이 조용히 사용자 말풍선이 된다(ChatSessionEvent.UserMessageEchoed). 그래서
 * 가르는 자리를 그리는 쪽에 두고, 못 알아본 날의 결과를 "사용자가 하지 않은 말이 말풍선으로
 * 선다" 한 줄로 좁혔다.
 *
 * **구조로 가르는 길도 있었지만 고르지 않았다.** 실측에서 앱이 보낸 말은 `isReplay: true` 로
 * 되돌아왔고 이 중단 문구에는 그 필드가 없었다(2026-09-04 프로브). 그 차이로 가르면 문구가
 * 바뀌어도 버틴다. 다만 어긋나는 날의 결과가 반대로 나쁘다 — `isReplay` 가 빠지는 순간 **사용자
 * 자신의 말**이 전부 중단 안내로 바뀐다. 한 번의 실측으로 그 위험을 지지 않는다.
 *
 * 앞부분만 본다. 도구가 도는 중에 끊으면 뒤에 낱말이 붙어 오는 판이 있다고 알려져 있는데
 * (`… for tool use`) 이 프로브에서는 재지 못했다 — 접두만 보면 그 판에서도 안내로 선다.
 */
internal fun isTurnInterruptionEcho(text: String): Boolean =
    text.trimStart().startsWith(INTERRUPTION_ECHO_PREFIX)

/** 실측에서 온 것은 정확히 `[Request interrupted by user]` 한 줄이다. */
private const val INTERRUPTION_ECHO_PREFIX = "[Request interrupted by user"
