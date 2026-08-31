package com.kyuchestration.desktop.dashboard

import com.kyuchestration.desktop.workdir.RepoState

/**
 * 카드 한 장이 세션에 대해 보여줄 것. 엔진이 본 것과 앱이 아는 것을 합친 결과다.
 *
 * **두 세계가 갈린다는 사실을 감추지 않는 것이 이 타입의 목적이다**(설계 원칙 12). 앱 세션과 CLI
 * 세션은 서로를 보지 못한다 — 감추면 사용자는 `RUNNING` 카드를 눌렀는데 새 대화가 열리는 것으로,
 * 또는 같은 레포에 `claude` 를 둘 띄우고 나서 그것을 알게 된다.
 *
 * 화면 밖에서 정한다. 어느 사실이 어느 사실을 가리는가는 프로세스를 띄우지 않고도 전부 시험할 수
 * 있는 판단이고, Composable 안에 두면 그것을 확인하려고 화면 전체를 세워야 한다.
 */
data class CardSessionFacts(
    /** 이 앱이 지금 이 세션을 보유하고 있다. `kyu list` 는 이 사실을 알지 못한다. */
    val appSessionHeld: Boolean,
    /** `kyu start` 가 tmux(또는 감독) 위에 띄운 세션이 떠 있다. 앱은 여기에 붙지 못한다. */
    val cliSessionRunning: Boolean,
    /** git 이 본 상태. CLI 세션이 그것을 가리고 있으면 null. */
    val gitState: RepoState?,
    /**
     * 이 카드를 누르면 이어갈 대화가 있다.
     *
     * 기록이 있다는 사실 그대로가 아니라 "지금 누르면 이어진다" 는 뜻이다 — 아래 두 합치는
     * 함수가 그 차이를 만든다.
     */
    val conversationToResume: Boolean,
)

/**
 * 레포 카드 한 장의 사실을 합친다.
 *
 * **얻는 것이 하나 있다.** `kyu list` 의 `state` 는 값 하나라 CLI 세션이 떠 있으면 git 상태가
 * 가려진다. 앱 세션은 엔진 눈에 보이지 않으므로 엔진은 git 상태를 답하고, 앱은 그 위에 자기
 * 사실을 얹는다 — 그래서 **앱 세션이 도는 레포는 "돌고 있다" 와 "더럽다" 를 한 카드에서 함께
 * 볼 수 있다**(설계 문서 5.4.1). 계약을 바꾸지 않고 정보가 는 자리다.
 *
 * @param engineReportedState `kyu list --json` 이 답한 상태. "레포 상태 그 자체" 가 아니라
 *   "엔진이 본 것" 으로 읽는다.
 */
fun mergeRepoCardSessionFacts(
    engineReportedState: RepoState,
    hasRecordedConversation: Boolean,
    appSessionHeld: Boolean,
): CardSessionFacts = CardSessionFacts(
    appSessionHeld = appSessionHeld,
    cliSessionRunning = engineReportedState == RepoState.Running,
    // RUNNING 은 상태가 아니라 "세션이 떠 있어 git 상태를 답하지 못했다" 는 뜻이다. 그것을
    // git 상태 자리에 그대로 두면 화면이 없는 사실을 있다고 말하게 된다.
    gitState = engineReportedState.takeIf { it != RepoState.Running },
    conversationToResume = conversationToResume(hasRecordedConversation, appSessionHeld),
)

/**
 * 메인 세션 한 줄의 사실을 합친다.
 *
 * git 상태가 없다 — 워크디렉토리 최상위는 레포가 아니라 `DIRTY` 라는 말이 성립하지 않는다
 * (설계 문서 5.4). 그래서 엔진이 답하는 것도 생존 하나뿐이다.
 *
 * 레포와 갈라 두면서도 함수로 둔다. 화면이 직접 조립하면 "보유 중이면 이어갈 대화를 말하지
 * 않는다" 는 규칙이 레포 쪽에만 있게 되고, 두 자리가 갈린 것을 눈으로 볼 수 있는 사람은
 * 그 화면을 띄운 사람뿐이다.
 */
fun mergeMainCardSessionFacts(
    mainSessionAlive: Boolean,
    hasRecordedConversation: Boolean,
    appSessionHeld: Boolean,
): CardSessionFacts = CardSessionFacts(
    appSessionHeld = appSessionHeld,
    cliSessionRunning = mainSessionAlive,
    gitState = null,
    conversationToResume = conversationToResume(hasRecordedConversation, appSessionHeld),
)

/**
 * 지금 누르면 대화가 이어지는가.
 *
 * 보유 중인 세션에는 말하지 않는다. 그 대화는 이어갈 것이 아니라 **지금 쓰고 있는 것**이고,
 * 화면이 앱 세션 칩 옆에 "이어갈 대화" 를 함께 띄우면 사용자는 카드 하나에 대화가 둘 있는
 * 것으로 읽는다. 카드를 눌러 그 세션으로 돌아가는 것은 새로 이어가는 일이 아니다.
 */
private fun conversationToResume(hasRecordedConversation: Boolean, appSessionHeld: Boolean): Boolean =
    hasRecordedConversation && !appSessionHeld
