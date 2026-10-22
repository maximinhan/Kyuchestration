package com.kyuchestration.desktop.core

import com.kyuchestration.desktop.diagnostics.DiagnosticLog
import com.kyuchestration.desktop.mirror.CoreMirror
import com.kyuchestration.desktop.protocol.CommandOutcome
import com.kyuchestration.desktop.protocol.FrameToCore
import com.kyuchestration.desktop.protocol.FrameToScreen
import com.kyuchestration.desktop.protocol.ScreenChange
import com.kyuchestration.desktop.protocol.ScreenCommand
import com.kyuchestration.desktop.protocol.decodeFrameToScreen
import com.kyuchestration.desktop.protocol.encodeFrameToCore
import com.kyuchestration.desktop.terminal.SessionConversationChoice
import com.kyuchestration.desktop.terminal.SessionTarget
import com.kyuchestration.desktop.terminal.chat.ChatSessionEvent
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * 같은 JVM 안에서 코어 → 게시자 → 틀 줄 → 거울이 화면에 코어와 같은 상태를 주는가(core-ui-split-design.md 7 절 1 단계).
 *
 * 코어 쪽은 진짜 홀더 여섯이다(포트만 가짜). 화면 쪽은 거울이다. 둘 사이에는 문자열 줄 두 가닥뿐이다 — 2 단계에서 그 두
 * 가닥이 자식 프로세스의 stdin · stdout 으로 바뀐다.
 */
class ScreenPipeRoundTripTest {

    private val workDirPath: Path = createTempDirectory("kyu-screen-pipe")

    @AfterTest
    fun 임시_디렉토리를_치운다() {
        workDirPath.toFile().deleteRecursively()
    }

    @Test
    fun `거울에 부은 명령이 홀더까지 가고 홀더의 상태가 거울에 같은 값으로 선다`() = runTest {
        val bed = inProcessCoreTestBed()
        val pipe = startPipe(bed.core)
        val mirror = pipe.connectMirror()

        mirror.openWorkDir(workDirPath.toString())
        runCurrent()
        mirror.enterSession(SessionTarget.Main, SessionConversationChoice.ContinueRecordedConversation)
        runCurrent()
        bed.chatSessionOpener.lastSession.emit(ChatSessionEvent.AssistantTextStreaming("안녕"))
        runCurrent()
        bed.chatSessionOpener.lastSession.emit(ChatSessionEvent.AssistantTextStreaming("하세요"))
        runCurrent()
        assertEquals("안녕하세요", mirror.chatConversations.value.getValue(SessionTarget.Main).streamingText)
        bed.chatSessionOpener.lastSession.emit(ChatSessionEvent.AssistantTextArrived("안녕하세요", parentToolUseId = null))
        runCurrent()
        mirror.sendUserMessage(SessionTarget.Main, "반가워요")
        runCurrent()

        assertTrue(SessionTarget.Main in mirror.chatConversations.value)
        assertMirrorShowsCore(bed.core, mirror)
    }

    @Test
    fun `명령 서른셋이 틀을 건너 같은 모양으로 코어에 닿는다`() = runTest {
        val core = RecordingScreenFacingCore()
        val mirror = startPipe(core).connectMirror()
        val expected = RecordingScreenFacingCore()

        CALLS_TO_EVERY_SCREEN_COMMAND.forEach { call ->
            call(mirror)
            call(expected)
        }
        runCurrent()

        assertEquals(expected.receivedCommands, core.receivedCommands)
    }

    @Test
    fun `명령의 답은 그 명령이 남긴 변화 뒤에 온다`() = runTest {
        // 진입을 보낸 화면은 답을 받는 순간 "띄우는 중" 을 이미 거울에 들고 있어야 한다.
        val bed = inProcessCoreTestBed()
        bed.core.openWorkDir(workDirPath.toString())
        runCurrent()
        val pipe = startPipe(bed.core)
        pipe.screenSends(FrameToCore.Hello)
        runCurrent()
        pipe.framesReceivedByScreen()

        pipe.screenSends(FrameToCore.Command("c-1", ScreenCommand.EnterSession(SessionTarget.Main, SessionConversationChoice.ContinueRecordedConversation)))
        runCurrent()
        val frames = pipe.framesReceivedByScreen()

        val entryChangeIndex = frames.indexOfFirst { (it as? FrameToScreen.Change)?.change is ScreenChange.SessionEntriesChanged }
        val replyIndex = frames.indexOf(FrameToScreen.Reply("c-1", CommandOutcome.Accepted))
        assertTrue(entryChangeIndex in 0 until replyIndex, "변화 $entryChangeIndex · 답 $replyIndex")
    }

