package com.kyuchestration.desktop.dashboard

import com.kyuchestration.desktop.workdir.RepoState

/**
 * 카드 한 장이 세션에 대해 보여줄 것. 엔진이 본 것과 앱이 아는 것을 합친 결과다.
 *
 * **세션을 아는 것은 앱뿐이다.** 엔진은 세션을 띄우지도 세지도 않으므로, 카드에 뜨는 세션은
 * 언제나 이 앱이 지금 보유하고 있는 것이다. 엔진이 답하는 것은 git 이 본 레포 상태 하나이고,
 * 앱은 그 위에 자기 사실을 얹는다 — 두 사실이 서로를 가리지 않으므로 **한 카드가 "돌고 있다" 와
 * "더럽다" 를 함께 보여준다**(설계 문서 5.4.1).
 *
 * 화면 밖에서 정한다. 어느 사실이 어느 사실을 가리는가는 프로세스를 띄우지 않고도 전부 시험할 수
 * 있는 판단이고, Composable 안에 두면 그것을 확인하려고 화면 전체를 세워야 한다.
 */
data class CardSessionFacts(
    /** 이 앱이 지금 이 세션을 보유하고 있다. */
    val appSessionHeld: Boolean,
    /** git 이 본 상태. 레포가 아닌 자리에서는 null. */
    val gitState: RepoState?,
    /**
     * 이 카드를 누르면 이어갈 대화가 있다.
     *
     * 기록이 있다는 사실 그대로가 아니라 "지금 누르면 이어진다" 는 뜻이다 — 아래 합치는 함수가
     * 그 차이를 만든다.
     */
    val conversationToResume: Boolean,
)

/**
 * 카드 한 줄의 사실을 합친다. 레포 카드와 메인 세션 줄이 같은 함수를 쓴다.
 *
 * 둘을 갈라 두던 이유가 사라졌다. 예전에는 레포에 대해서는 상태를, 메인 세션에 대해서는 세션
 * 생존을 엔진에게 물었기 때문에 합치는 규칙이 달랐다. 이제 엔진에게 묻는 것은 git 상태 하나뿐이고
 * 남은 차이는 그 자리가 레포인가 아닌가 하나라, 널 가능 인자 하나로 갈린다. 하나로 두면
 * "보유 중인 세션에는 이어갈 대화를 말하지 않는다" 는 규칙이 한 자리에만 있게 된다.
 *
 * @param gitState `kyu list --json` 이 답한 상태. 워크디렉토리 최상위는 레포가 아니어서 DIRTY 라는
 *   말이 성립하지 않으므로(설계 문서 5.4), 메인 세션 줄은 null 을 준다.
 */
fun mergeCardSessionFacts(
    gitState: RepoState?,
    hasRecordedConversation: Boolean,
    appSessionHeld: Boolean,
): CardSessionFacts = CardSessionFacts(
    appSessionHeld = appSessionHeld,
    gitState = gitState,
    // 보유 중인 세션에는 말하지 않는다. 그 대화는 이어갈 것이 아니라 **지금 쓰고 있는 것**이고,
    // 화면이 세션 칩 옆에 "이어갈 대화" 를 함께 띄우면 사용자는 카드 하나에 대화가 둘 있는
    // 것으로 읽는다. 카드를 눌러 그 세션으로 돌아가는 것은 새로 이어가는 일이 아니다.
    conversationToResume = hasRecordedConversation && !appSessionHeld,
)
