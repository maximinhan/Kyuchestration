package com.kyuchestration.desktop.terminal.chat

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 녹화한 줄을 넣어 이 앱의 이벤트로 옮겨지는지 본다.
 *
 * **입력이 진짜라는 것이 이 시험의 전부다**([RecordedChatStreamLines]). 지어낸 JSON 으로
 * 시험하면 확인되는 것은 우리가 상상한 `claude` 이고, 그 상상이 어긋난 자리는 앱을 띄운
 * 사람이 처음 발견한다.
 *
 * 프로세스도 코루틴도 없다. 여기서 갈리는 것은 "이 줄이 무엇인가" 하나이고, 그 답을 얻는 데
 * `claude` 도 파이프도 필요하지 않다.
 */
class ChatStreamLineReadingTest {

    @Test
    fun `system init 은 이 세션이 무엇을 가졌는지 말한다`() {
        val described = onlyEventIn(RecordedChatStreamLines.SESSION_INIT)

        assertIs<ChatSessionEvent.SessionDescribed>(described)
        assertEquals("8129eebb-0f2f-4067-af9c-d189154b6617", described.conversationId)
        assertEquals("claude-opus-5[1m]", described.modelName)
        assertEquals(listOf("deep-research", "design-sync", "dataviz"), described.availableSlashCommands)
    }

    @Test
    fun `init 이 보고한 MCP 서버는 이름과 상태로 온다`() {
        // 실측: --mcp-config 로 붙인 서버가 MCP 를 말할 줄 모르면 status 가 failed 로 온다.
        // 이름만 오는 줄 알고 문자열 배열로 읽으면 이 줄에서 통째로 깨진다.
        val line = """{"type":"system","subtype":"init","session_id":"s","model":"m","slash_commands":[],""" +
            """"mcp_servers":[{"name":"kyu","status":"connected"},{"name":"kyu-probe","status":"failed"}]}"""

        val described = assertIs<ChatSessionEvent.SessionDescribed>(onlyEventIn(line))

        assertEquals(
            listOf(McpServerStatus("kyu", "connected"), McpServerStatus("kyu-probe", "failed")),
            described.connectedMcpServers,
        )
    }

    @Test
    fun `되돌려받은 사용자 메시지가 전사의 사용자 말풍선이 된다`() {
        // 원칙 14 — 앱이 자기가 보낸 것을 직접 넣지 않고 이 이벤트로 그린다(3.14).
        val echoed = assertIs<ChatSessionEvent.UserMessageEchoed>(
            onlyEventIn(RecordedChatStreamLines.USER_MESSAGE_REPLAYED),
        )

        assertEquals("README.txt 를 Read 도구로 읽고 첫 줄을 그대로 답해.", echoed.text)
    }

    @Test
    fun `모델의 텍스트 블록은 완성본으로 온다`() {
        val arrived = assertIs<ChatSessionEvent.AssistantTextArrived>(
            onlyEventIn(RecordedChatStreamLines.ASSISTANT_TEXT),
        )

        assertTrue(arrived.text.startsWith("첫 줄: `hello from fake repo`"), "받은 것: ${arrived.text}")
        assertEquals(null, arrived.parentToolUseId, "메인 대화의 말은 도구 카드 안으로 접히지 않는다")
    }

    @Test
    fun `도구 호출은 인자가 다 채워진 채로 온다`() {
        val requested = assertIs<ChatSessionEvent.ToolCallRequested>(
            onlyEventIn(RecordedChatStreamLines.ASSISTANT_TOOL_USE),
        )

        assertEquals("toolu_01J5SUwNqw8vdmZEYXkCZQqM", requested.toolUseId)
        assertEquals("Read", requested.toolName)
        assertEquals("/tmp/fakerepo/README.txt", requested.input["file_path"]?.jsonPrimitive?.content)
    }

    @Test
    fun `사고 블록은 텍스트와 다른 갈래로 온다`() {
        // 녹화에 실려 온 thinking 은 빈 문자열이었다(서명만 왔다). 그래서 여기서 확인하는 것은
        // 갈래 하나다 — 사고를 말풍선으로 그리지 않는 근거가 이 갈림이다.
        val events = chatSessionEventsFrom(RecordedChatStreamLines.ASSISTANT_THINKING)

        assertTrue(
            events.any { it is ChatSessionEvent.AssistantThinkingArrived },
            "사고 블록이 든 줄에서 사고 이벤트를 기대: $events",
        )
    }

    @Test
    fun `도구 결과는 모델이 읽은 텍스트와 곁가지를 함께 들고 온다`() {
        val answered = assertIs<ChatSessionEvent.ToolCallAnswered>(
            onlyEventIn(RecordedChatStreamLines.TOOL_RESULT),
        )

        assertEquals("toolu_01J5SUwNqw8vdmZEYXkCZQqM", answered.toolUseId, "호출과 결과를 잇는 값이다")
        assertEquals(false, answered.failed)
        assertEquals("1\thello from fake repo\n2\t", answered.modelVisibleText, "모델이 읽는 것에는 줄 번호가 붙어 있다")

        // 도구별 카드를 그리는 근거가 이 곁가지다(3.4). content 만 들고 가면 Read 가 실제로 무엇을
        // 읽었는지(파일 경로·줄 수)가 사라진다.
        val typedResult = assertIs<JsonObject>(answered.typedResult)
        assertEquals("/tmp/fakerepo/README.txt", typedResult["file"]?.jsonObject?.get("filePath")?.jsonPrimitive?.content)
    }

