package com.kyuchestration.desktop.core

import com.kyuchestration.desktop.engine.EngineInstallationState
import com.kyuchestration.desktop.protocol.ConversationHeader
import com.kyuchestration.desktop.protocol.FrameToScreen
import com.kyuchestration.desktop.protocol.ScreenChange
import com.kyuchestration.desktop.protocol.SessionEntryOfTarget
import com.kyuchestration.desktop.protocol.SmallStates
import com.kyuchestration.desktop.protocol.decodeFrameToScreen
import com.kyuchestration.desktop.protocol.encodeFrameToScreen
import com.kyuchestration.desktop.terminal.SessionTarget
import com.kyuchestration.desktop.terminal.chat.ChatConversation
import com.kyuchestration.desktop.terminal.chat.ChatEntry
import com.kyuchestration.desktop.terminal.chat.SessionEntryState
import com.kyuchestration.desktop.terminal.chat.ToolCallAnswer
import com.kyuchestration.desktop.terminal.chat.TurnState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject

/**
 * 코어의 상태가 틀이 되는 규칙(core-ui-split-design.md 6.5 · 6.6). 작은 상태는 바뀌면 통째로, 대화는 바뀐 꼬리만, 흐르는 글은
 * 덧붙인 만큼만 — 그리고 변화마다 하나씩 느는 `seq`.
 */
class ScreenChangePublisherTest {

    private val core = RecordingScreenFacingCore()
    private val publisher = ScreenChangePublisher(core)

    @Test
    fun `스냅샷을 내기 전에는 변화도 내지 않는다 — 기준선이 없다`() {
        core.engineInstallationState.value = EngineInstallationState.InstallationRunning

        assertEquals(emptyList(), publisher.pendingChangeFrames())
    }

    @Test
    fun `스냅샷은 작은 상태 전부로 시작하고 대화마다 머리 · 꼬리 · 흐르는 글을 잇고 마지막 조각에 last 를 단다`() {
        core.chatSessionEntries.value = mapOf(SessionTarget.Repo("proj-a") to SessionEntryState.Running)
        core.heldChatSessionTargets.value = setOf(SessionTarget.Main)
        core.chatConversations.value = mapOf(SessionTarget.Main to MAIN.copy(streamingText = "흐르는", streamingBlockOrdinal = 2))

        val frames = publisher.snapshotFramesThroughTheWire()

        assertEquals(
            listOf(
                ScreenChange.SmallStatesReplaced(
                    SmallStates(
                        engineInstallation = core.engineInstallationState.value,
                        claudeAuthentication = core.claudeAuthenticationState.value,
                        workDirDashboard = core.workDirDashboardState.value,
                        workDirInitialization = core.workDirInitializationState.value,
                        sessionEntries = listOf(SessionEntryOfTarget(SessionTarget.Repo("proj-a"), SessionEntryState.Running)),
                        heldSessions = listOf(SessionTarget.Main),
                        sessionTree = core.sessionTree.value,
                        gateQuestions = emptyList(),
                        repositoryClone = core.repositoryCloneState.value,
                    ),
                ),
                ScreenChange.ConversationHeaderChanged(SessionTarget.Main, MAIN_HEADER),
                ScreenChange.ConversationTailChanged(SessionTarget.Main, 0, MAIN.entries),
                ScreenChange.StreamingTextReplaced(SessionTarget.Main, blockOrdinal = 2, text = "흐르는"),
            ),
            frames.map { it.part },
        )
        assertEquals(listOf(false, false, false, true), frames.map { it.last })
        assertEquals(setOf(0L), frames.map { it.seq }.toSet())
    }

    @Test
    fun `스냅샷 뒤에 바뀐 작은 상태는 통째로 하나의 변화가 되고 seq 가 하나씩 는다`() {
        publisher.snapshotFramesThroughTheWire()

        core.engineInstallationState.value = EngineInstallationState.InstallationRunning
        val first = publisher.pendingChangeFrames()
        core.engineInstallationState.value = EngineInstallationState.EngineReady
        val second = publisher.pendingChangeFrames()

        assertEquals(
            listOf(FrameToScreen.Change(1, ScreenChange.EngineInstallationChanged(EngineInstallationState.InstallationRunning))),
            first,
        )
        assertEquals(
            listOf(FrameToScreen.Change(2, ScreenChange.EngineInstallationChanged(EngineInstallationState.EngineReady))),
            second,
        )
        assertEquals(emptyList(), publisher.pendingChangeFrames())
    }

