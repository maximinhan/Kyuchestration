package com.kyuchestration.desktop.protocol

import com.kyuchestration.desktop.claudeauth.ClaudeAuthenticationState
import com.kyuchestration.desktop.dashboard.WorkDirDashboardState
import com.kyuchestration.desktop.engine.EngineInstallationState
import com.kyuchestration.desktop.initialization.WorkDirInitializationState
import com.kyuchestration.desktop.repoclone.CloneOutcome
import com.kyuchestration.desktop.repoclone.CloneStatus
import com.kyuchestration.desktop.repoclone.CloneStep
import com.kyuchestration.desktop.repoclone.OwnerKind
import com.kyuchestration.desktop.repoclone.RegisteredTokenProfile
import com.kyuchestration.desktop.repoclone.RemoteRepository
import com.kyuchestration.desktop.repoclone.RepositoryCloneResult
import com.kyuchestration.desktop.repoclone.RepositoryCloneState
import com.kyuchestration.desktop.repoclone.RepositoryOwner
import com.kyuchestration.desktop.repoclone.TokenProfile
import com.kyuchestration.desktop.repoclone.TokenStorage
import com.kyuchestration.desktop.terminal.ChildSessionGate
import com.kyuchestration.desktop.terminal.GatedExecutionConfig
import com.kyuchestration.desktop.terminal.SessionConversationChoice
import com.kyuchestration.desktop.terminal.SessionTarget
import com.kyuchestration.desktop.terminal.chat.ChatEntry
import com.kyuchestration.desktop.terminal.chat.ChildEndReason
import com.kyuchestration.desktop.terminal.chat.ChildLifecycle
import com.kyuchestration.desktop.terminal.chat.ChildSessionGateQuestion
import com.kyuchestration.desktop.terminal.chat.ChildSessionNode
import com.kyuchestration.desktop.terminal.chat.ChildTurnResult
import com.kyuchestration.desktop.terminal.chat.ChildWaitingFor
import com.kyuchestration.desktop.terminal.chat.OutgoingUserMessage
import com.kyuchestration.desktop.terminal.chat.PermissionAnswer
import com.kyuchestration.desktop.terminal.chat.PermissionCardChoice
import com.kyuchestration.desktop.terminal.chat.PermissionDenial
import com.kyuchestration.desktop.terminal.chat.RateLimitWindow
import com.kyuchestration.desktop.terminal.chat.SessionEntryState
import com.kyuchestration.desktop.terminal.chat.SessionTree
import com.kyuchestration.desktop.terminal.chat.SubagentRun
import com.kyuchestration.desktop.terminal.chat.ToolCallAnswer
import com.kyuchestration.desktop.terminal.chat.TurnOutcome
import com.kyuchestration.desktop.terminal.chat.TurnState
import com.kyuchestration.desktop.terminal.chat.TurnUsage
import com.kyuchestration.desktop.terminal.chat.UserMessageSender
import com.kyuchestration.desktop.workdir.RepoSnapshot
import com.kyuchestration.desktop.workdir.RepoState
import com.kyuchestration.desktop.workdir.RepoTask
import com.kyuchestration.desktop.workdir.WorkDirSnapshot
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 틀 한 줄이 화면과 코어 사이를 건너도 같은 값으로 돌아오는가(core-ui-split-design.md 6.4 · 6.15 의 "직렬화 왕복").
 *
 * 경계의 갈래는 하나도 빠짐없이 한 번씩 건너 본다. sealed 의 갈래 하나가 직렬화기를 잃으면 그 갈래가 처음 화면에 서는 날
 * 앱이 죽는다 — 컴파일은 그것을 잡지 못한다.
 */
class ScreenProtocolSerializationTest {

