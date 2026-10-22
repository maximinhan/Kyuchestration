package com.kyuchestration.desktop.core

import com.kyuchestration.desktop.engine.EngineInstallationState
import com.kyuchestration.desktop.protocol.CoreStateImage
import com.kyuchestration.desktop.protocol.FrameToScreen
import com.kyuchestration.desktop.protocol.ScreenChange
import com.kyuchestration.desktop.protocol.ScreenFacingCore
import com.kyuchestration.desktop.protocol.decodeFrameToScreen
import com.kyuchestration.desktop.protocol.encodeFrameToScreen
import com.kyuchestration.desktop.repoclone.CloneStep
import com.kyuchestration.desktop.repoclone.RepositoryCloneState
import com.kyuchestration.desktop.terminal.SessionTarget
import com.kyuchestration.desktop.terminal.chat.ChatConversation
import com.kyuchestration.desktop.terminal.chat.ChatEntry
import com.kyuchestration.desktop.terminal.chat.OutgoingUserMessage
import com.kyuchestration.desktop.terminal.chat.PermissionAnswer
import com.kyuchestration.desktop.terminal.chat.SessionEntryState
import com.kyuchestration.desktop.terminal.chat.TurnState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonObject

/**
 * 게시자가 낸 틀을 줄로 썼다 읽어 상태에 얹으면 코어의 상태와 같은가(core-ui-split-design.md 6.15 의 "게시자 → 거울").
 *
 * 한 걸음마다 견준다. 마지막에만 견주면 중간에 어긋났다가 우연히 맞아 들어간 길을 놓친다.
 */
class ScreenChangeRoundTripTest {

    private val core = RecordingScreenFacingCore()
    private val publisher = ScreenChangePublisher(core)

    @Test
    fun `스냅샷과 그 뒤 변화들을 얹은 상태가 걸음마다 코어의 상태와 같다`() {
        core.chatConversations.value = mapOf(
            SessionTarget.Main to ChatConversation(SessionTarget.Main, entries = List(3) { ChatEntry.AssistantSaid("가".repeat(200_000)) }),
        )
        var image = imageFromSnapshot()
        assertEquals(core.currentStateImage(), image)

        val steps: List<() -> Unit> = listOf(
            { updateMain { it.copy(entries = it.entries + ChatEntry.UserSaid("다음")) } },
            { updateMain { it.copy(pendingUserMessages = listOf(OutgoingUserMessage("기다리는 말", uuid = "u-1"))) } },
            { updateMain { it.copy(turnState = TurnState.Running, streamingText = "흐", streamingBlockOrdinal = 1) } },
            { updateMain { it.copy(streamingText = "흐르는 글") } },
            { updateMain { it.copy(streamingText = "새 블록", streamingBlockOrdinal = 2) } },
            {
                updateMain {
                    it.copy(
                        entries = it.entries + ChatEntry.PermissionAsked("toolu_01", "Write", JsonObject(emptyMap())),
                        streamingText = null,
                    )
                }
            },
            { updateMain { it.copy(entries = it.entries.dropLast(1) + ChatEntry.PermissionAsked("toolu_01", "Write", JsonObject(emptyMap()), PermissionAnswer.Withdrawn)) } },
            { updateMain { it.copy(entries = it.entries.take(1)) } },
            { core.chatSessionEntries.value = mapOf(SessionTarget.Repo("proj-a") to SessionEntryState.Running) },
            {
                core.chatConversations.value += SessionTarget.Repo("proj-a") to ChatConversation(SessionTarget.Repo("proj-a"), workingDirectory = "/w/proj-a")
                core.chatSessionEntries.value = emptyMap()
                core.heldChatSessionTargets.value = setOf(SessionTarget.Main, SessionTarget.Repo("proj-a"))
            },
            { core.engineInstallationState.value = EngineInstallationState.InstallationRunning },
            { core.repositoryCloneState.value = RepositoryCloneState.StepRunning(CloneStep.LoadTokenProfiles) },
            {
                core.chatConversations.value -= SessionTarget.Main
                core.heldChatSessionTargets.value = setOf(SessionTarget.Repo("proj-a"))
            },
            { core.chatConversations.value += SessionTarget.Main to ChatConversation(SessionTarget.Main) },
        )

        steps.forEach { step ->
            step()
            image = publisher.pendingChangeFrames().fold(image) { current, frame -> current.after(throughTheWire(frame).change) }
            assertEquals(core.currentStateImage(), image)
        }
    }

    private fun updateMain(change: (ChatConversation) -> ChatConversation) {
        core.chatConversations.value += SessionTarget.Main to change(core.chatConversations.value.getValue(SessionTarget.Main))
    }

    private fun imageFromSnapshot(): CoreStateImage {
        val parts = publisher.snapshotLines().map { (decodeFrameToScreen(it) as FrameToScreen.Snapshot).part }
        val first = parts.first() as ScreenChange.SmallStatesReplaced
        return parts.drop(1).fold(CoreStateImage.of(first.states)) { image, part -> image.after(part) }
    }


    @Suppress("UNCHECKED_CAST")
    private fun <T : FrameToScreen> throughTheWire(frame: T): T = decodeFrameToScreen(encodeFrameToScreen(frame)) as T
}