    @Test
    fun `대화 끝에 붙은 줄은 그 줄만 꼬리로 나간다`() {
        core.chatConversations.value = mapOf(SessionTarget.Main to MAIN)
        publisher.snapshotFramesThroughTheWire()

        val said = ChatEntry.AssistantSaid("새 답")
        core.chatConversations.value = mapOf(SessionTarget.Main to MAIN.copy(entries = MAIN.entries + said))

        assertEquals(
            listOf(ScreenChange.ConversationTailChanged(SessionTarget.Main, fromIndex = 2, entries = listOf(said))),
            publisher.pendingChangeFrames().map { it.change },
        )
    }

    @Test
    fun `가운데 줄이 바뀌면 그 자리부터 끝까지가 나간다`() {
        core.chatConversations.value = mapOf(SessionTarget.Main to MAIN)
        publisher.snapshotFramesThroughTheWire()

        val answered = TOOL_CALL.copy(answer = ToolCallAnswer(failed = false, modelVisibleText = "ok"))
        core.chatConversations.value = mapOf(SessionTarget.Main to MAIN.copy(entries = listOf(MAIN.entries[0], answered)))

        assertEquals(
            listOf(ScreenChange.ConversationTailChanged(SessionTarget.Main, fromIndex = 1, entries = listOf(answered))),
            publisher.pendingChangeFrames().map { it.change },
        )
    }

    @Test
    fun `머리만 바뀌면 줄은 보내지 않는다`() {
        core.chatConversations.value = mapOf(SessionTarget.Main to MAIN)
        publisher.snapshotFramesThroughTheWire()

        core.chatConversations.value = mapOf(SessionTarget.Main to MAIN.copy(turnState = TurnState.Running))

        assertEquals(
            listOf(ScreenChange.ConversationHeaderChanged(SessionTarget.Main, MAIN_HEADER.copy(turnState = TurnState.Running))),
            publisher.pendingChangeFrames().map { it.change },
        )
    }

    @Test
    fun `같은 블록에서 흐르는 글은 덧붙인 만큼만 나가고 블록이 바뀌거나 비면 통째로 나간다`() {
        core.chatConversations.value = mapOf(SessionTarget.Main to MAIN.copy(streamingText = "안녕", streamingBlockOrdinal = 1))
        publisher.snapshotFramesThroughTheWire()

        core.chatConversations.value = mapOf(SessionTarget.Main to MAIN.copy(streamingText = "안녕하세요", streamingBlockOrdinal = 1))
        val appended = publisher.pendingChangeFrames().map { it.change }
        core.chatConversations.value = mapOf(SessionTarget.Main to MAIN.copy(streamingText = "새", streamingBlockOrdinal = 2))
        val newBlock = publisher.pendingChangeFrames().map { it.change }
        core.chatConversations.value = mapOf(SessionTarget.Main to MAIN.copy(streamingText = null, streamingBlockOrdinal = 2))
        val cleared = publisher.pendingChangeFrames().map { it.change }

        assertEquals(listOf(ScreenChange.StreamingTextAppended(SessionTarget.Main, 1, "하세요")), appended)
        assertEquals(listOf(ScreenChange.StreamingTextReplaced(SessionTarget.Main, 2, "새")), newBlock)
        assertEquals(listOf(ScreenChange.StreamingTextReplaced(SessionTarget.Main, 2, null)), cleared)
    }

    @Test
    fun `사라진 대화는 사라졌다고 나간다`() {
        core.chatConversations.value = mapOf(SessionTarget.Main to MAIN)
        publisher.snapshotFramesThroughTheWire()

        core.chatConversations.value = emptyMap()

        assertEquals(listOf(ScreenChange.ConversationRemoved(SessionTarget.Main)), publisher.pendingChangeFrames().map { it.change })
    }

