package com.kyuchestration.desktop

import com.kyuchestration.desktop.terminal.SessionTarget
import com.kyuchestration.desktop.terminal.chat.ChatConversation
import com.kyuchestration.desktop.terminal.chat.ChatEntry
import com.kyuchestration.desktop.terminal.chat.RecordedChatStreamLines
import com.kyuchestration.desktop.terminal.chat.after
import com.kyuchestration.desktop.terminal.chat.chatSessionEventsFrom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 도구 호출 하나가 어느 카드로 그려지는가(설계 6.3).
 *
 * **넣는 줄이 진짜다.** 녹화한 `claude` 출력을 어댑터에 통과시키고 대화로 접은 뒤, 거기서 나온
 * 도구 카드를 그대로 넣는다 — 곁가지의 키 이름을 손으로 지어 넣으면 이 검증이 확인하는 것은
 * 우리가 상상한 `claude` 이고, 카드가 비는 것은 앱을 띄운 사람이 발견한다.
 */
class ToolCallCardContentTest {

    @Test
    fun `Bash 는 명령과 출력을 보인다`() {
        val content = assertIs<ToolCallCardContent.ShellCommand>(cardContentAfter(
            RecordedChatStreamLines.BASH_TOOL_USE,
            RecordedChatStreamLines.BASH_RESULT,
        ))

        assertEquals("echo probe-bash", content.command)
        assertEquals("probe-bash", content.standardOutput)
        assertEquals("", content.standardError)
    }

    @Test
    fun `결과를 기다리는 Bash 도 명령은 이미 보인다`() {
        // 인자는 완성된 채로 온다(3.4). 도는 동안 JSON 을 보여줄 이유가 없다.
        val content = assertIs<ToolCallCardContent.ShellCommand>(
            cardContentAfter(RecordedChatStreamLines.BASH_TOOL_USE),
        )

        assertEquals("echo probe-bash", content.command)
        assertEquals("", content.standardOutput)
    }

    @Test
    fun `거절된 Bash 도 어떤 명령이었는지 보인다`() {
        // 거절된 호출의 곁가지는 객체가 아니라 문자열이다(실측). 그래도 명령은 인자에 있으므로
        // 카드가 그것을 보인다 — 거절된 것이 무엇인지가 이 카드에서 사용자가 가장 알고 싶은 것이고,
        // 거절 사유는 머리말의 실패 표시가 말한다(6.3).
        val content = cardContentAfter(
            RecordedChatStreamLines.BASH_TOOL_USE_REFUSED,
            RecordedChatStreamLines.BASH_RESULT_REFUSED,
        )

        val shellCommand = assertIs<ToolCallCardContent.ShellCommand>(content)
        assertEquals("sh -c \"exit 3\"", shellCommand.command)
        assertEquals("", shellCommand.standardOutput, "거절된 호출에는 출력이 없다")
    }

    @Test
    fun `Edit 는 파일과 diff 를 보인다`() {
        val content = assertIs<ToolCallCardContent.FileChanged>(cardContentAfter(
            RecordedChatStreamLines.EDIT_TOOL_USE,
            RecordedChatStreamLines.EDIT_RESULT,
        ))

        assertEquals("/tmp/fakerepo/notes.md", content.filePath)
        assertEquals(1, content.addedLineCount)
        assertEquals(1, content.removedLineCount)
        assertEquals(false, content.createdFile)
        // 마커를 떼고 들어온다. 화면이 색과 기호로 다시 그리므로 본문에 남아 있으면 두 번 그려진다.
        assertEquals(
            listOf(
                DiffLine(DiffLineKind.Context, "첫째 줄"),
                DiffLine(DiffLineKind.Removed, "둘째 줄"),
                DiffLine(DiffLineKind.Added, "둘째 줄 고침"),
                DiffLine(DiffLineKind.Context, "셋째 줄"),
            ),
            content.hunks.single().lines,
        )
    }

