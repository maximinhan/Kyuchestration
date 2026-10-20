package com.kyuchestration.desktop.terminal.chat

import com.kyuchestration.desktop.terminal.SessionTarget
import java.nio.file.Path

/**
 * 메인 아래 하위 세션들(session-tree-design.md 6.11).
 *
 * **상위를 필드로 두지 않는다.** v1 의 상위는 메인 하나이고 깊이는 1 로 고정이라(6.3), 상위가 누구인지는
 * 이 트리를 쥔 자리가 이미 안다. 상위의 대화는 다른 세션과 똑같이 상태 홀더의 대화 맵에 있다.
 *
 * **끝난 하위도 남는다.** `list_sessions` 가 끝난 것까지 답하고(6.2), 사람은 그 하위가 왜 끝났는지
 * 전사를 다시 볼 수 있어야 한다. 트리에서 하위가 사라지는 것은 상위의 대화가 사라질 때다(6.13).
 */
data class SessionTree(val children: List<ChildSessionNode> = emptyList()) {

    /** 살아 있는 하위들의 자리 — 엔진에게 `--live-child-cwd` 로 넘기는 값이다(6.8). */
    val liveChildRelativeCwds: List<String>
        get() = children.filter { it.lifecycle == ChildLifecycle.Running }.map { it.relativeCwd }

    fun child(childId: String): ChildSessionNode? = children.firstOrNull { it.childId == childId }

    /**
     * 그 이름의 하위를 바꾸고, 없으면 끝에 세운다.
     *
     * 자리를 바꾸지 않는 것이 요점이다. 패널의 줄 순서가 상태가 바뀔 때마다 흔들리면 사람이 누르려던
     * 줄이 손 밑에서 옮겨 간다.
     */
    fun withChild(node: ChildSessionNode): SessionTree =
        if (child(node.childId) == null) {
            copy(children = children + node)
        } else {
            copy(children = children.map { if (it.childId == node.childId) node else it })
        }
}

/**
 * 하위 세션 하나의 사실.
 *
 * **대화는 여기 없다.** 하위의 전사는 메인·레포 세션과 같은 홀더가 같은 맵에 쥔다(6.11) — 같은 어댑터,
 * 같은 이벤트 모델이다. 이 노드는 그 대화 밖에서만 아는 것, 곧 엔진이 답한 자리와 수명을 든다.
 *
 * @param relativeCwd 워크디렉토리 기준 `/` 경로. 엔진이 답한 그대로라(`childSession.cwd`) 다음 spawn 에
 *   `--live-child-cwd` 로 다시 넘길 때 앱이 손대지 않는다.
 * @param workingDirectory 그 `claude` 가 도는 절대 자리. 사람이 연 레포 세션과 같은 자리인지 견주는 근거다.
 * @param conversationId 엔진이 배정한 대화 ID. 기록(`childSessions`)과 이 하위를 잇는다.
 */
data class ChildSessionNode(
    val childId: String,
    val title: String,
    val relativeCwd: String,
    val workingDirectory: Path,
    val conversationId: String,
    val waitingFor: ChildWaitingFor = ChildWaitingFor.Nothing,
    val lifecycle: ChildLifecycle = ChildLifecycle.Running,
) {

    val target: SessionTarget.Child get() = SessionTarget.Child(childId)
}

/**
 * 하위가 지금 무엇을 기다리는가(6.2 의 `waitingFor`). 상위가 하위의 침묵을 해석할 재료다.
 *
 * @param wireName 앱 소켓의 답에 실리는 낱말.
 */
enum class ChildWaitingFor(val wireName: String) {
    Nothing("nothing"),
    Model("model"),

    /** `ask_parent` 로 상위의 답을 기다린다. 상위가 `send_to_session` 으로 답할 때까지 하위의 턴이 멈춰 있다. */
    Parent("parent"),
    Approval("approval"),
}

/** 하위의 수명(6.13). 살아 있거나, 어떤 이유로 끝났다. */
sealed interface ChildLifecycle {

    data object Running : ChildLifecycle

    /** @param exitCode 프로세스가 남긴 종료 코드. 앱이 거둬 아직 받지 못했으면 null. */
    data class Ended(val reason: ChildEndReason, val exitCode: Int?) : ChildLifecycle
}