    @Test
    fun `거절된 도구 호출은 실패로 온다`() {
        val answered = assertIs<ChatSessionEvent.ToolCallAnswered>(
            onlyEventIn(RecordedChatStreamLines.BASH_RESULT_REFUSED),
        )

        assertEquals(true, answered.failed)
        assertEquals("This command requires approval", answered.modelVisibleText)
        // 거절된 호출의 곁가지는 객체가 아니라 문자열이다. 이것을 객체로만 읽으면 도구 카드가
        // 거절을 만나는 순간 화면이 빈다.
        assertIs<JsonPrimitive>(answered.typedResult)
    }

    @Test
    fun `서브에이전트 안쪽의 말은 바깥 도구 호출을 가리킨다`() {
        // parent_tool_use_id 가 챗 UI 의 중첩 열쇠다(3.10). 이 값이 끊기면 안쪽 대화가 메인 전사에
        // 그대로 쏟아진다.
        val requested = assertIs<ChatSessionEvent.ToolCallRequested>(
            onlyEventIn(RecordedChatStreamLines.SUBAGENT_TOOL_USE),
        )

        assertEquals("toolu_01QUbBJ2Rh6wpXTtCkjoC95M", requested.parentToolUseId)
    }

    @Test
    fun `글자 조각은 스트리밍 이벤트가 된다`() {
        val streaming = assertIs<ChatSessionEvent.AssistantTextStreaming>(
            onlyEventIn(RecordedChatStreamLines.STREAM_TEXT_DELTA),
        )

        assertEquals("첫", streaming.chunk)
    }

    @Test
    fun `도구 인자 조각은 이어 붙이려 들지 않는다`() {
        // 잘린 JSON 이라 파싱되지 않고, 완성본이 곧 assistant 로 온다(3.3). 여기서 무언가를
        // 내보내면 화면이 반쯤 만들어진 인자를 그리게 된다.
        assertEquals(emptyList(), chatSessionEventsFrom(RecordedChatStreamLines.STREAM_INPUT_JSON_DELTA))
    }

    @Test
    fun `조각의 뼈대와 아직 화면이 없는 갈래는 조용히 지나간다`() {
        // 이것들을 Unrecognized 로 실어 보내면 턴마다 오는 status 가 진단 기록을 덮어,
        // 정작 판이 바뀐 줄이 묻힌다.
        assertEquals(emptyList(), chatSessionEventsFrom(RecordedChatStreamLines.STREAM_TOOL_BLOCK_STARTED))
        assertEquals(emptyList(), chatSessionEventsFrom(RecordedChatStreamLines.SYSTEM_STATUS))
        assertEquals(emptyList(), chatSessionEventsFrom(RecordedChatStreamLines.TASK_STARTED))
        assertEquals(emptyList(), chatSessionEventsFrom(RecordedChatStreamLines.CONTROL_RESPONSE))
    }

    @Test
    fun `서브에이전트의 진행은 바깥 도구 호출에 이어 붙을 수 있게 온다`() {
        val progressed = assertIs<ChatSessionEvent.DelegationProgressed>(
            onlyEventIn(RecordedChatStreamLines.TASK_PROGRESS),
        )

        assertEquals("af9ca720398708b1c", progressed.taskId)
        assertEquals("toolu_01QUbBJ2Rh6wpXTtCkjoC95M", progressed.toolUseId, "이 값이 진행을 도구 카드에 잇는다")
        assertEquals("Reading README.txt", progressed.description)
        assertEquals("Read", progressed.lastToolName)
    }

    @Test
    fun `턴의 끝은 비용과 토큰을 함께 준다`() {
        val finished = assertIs<ChatSessionEvent.TurnFinished>(
            onlyEventIn(RecordedChatStreamLines.RESULT_SUCCESS),
        )

        assertEquals(TurnOutcome.Completed, finished.outcome)
        assertEquals(0.0888635, finished.costUsd)
        assertEquals(263, finished.usage.outputTokens)
        assertEquals(26703, finished.usage.cacheReadInputTokens, "캐시 적중이 따로 와야 비용을 설명할 수 있다")
        assertEquals(emptyList(), finished.permissionDenials)
    }

    @Test
    fun `턴이 얼마나 걸렸는지도 그 줄에 있다`() {
        // 화면의 턴 배지가 소요 시간을 말한다(6.3 의 TurnFooter). 앱이 직접 재는 길도 있지만,
        // 큐잉으로 두 메시지가 한 턴으로 합쳐지면(3.11) 앱이 잰 시작점이 그 턴의 시작이 아니다.
        val finished = assertIs<ChatSessionEvent.TurnFinished>(
            onlyEventIn(RecordedChatStreamLines.RESULT_SUCCESS),
        )

        assertEquals(6459, finished.durationMillis)
    }

