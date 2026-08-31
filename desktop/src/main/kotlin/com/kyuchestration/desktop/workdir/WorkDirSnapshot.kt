package com.kyuchestration.desktop.workdir

/**
 * 워크디렉토리 한 곳을 한 번 관찰한 결과.
 *
 * `kyu list --json` 문서(schemaVersion 1)와 같은 사실을 담지만, 그 문서의 모양에 매이지 않는다.
 * 화면과 상태 홀더는 이 타입만 알고, JSON 의 필드 이름은 어댑터(workdir.kyucli) 안에서 끝난다.
 * 그래야 계약이 판을 올릴 때 고쳐야 할 곳이 어댑터 한 군데로 남는다.
 */
data class WorkDirSnapshot(
    val name: String,
    val absolutePath: String,
    /** 워크디렉토리 바로 아래의 레포, 이름순. 메인 세션은 여기 없다. */
    val repos: List<RepoSnapshot>,
    val mainSessionAlive: Boolean,
    /**
     * 메인 세션이 이어갈 대화가 적혀 있는지(`.coord/conversations.json`).
     *
     * 생존과 다른 축의 사실이라 따로 싣는다 — 생존은 지금 돌고 있는가이고, 이것은 다음에 열면
     * 이어갈 것이 있는가다. 앱을 껐다 켠 사용자에게는 뒤쪽이 남는다.
     */
    val mainSessionHasRecordedConversation: Boolean,
    /** 계획을 그대로 쓰지 못한 이유. 계획이 깨져도 목록 자체는 성립하므로 실패가 아니라 곁들이는 말이다. */
    val planWarnings: List<String>,
) {
    /**
     * `kyu start` 가 띄운 세션 중 지금 떠 있는 것의 수. 메인 세션도 한 자리를 차지한다.
     *
     * **앱이 보유한 세션은 여기 없다.** 앱 세션은 tmux 세션도 감독 세션도 아니라 엔진 눈에
     * 보이지 않는다(설계 문서 5.4). 이름에 CLI 를 적어 두는 이유가 그것이다 — "떠 있는 세션의
     * 수" 라고만 부르면 화면이 앱 세션을 빠뜨린 채로 전부라고 말하게 된다.
     *
     * 레포의 세션 생존은 따로 실려 오지 않는다 — 상태가 RUNNING 인 것이 곧 세션이 떠 있다는 뜻이다
     * (README 의 상태 표).
     */
    val cliSessionCount: Int =
        repos.count { it.state == RepoState.Running } + if (mainSessionAlive) 1 else 0
}

data class RepoSnapshot(
    val name: String,
    val absolutePath: String,
    val state: RepoState,
    /** 계획에서 고른 "지금 볼 작업". 고를 것이 없으면 null. */
    val task: RepoTask?,
    val doneTaskCount: Int,
    val totalTaskCount: Int,
    /**
     * 이 레포의 세션이 이어갈 대화가 적혀 있는지.
     *
     * 앱이 이 사실을 알려고 `kyu session-command` 를 미리 부를 수는 없다 — 묻는 것이 곧 기록하는
     * 것이라, 카드를 그리려던 물음이 대화를 만들어 버린다(설계 문서 5.5.3). 그래서 목록이 답한다.
     */
    val hasRecordedConversation: Boolean,
)

data class RepoTask(
    val id: String,
    /**
     * blocked · ready · doing · done.
     *
     * 낱말을 타입으로 좁히지 않는다. 화면은 이 낱말을 그대로 찍고, 막혔는지는 blockedBy 가
     * 비었는지로 이미 알 수 있다. 여기서 좁혀 두면 계획 상태가 하나 늘 때 GUI 가 표시조차
     * 못 하고 멈춘다.
     */
    val status: String,
    /** 아직 끝나지 않은 선행 작업의 id. 막힌 작업이 아니면 빈 목록. */
    val blockedBy: List<String>,
)

/**
 * 레포의 상태. kyu 가 tmux 와 git 에게 물어 매번 다시 계산한 결과다.
 *
 * 상태마다 화면에서 색이 다르므로 문자열이 아니라 타입으로 좁힌다. 다만 계약은 값이 하나 늘어도
 * schemaVersion 을 올리지 않으므로(list_json.go 의 판 규약), 모르는 낱말을 만나도 멈추지 않고
 * 그대로 들고 있는다. GUI 가 엔진보다 늦게 배포되는 일은 흔하고, 그때 화면 전체가 죽는 것보다
 * 낯선 낱말 하나를 회색으로 보여주는 편이 낫다.
 */
sealed interface RepoState {

    /** kyu 문서에 실려 온 낱말. 화면도 같은 낱말을 쓴다. */
    val rawValue: String

    data object Running : RepoState {
        override val rawValue: String = "RUNNING"
    }

    data object Dirty : RepoState {
        override val rawValue: String = "DIRTY"
    }

    data object Ahead : RepoState {
        override val rawValue: String = "AHEAD"
    }

    data object Idle : RepoState {
        override val rawValue: String = "IDLE"
    }

    data class Unrecognized(override val rawValue: String) : RepoState

    companion object {
        fun ofRawValue(rawValue: String): RepoState = when (rawValue) {
            Running.rawValue -> Running
            Dirty.rawValue -> Dirty
            Ahead.rawValue -> Ahead
            Idle.rawValue -> Idle
            else -> Unrecognized(rawValue)
        }
    }
}
