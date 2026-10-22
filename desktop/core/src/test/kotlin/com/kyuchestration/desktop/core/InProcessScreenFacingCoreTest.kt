package com.kyuchestration.desktop.core

import com.kyuchestration.desktop.claudeauth.ClaudeAuthenticationState
import com.kyuchestration.desktop.dashboard.WorkDirDashboardState
import com.kyuchestration.desktop.engine.EngineInstallationState
import com.kyuchestration.desktop.initialization.WorkDirInitializationState
import com.kyuchestration.desktop.protocol.ScreenFacingCore
import com.kyuchestration.desktop.terminal.SessionConversationChoice
import com.kyuchestration.desktop.terminal.SessionTarget
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * 같은 JVM 의 홀더들을 화면 쪽 경계로 내놓는 자리(core-ui-split-design.md 6.2).
 *
 * 여기서 갈리는 것은 화면에 있던 조립의 결정들이 이 자리로 옮겨 와서도 같게 도는가다 — 엔진이 선 뒤에 인증을 묻고,
 * 고른 자리를 초기화를 거쳐 대시보드로 열고, 세션을 열린 워크디렉토리에서 띄우고, 워크디렉토리를 닫을 때 그 안의
 * 것을 함께 놓는다. 홀더 각자의 규칙은 그 홀더의 시험이 본다. 포트는 람다와 작은 가짜로 세운다.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class InProcessScreenFacingCoreTest {

    private val workDirPath: Path = createTempDirectory("kyu-screen-facing-core")

    @AfterTest
    fun 임시_디렉토리를_치운다() {
        workDirPath.toFile().deleteRecursively()
    }

    @Test
    fun `엔진이 이미 있으면 곧바로 claude 인증을 묻는다`() = runTest {
        val bed = inProcessCoreTestBed(engineInstalledAtStart = true)
        runCurrent()

        assertEquals(1, bed.claudeCli.credentialChecks)
        assertEquals(ClaudeAuthenticationState.CredentialsReady, bed.core.claudeAuthenticationState.value)
    }

    @Test
    fun `엔진이 없으면 묻지 않다가 엔진을 받아 서면 그때 묻는다`() = runTest {
        // 토큰을 맡아 두는 자리가 kyu 라서, 엔진이 없을 때 물으면 진짜 문제가 아닌 실패가 기록에 쌓인다.
        val bed = inProcessCoreTestBed(engineInstalledAtStart = false)

        assertEquals(EngineInstallationState.InstallationRunning, bed.core.engineInstallationState.value)
        assertEquals(0, bed.claudeCli.credentialChecks)

        runCurrent()

        assertEquals(EngineInstallationState.EngineReady, bed.core.engineInstallationState.value)
        assertEquals(1, bed.claudeCli.credentialChecks)
    }

    @Test
    fun `고른 자리를 열면 초기화를 거쳐 대시보드가 그 자리를 관찰한다`() = runTest {
        val bed = inProcessCoreTestBed()

        bed.core.openWorkDir(workDirPath.toString())
        runCurrent()

        assertIs<WorkDirDashboardState.WorkDirObserved>(bed.core.workDirDashboardState.value)
        assertEquals(workDirPath.toString(), bed.core.workDirDashboardState.value.workDirPath)
    }

    @Test
    fun `세션은 열린 워크디렉토리에서 띄우고 보유 세션은 대상만 내놓는다`() = runTest {
        val bed = inProcessCoreTestBed()
        bed.core.openWorkDir(workDirPath.toString())
        runCurrent()

        bed.core.enterSession(SessionTarget.Main, SessionConversationChoice.ContinueRecordedConversation)
        runCurrent()

        assertEquals(listOf<SessionTarget>(SessionTarget.Main), bed.sessionCommandSource.askedTargets)
        assertEquals(setOf<SessionTarget>(SessionTarget.Main), bed.core.heldChatSessionTargets.value)
    }

    @Test
    fun `열린 워크디렉토리가 없으면 세션을 띄우지 않는다`() = runTest {
        val bed = inProcessCoreTestBed()

        bed.core.enterSession(SessionTarget.Main, SessionConversationChoice.ContinueRecordedConversation)
        runCurrent()

        assertEquals(emptyList(), bed.sessionCommandSource.askedTargets)
    }

    @Test
    fun `워크디렉토리를 닫으면 그 안의 세션을 끝내고 대시보드를 닫는다`() = runTest {
        val bed = inProcessCoreTestBed()
        bed.core.openWorkDir(workDirPath.toString())
        runCurrent()
        bed.core.enterSession(SessionTarget.Main, SessionConversationChoice.ContinueRecordedConversation)
        runCurrent()

        bed.core.closeWorkDir()
        runCurrent()

        assertEquals(1, bed.chatSessionOpener.lastSession.endSessionCount)
        assertEquals(emptySet(), bed.core.heldChatSessionTargets.value)
        assertEquals(WorkDirDashboardState.NoWorkDirOpened, bed.core.workDirDashboardState.value)
    }

    @Test
    fun `워크디렉토리를 닫는 그 자리에서 세션과 전사를 놓고 대시보드까지 닫는다`() = runTest {
        // 세션 놓기가 뒤로 밀리면, 닫힌 대시보드에서 새 워크디렉토리를 열고 메인에 들어가는 사이 옛 메인이 아직 보유
        // 중이라 그 진입이 버려지고 옛 전사가 화면에 선다.
        val bed = inProcessCoreTestBed()
        bed.core.openWorkDir(workDirPath.toString())
        runCurrent()
        bed.core.enterSession(SessionTarget.Main, SessionConversationChoice.ContinueRecordedConversation)
        runCurrent()

        bed.core.closeWorkDir()

        assertEquals(emptySet(), bed.core.heldChatSessionTargets.value)
        assertEquals(emptyMap(), bed.core.chatConversations.value)
        assertEquals(WorkDirDashboardState.NoWorkDirOpened, bed.core.workDirDashboardState.value)
    }

    @Test
    fun `이 OS 의 경로로 읽히지 않는 자리를 열면 예외가 아니라 여는 걸음의 실패로 답한다`() = runTest {
        // 화면 경로는 화면 OS 의 문자열이다(설계 6.9). 코어가 그것을 풀다 던지면 그 명령 하나로 코어가 끝난다.
        val bed = inProcessCoreTestBed()

        bed.core.openWorkDir("/w/nul\u0000이름")
        runCurrent()

        assertIs<WorkDirInitializationState.Failed>(bed.core.workDirInitializationState.value)
        assertEquals(WorkDirDashboardState.NoWorkDirOpened, bed.core.workDirDashboardState.value)
    }
}
