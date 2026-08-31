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
fun mergeRepoCardSessionFacts(engineReportedState: RepoState, appSessionHeld: Boolean): CardSessionFacts =
    CardSessionFacts(
        appSessionHeld = appSessionHeld,
        cliSessionRunning = engineReportedState == RepoState.Running,
        // RUNNING 은 상태가 아니라 "세션이 떠 있어 git 상태를 답하지 못했다" 는 뜻이다. 그것을
        // git 상태 자리에 그대로 두면 화면이 없는 사실을 있다고 말하게 된다.
        gitState = engineReportedState.takeIf { it != RepoState.Running },
    )