    @Test
    fun `소요 시간이 없는 줄은 0 으로 온다`() {
        // 설계 부록 A.9 는 중단된 턴에 duration_ms 가 없다고 적었는데, 이 판(2.1.259)의 실측에는
        // 실려 왔다(RESULT_ABORTED 는 4732 다). 그래도 없는 줄을 0 으로 두는 규칙은 그대로 둔다 —
        // 없는 자리를 지어내지 않으면 화면이 "잴 것이 없었다" 를 배지에서 빼는 것으로 말한다.
        val finished = assertIs<ChatSessionEvent.TurnFinished>(
            onlyEventIn("""{"type":"result","subtype":"success","total_cost_usd":0.01}"""),
        )

        assertEquals(0, finished.durationMillis)
    }

    @Test
    fun `끊긴 턴의 소요 시간은 실려 온 그대로다`() {
        val finished = assertIs<ChatSessionEvent.TurnFinished>(
            onlyEventIn(RecordedChatStreamLines.RESULT_ABORTED),
        )

        assertEquals(4732, finished.durationMillis)
    }

    @Test
    fun `중단된 턴은 실패가 아니라 중단으로 온다`() {
        // 중단은 이번 턴만 끊고 프로세스와 대화를 살려 둔다(3.8). 이것을 실패로 그리면 사용자는
        // 대화가 끝난 줄 알게 된다.
        val finished = assertIs<ChatSessionEvent.TurnFinished>(
            onlyEventIn(RecordedChatStreamLines.RESULT_ABORTED),
        )

        assertEquals(TurnOutcome.Interrupted, finished.outcome)
    }

    @Test
    fun `이어갈 대화를 찾지 못한 턴은 실패로 온다`() {
        // 없는 대화를 resume 하면 result 가 error_during_execution 으로 한 줄 오고 종료 코드가 1 이다(3.9).
        val line = """{"type":"result","subtype":"error_during_execution","result":null,""" +
            """"terminal_reason":"errored","session_id":"00000000-0000-4000-8000-000000000000"}"""

        val finished = assertIs<ChatSessionEvent.TurnFinished>(onlyEventIn(line))

        assertEquals(TurnOutcome.Failed, finished.outcome)
    }

    @Test
    fun `한도는 창마다 사용률과 초기화 시각으로 온다`() {
        val observed = assertIs<ChatSessionEvent.UsageWindowsObserved>(
            onlyEventIn(RecordedChatStreamLines.RATE_LIMIT),
        )

        assertEquals(
            listOf(
                RateLimitWindow("five_hour", 0.75, 1788513600),
                RateLimitWindow("seven_day", 0.07, 1789074000),
            ),
            observed.windows,
        )
    }

    @Test
    fun `한 줄에 블록이 여럿이면 이벤트도 여럿이다`() {
        // assistant 이벤트 하나를 말풍선 하나로 두면 도구 호출이 빈 말풍선이 된다(3.2).
        val line = """{"type":"assistant","parent_tool_use_id":null,"message":{"role":"assistant","content":[""" +
            """{"type":"text","text":"읽어 볼게요."},""" +
            """{"type":"tool_use","id":"toolu_1","name":"Read","input":{"file_path":"/tmp/fakerepo/README.txt"}}]}}"""

        val events = chatSessionEventsFrom(line)

        assertEquals(2, events.size, "받은 것: $events")
        assertIs<ChatSessionEvent.AssistantTextArrived>(events[0])
        assertIs<ChatSessionEvent.ToolCallRequested>(events[1])
    }

    @Test
    fun `모르는 줄은 원문째로 들고 나간다`() {
        // claude 의 판이 올라 새 이벤트가 생기는 날, 버리는 어댑터는 아무 말도 하지 않는다.
        val newKindOfLine = """{"type":"context_compacted","removed_messages":12}"""

        val unrecognized = assertIs<ChatSessionEvent.Unrecognized>(onlyEventIn(newKindOfLine))

        assertEquals(newKindOfLine, unrecognized.rawLine)
    }

    @Test
    fun `읽을 수 없는 줄에서도 던지지 않는다`() {
        // 이 함수를 부르는 것은 파이프를 읽는 코루틴 하나뿐이다. 여기서 예외가 나면 그 세션의
        // 나머지 출력이 통째로 끊긴다 — 화면은 멎고, 이유는 어디에도 남지 않는다.
        val brokenLines = listOf("", "   ", "이건 JSON 이 아니다", """{"type":"result",""", "[1,2,3]")

        brokenLines.forEach { brokenLine ->
            val unrecognized = assertIs<ChatSessionEvent.Unrecognized>(
                onlyEventIn(brokenLine),
                "끊긴 줄: $brokenLine",
            )
            assertEquals(brokenLine, unrecognized.rawLine)
        }
    }

    private fun onlyEventIn(streamLine: String): ChatSessionEvent {
        val events = chatSessionEventsFrom(streamLine)
        assertEquals(1, events.size, "이 줄은 이벤트 하나가 되어야 한다: $events")
        return events.single()
    }
}
