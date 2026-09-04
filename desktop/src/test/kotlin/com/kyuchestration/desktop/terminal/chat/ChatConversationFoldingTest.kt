package com.kyuchestration.desktop.terminal.chat

import com.kyuchestration.desktop.terminal.SessionTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 이벤트가 대화로 쌓이는 규칙(chat-ui-design.md 5.5).
 *
 * **넣는 줄이 진짜다.** 이벤트를 손으로 지어 넣으면 이 검증이 확인하는 것은 우리가 상상한
 * `claude` 이고, 그것과 진짜가 어긋나는 자리는 앱을 띄운 사람이 처음 발견한다. 그래서 2 단계가
 * 녹화해 둔 줄([RecordedChatStreamLines])을 어댑터 파서에 그대로 통과시켜 나온 이벤트를 넣는다 —
 * 파서와 이 규칙 사이에 손으로 만든 것이 하나도 없다.
 */
class ChatConversationFoldingTest {

    @Test
    fun `턴마다 오는 init 이 전사를 비우지 않는다`() {
        // 이 앱이 가장 쉽게 저지를 수 있었던 결함이다. system/init 은 세션의 시작이 아니라 턴마다
        // 다시 오는 줄이고(3.1), 그것을 "새 세션" 으로 읽으면 매 턴 대화가 사라진다.
        val conversation = conversationAfter(
            RecordedChatStreamLines.SESSION_INIT,
            RecordedChatStreamLines.USER_MESSAGE_REPLAYED,
            RecordedChatStreamLines.ASSISTANT_TEXT,
            RecordedChatStreamLines.SESSION_INIT,
        )

        assertEquals(2, conversation.entries.size, "둘째 init 이 앞의 대화를 지웠다")
        assertIs<ChatEntry.UserSaid>(conversation.entries.first())
        assertEquals("8129eebb-0f2f-4067-af9c-d189154b6617", conversation.conversationId)
        assertEquals("claude-opus-5[1m]", conversation.modelName)
    }

    @Test
    fun `사용자 말풍선은 되돌아온 것으로 그린다`() {
        val conversation = conversationAfter(RecordedChatStreamLines.USER_MESSAGE_REPLAYED)

        val said = assertIs<ChatEntry.UserSaid>(conversation.entries.single())
        assertEquals("README.txt 를 Read 도구로 읽고 첫 줄을 그대로 답해.", said.text)
    }

    @Test
    fun `글자 조각은 전사가 아니라 버퍼에 쌓인다`() {
        val conversation = conversationAfter(
            RecordedChatStreamLines.STREAM_TEXT_DELTA,
            RecordedChatStreamLines.STREAM_TEXT_DELTA,
        )

        assertEquals(emptyList(), conversation.entries)
        assertEquals("첫첫", conversation.streamingText)
    }

    @Test
    fun `완성본이 오면 버퍼를 버리고 그것으로 바꾼다`() {
        // 조각만으로 전사를 만들면 중단된 턴의 잘린 문장이 영구히 남는다(5.3.2).
        val conversation = conversationAfter(
            RecordedChatStreamLines.STREAM_TEXT_DELTA,
            RecordedChatStreamLines.ASSISTANT_TEXT,
        )

        assertNull(conversation.streamingText)
        val said = assertIs<ChatEntry.AssistantSaid>(conversation.entries.single())
        assertTrue(said.text.startsWith("첫 줄: `hello from fake repo`"))
    }

    @Test
    fun `턴이 끝나면 완성본을 못 받은 조각도 버린다`() {
        // 중단된 턴이 이 모양이다 — 조각은 왔는데 완성본이 오지 않는다. 남겨 두면 다음 턴의
        // 답 위에 잘린 문장이 계속 떠 있는다.
        val conversation = conversationAfter(
            RecordedChatStreamLines.STREAM_TEXT_DELTA,
            FromDesignAppendix.RESULT_ABORTED,
        )

        assertNull(conversation.streamingText)
    }

    @Test
    fun `도구 호출은 카드가 되고 결과가 그 카드를 채운다`() {
        val requested = conversationAfter(RecordedChatStreamLines.ASSISTANT_TOOL_USE)
        val pending = assertIs<ChatEntry.ToolCall>(requested.entries.single())
        assertEquals("Read", pending.toolName)
        assertNull(pending.answer, "결과가 오기 전에는 도는 중이다")

        val answered = requested.after(RecordedChatStreamLines.TOOL_RESULT)
        val card = assertIs<ChatEntry.ToolCall>(answered.entries.single())
        assertEquals(1, answered.entries.size, "결과가 카드를 채우지 않고 항목을 하나 더 만들었다")
        val answer = assertNotNull(card.answer)
        assertEquals("1\thello from fake repo\n2\t", answer.modelVisibleText)
        assertFalse(answer.failed)
    }

