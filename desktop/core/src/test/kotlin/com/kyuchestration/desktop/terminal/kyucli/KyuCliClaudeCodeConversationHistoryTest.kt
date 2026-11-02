package com.kyuchestration.desktop.terminal.kyucli

import com.kyuchestration.desktop.kyu.KyuCommandFailure
import com.kyuchestration.desktop.kyu.KyuCommandResult
import com.kyuchestration.desktop.kyu.RecordingKyuCommandRunner
import com.kyuchestration.desktop.kyu.succeedingKyuCommandResult
import com.kyuchestration.desktop.terminal.ClaudeCodeConversationHistory
import com.kyuchestration.desktop.terminal.ClaudeCodeConversationHistoryFailure
import com.kyuchestration.desktop.terminal.SessionTarget
import com.kyuchestration.desktop.terminal.chat.ChatEntry
import com.kyuchestration.desktop.terminal.chat.PastTurnContent
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** `kyu conversations show --json` 을 부르고 읽는 쪽(app-owned-sessions-design.md 5.5.8). */
class KyuCliClaudeCodeConversationHistoryTest {

    @Test
    fun `메인 세션 자리는 워크디렉토리의 절대경로와 대화 id 로 묻고 항목 수는 엔진의 기본값에 맡긴다`() {
        val runner = RecordingKyuCommandRunner { succeedingKyuCommandResult(EMPTY_HISTORY) }

        KyuCliClaudeCodeConversationSource(runner).conversationHistoryOf(WORK_DIR_PATH, SessionTarget.Main, CONVERSATION_ID)

        assertEquals(
            listOf(listOf("conversations", "show", WORK_DIR_PATH.toString(), CONVERSATION_ID, "--json")),
            runner.receivedArguments,
        )
        assertEquals(listOf<Path?>(WORK_DIR_PATH), runner.receivedWorkingDirectories)
    }

    @Test
    fun `레포 세션 자리는 레포 이름을 대화 id 앞에 둔다`() {
        val runner = RecordingKyuCommandRunner { succeedingKyuCommandResult(EMPTY_HISTORY) }

        KyuCliClaudeCodeConversationSource(runner).conversationHistoryOf(WORK_DIR_PATH, SessionTarget.Repo("proj-a"), CONVERSATION_ID)

        // conversations.go 의 parseConversationsShowArgs — 위치 인자는 경로, (레포), id 순이다.
        assertEquals(
            listOf(listOf("conversations", "show", WORK_DIR_PATH.toString(), "proj-a", CONVERSATION_ID, "--json")),
            runner.receivedArguments,
        )
    }

    @Test
    fun `다섯 갈래를 지난 항목으로 읽고 시각이 null 이면 null 로 둔다`() {
        val runner = RecordingKyuCommandRunner { succeedingKyuCommandResult(FIVE_KINDS) }

        val history = KyuCliClaudeCodeConversationSource(runner).conversationHistoryOf(WORK_DIR_PATH, SessionTarget.Main, CONVERSATION_ID)

        assertEquals(
            ClaudeCodeConversationHistory(
                pastTurns = listOf(
                    ChatEntry.PastTurn(PastTurnContent.UserSaid("ls 해 줘"), Instant.parse("2026-11-01T09:06:18Z")),
                    ChatEntry.PastTurn(PastTurnContent.SystemNoted("[Request interrupted by user]"), at = null),
                    ChatEntry.PastTurn(PastTurnContent.AssistantSaid("보겠습니다."), Instant.parse("2026-11-01T09:06:20Z")),
                    ChatEntry.PastTurn(PastTurnContent.ToolUsed("Bash", "ls -la"), Instant.parse("2026-11-01T09:06:21Z")),
                    ChatEntry.PastTurn(PastTurnContent.LinesOmitted(3)),
                ),
                omittedBeforeCount = 407,
            ),
            history,
        )
    }

    @Test
    fun `빈 전사는 지난 항목 없이 생략도 없다`() {
        val runner = RecordingKyuCommandRunner { succeedingKyuCommandResult(EMPTY_HISTORY) }

        assertEquals(
            ClaudeCodeConversationHistory(pastTurns = emptyList(), omittedBeforeCount = 0),
            KyuCliClaudeCodeConversationSource(runner).conversationHistoryOf(WORK_DIR_PATH, SessionTarget.Main, CONVERSATION_ID),
        )
    }

    @Test
    fun `모르는 갈래는 건너뛰되 앞에서 생략된 수에는 세지 않는다`() {
        // 엔진은 kind 를 더해도 판을 올리지 않는다(conversations_json.go). 모르는 것은 실패가 아니다.
        val runner = RecordingKyuCommandRunner {
            succeedingKyuCommandResult(
                """{"schemaVersion":1,"conversationId":"$CONVERSATION_ID","cwd":"/w","totalEntries":5,"entries":[""" +
                    """{"kind":"image","at":null},{"kind":"user","text":"안녕","at":null}]}""",
            )
        }

        val history = KyuCliClaudeCodeConversationSource(runner).conversationHistoryOf(WORK_DIR_PATH, SessionTarget.Main, CONVERSATION_ID)

        assertEquals(listOf(ChatEntry.PastTurn(PastTurnContent.UserSaid("안녕"), at = null)), history.pastTurns)
        assertEquals(3, history.omittedBeforeCount)
    }

