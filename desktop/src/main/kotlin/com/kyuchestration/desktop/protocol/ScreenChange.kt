package com.kyuchestration.desktop.protocol

import com.kyuchestration.desktop.claudeauth.ClaudeAuthenticationState
import com.kyuchestration.desktop.dashboard.WorkDirDashboardState
import com.kyuchestration.desktop.engine.EngineInstallationState
import com.kyuchestration.desktop.initialization.WorkDirInitializationState
import com.kyuchestration.desktop.repoclone.RepositoryCloneState
import com.kyuchestration.desktop.terminal.PersonOpenedSessionTarget
import com.kyuchestration.desktop.terminal.SessionTarget
import com.kyuchestration.desktop.terminal.chat.ChatEntry
import com.kyuchestration.desktop.terminal.chat.ChildSessionGateQuestion
import com.kyuchestration.desktop.terminal.chat.OutgoingUserMessage
import com.kyuchestration.desktop.terminal.chat.RateLimitWindow
import com.kyuchestration.desktop.terminal.chat.SessionEntryState
import com.kyuchestration.desktop.terminal.chat.SessionTree
import com.kyuchestration.desktop.terminal.chat.TurnState
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 코어의 상태가 바뀐 것 하나(core-ui-split-design.md 6.5).
 *
 * **작은 상태는 통째로 바꾼다.** 엔진 설치 · 인증 · 대시보드 · 초기화 · 진입 · 보유 세션 · 트리 · 관문 · 클론은 작아서, 견주고
 * 읽는 비용이 가장 적은 것이 통째로 보내는 것이다. **대화는 좁은 변화로 바꾼다**([ConversationChange]) — 크고, 거의
 * 꼬리에서만 바뀐다.
 *
 * 맵은 목록으로 건넨다. JSON 객체의 열쇠는 문자열뿐이라 `{"type":"repo","repoName":…}` 같은 대상을 열쇠로 둘 수 없다.
 */
@Serializable
sealed interface ScreenChange {

    @Serializable
    @SerialName("engine_installation")
    data class EngineInstallationChanged(val state: EngineInstallationState) : ScreenChange

    @Serializable
    @SerialName("claude_authentication")
    data class ClaudeAuthenticationChanged(val state: ClaudeAuthenticationState) : ScreenChange

    @Serializable
    @SerialName("workdir_dashboard")
    data class WorkDirDashboardChanged(val state: WorkDirDashboardState) : ScreenChange

    @Serializable
    @SerialName("workdir_initialization")
    data class WorkDirInitializationChanged(val state: WorkDirInitializationState) : ScreenChange

    @Serializable
    @SerialName("session_entries")
    data class SessionEntriesChanged(val entries: List<SessionEntryOfTarget>) : ScreenChange

    @Serializable
    @SerialName("held_sessions")
    data class HeldSessionsChanged(val targets: List<SessionTarget>) : ScreenChange

    @Serializable
    @SerialName("session_tree")
    data class SessionTreeChanged(val tree: SessionTree) : ScreenChange

    @Serializable
    @SerialName("gate_questions")
    data class GateQuestionsChanged(val questions: List<ChildSessionGateQuestion>) : ScreenChange

    @Serializable
    @SerialName("repository_clone")
    data class RepositoryCloneChanged(val state: RepositoryCloneState) : ScreenChange

    /**
     * 작은 상태 전부 — 스냅샷의 첫 조각(6.6). 거울은 이것으로 새 기준을 세우고 나머지 조각(대화)을 그 위에 얹는다.
     * 스냅샷 밖에서는 오지 않는다.
     */
    @Serializable
    @SerialName("small_states")
    data class SmallStatesReplaced(val states: SmallStates) : ScreenChange

    /** 대화 하나의 변화. 대화는 세션마다 따로 바뀌므로 언제나 대상을 단다. */
    @Serializable
    sealed interface ConversationChange : ScreenChange {
        val target: SessionTarget
    }

    /** 줄과 흐르는 글을 뺀 대화의 나머지. 처음 오면 그 대화가 선다. */
    @Serializable
    @SerialName("conversation_header")
    data class ConversationHeaderChanged(
        override val target: SessionTarget,
        val header: ConversationHeader,
    ) : ConversationChange

    /**
     * [fromIndex] 부터 끝까지의 줄을 [entries] 로 바꾼다.
     *
     * 줄은 꼬리에 붙고, 바뀌는 것도 꼬리의 몇 줄이다(도구 카드에 답이 오고 승인 카드에 답이 적힌다). 같은 앞부분을 건너뛰고
     * 나머지를 통째로 바꾸면 덧붙이기와 고치기와 줄이기가 한 모양으로 선다.
     */
    @Serializable
    @SerialName("conversation_tail")
    data class ConversationTailChanged(
        override val target: SessionTarget,
        val fromIndex: Int,
        val entries: List<ChatEntry>,
    ) : ConversationChange

    /**
     * 흐르는 글에 덧붙었다. 글자가 흐를 때마다 글 전체를 보내면 보내는 양이 길이의 제곱으로 는다.
     *
     * 덧붙이기와 바꾸기를 두 갈래로 가르는 것은 바꾸기의 null(완성본이 와서 버퍼가 비었다)이 "필드 없음" 과 섞이지 않게다.
     */
    @Serializable
    @SerialName("streaming_text_appended")
    data class StreamingTextAppended(
        override val target: SessionTarget,
        val blockOrdinal: Long,
        val append: String,
    ) : ConversationChange

    /** 흐르는 글이 통째로 바뀌었다 — 새 블록이 시작됐거나 완성본이 와서 비었다(null). */
    @Serializable
    @SerialName("streaming_text_replaced")
    data class StreamingTextReplaced(
        override val target: SessionTarget,
        val blockOrdinal: Long,
        val text: String?,
    ) : ConversationChange

    @Serializable
    @SerialName("conversation_removed")
    data class ConversationRemoved(override val target: SessionTarget) : ConversationChange
}

/** 진입 상태 맵의 한 칸. */
@Serializable
data class SessionEntryOfTarget(val target: PersonOpenedSessionTarget, val entry: SessionEntryState)

/** 스냅샷의 첫 조각이 싣는 작은 상태 아홉. */
@Serializable
data class SmallStates(
    val engineInstallation: EngineInstallationState,
    val claudeAuthentication: ClaudeAuthenticationState,
    val workDirDashboard: WorkDirDashboardState,
    val workDirInitialization: WorkDirInitializationState,
    val sessionEntries: List<SessionEntryOfTarget>,
    val heldSessions: List<SessionTarget>,
    val sessionTree: SessionTree,
    val gateQuestions: List<ChildSessionGateQuestion>,
    val repositoryClone: RepositoryCloneState,
)

/**
 * [ChatConversation] 에서 줄([ChatConversation.entries])과 흐르는 글을 뺀 것. 그 둘은 따로 바뀌고(6.5), 나머지는 작아서
 * 하나라도 바뀌면 통째로 보낸다.
 */
@Serializable
data class ConversationHeader(
    val workingDirectory: String?,
    val conversationId: String?,
    val modelName: String?,
    val resumedConversationId: String?,
    val pendingUserMessages: List<OutgoingUserMessage>,
    val turnState: TurnState,
    val totalCostUsd: Double,
    val usageWindows: List<RateLimitWindow>,
    val endedExitCode: Int?,
)
