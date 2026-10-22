package com.kyuchestration.desktop.mirror

import com.kyuchestration.desktop.claudeauth.ClaudeAuthenticationState
import com.kyuchestration.desktop.core.CALLS_TO_EVERY_SCREEN_COMMAND
import com.kyuchestration.desktop.core.RecordingScreenFacingCore
import com.kyuchestration.desktop.core.ScreenFacingCore
import com.kyuchestration.desktop.dashboard.WorkDirDashboardState
import com.kyuchestration.desktop.diagnostics.DiagnosticLog
import com.kyuchestration.desktop.diagnostics.DiagnosticLogEntry
import com.kyuchestration.desktop.engine.EngineInstallationState
import com.kyuchestration.desktop.initialization.WorkDirInitializationState
import com.kyuchestration.desktop.protocol.CommandOutcome
import com.kyuchestration.desktop.protocol.ConversationHeader
import com.kyuchestration.desktop.protocol.FrameToCore
import com.kyuchestration.desktop.protocol.FrameToScreen
import com.kyuchestration.desktop.protocol.ScreenChange
import com.kyuchestration.desktop.protocol.SessionEntryOfTarget
import com.kyuchestration.desktop.protocol.SmallStates
import com.kyuchestration.desktop.protocol.decodeFrameToCore
import com.kyuchestration.desktop.protocol.encodeFrameToScreen
import com.kyuchestration.desktop.repoclone.RepositoryCloneState
import com.kyuchestration.desktop.terminal.PersonOpenedSessionTarget
import com.kyuchestration.desktop.terminal.SessionTarget
import com.kyuchestration.desktop.terminal.chat.ChatEntry
import com.kyuchestration.desktop.terminal.chat.SessionEntryState
import com.kyuchestration.desktop.terminal.chat.SessionTree
import com.kyuchestration.desktop.terminal.chat.TurnState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * 화면 쪽 거울(core-ui-split-design.md 6.5) — 틀을 받아 `ScreenFacingCore` 를 다시 세우고, 명령을 틀로 보낸다.
 *
 * 코어 쪽은 손으로 쓴 틀이다. 게시자가 낸 틀이 거울에 같은 상태로 서는지는 파이프 왕복 시험이 본다 — 여기는 거울의 규칙
 * (인사 → 스냅샷, `seq` 건너뜀에 다시 인사, 명령의 id 와 답)만 본다.
 */
class CoreMirrorTest {

    @Test
    fun `붙으면 인사하고 인사 답과 스냅샷으로 첫 상태를 세운다`() = runTest {
        val pipe = MirrorTestPipe()
        pipe.coreSends(FrameToScreen.Welcome(engineDirectory = "/home/yawn/engine"))
        pipe.coreSendsSnapshot(seq = 4, SMALL_STATES, MAIN_HEADER, MAIN_TAIL)

        val mirror = pipe.connect(this)

        assertEquals(listOf<FrameToCore>(FrameToCore.Hello), pipe.framesSentToCore())
        assertEquals("/home/yawn/engine", mirror.engineDirectoryLabel)
        assertEquals(EngineInstallationState.EngineReady, mirror.engineInstallationState.value)
        assertEquals(mapOf<PersonOpenedSessionTarget, SessionEntryState>(SessionTarget.Repo("proj-a") to SessionEntryState.Running), mirror.chatSessionEntries.value)
        assertEquals(listOf(ChatEntry.UserSaid("안녕")), mirror.chatConversations.value.getValue(SessionTarget.Main).entries)
        assertEquals(TurnState.Running, mirror.chatConversations.value.getValue(SessionTarget.Main).turnState)
    }

    @Test
    fun `이어지는 변화를 그 흐름에 얹는다`() = runTest {
        val pipe = MirrorTestPipe()
        pipe.coreSends(FrameToScreen.Welcome("/e"))
        pipe.coreSendsSnapshot(seq = 4, SMALL_STATES, MAIN_HEADER, MAIN_TAIL)
        val mirror = pipe.connect(this)

        pipe.coreSends(FrameToScreen.Change(5, ScreenChange.EngineInstallationChanged(EngineInstallationState.InstallationRunning)))
        pipe.coreSends(FrameToScreen.Change(6, ScreenChange.ConversationTailChanged(SessionTarget.Main, 1, listOf(ChatEntry.AssistantSaid("네")))))
        runCurrent()

        assertEquals(EngineInstallationState.InstallationRunning, mirror.engineInstallationState.value)
        assertEquals(
            listOf(ChatEntry.UserSaid("안녕"), ChatEntry.AssistantSaid("네")),
            mirror.chatConversations.value.getValue(SessionTarget.Main).entries,
        )
    }

