package com.kyuchestration.desktop.protocol

import kotlinx.serialization.Serializable

/**
 * 화면에 보일 실패 하나(core-ui-split-design.md 6.3).
 *
 * 코어의 실패는 예외다 — 원인 사슬(`cause`)을 쥐고, 그 사슬 끝에는 소켓·프로세스의 예외가 걸려 있다. 그것은 경계를 건너지
 * 못하고(원칙 22), 화면이 쓰는 것도 문구와 안내 둘뿐이다. 그래서 상태에는 예외 대신 이 값을 둔다. 원인 사슬은 코어의
 * 진단 기록에 남는다.
 *
 * @param kind 실패의 갈래 이름(`GitHubUnreachable` …). 화면이 갈래마다 다른 문구나 버튼을 고를 재료다 — 문구를 견주어
 *   갈래를 알아내면 문구를 고치는 날 그 판단이 조용히 깨진다.
 * @param guidance 사람이 지금 할 수 있는 일. 화면이 문구 아래에 그대로 보인다.
 */
@Serializable
data class FailureView(
    val kind: String,
    val message: String,
    val guidance: String,
)

/**
 * 사람에게 할 일을 안내하는 실패. 실패 계열 다섯(엔진 설치 · claude 인증 · 관찰 · 세션 진입 · 클론)이 모두 이 모양이라,
 * 값으로 바꾸는 규칙을 한 자리([toFailureView])에 둔다.
 */
interface GuidedFailure {

    val message: String?

    val guidance: String

    /**
     * 이 실패가 속한 계열의 이름. 계열을 갈래 이름 앞에 붙여야 [FailureView.kind] 가 겹치지 않는다 — kyu 를 찾지 못한 것은
     * 관찰 · 세션 진입 · 클론 세 계열에 같은 이름으로 있고, 사람이 할 일이 계열마다 다르다.
     */
    val failureFamily: String
}

fun GuidedFailure.toFailureView(): FailureView {
    // 갈래는 이름 있는 클래스여야 한다. 이름 없는 하위 클래스로 실패를 만들면 화면이 고를 갈래가 사라지므로 여기서 멈춘다.
    val caseName = checkNotNull(this::class.simpleName) { "$failureFamily 의 이름 없는 갈래는 화면에 넘길 수 없다" }
    return FailureView(kind = "$failureFamily.$caseName", message = message.orEmpty(), guidance = guidance)
}
