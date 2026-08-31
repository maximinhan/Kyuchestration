package com.kyuchestration.desktop.terminal.kyucli

import com.kyuchestration.desktop.terminal.TerminalSessionFailure
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class KyuSessionCommandOutputTest {

    @Test
    fun `앱이 실행할 argv 를 순서 그대로 읽는다`() {
        val answer = parseKyuSessionCommandOutput(MAIN_SESSION_DOCUMENT)

        // 순서가 곧 계약이다. 정렬하거나 중복을 걷어내면 --add-dir 이 자기 값과 갈라진다.
        assertEquals(
            listOf(
                "claude",
                "--session-id", "d1b9d634-a927-4abf-ad80-5941671b08a8",
                "--add-dir", "/home/me/work/WorkDir-featureX/proj-a",
                "--add-dir", "/home/me/work/WorkDir-featureX/proj-b",
            ),
            answer.command,
        )
    }

    @Test
    fun `cwd 를 그 세션의 작업 디렉토리로 읽는다`() {
        val answer = parseKyuSessionCommandOutput(REPO_SESSION_DOCUMENT)

        // 레포 세션에서 이 한 줄이 그 레포의 .mcp.json 과 CLAUDE.md 를 살린다.
        assertEquals(Path.of("/home/me/work/WorkDir-featureX/proj-a"), answer.workingDirectory)
    }

    @Test
    fun `더할 환경을 그대로 읽는다`() {
        val answer = parseKyuSessionCommandOutput(MAIN_SESSION_DOCUMENT)

        assertEquals(mapOf("CLAUDE_CODE_ADDITIONAL_DIRECTORIES_CLAUDE_MD" to "1"), answer.environmentToAdd)
    }

    @Test
    fun `더할 것이 없으면 빈 환경이다`() {
        val answer = parseKyuSessionCommandOutput(REPO_SESSION_DOCUMENT)

        assertEquals(emptyMap(), answer.environmentToAdd)
    }

    @Test
    fun `모르는 필드가 늘어도 그대로 읽는다`() {
        // 필드를 더하는 변경으로는 판이 오르지 않는다(session_command_json.go 의 판 규약).
        // 3 단계가 resume 실패를 가려낼 필드를 더할 자리가 여기다.
        val answer = parseKyuSessionCommandOutput(
            """
            {
              "schemaVersion": 1,
              "command": ["claude"],
              "cwd": "/home/me/work/WorkDir-featureX/proj-a",
              "env": {},
              "resumedConversationId": "211f6974-88a8-4453-9248-a02b0d6febae"
            }
            """.trimIndent(),
        )

        assertEquals(listOf("claude"), answer.command)
    }

    @Test
    fun `판이 다르면 읽지 않고 멈춘다`() {
        val failure = assertFailsWith<TerminalSessionFailure.UnsupportedSchemaVersion> {
            parseKyuSessionCommandOutput(
                """{"schemaVersion": 2, "command": ["claude"], "cwd": "/tmp", "env": {}}""",
            )
        }

        assertEquals(2, failure.actualSchemaVersion)
        assertEquals(1, failure.supportedSchemaVersion)
    }

    @Test
    fun `문서가 아닌 것을 받으면 읽지 못했다고 답한다`() {
        assertFailsWith<TerminalSessionFailure.UnreadableKyuOutput> {
            parseKyuSessionCommandOutput("이건 JSON 이 아닙니다")
        }
    }

    private companion object {
        val MAIN_SESSION_DOCUMENT = """
            {
              "schemaVersion": 1,
              "command": [
                "claude",
                "--session-id", "d1b9d634-a927-4abf-ad80-5941671b08a8",
                "--add-dir", "/home/me/work/WorkDir-featureX/proj-a",
                "--add-dir", "/home/me/work/WorkDir-featureX/proj-b"
              ],
              "cwd": "/home/me/work/WorkDir-featureX",
              "env": { "CLAUDE_CODE_ADDITIONAL_DIRECTORIES_CLAUDE_MD": "1" }
            }
        """.trimIndent()

        val REPO_SESSION_DOCUMENT = """
            {
              "schemaVersion": 1,
              "command": ["claude", "--resume", "211f6974-88a8-4453-9248-a02b0d6febae"],
              "cwd": "/home/me/work/WorkDir-featureX/proj-a",
              "env": {}
            }
        """.trimIndent()
    }
}