    @Test
    fun `거절된 도구 호출은 실패로 표시된다`() {
        // 접힌 카드만 보고도 무엇이 잘못됐는지 알아야 실패가 대화에 묻히지 않는다(6.3).
        val conversation = conversationAfter(
            """{"type":"assistant","message":{"role":"assistant","content":[{"type":"tool_use",""" +
                """"id":"toolu_01EK4pNYexJLbkuiiosevhXq","name":"Write","input":{"file_path":"/tmp/x"}}]},""" +
                """"parent_tool_use_id":null}""",
            FromDesignAppendix.TOOL_RESULT_DENIED,
        )

        val card = assertIs<ChatEntry.ToolCall>(conversation.entries.single())
        val answer = assertNotNull(card.answer)
        assertTrue(answer.failed)
        assertEquals("사람이 거절했습니다 (프로브)", answer.modelVisibleText)
    }

    @Test
    fun `서브에이전트가 한 일은 그 도구 카드 안으로 접힌다`() {
        // parent_tool_use_id 가 챗 UI 의 중첩 열쇠다(3.10). 본문에 풀어 놓으면 메인 대화와
        // 안쪽 대화가 한 줄기로 섞여 누가 한 말인지 알 수 없게 된다.
        val conversation = conversationAfter(
            TASK_TOOL_USE,
            RecordedChatStreamLines.SUBAGENT_TOOL_USE,
        )

        val outerCard = assertIs<ChatEntry.ToolCall>(conversation.entries.single())
        val nested = assertIs<ChatEntry.ToolCall>(outerCard.nestedEntries.single())
        assertEquals("Read", nested.toolName)
    }

    @Test
    fun `안쪽 도구 호출의 결과도 그 안쪽 카드가 받는다`() {
        val conversation = conversationAfter(
            TASK_TOOL_USE,
            RecordedChatStreamLines.SUBAGENT_TOOL_USE,
            SUBAGENT_TOOL_RESULT,
        )

        val outerCard = assertIs<ChatEntry.ToolCall>(conversation.entries.single())
        val nested = assertIs<ChatEntry.ToolCall>(outerCard.nestedEntries.single())
        assertEquals("hello from fake repo", nested.answer?.modelVisibleText)
    }

    @Test
    fun `턴이 끝나면 배지가 붙고 비용이 쌓인다`() {
        val conversation = conversationAfter(
            RecordedChatStreamLines.RESULT_SUCCESS,
            RecordedChatStreamLines.RESULT_SUCCESS,
        )

        val ended = assertIs<ChatEntry.TurnEnded>(conversation.entries.last())
        assertEquals(TurnOutcome.Completed, ended.outcome)
        assertEquals(6459, ended.durationMillis)
        assertEquals(263, ended.usage.outputTokens)
        // 배지는 그 턴의 것이고 머리말은 이 대화가 쓴 것 전부를 말한다.
        assertEquals(0.0888635 * 2, conversation.totalCostUsd)
    }

    @Test
    fun `턴이 끝나면 입력 잠금이 풀린다`() {
        val running = ChatConversation(target = SessionTarget.Main, turnState = TurnState.Running)

        val finished = running.after(RecordedChatStreamLines.RESULT_SUCCESS)

        assertEquals(TurnState.Idle, finished.turnState)
    }

    @Test
    fun `한도 창은 마지막에 온 것으로 바뀐다`() {
        val conversation = conversationAfter(RecordedChatStreamLines.RATE_LIMIT)

        assertEquals(listOf("five_hour", "seven_day"), conversation.usageWindows.map { it.windowName })
    }

    @Test
    fun `엔진이 남긴 줄은 전사에 안내로 남는다`() {
        // PTY 시절에는 이 줄이 터미널 화면에 그대로 보였다. 챗에서 자리를 주지 않으면 사라진다.
        val conversation = ChatConversation(target = SessionTarget.Main)
            .after(ChatSessionEvent.EngineSpoke("No conversation found with session ID: 8129eebb"))

        val notice = assertIs<ChatEntry.EngineNotice>(conversation.entries.single())
        assertTrue("No conversation found" in notice.line)
    }

