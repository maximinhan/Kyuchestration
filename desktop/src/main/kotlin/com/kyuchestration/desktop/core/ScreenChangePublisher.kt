package com.kyuchestration.desktop.core

import com.kyuchestration.desktop.protocol.CoreStateImage
import com.kyuchestration.desktop.protocol.FrameToScreen
import com.kyuchestration.desktop.protocol.ScreenChange
import com.kyuchestration.desktop.protocol.ScreenChange.ConversationChange
import com.kyuchestration.desktop.protocol.encodedByteCount
import com.kyuchestration.desktop.protocol.header
import com.kyuchestration.desktop.protocol.toWire
import com.kyuchestration.desktop.terminal.SessionTarget
import com.kyuchestration.desktop.terminal.chat.ChatConversation
import com.kyuchestration.desktop.terminal.chat.ChatEntry

/**
 * 코어의 상태를 화면에 보낼 틀로 바꾸는 자리(core-ui-split-design.md 6.5).
 *
 * **상태 홀더는 바꾸지 않는다.** 홀더는 지금처럼 `StateFlow` 를 내놓고, 이 자리가 **마지막으로 화면에 보낸 값**과 지금 값을
 * 견주어 변화를 짓는다. 홀더마다 "변화 사건" 을 직접 내게 하면 `value = …` 하는 모든 자리(챗 홀더만 수십 군데)가 사건을
 * 함께 내야 하고, 하나를 빠뜨리면 그 변화만 화면에 안 온다. 견주기는 그 자리를 여기 하나로 줄인다.
 *
 * **합치기(conflation)를 그대로 쓴다.** 언제 견주든 지금 값과 견주므로, 그 사이 몇 번 바뀌었는지는 상관없다. 상태를 보내는
 * 것이지 사건 기록을 보내는 것이 아니라 잃는 것이 없다 — 전사의 사건 순서는 대화의 줄 안에 이미 있다(원칙 14).
 *
 * **한 줄로 부른다.** "마지막으로 보낸 값" 을 쥐는 자리가 하나여야 스냅샷과 그 뒤 변화가 같은 기준선에서 나온다. 부르는
 * 쪽(serveScreen)이 코어의 단일 스레드에서만 부른다.
 */
class ScreenChangePublisher(private val core: ScreenFacingCore) {

    /** 화면이 마지막으로 받은 상태. 스냅샷을 내기 전(아직 붙은 화면이 없다)에는 없다. */
    private var lastSent: CoreStateImage? = null

    private var lastSequence: Long = 0

    /**
     * 지금 상태 전부를 스냅샷 조각들로 내고, 그것을 새 기준선으로 삼는다(6.6).
     *
     * 첫 조각은 작은 상태 전부다. 그다음 대화마다 머리 하나, 1 MiB 안쪽으로 나눈 꼬리 여럿, 흐르는 글이 있으면 그것. 줄 하나가
     * 1 MiB 를 넘으면 그 조각만 크다 — 쪼개면 반쪽짜리 카드를 그리는 길이 생긴다.
     */
    fun snapshotFrames(): List<FrameToScreen.Snapshot> {
        val now = core.currentStateImage()
        lastSent = now
        val parts = buildList {
            add(ScreenChange.SmallStatesReplaced(now.smallStates()))
            now.conversations.forEach { (target, conversation) ->
                add(ScreenChange.ConversationHeaderChanged(target, conversation.header))
                addAll(tailInPieces(target, conversation.entries))
                streamingChange(target, previous = null, current = conversation)?.let(::add)
            }
        }
        return parts.mapIndexed { index, part ->
            FrameToScreen.Snapshot(seq = lastSequence, part = part, last = index == parts.lastIndex)
        }
    }

    /**
     * 마지막으로 보낸 뒤 바뀐 것을 변화 틀로 내고 기준선을 지금으로 옮긴다. 스냅샷을 내기 전이면 아무것도 내지 않는다.
     *
     * 순서가 뜻이다 — 대화가 서는 변화, 작은 상태, 대화가 사라지는 변화. 홀더는 진입이 끝나면 대화를 세우고 진입 줄을 빼는데,
     * 그 둘이 한 번에 견주어지면 이 순서가 거울이 "그 세션이 어디에도 없는" 순간을 들지 않게 한다. 사라질 때는 거꾸로 —
     * 세션을 다시 띄우는 진입이 사라지는 대화보다 먼저 선다.
     */
    fun pendingChangeFrames(): List<FrameToScreen.Change> {
        val sent = lastSent ?: return emptyList()
        val now = core.currentStateImage()
        lastSent = now

        val changes = buildList {
            now.conversations.forEach { (target, conversation) ->
                addAll(conversationChanges(target, sent.conversations[target], conversation))
            }
            if (now.engineInstallation != sent.engineInstallation) {
                add(ScreenChange.EngineInstallationChanged(now.engineInstallation))
            }
            if (now.claudeAuthentication != sent.claudeAuthentication) {
                add(ScreenChange.ClaudeAuthenticationChanged(now.claudeAuthentication))
            }
            if (now.workDirDashboard != sent.workDirDashboard) {
                add(ScreenChange.WorkDirDashboardChanged(now.workDirDashboard))
            }
            if (now.workDirInitialization != sent.workDirInitialization) {
                add(ScreenChange.WorkDirInitializationChanged(now.workDirInitialization))
            }
            if (now.sessionEntries != sent.sessionEntries) {
                add(ScreenChange.SessionEntriesChanged(now.sessionEntries.toWire()))
            }
            if (now.heldSessions != sent.heldSessions) {
                add(ScreenChange.HeldSessionsChanged(now.heldSessions.toList()))
            }
            if (now.sessionTree != sent.sessionTree) {
                add(ScreenChange.SessionTreeChanged(now.sessionTree))
            }
            if (now.gateQuestions != sent.gateQuestions) {
                add(ScreenChange.GateQuestionsChanged(now.gateQuestions))
            }
            if (now.repositoryClone != sent.repositoryClone) {
                add(ScreenChange.RepositoryCloneChanged(now.repositoryClone))
            }
            (sent.conversations.keys - now.conversations.keys).forEach { target ->
                add(ScreenChange.ConversationRemoved(target))
            }
        }
        return changes.map { change -> FrameToScreen.Change(seq = ++lastSequence, change = change) }
    }