    @Test
    fun `seq 가 건너뛰면 다시 인사하고 새 스냅샷의 마지막 조각이 올 때까지 거울을 그대로 둔다`() = runTest {
        val pipe = MirrorTestPipe()
        pipe.coreSends(FrameToScreen.Welcome("/e"))
        pipe.coreSendsSnapshot(seq = 4, SMALL_STATES, MAIN_HEADER, MAIN_TAIL)
        val mirror = pipe.connect(this)

        pipe.coreSends(FrameToScreen.Change(6, ScreenChange.EngineInstallationChanged(EngineInstallationState.InstallationRunning)))
        runCurrent()

        assertEquals(listOf<FrameToCore>(FrameToCore.Hello, FrameToCore.Hello), pipe.framesSentToCore())
        assertEquals(EngineInstallationState.EngineReady, mirror.engineInstallationState.value)

        // 다시 인사한 뒤로도 그 전에 나간 변화가 먼저 온다. 스냅샷이 오기 전의 것은 거울에 얹지 않는다.
        pipe.coreSends(FrameToScreen.Change(7, ScreenChange.EngineInstallationChanged(EngineInstallationState.EngineMissing)))
        pipe.coreSends(FrameToScreen.Welcome("/e"))
        pipe.coreSends(FrameToScreen.Snapshot(7, ScreenChange.SmallStatesReplaced(SMALL_STATES.copy(engineInstallation = EngineInstallationState.EngineMissing)), last = false))
        runCurrent()
        assertEquals(EngineInstallationState.EngineReady, mirror.engineInstallationState.value)

        pipe.coreSends(FrameToScreen.Snapshot(7, MAIN_HEADER, last = true))
        pipe.coreSends(FrameToScreen.Change(8, ScreenChange.ConversationTailChanged(SessionTarget.Main, 0, listOf(ChatEntry.AssistantSaid("다시")))))
        runCurrent()

        assertEquals(EngineInstallationState.EngineMissing, mirror.engineInstallationState.value)
        assertEquals(listOf(ChatEntry.AssistantSaid("다시")), mirror.chatConversations.value.getValue(SessionTarget.Main).entries)
        assertEquals(mapOf<PersonOpenedSessionTarget, SessionEntryState>(SessionTarget.Repo("proj-a") to SessionEntryState.Running), mirror.chatSessionEntries.value)
    }

    @Test
    fun `명령마다 다른 id 를 단 명령 틀을 보내고 그 모양은 같은 JVM 의 코어가 받는 것과 같다`() = runTest {
        val pipe = MirrorTestPipe()
        pipe.coreSends(FrameToScreen.Welcome("/e"))
        pipe.coreSendsSnapshot(seq = 0, SMALL_STATES)
        val mirror = pipe.connect(this)
        val inProcess = RecordingScreenFacingCore()

        CALLS_TO_EVERY_SCREEN_COMMAND.forEach { call ->
            call(mirror)
            call(inProcess)
        }

        val commandFrames = pipe.framesSentToCore().filterIsInstance<FrameToCore.Command>()
        assertEquals(inProcess.receivedCommands, commandFrames.map { it.command })
        assertEquals(33, commandFrames.map { it.id }.toSet().size)
    }