    @Test
    fun `세션이 끝나면 대화가 그 사실을 들고 있는다`() {
        val conversation = ChatConversation(target = SessionTarget.Main, resumedConversationId = RESUMED_ID)
            .after(ChatSessionEvent.SessionEnded(exitCode = 1))

        assertFalse(conversation.alive)
        assertEquals(1, conversation.endedExitCode)
        assertTrue(conversation.resumeFailureSuspected, "이 사실이 없으면 화면은 사용자의 /exit 과 구분하지 못한다")
    }

    @Test
    fun `새 대화로 연 세션이 끝난 것은 이어가기 실패가 아니다`() {
        val conversation = ChatConversation(target = SessionTarget.Main)
            .after(ChatSessionEvent.SessionEnded(exitCode = 1))

        assertFalse(conversation.resumeFailureSuspected, "이어간 적이 없으므로 이어가기가 실패했을 리 없다")
    }

    @Test
    fun `모르는 줄은 대화를 바꾸지 않는다`() {
        val conversation = conversationAfter(
            RecordedChatStreamLines.USER_MESSAGE_REPLAYED,
            """{"type":"something_new_in_claude","payload":{}}""",
        )

        assertEquals(1, conversation.entries.size)
    }

    @Test
    fun `한 턴이 통째로 오면 순서대로 쌓인다`() {
        // 실제 한 턴의 줄들을 온 순서 그대로 흘린다. 규칙 하나하나가 아니라 그 규칙들이 함께
        // 만들어 내는 화면을 보는 자리다.
        val conversation = conversationAfter(
            RecordedChatStreamLines.SESSION_INIT,
            RecordedChatStreamLines.RATE_LIMIT,
            RecordedChatStreamLines.USER_MESSAGE_REPLAYED,
            RecordedChatStreamLines.SYSTEM_STATUS,
            RecordedChatStreamLines.STREAM_TOOL_BLOCK_STARTED,
            RecordedChatStreamLines.STREAM_INPUT_JSON_DELTA,
            RecordedChatStreamLines.ASSISTANT_TOOL_USE,
            RecordedChatStreamLines.TOOL_RESULT,
            RecordedChatStreamLines.STREAM_TEXT_DELTA,
            RecordedChatStreamLines.ASSISTANT_TEXT,
            RecordedChatStreamLines.RESULT_SUCCESS,
        )

        assertEquals(
            listOf(
                ChatEntry.UserSaid::class,
                ChatEntry.ToolCall::class,
                ChatEntry.AssistantSaid::class,
                ChatEntry.TurnEnded::class,
            ),
            conversation.entries.map { it::class },
        )
        assertNull(conversation.streamingText)
        assertEquals(TurnState.Idle, conversation.turnState)
    }

    /** 줄들을 어댑터에 통과시켜 나온 이벤트를 순서대로 접는다. */
    private fun conversationAfter(vararg streamLines: String): ChatConversation =
        streamLines.fold(ChatConversation(target = SessionTarget.Main)) { conversation, line ->
            conversation.after(line)
        }

    private fun ChatConversation.after(streamLine: String): ChatConversation =
        chatSessionEventsFrom(streamLine).fold(this) { conversation, event -> conversation.after(event) }

    private companion object {

        /** 메인이 서브에이전트를 띄우는 도구 호출. 녹화된 안쪽 이벤트의 `parent_tool_use_id` 와 짝이다. */
        const val TASK_TOOL_USE: String =
            """{"type":"assistant","message":{"role":"assistant","content":[{"type":"tool_use",""" +
                """"id":"toolu_01QUbBJ2Rh6wpXTtCkjoC95M","name":"Task","input":{"description":"Read README.txt first line"}}]},""" +
                """"parent_tool_use_id":null}"""

        /** 그 서브에이전트 안쪽 Read 의 결과. 녹화된 SUBAGENT_TOOL_USE 의 `id` 와 짝이다. */
        const val SUBAGENT_TOOL_RESULT: String =
            """{"type":"user","message":{"role":"user","content":[{"tool_use_id":"toolu_015cgoqteHactDNH8Er6kf4J",""" +
                """"type":"tool_result","content":"hello from fake repo"}]},""" +
                """"parent_tool_use_id":"toolu_01QUbBJ2Rh6wpXTtCkjoC95M"}"""

        const val RESUMED_ID = "211f6974-88a8-4453-9248-a02b0d6febae"
    }
}