    @Test
    fun `명령 틀은 겉 열쇠 frame 과 명령의 type 으로 읽힌다`() {
        val frame = FrameToCore.Command(
            id = "c-41",
            command = ScreenCommand.SendUserMessage(SessionTarget.Main, "안녕"),
        )

        assertEquals(
            """{"frame":"command","id":"c-41","command":{"type":"send_user_message","target":{"type":"main"},"text":"안녕"}}""",
            encodeFrameToCore(frame),
        )
        assertEquals(frame, decodeFrameToCore(encodeFrameToCore(frame)))
    }

    @Test
    fun `인사 틀은 frame 하나뿐이다`() {
        assertEquals("""{"frame":"hello"}""", encodeFrameToCore(FrameToCore.Hello))
    }

    @Test
    fun `명령 서른셋이 모두 건너 같은 값으로 돌아온다`() {
        assertEquals(33, ALL_COMMANDS.size)
        assertEquals(33, ALL_COMMANDS.map { it::class }.toSet().size)

        ALL_COMMANDS.forEachIndexed { index, command ->
            val frame = FrameToCore.Command(id = "c-$index", command = command)
            assertEquals(frame, decodeFrameToCore(encodeFrameToCore(frame)))
        }
    }

    @Test
    fun `답 틀은 받았다와 거절했다를 outcome 낱말로 싣는다`() {
        assertEquals(
            """{"frame":"reply","id":"c-41","outcome":"accepted"}""",
            encodeFrameToScreen(FrameToScreen.Reply("c-41", CommandOutcome.Accepted)),
        )
        val rejected = FrameToScreen.Reply("c-42", CommandOutcome.Rejected, reason = "unreadable_command")
        assertEquals(rejected, decodeFrameToScreen(encodeFrameToScreen(rejected)))
    }

    @Test
    fun `작은 상태의 갈래가 모두 건너 같은 값으로 돌아온다`() {
        val changes = ENGINE_STATES.map(ScreenChange::EngineInstallationChanged) +
            AUTHENTICATION_STATES.map(ScreenChange::ClaudeAuthenticationChanged) +
            DASHBOARD_STATES.map(ScreenChange::WorkDirDashboardChanged) +
            INITIALIZATION_STATES.map(ScreenChange::WorkDirInitializationChanged) +
            CLONE_STATES.map(ScreenChange::RepositoryCloneChanged) +
            listOf(
                ScreenChange.SessionEntriesChanged(
                    listOf(
                        SessionEntryOfTarget(SessionTarget.Main, SessionEntryState.Running),
                        SessionEntryOfTarget(SessionTarget.Repo("proj-a"), SessionEntryState.Failed(FAILURE)),
                    ),
                ),
                ScreenChange.HeldSessionsChanged(listOf(SessionTarget.Main, SessionTarget.Repo("proj-a"), SessionTarget.Child("child-1"))),
                ScreenChange.SessionTreeChanged(SESSION_TREE),
                ScreenChange.GateQuestionsChanged(listOf(GATE_QUESTION)),
            )

        changes.forEachIndexed { index, change ->
            val frame = FrameToScreen.Change(seq = index.toLong(), change = change)
            assertEquals(frame, decodeFrameToScreen(encodeFrameToScreen(frame)))
        }
    }

    @Test
    fun `대화의 줄 갈래와 머리와 흐르는 글이 건너 같은 값으로 돌아온다`() {
        val changes = listOf(
            ScreenChange.ConversationHeaderChanged(
                target = SessionTarget.Child("child-1"),
                header = ConversationHeader(
                    workingDirectory = "/w/proj-a",
                    conversationId = "c0nv",
                    modelName = "claude-opus",
                    resumedConversationId = "before",
                    pendingUserMessages = listOf(OutgoingUserMessage("곧 갑니다", UserMessageSender.ParentSession, "uuid-1")),
                    turnState = TurnState.Interrupting,
                    totalCostUsd = 0.42,
                    usageWindows = listOf(RateLimitWindow("five_hour", 0.3, 1_760_000_000)),
                    endedExitCode = 143,
                ),
            ),
            ScreenChange.ConversationTailChanged(SessionTarget.Main, fromIndex = 3, entries = ALL_ENTRIES),
            ScreenChange.StreamingTextAppended(SessionTarget.Main, blockOrdinal = 2, append = "이어지는 글"),
            ScreenChange.StreamingTextReplaced(SessionTarget.Main, blockOrdinal = 3, text = null),
            ScreenChange.ConversationRemoved(SessionTarget.Repo("proj-a")),
        )

        changes.forEachIndexed { index, change ->
            val frame = FrameToScreen.Change(seq = index.toLong(), change = change)
            assertEquals(frame, decodeFrameToScreen(encodeFrameToScreen(frame)))
        }
    }

