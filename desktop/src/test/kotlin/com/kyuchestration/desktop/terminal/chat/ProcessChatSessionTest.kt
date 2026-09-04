package com.kyuchestration.desktop.terminal.chat

import com.kyuchestration.desktop.terminal.SessionEntryPlan
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 파이프에 물린 프로세스 하나를 세션으로 부리는 자리를 본다.
 *
 * 파이프 반대편에 있는 것은 `claude` 가 아니라, 녹화한 줄을 뱉고 받은 것을 적어 두는 sh 다
 * ([ChatProcessStub]). 진짜 `claude` 와 맞춰 보는 일은 따로 있다
 * ([RealClaudeChatSessionIntegrationTest]).
 *
 * **시험 함수마다 `: Unit` 을 적어 둔다.** `= runBlocking { … }` 는 표현식 몸통이라 반환 타입이
 * 마지막 식에서 추론되는데, 그것이 `Unit` 이 아니면 JUnit 이 그 시험을 **조용히 건너뛴다**.
 * 여기서 실제로 하나가 그렇게 사라졌다 — `assertIs` 가 확인한 값을 돌려주는 바람에 마지막
 * 시험의 반환 타입이 `Unit` 이 아니게 됐고, 초록불은 그대로였다.
 */
class ProcessChatSessionTest {

    private val temporaryDirectory = createTempDirectory("kyu-chat-session-test")

    private val openedSessions = mutableListOf<OpenedChatSession>()

    @AfterTest
    fun 열어_둔_세션과_임시_디렉토리를_치운다() {
        runBlocking { openedSessions.forEach { it.endSession() } }
        temporaryDirectory.toFile().deleteRecursively()
    }

    @Test
    fun `받은 줄을 순서 그대로 이벤트로 낸다`(): Unit = runBlocking {
        val session = openSession(
            emitLinesCommand(
                stdoutLinesFile = linesFile(
                    temporaryDirectory.resolve("stdout.jsonl"),
                    listOf(
                        RecordedChatStreamLines.SESSION_INIT,
                        RecordedChatStreamLines.ASSISTANT_TEXT,
                        RecordedChatStreamLines.RESULT_SUCCESS,
                    ),
                ),
            ),
        )

        val events = session.eventsUntilItEnds()

        // 순서가 곧 전사의 순서다. 어긋나면 사용자가 스크롤을 올릴 때 답이 물음보다 위에 있다.
        assertEquals(
            listOf(
                ChatSessionEvent.SessionDescribed::class,
                ChatSessionEvent.AssistantTextArrived::class,
                ChatSessionEvent.TurnFinished::class,
                ChatSessionEvent.SessionEnded::class,
            ),
            events.map { it::class },
        )
        assertEquals(ChatSessionEvent.SessionEnded(exitCode = 0), events.last())
    }

    @Test
    fun `stderr 는 따로 비워져 진단으로 온다`(): Unit = runBlocking {
        // 한 코루틴이 두 파이프를 순서대로 읽으면, 안 읽는 쪽 버퍼가 차는 순간 양쪽이 함께 멎는다.
        // 없는 대화를 이어가려 한 실패가 이 통로로 온다(3.9).
        val session = openSession(
            emitLinesCommand(
                stdoutLinesFile = linesFile(
                    temporaryDirectory.resolve("stdout.jsonl"),
                    listOf(RecordedChatStreamLines.RESULT_SUCCESS),
                ),
                stderrLinesFile = linesFile(
                    temporaryDirectory.resolve("stderr.txt"),
                    listOf("No conversation found with session ID: 00000000-0000-4000-8000-000000000000"),
                ),
                exitCode = 1,
            ),
        )

        val events = session.eventsUntilItEnds()

        val spoken = events.filterIsInstance<ChatSessionEvent.EngineSpoke>().single()
        assertTrue(spoken.line.startsWith("No conversation found"), "받은 것: ${spoken.line}")
        assertEquals(ChatSessionEvent.SessionEnded(exitCode = 1), events.last(), "이 종료 코드가 실패를 가려낸다")
    }

    @Test
    fun `사용자 메시지를 실측이 받은 모양으로 stdin 에 흘린다`(): Unit = runBlocking {
        val receivedLines = temporaryDirectory.resolve("받은-줄.jsonl")
        val session = openSession(recordStandardInputCommand(receivedLines))

        session.sendUserMessage("1 더하기 1은? 숫자만.")

        assertTrue(waitUntilFileAppears(receivedLines), "보낸 줄이 자식에게 닿지 않았습니다")
        val sent = Json.parseToJsonElement(receivedLines.readText().trim()).jsonObject
        assertEquals("user", sent["type"]?.jsonPrimitive?.content)
        val onlyBlock = sent["message"]?.jsonObject?.get("content")?.jsonArray?.single()?.jsonObject
        assertEquals("text", onlyBlock?.get("type")?.jsonPrimitive?.content)
        assertEquals("1 더하기 1은? 숫자만.", onlyBlock?.get("text")?.jsonPrimitive?.content)
    }

