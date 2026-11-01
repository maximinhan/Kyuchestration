package com.kyuchestration.desktop.terminal.chat

import com.kyuchestration.desktop.diagnostics.DiagnosticLogEntry
import com.kyuchestration.desktop.diagnostics.RecordingDiagnosticLog
import com.kyuchestration.desktop.terminal.ClaudeCodeConversation
import com.kyuchestration.desktop.terminal.ClaudeCodeConversationListingFailure
import com.kyuchestration.desktop.terminal.ClaudeCodeConversationSource
import com.kyuchestration.desktop.terminal.SessionConversationChoice
import com.kyuchestration.desktop.terminal.SessionTarget
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * 이어가기를 누른 자리에서 폴더 밖(터미널)의 대화를 고르게 할지 정하는 자리(app-owned-sessions-design.md 5.5.7).
 *
 * 묻는 조건은 "앱에 적힌 것보다 최근인 바깥 대화가 있다" 하나다. 그래서 시험은 시각만 바꿔 가며 같은 목록을 쓴다 — 갈리는
 * 것이 시각뿐임을 시험의 모양이 말하게.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ConversationChoiceStateHolderTest {

    @Test
    fun `바깥 대화가 없으면 묻지 않고 오늘처럼 이어간다`() = runTest {
        val commandSource = RecordingSessionCommandSource()
        val holder = stateHolder(
            RecordingChatSessionOpener(),
            commandSource,
            claudeCodeConversationSource = listing(recordedForMain(at = "2026-10-31T12:00:00Z")),
        )

        holder.enterSession(TEST_WORK_DIR_PATH, SessionTarget.Main, SessionConversationChoice.ContinueRecordedConversation)
        runCurrent()

        assertEquals(listOf<SessionConversationChoice>(SessionConversationChoice.ContinueRecordedConversation), commandSource.askedConversationChoices)
        assertEquals(listOf<SessionTarget>(SessionTarget.Main), holder.heldSessions.value.map { it.target })
    }

    @Test
    fun `적힌 대화가 없는데 바깥 대화가 있으면 고르게 하고 가장 최근 것을 기본으로 둔다`() = runTest {
        val commandSource = RecordingSessionCommandSource()
        val older = outside("1f0c2a7e-0000-4000-8000-000000000001", at = "2026-10-30T01:00:00Z")
        val newer = outside(TERMINAL_CONVERSATION_ID, at = "2026-11-01T09:00:56Z", startedAt = "2026-08-27T06:05:50Z")
        val holder = stateHolder(
            RecordingChatSessionOpener(),
            commandSource,
            // 다른 라벨에 적힌 대화는 그 라벨의 것이다 — 후보에 섞이면 두 세션이 한 대화를 연다.
            claudeCodeConversationSource = listing(newer, recordedFor("proj-a", at = "2026-11-01T10:00:00Z"), older),
        )

        holder.enterSession(TEST_WORK_DIR_PATH, SessionTarget.Main, SessionConversationChoice.ContinueRecordedConversation)
        runCurrent()

        val choosing = assertIs<SessionEntryState.ChoosingConversation>(holder.sessionEntries.value[SessionTarget.Main])
        assertEquals(
            listOf(
                ExistingConversation(TERMINAL_CONVERSATION_ID, "대화 $TERMINAL_CONVERSATION_ID", "2026-08-27T06:05:50Z", "2026-11-01T09:00:56Z"),
                ExistingConversation("1f0c2a7e-0000-4000-8000-000000000001", "대화 1f0c2a7e-0000-4000-8000-000000000001", null, "2026-10-30T01:00:00Z"),
            ),
            choosing.candidates,
        )
        assertEquals(null, choosing.recorded)
        assertEquals(SessionConversationChoice.AdoptConversation(TERMINAL_CONVERSATION_ID), choosing.defaultChoice)
        // 고르기 전에는 엔진에게 묻지 않는다 — 묻는 것이 곧 기록하는 것이다(5.5.3).
        assertEquals(emptyList(), commandSource.askedConversationChoices)
    }

    @Test
    fun `바깥 대화가 적힌 것보다 오래되었으면 묻지 않는다`() = runTest {
        val commandSource = RecordingSessionCommandSource()
        val holder = stateHolder(
            RecordingChatSessionOpener(),
            commandSource,
            claudeCodeConversationSource = listing(
                recordedForMain(at = "2026-11-01T09:00:56Z"),
                outside(TERMINAL_CONVERSATION_ID, at = "2026-10-30T01:00:00Z"),
            ),
        )

        holder.enterSession(TEST_WORK_DIR_PATH, SessionTarget.Main, SessionConversationChoice.ContinueRecordedConversation)
        runCurrent()

        // 한 번 골라 그 대화로 말을 주고받은 뒤의 모습이다. 다음부터는 묻지 않는다 — 상태 없이 얻는 "한 번만 묻기".
        assertEquals(listOf<SessionConversationChoice>(SessionConversationChoice.ContinueRecordedConversation), commandSource.askedConversationChoices)
        assertEquals(listOf<SessionTarget>(SessionTarget.Main), holder.heldSessions.value.map { it.target })
    }

    @Test
    fun `바깥 대화가 적힌 것보다 최근이면 묻고 적힌 대화도 함께 보인다`() = runTest {
        val holder = stateHolder(
            RecordingChatSessionOpener(),
            claudeCodeConversationSource = listing(
                outside(TERMINAL_CONVERSATION_ID, at = "2026-11-01T09:00:56Z"),
                recordedFor("proj-a", at = "2026-10-31T12:00:00Z"),
            ),
        )

        holder.enterSession(TEST_WORK_DIR_PATH, SessionTarget.Repo("proj-a"), SessionConversationChoice.ContinueRecordedConversation)
        runCurrent()

        val choosing = assertIs<SessionEntryState.ChoosingConversation>(holder.sessionEntries.value[SessionTarget.Repo("proj-a")])
        assertEquals(listOf(TERMINAL_CONVERSATION_ID), choosing.candidates.map { it.id })
        assertEquals(RECORDED_CONVERSATION_ID, assertNotNull(choosing.recorded).id)
        assertEquals(SessionConversationChoice.AdoptConversation(TERMINAL_CONVERSATION_ID), choosing.defaultChoice)
    }

    @Test
    fun `고른 바깥 대화를 채택하라고 엔진에게 묻고 세션을 보유한다`() = runTest {
        val commandSource = RecordingSessionCommandSource().apply { resumedConversationId = TERMINAL_CONVERSATION_ID }
        val holder = stateHolder(
            RecordingChatSessionOpener(),
            commandSource,
            claudeCodeConversationSource = listing(outside(TERMINAL_CONVERSATION_ID, at = "2026-11-01T09:00:56Z")),
        )
        holder.enterSession(TEST_WORK_DIR_PATH, SessionTarget.Main, SessionConversationChoice.ContinueRecordedConversation)
        runCurrent()

        holder.chooseConversation(SessionTarget.Main, SessionConversationChoice.AdoptConversation(TERMINAL_CONVERSATION_ID))
        runCurrent()

        assertEquals(
            listOf<SessionConversationChoice>(SessionConversationChoice.AdoptConversation(TERMINAL_CONVERSATION_ID)),
            commandSource.askedConversationChoices,
        )
        assertEquals(TERMINAL_CONVERSATION_ID, holder.conversations.value[SessionTarget.Main]?.resumedConversationId)
        assertEquals(emptyMap(), holder.sessionEntries.value)
    }

    @Test
    fun `고르기를 취소하면 진입이 사라지고 다시 누르면 다시 묻는다`() = runTest {
        val commandSource = RecordingSessionCommandSource()
        val holder = stateHolder(
            RecordingChatSessionOpener(),
            commandSource,
            claudeCodeConversationSource = listing(outside(TERMINAL_CONVERSATION_ID, at = "2026-11-01T09:00:56Z")),
        )
        holder.enterSession(TEST_WORK_DIR_PATH, SessionTarget.Main, SessionConversationChoice.ContinueRecordedConversation)
        runCurrent()

        holder.cancelConversationChoice(SessionTarget.Main)

        assertEquals(emptyMap(), holder.sessionEntries.value)
        assertEquals(emptyList(), commandSource.askedConversationChoices)

        // 취소한 진입이 기다리는 진입으로 남으면 다음 누름이 "이미 띄우는 중" 으로 버려진다.
        holder.enterSession(TEST_WORK_DIR_PATH, SessionTarget.Main, SessionConversationChoice.ContinueRecordedConversation)
        runCurrent()
        assertIs<SessionEntryState.ChoosingConversation>(holder.sessionEntries.value[SessionTarget.Main])
    }

    @Test
    fun `대화 목록을 읽지 못하면 묻지 않고 이어가며 그 사실을 기록과 대화에 남긴다`() = runTest {
        val commandSource = RecordingSessionCommandSource()
        val diagnosticLog = RecordingDiagnosticLog()
        val holder = stateHolder(
            RecordingChatSessionOpener(),
            commandSource,
            diagnosticLog,
            claudeCodeConversationSource = { _, _ -> throw ClaudeCodeConversationListingFailure("kyu conversations list 가 종료 코드 2 로 끝났습니다") },
        )

        holder.enterSession(TEST_WORK_DIR_PATH, SessionTarget.Main, SessionConversationChoice.ContinueRecordedConversation)
        runCurrent()

        // 묻지 못했다고 세션을 못 여는 것은 과하다 — 오늘 하던 대로 적힌 대화를 이어간다.
        assertEquals(listOf<SessionConversationChoice>(SessionConversationChoice.ContinueRecordedConversation), commandSource.askedConversationChoices)
        val logged = assertIs<DiagnosticLogEntry.ClaudeCodeConversationListingFailed>(diagnosticLog.recordedEntries.first())
        assertEquals("main", logged.targetLabel)
        assertEquals("kyu conversations list 가 종료 코드 2 로 끝났습니다", logged.failureMessage)
        val notice = assertIs<ChatEntry.EngineNotice>(assertNotNull(holder.conversations.value[SessionTarget.Main]).entries.first())
        assertEquals(
            "이 자리의 Claude Code 대화 목록을 읽지 못해 고르지 않고 이어갑니다 — kyu conversations list 가 종료 코드 2 로 끝났습니다",
            notice.line,
        )
    }

    @Test
    fun `고르는 동안 워크디렉토리를 닫으면 늦게 온 선택은 버린다`() = runTest {
        val commandSource = RecordingSessionCommandSource()
        val holder = stateHolder(
            RecordingChatSessionOpener(),
            commandSource,
            claudeCodeConversationSource = listing(outside(TERMINAL_CONVERSATION_ID, at = "2026-11-01T09:00:56Z")),
        )
        holder.enterSession(TEST_WORK_DIR_PATH, SessionTarget.Main, SessionConversationChoice.ContinueRecordedConversation)
        runCurrent()

        holder.endAllSessions()
        holder.chooseConversation(SessionTarget.Main, SessionConversationChoice.AdoptConversation(TERMINAL_CONVERSATION_ID))
        runCurrent()

        // 닫힌 워크디렉토리의 대화를 채택하면 그 폴더의 기록이 사람이 떠난 뒤에 바뀐다.
        assertEquals(emptyList(), commandSource.askedConversationChoices)
        assertEquals(emptyMap(), holder.sessionEntries.value)
        assertEquals(emptyList(), holder.heldSessions.value.map { it.target })
    }

    @Test
    fun `목록을 읽는 사이 세션을 끝내면 띄우지도 묻지도 않는다`() = runTest {
        val opener = RecordingChatSessionOpener()
        val commandSource = RecordingSessionCommandSource()
        val holder = stateHolder(
            opener,
            commandSource,
            claudeCodeConversationSource = listing(outside(TERMINAL_CONVERSATION_ID, at = "2026-11-01T09:00:56Z")),
        )
        holder.enterSession(TEST_WORK_DIR_PATH, SessionTarget.Main, SessionConversationChoice.ContinueRecordedConversation)

        holder.endSession(SessionTarget.Main)
        runCurrent()

        assertEquals(emptyList(), opener.openedPlans)
        assertEquals(emptyList(), commandSource.askedConversationChoices)
        assertEquals(emptyMap(), holder.sessionEntries.value)
    }

    @Test
    fun `새 대화와 채택은 목록을 묻지 않는다`() = runTest {
        var listingCount = 0
        val commandSource = RecordingSessionCommandSource()
        val holder = stateHolder(
            RecordingChatSessionOpener(),
            commandSource,
            claudeCodeConversationSource = { _, _ ->
                listingCount++
                listOf(outside(TERMINAL_CONVERSATION_ID, at = "2026-11-01T09:00:56Z"))
            },
        )

        holder.enterSession(TEST_WORK_DIR_PATH, SessionTarget.Main, SessionConversationChoice.StartNewConversation)
        holder.enterSession(TEST_WORK_DIR_PATH, SessionTarget.Repo("proj-a"), SessionConversationChoice.AdoptConversation(TERMINAL_CONVERSATION_ID))
        runCurrent()

        assertEquals(0, listingCount)
        assertEquals(2, commandSource.askedConversationChoices.size)
    }

    private fun listing(vararg conversations: ClaudeCodeConversation) = ClaudeCodeConversationSource { _, _ -> conversations.toList() }

    private fun outside(id: String, at: String, startedAt: String? = null) = ClaudeCodeConversation(
        id = id,
        title = "대화 $id",
        startedAt = startedAt?.let(Instant::parse),
        lastActiveAt = Instant.parse(at),
        recordedForLabel = null,
    )

    private fun recordedFor(label: String, at: String) =
        outside(RECORDED_CONVERSATION_ID, at).copy(recordedForLabel = label)

    private fun recordedForMain(at: String) = recordedFor(SessionTarget.Main.label, at)

    private companion object {
        const val TERMINAL_CONVERSATION_ID = "654ce605-b030-40e8-8448-2e9003ad32b1"
        const val RECORDED_CONVERSATION_ID = "2a9d3b8f-0000-4000-8000-000000000002"
    }
}