    @Test
    fun `모르는 판의 문서는 읽지 않고 실패로 올린다`() {
        val runner = RecordingKyuCommandRunner {
            succeedingKyuCommandResult("""{"schemaVersion":2,"conversationId":"x","cwd":"/w","totalEntries":0,"entries":[]}""")
        }

        val failure = assertFailsWith<ClaudeCodeConversationHistoryFailure> {
            KyuCliClaudeCodeConversationSource(runner).conversationHistoryOf(WORK_DIR_PATH, SessionTarget.Main, CONVERSATION_ID)
        }

        assertTrue("판 2" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun `아는 갈래에 제 자리가 없으면 계약이 깨진 것이라 실패로 올린다`() {
        val runner = RecordingKyuCommandRunner {
            succeedingKyuCommandResult(
                """{"schemaVersion":1,"conversationId":"x","cwd":"/w","totalEntries":1,"entries":[{"kind":"tool_use","at":null}]}""",
            )
        }

        assertFailsWith<ClaudeCodeConversationHistoryFailure> {
            KyuCliClaudeCodeConversationSource(runner).conversationHistoryOf(WORK_DIR_PATH, SessionTarget.Main, CONVERSATION_ID)
        }
    }

    @Test
    fun `읽을 수 없는 시각은 실패로 올린다`() {
        val runner = RecordingKyuCommandRunner {
            succeedingKyuCommandResult(
                """{"schemaVersion":1,"conversationId":"x","cwd":"/w","totalEntries":1,"entries":[{"kind":"user","text":"a","at":"어제"}]}""",
            )
        }

        val failure = assertFailsWith<ClaudeCodeConversationHistoryFailure> {
            KyuCliClaudeCodeConversationSource(runner).conversationHistoryOf(WORK_DIR_PATH, SessionTarget.Main, CONVERSATION_ID)
        }

        assertEquals("kyu conversations show 가 읽을 수 없는 시각을 냈습니다: 어제", failure.message)
    }

    @Test
    fun `kyu 가 거절하면 그 이유를 싣고 실패로 올린다`() {
        val runner = RecordingKyuCommandRunner {
            KyuCommandResult(exitCode = 1, standardOutput = "", standardError = "대화 전사가 없습니다\n")
        }

        val failure = assertFailsWith<ClaudeCodeConversationHistoryFailure> {
            KyuCliClaudeCodeConversationSource(runner).conversationHistoryOf(WORK_DIR_PATH, SessionTarget.Main, CONVERSATION_ID)
        }

        assertEquals("kyu conversations show 가 종료 코드 1 로 끝났습니다: 대화 전사가 없습니다", failure.message)
    }

    @Test
    fun `부를 kyu 가 없어도 실패로 올린다`() {
        val runner = RecordingKyuCommandRunner { throw KyuCommandFailure.ExecutableNotFound() }

        assertFailsWith<ClaudeCodeConversationHistoryFailure> {
            KyuCliClaudeCodeConversationSource(runner).conversationHistoryOf(WORK_DIR_PATH, SessionTarget.Main, CONVERSATION_ID)
        }
    }

    private companion object {
        val WORK_DIR_PATH: Path = Path.of("/home/me/work/WorkDir-featureX")

        const val CONVERSATION_ID = "654ce605-b030-40e8-8448-2e9003ad32b1"

        const val EMPTY_HISTORY =
            """{"schemaVersion":1,"conversationId":"$CONVERSATION_ID","cwd":"/home/me/work/WorkDir-featureX","totalEntries":0,"entries":[]}"""

        // internal/cli/conversations_json.go 의 스키마 예시를 옮겼다. 전체 412 개 중 마지막 다섯이다.
        val FIVE_KINDS = """
            {
              "schemaVersion": 1,
              "conversationId": "$CONVERSATION_ID",
              "cwd": "/home/me/work/WorkDir-featureX",
              "totalEntries": 412,
              "entries": [
                {"kind": "user", "text": "ls 해 줘", "at": "2026-11-01T09:06:18Z"},
                {"kind": "system", "text": "[Request interrupted by user]", "at": null},
                {"kind": "assistant", "text": "보겠습니다.", "at": "2026-11-01T09:06:20Z"},
                {"kind": "tool_use", "toolName": "Bash", "summary": "ls -la", "at": "2026-11-01T09:06:21Z"},
                {"kind": "omitted", "count": 3}
              ]
            }
        """.trimIndent()
    }
}