    @Test
    fun `중단은 제어 요청으로 나가고 프로세스를 죽이지 않는다`(): Unit = runBlocking {
        // SIGINT 은 턴과 함께 프로세스를 끝낸다(3.8). 그래서 중단은 시그널이 아니라 stdin 에
        // 쓰는 한 줄이고, 그 뒤에도 같은 프로세스에 다음 말을 걸 수 있어야 한다.
        val receivedLines = temporaryDirectory.resolve("받은-줄.jsonl")
        val session = openSession(recordStandardInputCommand(receivedLines))

        session.sendUserMessage("긴 글을 써 줘.")
        session.interruptCurrentTurn()
        session.sendUserMessage("방금 무슨 주제를 쓰고 있었지?")

        assertTrue(waitUntil { receivedLines.readTextOrEmpty().lines().count { it.isNotBlank() } == 3 })
        val sentLines = receivedLines.readText().trim().lines().map { Json.parseToJsonElement(it).jsonObject }
        assertEquals(listOf("user", "control_request", "user"), sentLines.map { it["type"]?.jsonPrimitive?.content })

        val interrupt = sentLines[1]
        assertEquals("interrupt", interrupt["request"]?.jsonObject?.get("subtype")?.jsonPrimitive?.content)
        assertTrue(
            interrupt["request_id"]?.jsonPrimitive?.content.orEmpty().startsWith("int_"),
            "실측이 받은 모양은 int_b8acf0f7 이다: $interrupt",
        )
    }

    @Test
    fun `아무도 모으지 않는 동안에도 파이프를 계속 비운다`(): Unit = runBlocking {
        // 이 앱이 이미 한 번 만난 함정이다 — 아무 위젯도 읽지 않는 세션이 생기고 버퍼가 차는
        // 순간 그 안의 claude 가 쓰기에서 멎었다(EmbeddedTerminalStateHolder 의 주석).
        //
        // 파이프 버퍼(리눅스 64KB)를 훌쩍 넘게 뱉게 해 두고, **모으기 전에** 자식이 다 뱉었는지
        // 본다. 읽기가 모으는 쪽을 기다린다면 자식은 그 표식을 남기지 못한 채 멎는다.
        val burstLineCount = 2_000
        val doneMarker = temporaryDirectory.resolve("다-뱉었다.txt")
        val session = openSession(
            emitLinesCommand(
                stdoutLinesFile = linesFile(
                    temporaryDirectory.resolve("stdout.jsonl"),
                    List(burstLineCount) { RecordedChatStreamLines.ASSISTANT_TEXT },
                ),
                doneMarkerFile = doneMarker,
            ),
        )

        assertTrue(waitUntilFileAppears(doneMarker), "자식이 파이프에서 멎었습니다 — 읽는 쪽이 비우지 않고 있습니다")

        val events = session.eventsUntilItEnds()
        assertEquals(
            burstLineCount,
            events.filterIsInstance<ChatSessionEvent.AssistantTextArrived>().size,
            "쌓아 둔 것이 한 줄도 사라지면 안 된다",
        )
    }

    @Test
    fun `세션을 끝내면 그 프로세스가 남지 않는다`(): Unit = runBlocking {
        // 화면이 사라지는 것으로 끝나지 않는다. 남은 프로세스는 앱 어디에도 보이지 않는다.
        val ownProcessId = temporaryDirectory.resolve("자식-pid.txt")
        val session = ProcessChatSessionOpener().openChatSession(
            planFor(recordStandardInputCommand(temporaryDirectory.resolve("받은-줄.jsonl"), ownProcessId)),
        )
        assertTrue(waitUntilFileAppears(ownProcessId))
        val sessionProcessId = ownProcessId.readText().trim().toLong()

        session.endSession()

        assertTrue(
            waitUntil { ProcessHandle.of(sessionProcessId).map { !it.isAlive }.orElse(true) },
            "세션의 프로세스가 남아 있습니다 (pid $sessionProcessId)",
        )
        val events = withTimeout(COLLECT_TIMEOUT_MILLIS) { session.events.toList() }
        assertIs<ChatSessionEvent.SessionEnded>(events.last(), "끝났다는 사실이 마지막 이벤트여야 한다")
    }

    private suspend fun openSession(command: List<String>): OpenedChatSession =
        ProcessChatSessionOpener().openChatSession(planFor(command)).also(openedSessions::add)

    private fun planFor(command: List<String>) = SessionEntryPlan(
        command = command,
        workingDirectory = temporaryDirectory,
        environment = mapOf("PATH" to System.getenv("PATH")),
    )

    /**
     * 마지막 이벤트까지 다 모은다.
     *
     * 흐름이 스스로 끝나는 것이 이 함수가 기대는 성질이다 — 어댑터는 프로세스가 끝나면
     * `SessionEnded` 를 내고 통로를 닫는다.
     */
    private suspend fun OpenedChatSession.eventsUntilItEnds(): List<ChatSessionEvent> =
        withTimeout(COLLECT_TIMEOUT_MILLIS) { events.toList() }

    private fun Path.readTextOrEmpty(): String = runCatching { readText() }.getOrDefault("")

    private companion object {
        const val COLLECT_TIMEOUT_MILLIS = 15_000L
    }
}