    @Test
    fun `없던 파일에 쓴 Write 는 쓴 내용 전체가 더해진 줄이다`() {
        // 새로 만든 파일의 structuredPatch 는 비어 있다(실측). 그것을 "바뀐 것 없음" 으로 읽으면
        // 파일을 만든 호출의 카드가 통째로 빈다.
        val content = assertIs<ToolCallCardContent.FileChanged>(cardContentAfter(
            RecordedChatStreamLines.WRITE_TOOL_USE_CREATING,
            RecordedChatStreamLines.WRITE_RESULT_CREATED,
        ))

        assertEquals("/tmp/fakerepo/written.txt", content.filePath)
        assertTrue(content.createdFile)
        assertEquals(1, content.addedLineCount)
        assertEquals(0, content.removedLineCount)
        assertEquals(listOf(DiffLine(DiffLineKind.Added, "written by probe")), content.hunks.single().lines)
    }

    @Test
    fun `이미 있던 파일을 덮어쓴 Write 는 Edit 과 같은 패치로 온다`() {
        val content = assertIs<ToolCallCardContent.FileChanged>(cardContentAfter(
            RecordedChatStreamLines.WRITE_TOOL_USE_OVERWRITING,
            RecordedChatStreamLines.WRITE_RESULT_UPDATED,
        ))

        assertEquals(false, content.createdFile)
        assertEquals(2, content.addedLineCount)
        assertEquals(1, content.removedLineCount)
    }

    @Test
    fun `Read 는 파일과 줄 수를 보인다`() {
        val content = assertIs<ToolCallCardContent.FileRead>(cardContentAfter(
            RecordedChatStreamLines.ASSISTANT_TOOL_USE,
            RecordedChatStreamLines.TOOL_RESULT,
        ))

        assertEquals("/tmp/fakerepo/README.txt", content.filePath)
        assertEquals(2, content.totalLineCount)
        // 모델이 읽는 텍스트에는 줄 번호가 붙어 있고(`1\thello…`) 곁가지의 것은 원문이다.
        assertEquals("hello from fake repo\n", content.content)
    }

    @Test
    fun `모르는 도구는 일반 카드로 떨어진다`() {
        // MCP 도구는 얼마든지 새로 붙는다(6.3). 새 도구가 붙는 날 화면이 비는 것보다 인자와
        // 결과를 그대로 보이는 편이 낫다.
        val content = assertIs<ToolCallCardContent.AnyTool>(
            toolCallCardContentOf("Skill", input = emptyJsonObject(), typedResult = null),
        )

        assertEquals("Skill", content.toolLabel)
    }

    @Test
    fun `MCP 도구는 서버와 도구를 갈라 적는다`() {
        // 이름의 모양은 실측이 정했다(A.15). 서버 이름의 하이픈은 그대로 남는다.
        assertEquals("kyu · run_in_repo", toolLabel("mcp__kyu__run_in_repo"))
        assertEquals("kyu-ask · approve", toolLabel("mcp__kyu-ask__approve"))
        // 우리가 아는 모양이 아니면 손대지 않는다 — 반쪽만 갈라 적으면 서버가 화면에서 사라진다.
        assertEquals("mcp__broken", toolLabel("mcp__broken"))
        assertEquals("Read", toolLabel("Read"))
    }

    /** 녹화한 줄들을 어댑터와 접기 규칙에 통과시켜 나온 도구 카드 하나의 내용. */
    private fun cardContentAfter(vararg streamLines: String): ToolCallCardContent {
        val conversation = streamLines.fold(ChatConversation(target = SessionTarget.Main)) { folded, line ->
            chatSessionEventsFrom(line).fold(folded) { conversation, event -> conversation.after(event) }
        }

        val toolCall = assertIs<ChatEntry.ToolCall>(conversation.entries.single())
        return toolCallCardContentOf(toolCall.toolName, toolCall.input, toolCall.answer?.typedResult)
    }

    private fun emptyJsonObject() = kotlinx.serialization.json.JsonObject(emptyMap())
}
