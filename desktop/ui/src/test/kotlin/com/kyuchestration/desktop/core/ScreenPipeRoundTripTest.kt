package com.kyuchestration.desktop.core

import com.kyuchestration.desktop.claudeauth.ClaudeAuthenticationState
import com.kyuchestration.desktop.diagnostics.DiagnosticLog
import com.kyuchestration.desktop.diagnostics.DiagnosticLogEntry
import com.kyuchestration.desktop.diagnostics.RecordingDiagnosticLog
import com.kyuchestration.desktop.engine.EngineInstallationState
import com.kyuchestration.desktop.mirror.CoreConnection
import com.kyuchestration.desktop.mirror.CoreMirror
import com.kyuchestration.desktop.protocol.ByeReason
import com.kyuchestration.desktop.protocol.CommandOutcome
import com.kyuchestration.desktop.protocol.FrameToCore
import com.kyuchestration.desktop.protocol.FrameToScreen
import com.kyuchestration.desktop.protocol.SCREEN_PROTOCOL_VERSION
import com.kyuchestration.desktop.protocol.ScreenChange
import com.kyuchestration.desktop.protocol.ScreenCommand
import com.kyuchestration.desktop.protocol.ScreenFacingCore
import com.kyuchestration.desktop.protocol.decodeFrameToScreen
import com.kyuchestration.desktop.protocol.encodeFrameToCore
import com.kyuchestration.desktop.terminal.SessionConversationChoice
import com.kyuchestration.desktop.terminal.SessionTarget
import com.kyuchestration.desktop.terminal.chat.ChatSessionEvent
import com.kyuchestration.desktop.terminal.chat.PermissionCardChoice
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * 같은 JVM 안에서 코어 → 게시자 → 틀 줄 → 거울이 화면에 코어와 같은 상태를 주는가(core-ui-split-design.md 7 절 1 단계).
 *
 * 코어 쪽은 진짜 홀더 여섯이다(포트만 가짜). 화면 쪽은 거울이다. 둘 사이에는 문자열 줄 두 가닥뿐이다 — 2 단계에서 그 두
 * 가닥이 자식 프로세스의 stdin · stdout 으로 바뀐다.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlinx.coroutines.DelicateCoroutinesApi::class)
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
        pipe.screenSends(TEST_SCREEN_HELLO)
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
    fun `모르는 틀과 읽지 못할 줄을 건너뛰고 코어는 계속 화면을 받는다`() = runTest {
        // 화면이 코어보다 새 판이면 코어가 모르는 틀(bye 같은 것)이 온다(설계 6.14). 그것 하나로 코어가 끝나면 세션이 모두 끊긴다.
        val core = RecordingScreenFacingCore()
        val pipe = startPipe(core)

        pipe.screenSendsLine("""{"frame":"bye","reason":"replaced_by_another_screen"}""")
        pipe.screenSendsLine("JSON 이 아닌 줄")
        pipe.screenSends(TEST_SCREEN_HELLO)
        pipe.screenSends(FrameToCore.Command("c-1", ScreenCommand.RefreshWorkDir))
        runCurrent()

        val frames = pipe.framesReceivedByScreen()
        assertEquals(welcomeFrom(core), frames.first())
        assertEquals(FrameToScreen.Reply("c-1", CommandOutcome.Accepted), frames.last())
        assertEquals(listOf<ScreenCommand>(ScreenCommand.RefreshWorkDir), core.receivedCommands)
        assertEquals(0, core.endAllSessionsCount)
    }

    @Test
    fun `인사 답은 규약 판 · 코어 판 · 코어의 진단 기록 · 엔진 자리를 싣고 스냅샷이 뒤따른다`() = runTest {
        val core = RecordingScreenFacingCore()
        val pipe = startPipe(core)

        pipe.screenSends(TEST_SCREEN_HELLO)
        runCurrent()

        val frames = pipe.framesReceivedByScreen()
        assertEquals(welcomeFrom(core), frames.first())
        assertTrue(frames.drop(1).all { it is FrameToScreen.Snapshot }, "인사 답 뒤에는 스냅샷: $frames")
    }

    @Test
    fun `규약 판이 다른 화면에는 인사 답 뒤에 작별하고 세션을 끝내고 줄을 닫는다`() = runTest {
        // 코어가 낡았는지 화면이 낡았는지는 인사 답의 판으로 화면이 가린다(설계 6.14). 다른 규약의 줄을 계속 받으면 화면은
        // 뜻이 바뀐 필드를 제 뜻으로 읽는다.
        val core = RecordingScreenFacingCore()
        val pipe = startPipe(core)

        pipe.screenSends(FrameToCore.Hello(protocol = SCREEN_PROTOCOL_VERSION + 1, screenVersion = "v9.0.0"))
        pipe.screenSends(FrameToCore.Command("c-1", ScreenCommand.RefreshWorkDir))
        runCurrent()

        assertEquals(listOf(welcomeFrom(core), FrameToScreen.Bye(ByeReason.ProtocolMismatch)), pipe.framesReceivedByScreen())
        assertEquals(emptyList(), core.receivedCommands)
        assertEquals(1, core.endAllSessionsCount)
        assertTrue(pipe.coreToScreen.isClosedForReceive)
    }

    @Test
    fun `열린 물음에 닿지 못한 답은 거절로 돌려준다`() = runTest {
        // 화면이 답을 보낸 순간 claude 가 그 도구 호출을 거뒀거나 같은 관문에 먼저 답이 갔을 수 있다(설계 6.7). 홀더는 그런 답을
        // 조용히 버리는데, 그러면 화면은 자기 답이 갔다고 안다. 닿았는지는 홀더가 답하고, 여기서는 그 답을 화면에 돌려준다.
        val core = RecordingScreenFacingCore().apply { questionsStillOpen = false }
        val pipe = startPipe(core)

        pipe.screenSends(FrameToCore.Command("c-1", ScreenCommand.AnswerPermission(SessionTarget.Main, "toolu_1", PermissionCardChoice.Deny("아니요"))))
        pipe.screenSends(FrameToCore.Command("c-2", ScreenCommand.AnswerGate("gate-1", approved = true)))
        runCurrent()

        assertEquals(
            listOf<FrameToScreen>(
                FrameToScreen.Reply("c-1", CommandOutcome.Rejected, "question_not_open"),
                FrameToScreen.Reply("c-2", CommandOutcome.Rejected, "question_not_open"),
            ),
            pipe.framesReceivedByScreen(),
        )
    }

    @Test
    fun `열린 물음에 닿은 답은 받았다고 돌려준다`() = runTest {
        val core = RecordingScreenFacingCore()
        val pipe = startPipe(core)

        pipe.screenSends(FrameToCore.Command("c-1", ScreenCommand.AnswerPermission(SessionTarget.Main, "toolu_1", PermissionCardChoice.Deny("아니요"))))
        pipe.screenSends(FrameToCore.Command("c-2", ScreenCommand.AnswerGate("gate-1", approved = true)))
        runCurrent()

        assertEquals(
            listOf<FrameToScreen>(FrameToScreen.Reply("c-1", CommandOutcome.Accepted), FrameToScreen.Reply("c-2", CommandOutcome.Accepted)),
            pipe.framesReceivedByScreen(),
        )
    }

    @Test
    fun `화면이 밀리면 변화를 버리고 밀린 것이 다 건네진 뒤 그때의 상태로 스냅샷을 다시 보낸다`() = runTest {
        // 화면이 읽지 못하는 동안 변화를 쌓아 두면 코어의 메모리가 한없이 는다(설계 6.5). 상태를 보내는 것이라 버려도 잃지 않는다.
        val core = RecordingScreenFacingCore()
        val pipe = FramePipe(this)
        val stalledScreen = ScreenFrameOutbox()
        backgroundScope.launch {
            serveScreen(core, TEST_CORE_IDENTITY, framesFromScreen = pipe.screenToCore, framesToScreen = stalledScreen, diagnosticLog = DiagnosticLog.Discarding, screenBacklogLimitBytes = 1)
        }
        pipe.screenSends(TEST_SCREEN_HELLO)
        runCurrent()

        core.engineInstallationState.value = EngineInstallationState.InstallationRunning
        runCurrent()
        core.claudeAuthenticationState.value = ClaudeAuthenticationState.CheckingCredentials
        runCurrent()

        val delivered = stalledScreen.linesDeliveredIn(backgroundScope)
        runCurrent()
        val frames = buildList {
            while (true) {
                add(decodeFrameToScreen(delivered.tryReceive().getOrNull() ?: break))
            }
        }

        assertEquals(welcomeFrom(core), frames.first())
        val changes = frames.filterIsInstance<FrameToScreen.Change>()
        assertEquals(listOf<ScreenChange>(ScreenChange.EngineInstallationChanged(EngineInstallationState.InstallationRunning)), changes.map { it.change })
        val resentSnapshot = frames.dropWhile { it !is FrameToScreen.Change }.filterIsInstance<FrameToScreen.Snapshot>()
        val resentStates = assertIs<ScreenChange.SmallStatesReplaced>(resentSnapshot.first().part).states
        assertEquals(ClaudeAuthenticationState.CheckingCredentials, resentStates.claudeAuthentication)
        assertTrue(resentSnapshot.last().last)
        assertTrue(resentSnapshot.first().seq > changes.single().seq, "다시 보낸 스냅샷은 버린 변화까지 센 seq 를 단다")
    }

    @Test
    fun `판을 싣기 전의 화면이 인사하면 판 불일치로 작별한다`() = runTest {
        val core = RecordingScreenFacingCore()
        val pipe = startPipe(core)

        pipe.screenSendsLine("""{"frame":"hello"}""")
        runCurrent()

        assertEquals(listOf(welcomeFrom(core), FrameToScreen.Bye(ByeReason.ProtocolMismatch)), pipe.framesReceivedByScreen())
        assertEquals(1, core.endAllSessionsCount)
    }

    @Test
    fun `화면이 줄을 닫으면 코어는 모든 세션을 끝내고 자기 줄을 닫는다`() = runTest {
        val core = RecordingScreenFacingCore()
        val pipe = startPipe(core)

        pipe.screenToCore.close()
        runCurrent()

        assertEquals(1, core.endAllSessionsCount)
        assertTrue(pipe.coreToScreen.isClosedForReceive)
    }

    @Test
    fun `명령 도중 코어가 실패하면 모든 세션을 끝내고 그 실패를 실어 자기 줄을 닫는다`() = runTest {
        // 세션 끝내기를 건너뛰고 줄만 닫히면, 화면은 떠나도 claude 프로세스들이 주인 없이 남는다.
        val core = RecordingScreenFacingCore().apply { commandThatFails = ScreenCommand.RefreshWorkDir }
        val pipe = FramePipe(this)
        // 실패를 시험이 받아 보도록 Deferred 에 담는다 — 감독 Job 아래의 async 는 실패를 부모로 올리지 않는다.
        val servingScope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        val serving = servingScope.async {
            serveScreen(core, TEST_CORE_IDENTITY, framesFromScreen = pipe.screenToCore, framesToScreen = pipe.coreOutbox, diagnosticLog = DiagnosticLog.Discarding)
        }

        pipe.screenSends(FrameToCore.Command("c-1", ScreenCommand.RefreshWorkDir))
        runCurrent()

        val holderFailure = assertFailsWith<IllegalStateException> { serving.await() }
        assertEquals(1, core.endAllSessionsCount)
        // 실패를 싣지 않고 닫으면 코어 프로세스는 0 으로 끝나 크래시가 정상 종료처럼 보인다(CoreMain 의 종료 코드는 이 실패로 정해진다).
        // 코루틴이 경계를 넘기며 예외를 다시 지을 수 있어(스택 복원) 같은 객체가 아니라 같은 실패인지를 본다.
        assertEquals(holderFailure.message, assertFailsWith<IllegalStateException> { pipe.coreToScreen.receive() }.message)
    }

    /** 윈도우 화면과 WSLg 화면이 같은 코어에 붙는 날, 받은 코어 기록만으로 어느 화면이었는지를 가른다. */
    @Test
    fun `인사한 화면의 판과 OS 를 코어의 진단 기록에 남기고 인사 답에 코어의 OS 와 배포판을 싣는다`() = runTest {
        val coreDiagnosticLog = RecordingDiagnosticLog()
        val pipe = FramePipe(this)
        backgroundScope.launch {
            serveScreen(
                RecordingScreenFacingCore(),
                TEST_CORE_IDENTITY,
                framesFromScreen = pipe.screenToCore,
                framesToScreen = pipe.coreOutbox,
                diagnosticLog = coreDiagnosticLog,
            )
        }

        pipe.screenSends(FrameToCore.Hello(protocol = SCREEN_PROTOCOL_VERSION, screenVersion = "v0.15.0", screenOs = "windows"))
        runCurrent()

        assertEquals(listOf<DiagnosticLogEntry>(DiagnosticLogEntry.ScreenGreeted("v0.15.0", "windows")), coreDiagnosticLog.recordedEntries)
        val welcome = assertIs<FrameToScreen.Welcome>(pipe.framesReceivedByScreen().first())
        assertEquals(TEST_CORE_IDENTITY.operatingSystem, welcome.coreOs)
        assertEquals(TEST_CORE_IDENTITY.wslDistributionName, welcome.wslDistro)
    }

    /** 거울은 seq 가 건너뛰면 다시 인사한다(6.4). 같은 화면이 여러 번 붙은 것처럼 적히면 기록을 읽는 사람이 화면을 셀 수 없다. */
    @Test
    fun `같은 연결에서 다시 온 인사는 화면이 붙었다고 다시 적지 않는다`() = runTest {
        val coreDiagnosticLog = RecordingDiagnosticLog()
        val pipe = FramePipe(this)
        backgroundScope.launch {
            serveScreen(
                RecordingScreenFacingCore(),
                TEST_CORE_IDENTITY,
                framesFromScreen = pipe.screenToCore,
                framesToScreen = pipe.coreOutbox,
                diagnosticLog = coreDiagnosticLog,
            )
        }

        pipe.screenSends(TEST_SCREEN_HELLO)
        pipe.screenSends(TEST_SCREEN_HELLO)
        runCurrent()

        assertEquals(1, coreDiagnosticLog.recordedEntries.filterIsInstance<DiagnosticLogEntry.ScreenGreeted>().size)
        assertEquals(2, pipe.framesReceivedByScreen().filterIsInstance<FrameToScreen.Welcome>().size)
    }

    @Test
    fun `코어 스코프가 취소돼도 모든 세션을 끝내고 자기 줄을 닫는다`() = runTest {
        val core = RecordingScreenFacingCore()
        val pipe = FramePipe(this)
        val serving = backgroundScope.launch {
            serveScreen(core, TEST_CORE_IDENTITY, framesFromScreen = pipe.screenToCore, framesToScreen = pipe.coreOutbox, diagnosticLog = DiagnosticLog.Discarding)
        }
        runCurrent()

        serving.cancel()
        runCurrent()

        assertEquals(1, core.endAllSessionsCount)
        assertTrue(pipe.coreToScreen.isClosedForReceive)
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

    private fun TestScope.startPipe(core: ScreenServingCore): FramePipe {
        val pipe = FramePipe(this)
        backgroundScope.launch { serveScreen(core, TEST_CORE_IDENTITY, framesFromScreen = pipe.screenToCore, framesToScreen = pipe.coreOutbox, diagnosticLog = DiagnosticLog.Discarding) }
        runCurrent()
        return pipe
    }

    private class FramePipe(private val testScope: TestScope) {
        val screenToCore = Channel<String>(Channel.UNLIMITED)
        val coreOutbox = ScreenFrameOutbox()
        val coreToScreen = coreOutbox.linesDeliveredIn(testScope.backgroundScope)

        suspend fun connectMirror(): CoreMirror = assertIs<CoreConnection.Connected>(
            CoreMirror.connect(
                framesFromCore = coreToScreen,
                framesToCore = screenToCore,
                readingScope = testScope.backgroundScope,
                diagnosticLog = DiagnosticLog.Discarding,
                screenVersionLabel = TEST_SCREEN_VERSION_LABEL,
            ),
        ).mirror

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
