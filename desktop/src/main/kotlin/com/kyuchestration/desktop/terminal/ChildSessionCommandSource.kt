package com.kyuchestration.desktop.terminal

import java.nio.file.Path

/**
 * 하위 세션 하나가 무엇을 띄울지 엔진에게 묻는 자리(session-tree-design.md 7 절 3 단계의 `--child`).
 *
 * [SessionCommandSource] 와 따로 둔다. 묻는 것도 답도 다르다 — 사람이 연 세션은 라벨과 대화 선택을 묻고
 * 명령 하나를 받지만, 하위는 자리·이름·살아 있는 하위들을 묻고 **명령 대신 관문의 물음을 받을 수 있다.**
 * 하나의 포트에 담으면 사람이 연 세션을 여는 자리마다 "관문에 걸렸으면?" 이라는 오지 않는 갈래가 생긴다.
 */
fun interface ChildSessionCommandSource {

    /**
     * @param appSocketPath 앱이 이 하위를 위해 연 소켓. 하위의 `ask_parent` 와 승인이 이 자리로 온다 —
     *   엔진은 소켓 없는 하위 요청을 거절한다(하위는 상위에게 물을 길 없이 뜰 수 없다).
     * @throws TerminalSessionFailure 묻지 못했거나, kyu 가 거절했거나(cwd 규칙), 답을 읽지 못했을 때.
     */
    fun childSessionCommandFor(
        workDirPath: Path,
        request: ChildSessionCommandRequest,
        appSocketPath: Path,
    ): ChildSessionCommandAnswer
}

/**
 * 상위가 부탁한 하위 하나와, 앱만 아는 사실들.
 *
 * 이름은 묻지 않는다. 엔진이 기록을 보고 지어 [AssignedChildSession.childId] 로 답한다.
 *
 * @param requestedCwd 상위가 고른 자리 그대로. null 이면 워크디렉토리 루트 — 풀이는 엔진이 한다(6.8).
 * @param liveChildRelativeCwds 지금 살아 있는 하위들의 자리. 누가 살아 있는지는 앱만 안다(원칙 16).
 * @param approvedFingerprint 사람이 관문 대화 상자에서 승인한 지문. 처음 물을 때는 null.
 */
data class ChildSessionCommandRequest(
    val requestedCwd: String?,
    val createDirectory: Boolean,
    val title: String?,
    val liveChildRelativeCwds: List<String>,
    val approvedFingerprint: String?,
)

/** 하위 하나에 대한 엔진의 답 — 띄울 명령이거나, 사람에게 먼저 보일 관문의 물음이다. */
sealed interface ChildSessionCommandAnswer {

    /** 띄울 수 있다. 엔진이 이 하위의 대화를 이미 기록에 적었다. */
    data class Ready(
        val sessionCommandAnswer: SessionCommandAnswer,
        val assignedChildSession: AssignedChildSession,
    ) : ChildSessionCommandAnswer

    /** 그 자리에 사람이 승인하지 않은 실행 유발 설정이 있다(6.9). 아무것도 띄우지 않았다. */
    data class ApprovalRequired(val gate: ChildSessionGate) : ChildSessionCommandAnswer
}

/**
 * 엔진이 배정한 하위의 사실(`childSession`).
 *
 * @param childId 엔진이 지은 이름. 앱은 이 값을 트리 · 화면 · 상위의 답에 그대로 쓴다.
 * @param relativeCwd 링크를 따라간 자리의 워크디렉토리 기준 `/` 경로. 다음 spawn 의 `--live-child-cwd` 로 그대로 넘긴다.
 */
data class AssignedChildSession(
    val childId: String,
    val relativeCwd: String,
    val title: String,
    val conversationId: String,
)

/**
 * 사람에게 보일 관문의 물음(`approvalRequired`).
 *
 * 승인은 자리가 아니라 [fingerprint] 에 대한 것이다 — 사람이 본 뒤 파일이 바뀌면 엔진이 지금 내용으로 다시 묻는다.
 */
data class ChildSessionGate(
    val relativeCwd: String,
    val fingerprint: String,
    val executionConfigs: List<GatedExecutionConfig>,
)

/** 걸린 설정 파일 하나. 무엇을 승인하는지 모르는 승인은 관문이 아니라 내용을 통째로 든다. */
data class GatedExecutionConfig(
    val relativePath: String,
    val absolutePath: String,
    val content: String,
)
