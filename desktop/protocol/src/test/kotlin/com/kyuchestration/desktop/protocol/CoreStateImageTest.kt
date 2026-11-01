package com.kyuchestration.desktop.protocol

import com.kyuchestration.desktop.claudeauth.ClaudeAuthenticationState
import com.kyuchestration.desktop.dashboard.WorkDirDashboardState
import com.kyuchestration.desktop.engine.EngineInstallationState
import com.kyuchestration.desktop.initialization.WorkDirInitializationState
import com.kyuchestration.desktop.repoclone.CloneStep
import com.kyuchestration.desktop.repoclone.RepositoryCloneState
import com.kyuchestration.desktop.terminal.ChildSessionGate
import com.kyuchestration.desktop.terminal.SessionConversationChoice
import com.kyuchestration.desktop.terminal.SessionTarget
import com.kyuchestration.desktop.terminal.chat.ChildSessionGateQuestion
import com.kyuchestration.desktop.terminal.chat.ChildSessionNode
import com.kyuchestration.desktop.terminal.chat.ExistingConversation
import com.kyuchestration.desktop.terminal.chat.SessionEntryState
import com.kyuchestration.desktop.terminal.chat.SessionTree
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 코어의 상태 한 벌이 스냅샷의 첫 조각(작은 상태 전부)으로 건너가 다시 서는가.
 *
 * 게시자와 거울이 같은 [CoreStateImage] 를 쓰므로, 흐름이 하나 늘 때 고칠 자리는 이 타입과 그 짝(smallStates · of)이다. 둘 중
 * 하나에서 필드가 빠지면 그 흐름은 스냅샷에서만 조용히 빠진다 — 모든 필드를 기본값이 아닌 값으로 채워 왕복시킨다.
 */
class CoreStateImageTest {

    @Test
    fun `대화를 뺀 상태 전부가 작은 상태로 건너가 같은 값으로 돌아온다`() {
        val image = CoreStateImage(
            engineInstallation = EngineInstallationState.InstallationRunning,
            claudeAuthentication = ClaudeAuthenticationState.BrowserLoginStarting,
            workDirDashboard = WorkDirDashboardState.FirstObservationRunning("/w"),
            workDirInitialization = WorkDirInitializationState.Running,
            conversations = emptyMap(),
            sessionEntries = mapOf(
                SessionTarget.Repo("proj-a") to SessionEntryState.Running,
                SessionTarget.Main to SessionEntryState.ChoosingConversation(
                    candidates = listOf(
                        ExistingConversation("654ce605-b030-40e8-8448-2e9003ad32b1", "터미널 대화", null, Instant.parse("2026-11-01T09:00:56Z")),
                    ),
                    recorded = ExistingConversation(
                        "2a9d3b8f-0000-4000-8000-000000000002",
                        "앱 대화",
                        Instant.parse("2026-10-01T00:00:00Z"),
                        Instant.parse("2026-10-31T12:00:00Z"),
                    ),
                    defaultChoice = SessionConversationChoice.AdoptConversation("654ce605-b030-40e8-8448-2e9003ad32b1"),
                ),
            ),
            heldSessions = setOf(SessionTarget.Main),
            sessionTree = SessionTree(listOf(ChildSessionNode("child-1", "일", "proj-a", "/w/proj-a", "c-1", Instant.EPOCH))),
            gateQuestions = listOf(ChildSessionGateQuestion("gate-1", "띄울까요?", ChildSessionGate("proj-a", "sha256:a", emptyList()))),
            repositoryClone = RepositoryCloneState.StepRunning(CloneStep.LoadTokenProfiles),
        )

        assertEquals(image, CoreStateImage.of(image.smallStates()))
    }
}
