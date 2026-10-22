package com.kyuchestration.desktop.protocol

import com.kyuchestration.desktop.claudeauth.ClaudeAuthenticationState
import com.kyuchestration.desktop.dashboard.WorkDirDashboardState
import com.kyuchestration.desktop.engine.EngineInstallationState
import com.kyuchestration.desktop.initialization.WorkDirInitializationState
import com.kyuchestration.desktop.repoclone.RepositoryCloneState
import com.kyuchestration.desktop.terminal.PersonOpenedSessionTarget
import com.kyuchestration.desktop.terminal.SessionTarget
import com.kyuchestration.desktop.terminal.chat.ChatConversation
import com.kyuchestration.desktop.terminal.chat.ChildSessionGateQuestion
import com.kyuchestration.desktop.terminal.chat.SessionEntryState
import com.kyuchestration.desktop.terminal.chat.SessionTree

/**
 * 화면이 받은 틀로 다시 세운 코어의 상태 — `ScreenFacingCore` 의 `StateFlow` 열 개가 들 값이다.
 *
 * 변화를 상태에 얹는 규칙([after])을 순수 함수로 둔다. 거울은 이것을 받아 흐름에 싣기만 하고, 게시자 → 거울의 왕복은 화면
 * 없이 이 함수로 시험한다(core-ui-split-design.md 6.15).
 */
data class CoreStateImage(
    val engineInstallation: EngineInstallationState,
    val claudeAuthentication: ClaudeAuthenticationState,
    val workDirDashboard: WorkDirDashboardState,
    val workDirInitialization: WorkDirInitializationState,
    val conversations: Map<SessionTarget, ChatConversation>,
    val sessionEntries: Map<PersonOpenedSessionTarget, SessionEntryState>,
    val heldSessions: Set<SessionTarget>,
    val sessionTree: SessionTree,
    val gateQuestions: List<ChildSessionGateQuestion>,
    val repositoryClone: RepositoryCloneState,
) {

    /**
     * [change] 를 얹은 상태.
     *
     * 대화의 변화는 머리가 먼저 와 대화를 세운다는 약속 위에 있다(게시자가 그렇게 낸다). 머리 없는 꼬리, 지금 줄 수보다 먼
     * 자리부터의 꼬리처럼 그 약속이 깨진 틀은 받아들이지 않는다 — 조용히 얹으면 거울이 코어와 어긋난 채로 계속 그린다.
     */
    fun after(change: ScreenChange): CoreStateImage = when (change) {
        is ScreenChange.EngineInstallationChanged -> copy(engineInstallation = change.state)
        is ScreenChange.ClaudeAuthenticationChanged -> copy(claudeAuthentication = change.state)
        is ScreenChange.WorkDirDashboardChanged -> copy(workDirDashboard = change.state)
        is ScreenChange.WorkDirInitializationChanged -> copy(workDirInitialization = change.state)
        is ScreenChange.SessionEntriesChanged -> copy(sessionEntries = change.entries.toSessionEntryMap())
        is ScreenChange.HeldSessionsChanged -> copy(heldSessions = change.targets.toSet())
        is ScreenChange.SessionTreeChanged -> copy(sessionTree = change.tree)
        is ScreenChange.GateQuestionsChanged -> copy(gateQuestions = change.questions)
        is ScreenChange.RepositoryCloneChanged -> copy(repositoryClone = change.state)
        is ScreenChange.SmallStatesReplaced -> of(change.states).copy(conversations = conversations)
        is ScreenChange.ConversationRemoved -> copy(conversations = conversations - change.target)
        is ScreenChange.ConversationHeaderChanged -> withConversation(
            change.target,
            (conversations[change.target] ?: ChatConversation(change.target)).withHeader(change.header),
        )
        is ScreenChange.ConversationTailChanged -> {
            val conversation = conversationTheChangeNeeds(change)
            check(change.fromIndex <= conversation.entries.size) {
                "${change.target.label} 대화는 줄이 ${conversation.entries.size} 개인데 ${change.fromIndex} 번째부터 바꾸라는 틀이 왔다"
            }
            withConversation(change.target, conversation.copy(entries = conversation.entries.take(change.fromIndex) + change.entries))
        }
        is ScreenChange.StreamingTextAppended -> {
            val conversation = conversationTheChangeNeeds(change)
            val streamingText = checkNotNull(conversation.streamingText) {
                "${change.target.label} 대화에 흐르는 글이 없는데 덧붙이라는 틀이 왔다"
            }
            withConversation(
                change.target,
                conversation.copy(streamingText = streamingText + change.append, streamingBlockOrdinal = change.blockOrdinal),
            )
        }
        is ScreenChange.StreamingTextReplaced -> withConversation(
            change.target,
            conversationTheChangeNeeds(change).copy(streamingText = change.text, streamingBlockOrdinal = change.blockOrdinal),
        )
    }

    private fun conversationTheChangeNeeds(change: ScreenChange.ConversationChange): ChatConversation =
        checkNotNull(conversations[change.target]) { "${change.target.label} 대화의 머리보다 ${change::class.simpleName} 이 먼저 왔다" }

    private fun withConversation(target: SessionTarget, conversation: ChatConversation) =
        copy(conversations = conversations + (target to conversation))

    companion object {

        /** 스냅샷의 첫 조각으로 세운 상태. 대화는 뒤따르는 조각이 하나씩 세운다. */
        fun of(states: SmallStates) = CoreStateImage(
            engineInstallation = states.engineInstallation,
            claudeAuthentication = states.claudeAuthentication,
            workDirDashboard = states.workDirDashboard,
            workDirInitialization = states.workDirInitialization,
            conversations = emptyMap(),
            sessionEntries = states.sessionEntries.toSessionEntryMap(),
            heldSessions = states.heldSessions.toSet(),
            sessionTree = states.sessionTree,
            gateQuestions = states.gateQuestions,
            repositoryClone = states.repositoryClone,
        )
    }
}

private fun List<SessionEntryOfTarget>.toSessionEntryMap(): Map<PersonOpenedSessionTarget, SessionEntryState> =
    associate { it.target to it.entry }

private fun ChatConversation.withHeader(header: ConversationHeader): ChatConversation = copy(
    workingDirectory = header.workingDirectory,
    conversationId = header.conversationId,
    modelName = header.modelName,
    resumedConversationId = header.resumedConversationId,
    pendingUserMessages = header.pendingUserMessages,
    turnState = header.turnState,
    totalCostUsd = header.totalCostUsd,
    usageWindows = header.usageWindows,
    endedExitCode = header.endedExitCode,
)