    @Test
    fun `스냅샷 틀과 인사 답이 건너 같은 값으로 돌아온다`() {
        val frames = listOf(
            FrameToScreen.Welcome(engineDirectory = "/home/yawn/.local/share/kyuchestration/engine"),
            FrameToScreen.Snapshot(
                seq = 1200,
                part = ScreenChange.SmallStatesReplaced(
                    SmallStates(
                        engineInstallation = EngineInstallationState.EngineReady,
                        claudeAuthentication = ClaudeAuthenticationState.CredentialsReady,
                        workDirDashboard = WorkDirDashboardState.NoWorkDirOpened,
                        workDirInitialization = WorkDirInitializationState.Idle,
                        sessionEntries = emptyList(),
                        heldSessions = emptyList(),
                        sessionTree = SessionTree(),
                        gateQuestions = emptyList(),
                        repositoryClone = RepositoryCloneState.Closed,
                    ),
                ),
                last = false,
            ),
            FrameToScreen.Snapshot(
                seq = 1200,
                part = ScreenChange.ConversationTailChanged(SessionTarget.Main, 0, ALL_ENTRIES),
                last = true,
            ),
        )

        frames.forEach { frame -> assertEquals(frame, decodeFrameToScreen(encodeFrameToScreen(frame))) }
    }

    @Test
    fun `레포를 고르는 상태의 걸러 낸 목록은 선에 싣지 않고 받는 쪽이 다시 거른다`() {
        val choosing = CLONE_STATES.filterIsInstance<RepositoryCloneState.ChoosingRepositories>().single()

        val line = encodeFrameToScreen(FrameToScreen.Change(1, ScreenChange.RepositoryCloneChanged(choosing)))

        assertFalse("matchedRepositories" in line)
        val decoded = decodeFrameToScreen(line) as FrameToScreen.Change
        val decodedState = (decoded.change as ScreenChange.RepositoryCloneChanged).state as RepositoryCloneState.ChoosingRepositories
        assertEquals(choosing.matchedRepositories, decodedState.matchedRepositories)
    }

    @Test
    fun `모르는 필드는 건너뛴다 — 필드를 더하는 변경은 판을 올리지 않는다`() {
        val line = """{"frame":"change","seq":7,"futureField":1,"change":{"type":"conversation_removed","target":{"type":"main","alsoNew":true}}}"""

        assertEquals(
            FrameToScreen.Change(7, ScreenChange.ConversationRemoved(SessionTarget.Main)),
            decodeFrameToScreen(line),
        )
    }