    @Test
    fun `거절된 명령은 갈래 이름과 사유만 진단 기록에 남긴다`() = runTest {
        val recordedEntries = mutableListOf<DiagnosticLogEntry>()
        val pipe = MirrorTestPipe(diagnosticLog = object : DiagnosticLog {
            override fun record(entry: DiagnosticLogEntry) {
                recordedEntries += entry
            }
        })
        pipe.coreSends(FrameToScreen.Welcome("/e"))
        pipe.coreSendsSnapshot(seq = 0, SMALL_STATES)
        val mirror = pipe.connect(this)

        mirror.useClaudeToken("sk-ant-oat01-secret")
        mirror.refreshWorkDir()
        val (tokenCommand, refreshCommand) = pipe.framesSentToCore().filterIsInstance<FrameToCore.Command>()
        pipe.coreSends(FrameToScreen.Reply(tokenCommand.id, CommandOutcome.Rejected, reason = "unreadable_command"))
        pipe.coreSends(FrameToScreen.Reply(refreshCommand.id, CommandOutcome.Accepted))
        runCurrent()

        assertEquals(listOf<DiagnosticLogEntry>(DiagnosticLogEntry.CoreRejectedScreenCommand("UseClaudeToken", "unreadable_command")), recordedEntries)
        assertFalse(recordedEntries.single().summary.contains("sk-ant"))
    }

    @Test
    fun `모든 세션을 끝내는 것은 코어로 가는 줄을 닫고 코어가 줄을 닫을 때까지 기다리는 것이다`() = runTest {
        val pipe = MirrorTestPipe()
        pipe.coreSends(FrameToScreen.Welcome("/e"))
        pipe.coreSendsSnapshot(seq = 0, SMALL_STATES)
        val mirror = pipe.connect(this)

        val ending = async { mirror.endAllSessions() }
        runCurrent()

        assertTrue(pipe.toCore.isClosedForSend)
        assertFalse(ending.isCompleted)

        pipe.fromCore.close()
        runCurrent()

        assertTrue(ending.isCompleted)
    }

    private class MirrorTestPipe(private val diagnosticLog: DiagnosticLog = DiagnosticLog.Discarding) {
        val fromCore = Channel<String>(Channel.UNLIMITED)
        val toCore = Channel<String>(Channel.UNLIMITED)
        private val sentToCore = mutableListOf<FrameToCore>()

        fun coreSends(frame: FrameToScreen) {
            fromCore.trySend(encodeFrameToScreen(frame)).getOrThrow()
        }

        fun coreSendsSnapshot(seq: Long, smallStates: SmallStates, vararg conversationParts: ScreenChange) {
            val parts = listOf<ScreenChange>(ScreenChange.SmallStatesReplaced(smallStates)) + conversationParts
            parts.forEachIndexed { index, part -> coreSends(FrameToScreen.Snapshot(seq, part, last = index == parts.lastIndex)) }
        }

        suspend fun connect(testScope: TestScope): CoreMirror = CoreMirror.connect(
            framesFromCore = fromCore,
            framesToCore = toCore,
            readingScope = testScope.backgroundScope,
            diagnosticLog = diagnosticLog,
        )

        fun framesSentToCore(): List<FrameToCore> {
            while (true) {
                val line = toCore.tryReceive().getOrNull() ?: break
                sentToCore += decodeFrameToCore(line)
            }
            return sentToCore.toList()
        }
    }

    private companion object {
        val SMALL_STATES = SmallStates(
            engineInstallation = EngineInstallationState.EngineReady,
            claudeAuthentication = ClaudeAuthenticationState.CredentialsReady,
            workDirDashboard = WorkDirDashboardState.FirstObservationRunning("/w"),
            workDirInitialization = WorkDirInitializationState.Idle,
            sessionEntries = listOf(SessionEntryOfTarget(SessionTarget.Repo("proj-a"), SessionEntryState.Running)),
            heldSessions = listOf(SessionTarget.Main),
            sessionTree = SessionTree(),
            gateQuestions = emptyList(),
            repositoryClone = RepositoryCloneState.Closed,
        )

        val MAIN_HEADER = ScreenChange.ConversationHeaderChanged(
            SessionTarget.Main,
            ConversationHeader(
                workingDirectory = "/w",
                conversationId = "c0nv",
                modelName = null,
                resumedConversationId = null,
                pendingUserMessages = emptyList(),
                turnState = TurnState.Running,
                totalCostUsd = 0.0,
                usageWindows = emptyList(),
                endedExitCode = null,
            ),
        )

        val MAIN_TAIL = ScreenChange.ConversationTailChanged(SessionTarget.Main, 0, listOf(ChatEntry.UserSaid("안녕")))
    }
}