    @Test
    fun `진입이 끝나 대화가 서는 묶음에서 대화가 진입 상태보다 먼저 나간다`() {
        // 거꾸로 나가면 거울이 그 사이 한 번 "그 세션이 어디에도 없다" 를 들고, 화면은 보던 세션을 놓는다(ChatViewing).
        core.chatSessionEntries.value = mapOf(SessionTarget.Main to SessionEntryState.Running)
        publisher.snapshotFramesThroughTheWire()

        core.chatConversations.value = mapOf(SessionTarget.Main to MAIN)
        core.chatSessionEntries.value = emptyMap()
        val changes = publisher.pendingChangeFrames().map { it.change }

        assertTrue(changes.indexOfFirst { it is ScreenChange.ConversationHeaderChanged } < changes.indexOfFirst { it is ScreenChange.SessionEntriesChanged })
    }

    @Test
    fun `큰 대화의 스냅샷은 1 MiB 안쪽의 꼬리 조각으로 나뉘고 줄 하나는 쪼개지 않는다`() {
        val bigEntries = List(5) { ChatEntry.AssistantSaid("가".repeat(150_000)) } + ChatEntry.AssistantSaid("나".repeat(400_000))
        core.chatConversations.value = mapOf(SessionTarget.Main to ChatConversation(SessionTarget.Main, entries = bigEntries))

        val tails = publisher.snapshotFramesThroughTheWire().map { it.part }.filterIsInstance<ScreenChange.ConversationTailChanged>()

        assertEquals(bigEntries, tails.flatMap { it.entries })
        assertEquals(tails.runningFold(0) { index, tail -> index + tail.entries.size }.dropLast(1), tails.map { it.fromIndex })
        tails.filter { it.entries.size > 1 }.forEach { tail ->
            assertTrue(encodeFrameToScreen(FrameToScreen.Snapshot(0, tail, last = false)).toByteArray().size <= 1024 * 1024 + 256)
        }
        assertTrue(tails.size > 1)
    }

    @Test
    fun `다시 스냅샷을 내면 기준선이 지금으로 옮겨 그 사이의 변화를 따로 내지 않고 seq 는 이어진다`() {
        publisher.snapshotFramesThroughTheWire()
        core.engineInstallationState.value = EngineInstallationState.InstallationRunning
        publisher.pendingChangeFrames()

        core.engineInstallationState.value = EngineInstallationState.EngineReady
        val again = publisher.snapshotFramesThroughTheWire()

        assertEquals(setOf(1L), again.map { it.seq }.toSet())
        assertEquals(emptyList(), publisher.pendingChangeFrames())
    }

    @Test
    fun `스냅샷의 꼬리 조각 줄은 줄마다 한 번 써 둔 것을 이어 붙여도 틀을 통째로 쓴 줄과 바이트까지 같다`() {
        val entries = listOf(ChatEntry.UserSaid("안녕 \"따옴표\" \n 줄바꿈"), TOOL_CALL, ChatEntry.AssistantSaid("entries:[] 를 담은 글"))
        core.chatConversations.value = mapOf(SessionTarget.Repo("entries") to ChatConversation(SessionTarget.Repo("entries"), entries = entries))

        val lines = publisher.snapshotLines()

        assertEquals(
            encodeFrameToScreen(
                FrameToScreen.Snapshot(0, ScreenChange.ConversationTailChanged(SessionTarget.Repo("entries"), 0, entries), last = true),
            ),
            lines.last(),
        )
    }

    private fun ScreenChangePublisher.snapshotFramesThroughTheWire(): List<FrameToScreen.Snapshot> =
        snapshotLines().map { decodeFrameToScreen(it) as FrameToScreen.Snapshot }

    private companion object {
        val TOOL_CALL = ChatEntry.ToolCall(toolUseId = "toolu_01", toolName = "Bash", input = JsonObject(emptyMap()))

        val MAIN = ChatConversation(
            target = SessionTarget.Main,
            workingDirectory = "/w",
            conversationId = "c0nv",
            entries = listOf(ChatEntry.UserSaid("안녕"), TOOL_CALL),
        )

        val MAIN_HEADER = ConversationHeader(
            workingDirectory = "/w",
            conversationId = "c0nv",
            modelName = null,
            resumedConversationId = null,
            pendingUserMessages = emptyList(),
            turnState = TurnState.Idle,
            totalCostUsd = 0.0,
            usageWindows = emptyList(),
            endedExitCode = null,
        )
    }
}