    private fun tailInPieces(target: SessionTarget, entries: List<ChatEntry>): List<ScreenChange.ConversationTailChanged> {
        val pieces = mutableListOf<ScreenChange.ConversationTailChanged>()
        var pieceStart = 0
        var pieceByteCount = 0
        entries.forEachIndexed { index, entry ->
            val entryByteCount = entry.encodedByteCount()
            if (index > pieceStart && pieceByteCount + entryByteCount > SNAPSHOT_PIECE_BYTE_LIMIT) {
                pieces += ScreenChange.ConversationTailChanged(target, pieceStart, entries.subList(pieceStart, index).toList())
                pieceStart = index
                pieceByteCount = 0
            }
            pieceByteCount += entryByteCount
        }
        if (pieceStart < entries.size) {
            pieces += ScreenChange.ConversationTailChanged(target, pieceStart, entries.subList(pieceStart, entries.size).toList())
        }
        return pieces
    }


    private companion object {
        /** 스냅샷 꼬리 조각 하나의 한도. 1.26 MB 한 줄 왕복이 65 ms 였다(설계 3.1) — 조각 하나가 화면을 그만큼만 붙잡는다. */
        const val SNAPSHOT_PIECE_BYTE_LIMIT = 1024 * 1024
    }
}


/** 코어의 흐름 열 개의 지금 값. 게시자가 견주는 기준이자, 거울이 다시 세우는 것과 같은 타입이다. */
internal fun ScreenFacingCore.currentStateImage() = CoreStateImage(
    engineInstallation = engineInstallationState.value,
    claudeAuthentication = claudeAuthenticationState.value,
    workDirDashboard = workDirDashboardState.value,
    workDirInitialization = workDirInitializationState.value,
    conversations = chatConversations.value,
    sessionEntries = chatSessionEntries.value,
    heldSessions = heldChatSessionTargets.value,
    sessionTree = sessionTree.value,
    gateQuestions = childSessionGateQuestions.value,
    repositoryClone = repositoryCloneState.value,
)

/**
 * 대화 하나가 [previous] 에서 [current] 로 온 길. [previous] 가 없으면 새로 선 대화다.
 *
 * 줄은 앞에서부터 견주어 같은 앞부분을 건너뛴다. 홀더는 `copy` 로 고치므로 안 바뀐 줄은 같은 객체이고, data class 의 견주기는
 * 같은 객체에서 곧바로 끝난다 — 긴 대화도 대부분 참조 비교로 지난다.
 */
private fun conversationChanges(
    target: SessionTarget,
    previous: ChatConversation?,
    current: ChatConversation,
): List<ConversationChange> = buildList {
    if (previous == null || previous.header != current.header) {
        add(ScreenChange.ConversationHeaderChanged(target, current.header))
    }
    val previousEntries = previous?.entries.orEmpty()
    val firstDifferentIndex = firstDifferentIndex(previousEntries, current.entries)
    if (firstDifferentIndex < previousEntries.size || firstDifferentIndex < current.entries.size) {
        add(
            ScreenChange.ConversationTailChanged(
                target = target,
                fromIndex = firstDifferentIndex,
                entries = current.entries.subList(firstDifferentIndex, current.entries.size).toList(),
            ),
        )
    }
    streamingChange(target, previous, current)?.let(::add)
}

private fun firstDifferentIndex(previous: List<ChatEntry>, current: List<ChatEntry>): Int {
    val sharedLength = minOf(previous.size, current.size)
    for (index in 0 until sharedLength) {
        if (previous[index] != current[index]) {
            return index
        }
    }
    return sharedLength
}

/**
 * 흐르는 글의 변화. 같은 블록에서 앞 글이 새 글의 앞부분이면 덧붙인 만큼만 보낸다. 블록이 바뀌었거나 완성본이 와서 비었으면
 * 통째로 보낸다 — 화면의 스트리밍 렌더러는 블록이 바뀌면 상태를 새로 세운다(ChatConversation.streamingBlockOrdinal).
 */
private fun streamingChange(target: SessionTarget, previous: ChatConversation?, current: ChatConversation): ConversationChange? {
    val previousText = previous?.streamingText
    val previousBlockOrdinal = previous?.streamingBlockOrdinal ?: 0
    val currentText = current.streamingText
    if (previousText == currentText && previousBlockOrdinal == current.streamingBlockOrdinal) {
        return null
    }
    if (previousBlockOrdinal == current.streamingBlockOrdinal && previousText != null && currentText != null &&
        currentText.startsWith(previousText)
    ) {
        return ScreenChange.StreamingTextAppended(target, current.streamingBlockOrdinal, currentText.substring(previousText.length))
    }
    return ScreenChange.StreamingTextReplaced(target, current.streamingBlockOrdinal, currentText)
}