    @Test
    fun `읽지 못한 명령은 그 id 로 거절을 돌려준다`() = runTest {
        // 화면이 코어보다 새 판이면 코어가 모르는 명령이 온다(설계 6.14). 조용히 버리면 화면은 그 명령이 갔다고 안다.
        val core = RecordingScreenFacingCore()
        val pipe = startPipe(core)

        pipe.screenSendsLine("""{"frame":"command","id":"c-9","command":{"type":"shutdown_core"}}""")
        runCurrent()

        assertEquals(listOf<FrameToScreen>(FrameToScreen.Reply("c-9", CommandOutcome.Rejected, "unreadable_command")), pipe.framesReceivedByScreen())
        assertEquals(emptyList(), core.receivedCommands)
    }

    @Test
    fun `화면이 줄을 닫으면 코어는 모든 세션을 끝내고 자기 줄을 닫는다`() = runTest {
        val core = RecordingScreenFacingCore()
        val pipe = startPipe(core)

        pipe.screenToCore.close()
        runCurrent()

        assertEquals(1, core.endAllSessionsCount)
        assertTrue(pipe.coreToScreen.isClosedForSend)
    }

    private fun assertMirrorShowsCore(core: ScreenFacingCore, mirror: ScreenFacingCore) {
        assertEquals(core.engineDirectoryLabel, mirror.engineDirectoryLabel)
        assertEquals(core.engineInstallationState.value, mirror.engineInstallationState.value)
        assertEquals(core.claudeAuthenticationState.value, mirror.claudeAuthenticationState.value)
        assertEquals(core.workDirDashboardState.value, mirror.workDirDashboardState.value)
        assertEquals(core.workDirInitializationState.value, mirror.workDirInitializationState.value)
        assertEquals(core.chatConversations.value, mirror.chatConversations.value)
        assertEquals(core.chatSessionEntries.value, mirror.chatSessionEntries.value)
        assertEquals(core.heldChatSessionTargets.value, mirror.heldChatSessionTargets.value)
        assertEquals(core.sessionTree.value, mirror.sessionTree.value)
        assertEquals(core.childSessionGateQuestions.value, mirror.childSessionGateQuestions.value)
        assertEquals(core.repositoryCloneState.value, mirror.repositoryCloneState.value)
    }

    private fun TestScope.startPipe(core: ScreenFacingCore): FramePipe {
        val pipe = FramePipe(this)
        backgroundScope.launch { serveScreen(core, framesFromScreen = pipe.screenToCore, framesToScreen = pipe.coreToScreen) }
        runCurrent()
        return pipe
    }

    private class FramePipe(private val testScope: TestScope) {
        val screenToCore = Channel<String>(Channel.UNLIMITED)
        val coreToScreen = Channel<String>(Channel.UNLIMITED)

        suspend fun connectMirror(): CoreMirror = CoreMirror.connect(
            framesFromCore = coreToScreen,
            framesToCore = screenToCore,
            readingScope = testScope.backgroundScope,
            diagnosticLog = DiagnosticLog.Discarding,
        )

        fun screenSends(frame: FrameToCore) = screenSendsLine(encodeFrameToCore(frame))

        fun screenSendsLine(line: String) {
            screenToCore.trySend(line).getOrThrow()
        }

        fun framesReceivedByScreen(): List<FrameToScreen> = buildList {
            while (true) {
                add(decodeFrameToScreen(coreToScreen.tryReceive().getOrNull() ?: break))
            }
        }
    }
}
