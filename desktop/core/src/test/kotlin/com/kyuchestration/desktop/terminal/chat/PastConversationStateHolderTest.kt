package com.kyuchestration.desktop.terminal.chat

import com.kyuchestration.desktop.diagnostics.DiagnosticLogEntry
import com.kyuchestration.desktop.diagnostics.RecordingDiagnosticLog
import com.kyuchestration.desktop.terminal.ClaudeCodeConversationHistory
import com.kyuchestration.desktop.terminal.ClaudeCodeConversationHistoryFailure
import com.kyuchestration.desktop.terminal.ClaudeCodeConversationHistorySource
import com.kyuchestration.desktop.terminal.PersonOpenedSessionTarget
import com.kyuchestration.desktop.terminal.SessionConversationChoice
import com.kyuchestration.desktop.terminal.SessionEntryPlan
import com.kyuchestration.desktop.terminal.SessionTarget
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * 이어가는 세션의 채팅 창 앞에 지난 대화를 까는 자리(app-owned-sessions-design.md 5.5.8).
 *
 * 사용자가 만난 결함이 이 시험의 모양이다 — 고른 대화로 세션이 `--resume` 으로 열려도 채팅 창이 비어 있었다.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PastConversationStateHolderTest {

    @Test
    fun `이어가는 세션은 지난 대화를 엔진에게 묻고 전사 앞에 깐 뒤 엔진의 경고를 둔다`() = runTest {
        val opener = RecordingChatSessionOpener()
        val historySource = RecordingConversationHistorySource { _, _, _ -> HISTORY }
        val commandSource = RecordingSessionCommandSource().apply {
            resumedConversationId = RESUMED_ID
            engineWarnings = listOf("대화 기록을 읽지 못해 버렸습니다")
        }
        val holder = stateHolder(opener, commandSource, claudeCodeConversationHistorySource = historySource)

        holder.enterSession(TEST_WORK_DIR_PATH, SessionTarget.Main, SessionConversationChoice.ContinueRecordedConversation)
        runCurrent()

        assertEquals(listOf(AskedHistory(TEST_WORK_DIR_PATH, SessionTarget.Main, RESUMED_ID)), historySource.asked)
        val conversation = holder.conversations.value.getValue(SessionTarget.Main)
        assertEquals(HISTORY.pastTurns + ChatEntry.EngineNotice("대화 기록을 읽지 못해 버렸습니다"), conversation.entries)
        assertEquals(407, conversation.pastTurnsOmittedBefore)
    }

    @Test
    fun `이번 세션의 항목은 지난 대화 뒤에 쌓인다`() = runTest {
        val opener = RecordingChatSessionOpener()
        val commandSource = RecordingSessionCommandSource().apply { resumedConversationId = RESUMED_ID }
        val holder = stateHolder(
            opener,
            commandSource,
            claudeCodeConversationHistorySource = RecordingConversationHistorySource { _, _, _ -> HISTORY },
        )
        holder.enterSession(TEST_WORK_DIR_PATH, SessionTarget.Main, SessionConversationChoice.ContinueRecordedConversation)
        runCurrent()

        opener.lastSession.emit(ChatSessionEvent.UserMessageEchoed("이어서 하자", parentToolUseId = null, messageUuid = null))
        runCurrent()

        assertEquals(
            HISTORY.pastTurns + ChatEntry.UserSaid("이어서 하자"),
            holder.conversations.value.getValue(SessionTarget.Main).entries,
        )
    }

    @Test
    fun `세션을 먼저 띄우고 지난 대화는 그 뒤에 묻는다`() = runTest {
        // 지난 대화를 읽는 동안 사람이 세션을 기다리지 않게 — 띄우는 일이 묻는 일에 막히지 않는다.
        val happened = mutableListOf<String>()
        val opener = object : ChatSessionOpener {
            val recording = RecordingChatSessionOpener()
            override suspend fun openChatSession(plan: SessionEntryPlan): OpenedChatSession {
                happened += "세션 띄움"
                return recording.openChatSession(plan)
            }
        }
        val commandSource = RecordingSessionCommandSource().apply { resumedConversationId = RESUMED_ID }
        val holder = stateHolder(
            opener,
            commandSource,
            claudeCodeConversationHistorySource = RecordingConversationHistorySource { _, _, _ ->
                happened += "지난 대화 물음"
                HISTORY
            },
        )

        holder.enterSession(TEST_WORK_DIR_PATH, SessionTarget.Main, SessionConversationChoice.ContinueRecordedConversation)
        runCurrent()

        assertEquals(listOf("세션 띄움", "지난 대화 물음"), happened)
    }

    @Test
    fun `새 대화는 지난 대화를 묻지 않는다`() = runTest {
        val historySource = RecordingConversationHistorySource { _, _, _ -> HISTORY }
        val commandSource = RecordingSessionCommandSource().apply { resumedConversationId = null }
        val holder = stateHolder(RecordingChatSessionOpener(), commandSource, claudeCodeConversationHistorySource = historySource)

        holder.enterSession(TEST_WORK_DIR_PATH, SessionTarget.Main, SessionConversationChoice.StartNewConversation)
        runCurrent()

        assertEquals(emptyList(), historySource.asked)
        val conversation = holder.conversations.value.getValue(SessionTarget.Main)
        assertEquals(emptyList(), conversation.entries)
        assertEquals(0, conversation.pastTurnsOmittedBefore)
    }

    @Test
    fun `지난 대화를 받지 못해도 세션은 열리고 그 까닭이 전사 맨 앞과 진단 기록에 남는다`() = runTest {
        val diagnosticLog = RecordingDiagnosticLog()
        val commandSource = RecordingSessionCommandSource().apply {
            resumedConversationId = RESUMED_ID
            engineWarnings = listOf("엔진의 경고")
        }
        val holder = stateHolder(
            RecordingChatSessionOpener(),
            commandSource,
            diagnosticLog,
            claudeCodeConversationHistorySource = RecordingConversationHistorySource { _, _, _ ->
                throw ClaudeCodeConversationHistoryFailure(
                    "kyu conversations show 가 종료 코드 1 로 끝났습니다: /w 에서 Claude Code 로 나눈 대화 중에 $RESUMED_ID 가 없습니다",
                )
            },
        )

        holder.enterSession(TEST_WORK_DIR_PATH, SessionTarget.Main, SessionConversationChoice.ContinueRecordedConversation)
        runCurrent()

        assertEquals(listOf<SessionTarget>(SessionTarget.Main), holder.heldSessions.value.map { it.target })
        assertEquals(emptyMap(), holder.sessionEntries.value)
        val conversation = holder.conversations.value.getValue(SessionTarget.Main)
        assertEquals(
            listOf<ChatEntry>(
                ChatEntry.EngineNotice(
                    "지난 대화를 불러오지 못했습니다: kyu conversations show 가 종료 코드 1 로 끝났습니다: " +
                        "/w 에서 Claude Code 로 나눈 대화 중에 $RESUMED_ID 가 없습니다",
                ),
                ChatEntry.EngineNotice("엔진의 경고"),
            ),
            conversation.entries,
        )
        val recorded = diagnosticLog.recordedEntries.filterIsInstance<DiagnosticLogEntry.ClaudeCodeConversationHistoryReadFailed>().single()
        assertEquals("main", recorded.targetLabel)
        // 대화 id 는 진단에 쓰이지 않으면서 사용자의 대화를 가리키는 값이다(SessionStarted 와 같은 선).
        assertFalse(RESUMED_ID in recorded.summary, recorded.summary)
        assertTrue("(대화 id) 가 없습니다" in recorded.summary, recorded.summary)
    }

    @Test
    fun `지난 대화 읽기가 뜻밖의 예외를 던져도 세션은 보유되고 그 까닭이 전사 맨 앞에 선다`() = runTest {
        // 실행기가 파이프를 읽다 받은 IOException 같은 것 — 포트의 실패 타입으로 옮겨지지 않은 예외다. 지난 대화는 세션에
        // 덧붙이는 것이라, 무엇으로 실패하든 떠 있는 claude 를 잃게 하지 않는다.
        val opener = RecordingChatSessionOpener()
        val diagnosticLog = RecordingDiagnosticLog()
        val commandSource = RecordingSessionCommandSource().apply { resumedConversationId = RESUMED_ID }
        val holder = stateHolder(
            opener,
            commandSource,
            diagnosticLog,
            claudeCodeConversationHistorySource = RecordingConversationHistorySource { _, _, _ ->
                throw IllegalStateException("파이프가 닫혔습니다 ($RESUMED_ID)")
            },
        )

        holder.enterSession(TEST_WORK_DIR_PATH, SessionTarget.Main, SessionConversationChoice.ContinueRecordedConversation)
        runCurrent()

        assertEquals(listOf<SessionTarget>(SessionTarget.Main), holder.heldSessions.value.map { it.target })
        assertEquals(emptyList(), opener.endedSessions)
        assertEquals(
            listOf<ChatEntry>(
                ChatEntry.EngineNotice("지난 대화를 불러오지 못했습니다: IllegalStateException: 파이프가 닫혔습니다 ($RESUMED_ID)"),
            ),
            holder.conversations.value.getValue(SessionTarget.Main).entries,
        )
        val recorded = diagnosticLog.recordedEntries.filterIsInstance<DiagnosticLogEntry.ClaudeCodeConversationHistoryReadFailed>().single()
        assertFalse(RESUMED_ID in recorded.summary, recorded.summary)
    }

    @Test
    fun `레포 세션은 그 레포의 자리로 묻는다`() = runTest {
        val historySource = RecordingConversationHistorySource { _, _, _ -> HISTORY }
        val commandSource = RecordingSessionCommandSource().apply { resumedConversationId = RESUMED_ID }
        val holder = stateHolder(RecordingChatSessionOpener(), commandSource, claudeCodeConversationHistorySource = historySource)

        holder.enterSession(TEST_WORK_DIR_PATH, SessionTarget.Repo("proj-a"), SessionConversationChoice.ContinueRecordedConversation)
        runCurrent()

        assertEquals(listOf(AskedHistory(TEST_WORK_DIR_PATH, SessionTarget.Repo("proj-a"), RESUMED_ID)), historySource.asked)
    }

    @Test
    fun `채택한 바깥 대화도 엔진이 답한 그 대화의 지난 내용을 깐다`() = runTest {
        // 묻는 id 는 앱이 고른 것이 아니라 엔진이 이어간다고 답한 것이다 — 그 둘이 갈리면 화면이 다른 대화를 보인다.
        val historySource = RecordingConversationHistorySource { _, _, _ -> HISTORY }
        val commandSource = RecordingSessionCommandSource().apply { resumedConversationId = TERMINAL_ID }
        val holder = stateHolder(RecordingChatSessionOpener(), commandSource, claudeCodeConversationHistorySource = historySource)

        holder.enterSession(TEST_WORK_DIR_PATH, SessionTarget.Main, SessionConversationChoice.AdoptConversation(TERMINAL_ID))
        runCurrent()

        assertEquals(listOf(AskedHistory(TEST_WORK_DIR_PATH, SessionTarget.Main, TERMINAL_ID)), historySource.asked)
        assertEquals(HISTORY.pastTurns, holder.conversations.value.getValue(SessionTarget.Main).entries)
    }

    private data class AskedHistory(val workDirPath: Path, val target: PersonOpenedSessionTarget, val conversationId: String)

    private class RecordingConversationHistorySource(
        private val respond: (Path, PersonOpenedSessionTarget, String) -> ClaudeCodeConversationHistory,
    ) : ClaudeCodeConversationHistorySource {
        val asked = mutableListOf<AskedHistory>()

        override fun conversationHistoryOf(
            workDirPath: Path,
            target: PersonOpenedSessionTarget,
            conversationId: String,
        ): ClaudeCodeConversationHistory {
            asked += AskedHistory(workDirPath, target, conversationId)
            return respond(workDirPath, target, conversationId)
        }
    }

    private companion object {
        const val RESUMED_ID = "211f6974-88a8-4453-9248-a02b0d6febae"
        const val TERMINAL_ID = "654ce605-b030-40e8-8448-2e9003ad32b1"

        val HISTORY = ClaudeCodeConversationHistory(
            pastTurns = listOf(
                ChatEntry.PastTurn(PastTurnContent.UserSaid("ls 해 줘"), Instant.parse("2026-11-01T09:06:18Z")),
                ChatEntry.PastTurn(PastTurnContent.ToolUsed("Bash", "ls -la"), Instant.parse("2026-11-01T09:06:21Z")),
                ChatEntry.PastTurn(PastTurnContent.LinesOmitted(3)),
            ),
            omittedBeforeCount = 407,
        )
    }
}