    private companion object {
        val FAILURE = FailureView(kind = "KyuExecutableNotFound", message = "kyu 실행 파일을 찾지 못했습니다.", guidance = "앱을 다시 띄우세요.")

        val AT: Instant = Instant.parse("2026-10-22T09:30:15.123456Z")

        val ALL_COMMANDS: List<ScreenCommand> = listOf(
            ScreenCommand.InstallEngine,
            ScreenCommand.LookForEngineAgain,
            ScreenCommand.CheckClaudeCredentials,
            ScreenCommand.StartClaudeBrowserLogin,
            ScreenCommand.SubmitClaudeBrowserLoginCode("code-123"),
            ScreenCommand.CancelClaudeBrowserLogin,
            ScreenCommand.UseClaudeToken("sk-ant-oat01-secret"),
            ScreenCommand.OpenWorkDir("/home/yawn/작업 디렉토리"),
            ScreenCommand.InitializeOpenedWorkDir,
            ScreenCommand.RefreshWorkDir,
            ScreenCommand.CloseWorkDir,
            ScreenCommand.EnterSession(SessionTarget.Repo("proj-a"), SessionConversationChoice.StartNewConversation),
            ScreenCommand.DismissSessionEntryFailure(SessionTarget.Main),
            ScreenCommand.SendUserMessage(SessionTarget.Child("child-1"), "줄\n바꿈과 \"따옴표\""),
            ScreenCommand.InterruptTurn(SessionTarget.Main),
            ScreenCommand.AnswerPermission(
                SessionTarget.Main,
                "toolu_01",
                PermissionCardChoice.AllowOnce(buildJsonObject { put("file_path", "/w/a.txt") }),
            ),
            ScreenCommand.EndSession(SessionTarget.Child("child-2")),
            ScreenCommand.AnswerGate("gate-3", approved = true),
            ScreenCommand.OpenRepositoryClone,
            ScreenCommand.CloseRepositoryClone,
            ScreenCommand.ChooseTokenProfile("work"),
            ScreenCommand.StartRemovingTokenProfile("old"),
            ScreenCommand.StopRemovingTokenProfile,
            ScreenCommand.RemoveChosenTokenProfile,
            ScreenCommand.StartRegisteringTokenProfile,
            ScreenCommand.StopRegisteringTokenProfile,
            ScreenCommand.RegisterTokenProfile("work", "ghp_secret"),
            ScreenCommand.ContinueWithRegisteredTokenProfile,
            ScreenCommand.ChooseRepositoryOwner("maximinhan"),
            ScreenCommand.ChangeRepositorySearchText("kyu"),
            ScreenCommand.ToggleRepositorySelection("maximinhan/Kyuchestration"),
            ScreenCommand.CloneSelectedRepositories,
            ScreenCommand.RetryFailedCloneStep,
        )

        val ENGINE_STATES = listOf(
            EngineInstallationState.EngineMissing,
            EngineInstallationState.InstallationRunning,
            EngineInstallationState.InstallationFailed(FAILURE),
            EngineInstallationState.EngineReady,
        )

        val AUTHENTICATION_STATES = listOf(
            ClaudeAuthenticationState.CheckingCredentials,
            ClaudeAuthenticationState.ClaudeCliMissing(FAILURE),
            ClaudeAuthenticationState.CredentialsMissing(lastFailure = FAILURE, tokenWouldBeStoredInPlaintext = true),
            ClaudeAuthenticationState.CredentialsMissing(lastFailure = null, tokenWouldBeStoredInPlaintext = false),
            ClaudeAuthenticationState.BrowserLoginStarting,
            ClaudeAuthenticationState.BrowserLoginWaitingForCode("https://claude.ai/oauth/authorize?code=true"),
            ClaudeAuthenticationState.BrowserLoginFinishing,
            ClaudeAuthenticationState.PastedTokenVerifying,
            ClaudeAuthenticationState.CredentialsReady,
        )

        val SNAPSHOT = WorkDirSnapshot(
            name = "WorkDir-featureX",
            absolutePath = "/w",
            repos = listOf(
                RepoSnapshot("proj-a", "/w/proj-a", RepoState.Dirty, RepoTask("T1", "doing", emptyList()), 1, 3, true),
                RepoSnapshot("proj-b", "/w/proj-b", RepoState.Ahead, RepoTask("T2", "blocked", listOf("T1")), 0, 1, false),
                RepoSnapshot("proj-c", "/w/proj-c", RepoState.Idle, null, 0, 0, false),
                RepoSnapshot("proj-d", "/w/proj-d", RepoState.Unrecognized("RUNNING"), null, 0, 0, false),
            ),
            mainSessionHasRecordedConversation = true,
            planWarnings = listOf("계획 파일의 T9 가 없는 작업을 가리킵니다"),
        )

        val DASHBOARD_STATES = listOf(
            WorkDirDashboardState.NoWorkDirOpened,
            WorkDirDashboardState.FirstObservationRunning("/w"),
            WorkDirDashboardState.WorkDirObserved("/w", SNAPSHOT, lastRefreshFailure = FAILURE, planFilePresent = false),
            WorkDirDashboardState.FirstObservationFailed("/w", FAILURE),
        )

        val INITIALIZATION_STATES = listOf(
            WorkDirInitializationState.Idle,
            WorkDirInitializationState.Running,
            WorkDirInitializationState.Failed("kyu init 이 거절했습니다"),
        )

        val PROFILES = listOf(
            TokenProfile("work", TokenStorage.Keychain),
            TokenProfile("home", TokenStorage.SecretService),
            TokenProfile("wsl", TokenStorage.ConfigFile),
            TokenProfile("new", TokenStorage.Unrecognized("vault")),
        )

        val CLONE_STATES = listOf(
            RepositoryCloneState.Closed,
            RepositoryCloneState.StepRunning(CloneStep.RegisterTokenProfile("work")),
            RepositoryCloneState.StepRunning(CloneStep.RemoveTokenProfile("old")),
            RepositoryCloneState.StepFailed(CloneStep.LoadTokenProfiles, FAILURE),
            RepositoryCloneState.StepFailed(CloneStep.LoadOwners("work"), FAILURE),
            RepositoryCloneState.StepFailed(CloneStep.LoadRepositories("work", "maximinhan"), FAILURE),
            RepositoryCloneState.StepFailed(CloneStep.CloneRepositories("/w", "work", listOf("maximinhan/a")), FAILURE),
            RepositoryCloneState.ChoosingTokenProfile(PROFILES, removalRejectionReason = "없는 프로필"),
            RepositoryCloneState.ConfirmingTokenProfileRemoval("old", PROFILES),
            RepositoryCloneState.RegisteringTokenProfile(PROFILES, rejectionReason = null),
            RepositoryCloneState.TokenProfileRegistered(RegisteredTokenProfile("work", "maximinhan")),
            RepositoryCloneState.ChoosingOwner(
                "work",
                listOf(
                    RepositoryOwner("maximinhan", OwnerKind.Personal),
                    RepositoryOwner("kyu-org", OwnerKind.Organization),
                    RepositoryOwner("ent", OwnerKind.Unrecognized("enterprise")),
                ),
            ),
            RepositoryCloneState.ChoosingRepositories(
                profileName = "work",
                ownerLogin = "maximinhan",
                repositories = listOf(
                    RemoteRepository("Kyuchestration", "maximinhan/Kyuchestration", isPrivate = true, lastUpdatedAt = AT),
                    RemoteRepository("dotfiles", "maximinhan/dotfiles", isPrivate = false, lastUpdatedAt = null),
                ),
                alreadyClonedRepositoryNames = setOf("dotfiles"),
                searchText = "kyu",
                selectedRepositoryFullNames = setOf("maximinhan/Kyuchestration"),
            ),
            RepositoryCloneState.CloneFinished(
                CloneOutcome(
                    listOf(
                        RepositoryCloneResult("maximinhan/a", CloneStatus.Cloned, ""),
                        RepositoryCloneResult("maximinhan/b", CloneStatus.Skipped, "이미 있습니다"),
                        RepositoryCloneResult("maximinhan/c", CloneStatus.Failed, "권한 없음"),
                        RepositoryCloneResult("maximinhan/d", CloneStatus.Unrecognized("partial"), ""),
                    ),
                ),
            ),
        )

        val SESSION_TREE = SessionTree(
            listOf(
                ChildSessionNode(
                    childId = "child-1",
                    title = "로그 정리",
                    relativeCwd = "proj-a",
                    workingDirectory = "/w/proj-a",
                    conversationId = "c-1",
                    startedAt = AT,
                    waitingFor = ChildWaitingFor.Parent,
                    lifecycle = ChildLifecycle.Running,
                    lastTurnResult = ChildTurnResult("반쯤 했습니다", isError = false),
                ),
                ChildSessionNode(
                    childId = "child-2",
                    title = "끝난 일",
                    relativeCwd = ".",
                    workingDirectory = "/w",
                    conversationId = "c-2",
                    startedAt = AT,
                    lifecycle = ChildLifecycle.Ended(ChildEndReason.EndedByParent, exitCode = null),
                    endedAt = AT.plusSeconds(60),
                ),
            ),
        )

        val GATE_QUESTION = ChildSessionGateQuestion(
            gateQuestionId = "gate-1",
            prompt = "이 설정으로 하위를 띄울까요?",
            gate = ChildSessionGate(
                relativeCwd = "proj-a",
                fingerprint = "sha256:abc",
                executionConfigs = listOf(GatedExecutionConfig(".mcp.json", "/w/proj-a/.mcp.json", "{\"mcpServers\":{}}")),
            ),
        )

        val TOOL_INPUT = buildJsonObject {
            put("command", "ls -la")
            put("timeout", 1000)
        }

        val ALL_ENTRIES: List<ChatEntry> = listOf(
            ChatEntry.UserSaid("사람의 말"),
            ChatEntry.UserSaid("상위의 말", UserMessageSender.ParentSession),
            ChatEntry.UserSaid("하위의 말", UserMessageSender.ChildSession("child-1")),
            ChatEntry.AssistantSaid("**마크다운** 답"),
            ChatEntry.AssistantThought("생각"),
            ChatEntry.ToolCall(
                toolUseId = "toolu_01",
                toolName = "Agent",
                input = TOOL_INPUT,
                requestedAt = AT,
                answer = ToolCallAnswer(
                    failed = false,
                    modelVisibleText = "끝",
                    typedResult = buildJsonObject { put("stdout", JsonPrimitive("a\nb")) },
                    answeredAt = AT.plusMillis(1500),
                ),
                nestedEntries = listOf(ChatEntry.AssistantSaid("안쪽 대화")),
                subagentRun = SubagentRun("general-purpose", "읽는 중", "Read", "completed", "/tmp/out.jsonl"),
            ),
            ChatEntry.ToolCall(toolUseId = "toolu_02", toolName = "Bash", input = TOOL_INPUT),
            ChatEntry.PermissionAsked("toolu_03", "Write", TOOL_INPUT, PermissionAnswer.Allowed(editedInput = TOOL_INPUT)),
            ChatEntry.PermissionAsked("toolu_04", "Write", TOOL_INPUT, PermissionAnswer.AllowedForThisSession),
            ChatEntry.PermissionAsked("toolu_05", "Write", TOOL_INPUT, PermissionAnswer.AllowedByThisSessionRule),
            ChatEntry.PermissionAsked("toolu_06", "Write", TOOL_INPUT, PermissionAnswer.Denied("안 됩니다")),
            ChatEntry.PermissionAsked("toolu_07", "Write", TOOL_INPUT, PermissionAnswer.Withdrawn),
            ChatEntry.PermissionAsked("toolu_08", "Write", TOOL_INPUT, answer = null),
            ChatEntry.TurnEnded(
                outcome = TurnOutcome.InterruptedDuringToolWait,
                costUsd = 0.0123,
                usage = TurnUsage(10, 20, 30, 40),
                durationMillis = 4200,
                permissionDenials = listOf(PermissionDenial("Write", "toolu_09", TOOL_INPUT)),
            ),
            ChatEntry.EngineNotice("No conversation found with session ID: x"),
        )
    }
}