/**
 * 하위가 왜 끝났는가.
 *
 * 설계 6.6 의 셋(`exited` · `ended_by_human` · `crashed`)에 둘을 더한다 — 상위가 `end_session` 으로
 * 끝낸 것과, 상위 자신이 끝나서 함께 끝난 것. 앞의 것을 `exited` 로 적으면 상위는 자기가 끝낸 하위가
 * 스스로 끝났다고 읽고, 뒤의 것은 사람이 메인을 닫은 사실이 하위 쪽 기록에서 사라진다.
 */
enum class ChildEndReason(val wireName: String) {
    Exited("exited"),
    Crashed("crashed"),
    EndedByHuman("ended_by_human"),
    EndedByParent("ended_by_parent"),
    ParentEnded("parent_ended"),
}

/** 다음 하위의 이름과, 이번 실행에서 그 이름까지 썼다는 수. */
data class ChildSessionIdAssignment(val childId: String, val ordinal: Int)

/**
 * 다음 하위의 이름을 정한다 — `child-<n>`, 기록과 이번 실행에서 쓴 수 모두의 뒤.
 *
 * **기록을 함께 보는 이유**: 엔진은 같은 상위 아래 같은 id 를 다시 받지 않는다(conversations.go 의
 * StartChildSessionConversation). 앱을 다시 켜면 메모리의 수는 0 이지만 기록에는 앞 실행의 하위가
 * 남아 있고, 이어간 상위의 전사에도 그 이름이 있다(6.15). 같은 이름을 다시 쓰면 엔진이 거절하고,
 * 거절하지 않더라도 상위는 다른 두 하위를 한 이름으로 읽는다.
 *
 * **이번 실행의 수를 함께 보는 이유**: 이름은 엔진에게 묻기 전에 정하므로 아직 기록에 없다.
 *
 * @param recordedChildIds 기록에서 메인 아래 적혀 있는 하위 id 들. 우리 모양(`child-<n>`)이 아닌 것은 세지 않는다.
 */
fun nextChildSessionId(recordedChildIds: Set<String>, lastAssignedOrdinal: Int): ChildSessionIdAssignment {
    val highestRecordedOrdinal = recordedChildIds
        .mapNotNull { CHILD_SESSION_ID_PATTERN.matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }
        .maxOrNull() ?: 0
    val ordinal = maxOf(highestRecordedOrdinal, lastAssignedOrdinal) + 1
    return ChildSessionIdAssignment(childId = "$CHILD_SESSION_ID_PREFIX$ordinal", ordinal = ordinal)
}

/**
 * 하위의 대화에서 지금 기다리는 것을 읽는다.
 *
 * **승인이 먼저다.** 승인 물음이 걸린 턴은 사람의 답 없이 나아가지 못하므로, 턴이 도는 중이라고 답하면
 * 상위는 기다리면 될 일로 읽는다. 상위에게 묻는 중인 것도 같은 까닭으로 모델보다 앞선다 — 그 턴은 상위의 답 없이
 * 나아가지 못한다. 하위의 도구 호출은 차례로 돌아(3.3) 둘이 함께 걸리지는 않는다.
 *
 * **보낸 말이 남아 있으면 모델을 기다린다.** 첫 프롬프트를 쓴 직후에는 그 말이 아직 되돌아오지 않아
 * 턴이 시작되지 않은 것처럼 보이지만, 하위는 이미 일을 받았다.
 */
fun childWaitingFor(
    conversation: ChatConversation,
    approvalPending: Boolean,
    parentQuestionPending: Boolean,
): ChildWaitingFor = when {
    approvalPending -> ChildWaitingFor.Approval
    parentQuestionPending -> ChildWaitingFor.Parent
    conversation.turnState != TurnState.Idle || conversation.pendingUserMessages.isNotEmpty() -> ChildWaitingFor.Model
    else -> ChildWaitingFor.Nothing
}

private const val CHILD_SESSION_ID_PREFIX = "child-"

private val CHILD_SESSION_ID_PATTERN = Regex("""child-(\d+)""")
