package com.kyuchestration.desktop.terminal.kyucli

import com.kyuchestration.desktop.kyu.KyuCommandFailure
import com.kyuchestration.desktop.kyu.KyuCommandResult
import com.kyuchestration.desktop.kyu.RecordingKyuCommandRunner
import com.kyuchestration.desktop.kyu.succeedingKyuCommandResult
import com.kyuchestration.desktop.terminal.ClaudeCodeConversation
import com.kyuchestration.desktop.terminal.ClaudeCodeConversationListingFailure
import com.kyuchestration.desktop.terminal.SessionTarget
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KyuCliClaudeCodeConversationSourceTest {

    @Test
    fun `메인 세션 자리는 워크디렉토리의 절대경로 하나로 묻는다`() {
        val runner = RecordingKyuCommandRunner { succeedingKyuCommandResult(EMPTY_LISTING) }

        KyuCliClaudeCodeConversationSource(runner).conversationsAt(WORK_DIR_PATH, SessionTarget.Main)

        // 이 명령은 경로를 생략하면 짐작하지 않고 거절한다(conversations.go 의 parseConversationsListArgs).
        assertEquals(listOf(listOf("conversations", "list", WORK_DIR_PATH.toString(), "--json")), runner.receivedArguments)
        assertEquals(listOf<Path?>(WORK_DIR_PATH), runner.receivedWorkingDirectories)
    }

    @Test
    fun `레포 세션 자리는 레포 이름을 덧붙인다`() {
        val runner = RecordingKyuCommandRunner { succeedingKyuCommandResult(EMPTY_LISTING) }

        KyuCliClaudeCodeConversationSource(runner).conversationsAt(WORK_DIR_PATH, SessionTarget.Repo("proj-a"))

        assertEquals(listOf(listOf("conversations", "list", WORK_DIR_PATH.toString(), "proj-a", "--json")), runner.receivedArguments)
    }

    @Test
    fun `빈 목록은 빈 목록이다`() {
        val runner = RecordingKyuCommandRunner { succeedingKyuCommandResult(EMPTY_LISTING) }

        assertEquals(emptyList(), KyuCliClaudeCodeConversationSource(runner).conversationsAt(WORK_DIR_PATH, SessionTarget.Main))
    }

    @Test
    fun `적힌 라벨과 시작 시각은 없으면 null 로 읽는다`() {
        val runner = RecordingKyuCommandRunner { succeedingKyuCommandResult(TWO_CONVERSATIONS) }

        val conversations = KyuCliClaudeCodeConversationSource(runner).conversationsAt(WORK_DIR_PATH, SessionTarget.Main)

        assertEquals(
            listOf(
                ClaudeCodeConversation(
                    id = "654ce605-b030-40e8-8448-2e9003ad32b1",
                    title = "workdir-orchestrator-design.md 검토",
                    startedAt = Instant.parse("2026-08-27T06:05:50Z"),
                    lastActiveAt = Instant.parse("2026-11-01T09:00:56Z"),
                    recordedForLabel = null,
                ),
                ClaudeCodeConversation(
                    id = "2a9d3b8f-0000-4000-8000-000000000002",
                    title = "",
                    startedAt = null,
                    lastActiveAt = Instant.parse("2026-10-31T12:00:00Z"),
                    recordedForLabel = "main",
                ),
            ),
            conversations,
        )
    }

    @Test
    fun `모르는 판의 문서는 읽지 않고 실패로 올린다`() {
        val runner = RecordingKyuCommandRunner { succeedingKyuCommandResult("""{"schemaVersion":2,"cwd":"/w","conversations":[]}""") }

        val failure = assertFailsWith<ClaudeCodeConversationListingFailure> {
            KyuCliClaudeCodeConversationSource(runner).conversationsAt(WORK_DIR_PATH, SessionTarget.Main)
        }

        assertTrue("판 2" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun `kyu 가 거절하면 그 이유를 싣고 실패로 올린다`() {
        val runner = RecordingKyuCommandRunner {
            KyuCommandResult(exitCode = 1, standardOutput = "", standardError = "없는 레포입니다: proj-z\n")
        }

        val failure = assertFailsWith<ClaudeCodeConversationListingFailure> {
            KyuCliClaudeCodeConversationSource(runner).conversationsAt(WORK_DIR_PATH, SessionTarget.Repo("proj-z"))
        }

        assertEquals("kyu conversations list 가 종료 코드 1 로 끝났습니다: 없는 레포입니다: proj-z", failure.message)
    }

    @Test
    fun `부를 kyu 가 없어도 실패로 올린다`() {
        val runner = RecordingKyuCommandRunner { throw KyuCommandFailure.ExecutableNotFound() }

        assertFailsWith<ClaudeCodeConversationListingFailure> {
            KyuCliClaudeCodeConversationSource(runner).conversationsAt(WORK_DIR_PATH, SessionTarget.Main)
        }
    }

    private companion object {
        val WORK_DIR_PATH: Path = Path.of("/home/me/work/WorkDir-featureX")

        const val EMPTY_LISTING = """{"schemaVersion":1,"cwd":"/home/me/work/WorkDir-featureX","conversations":[]}"""

        // internal/cli/conversations_json.go 의 스키마 예시를 그대로 옮겼다.
        val TWO_CONVERSATIONS = """
            {
              "schemaVersion": 1,
              "cwd": "/home/me/work/WorkDir-featureX",
              "conversations": [
                {
                  "id": "654ce605-b030-40e8-8448-2e9003ad32b1",
                  "title": "workdir-orchestrator-design.md 검토",
                  "startedAt": "2026-08-27T06:05:50Z",
                  "lastActiveAt": "2026-11-01T09:00:56Z",
                  "recordedFor": null
                },
                {
                  "id": "2a9d3b8f-0000-4000-8000-000000000002",
                  "title": "",
                  "startedAt": null,
                  "lastActiveAt": "2026-10-31T12:00:00Z",
                  "recordedFor": "main"
                }
              ]
            }
        """.trimIndent()
    }
}
