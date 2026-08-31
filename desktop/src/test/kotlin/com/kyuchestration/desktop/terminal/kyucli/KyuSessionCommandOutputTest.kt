package com.kyuchestration.desktop.terminal.kyucli

import com.kyuchestration.desktop.terminal.TerminalSessionFailure
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

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
        // 앱보다 앞서 나간 엔진이 한 줄 더 내보내도 세션은 열려야 한다.
        val answer = parseKyuSessionCommandOutput(
            """
            {
              "schemaVersion": 1,
              "command": ["claude"],
              "cwd": "/home/me/work/WorkDir-featureX/proj-a",
              "env": {},
              "answeredAt": "2026-08-31T00:00:00Z"
            }
            """.trimIndent(),
        )

        assertEquals(listOf("claude"), answer.command)
    }

    @Test
    fun `이어간 대화의 ID 를 그대로 읽는다`() {
        val answer = parseKyuSessionCommandOutput(RESUMED_SESSION_DOCUMENT)

        // 앱이 이 값을 만들거나 해석하지 않는다. 들고 있다가 세션이 끝났을 때 "이어가기로 연
        // 세션이었다" 를 말하는 데만 쓴다(설계 문서 5.5.4).
        assertEquals("211f6974-88a8-4453-9248-a02b0d6febae", answer.resumedConversationId)
    }

    @Test
    fun `새 대화면 이어간 대화가 없다`() {
        val answer = parseKyuSessionCommandOutput(MAIN_SESSION_DOCUMENT)

        assertNull(answer.resumedConversationId)
    }

    @Test
    fun `그 필드를 내지 않는 엔진과 만나도 세션은 열린다`() {
        // 엔진과 앱은 따로 배포된다. 여기서 멈추면 뒤처진 kyu 를 쓰는 사람은 카드를 눌러도
        // 세션이 아예 열리지 않는다 — 잃어도 되는 것은 실패를 가려내는 눈 하나뿐이다.
        val answer = parseKyuSessionCommandOutput(REPO_SESSION_DOCUMENT)

        assertNull(answer.resumedConversationId)
        assertEquals(listOf("claude", "--resume", "211f6974-88a8-4453-9248-a02b0d6febae"), answer.command)
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
              "env": { "CLAUDE_CODE_ADDITIONAL_DIRECTORIES_CLAUDE_MD": "1" },
              "resumedConversationId": null
            }
        """.trimIndent()

        /** 이 필드를 배우기 전의 kyu 가 내던 문서. 지금도 읽을 수 있어야 한다. */
        val REPO_SESSION_DOCUMENT = """
            {
              "schemaVersion": 1,
              "command": ["claude", "--resume", "211f6974-88a8-4453-9248-a02b0d6febae"],
              "cwd": "/home/me/work/WorkDir-featureX/proj-a",
              "env": {}
            }
        """.trimIndent()

        val RESUMED_SESSION_DOCUMENT = """
            {
              "schemaVersion": 1,
              "command": ["claude", "--resume", "211f6974-88a8-4453-9248-a02b0d6febae"],
              "cwd": "/home/me/work/WorkDir-featureX/proj-a",
              "env": {},
              "resumedConversationId": "211f6974-88a8-4453-9248-a02b0d6febae"
            }
        """.trimIndent()
    }
}
