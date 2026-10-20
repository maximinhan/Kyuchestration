package com.kyuchestration.desktop.terminal.chat

import java.io.BufferedReader
import java.io.BufferedWriter
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.getPosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 앱이 여는 승인 소켓의 왕복을 본다(chat-ui-design.md 5.4).
 *
 * **진짜 유닉스 도메인 소켓이다.** 반대편에 서는 것이 `kyu mcp ask` 가 아니라 그 자리를 흉내 내는
 * 시험용 클라이언트일 뿐, 통로도 줄 모양도 실제와 같다 — 그래야 이 브리지가 실제로 죽는 방식
 * (답 없이 끊긴 연결 · 읽을 수 없는 줄)을 여기서 잡는다.
 *
 * **시험 함수마다 `: Unit` 을 적어 둔다** — ProcessChatSessionTest 의 머리말이 적어둔 이유 그대로다.
 */
class AppSocketTest {

    // 이름을 짧게 둔다. AF_UNIX 경로는 107 바이트가 한계라(실측 3.7), 시험 이름이 그대로 들어가는
    // 디렉토리를 쓰면 시험이 재려는 것과 무관한 이유로 소켓이 열리지 않는다.
    private val socketDirectory = createTempDirectory("kyu-sock")

    private val openedSockets = mutableListOf<AppSocket>()

    @AfterTest
    fun 열어_둔_소켓과_임시_디렉토리를_치운다() {
        runBlocking { openedSockets.forEach { it.close() } }
        socketDirectory.toFile().deleteRecursively()
    }

    @Test
    fun `물어 온 것을 그대로 올리고 허용한 인자를 되돌려준다`(): Unit = runBlocking {
        val socket = openSocket()

        val answering = async {
            val asked = withTimeout(TEST_TIMEOUT_MILLIS) { socket.permissionRequests.first() }
            assertEquals("Bash", asked.request.toolName)
            assertEquals("toolu_01", asked.request.toolUseId)
            assertEquals("echo ORIGINAL", asked.request.input["command"]?.jsonPrimitive?.content)

            asked.answerWith(
                PermissionDecision.Allow(updatedInput = buildJsonObject { put("command", "echo EDITED") }),
            )
        }

        val answer = askOverTheSocket(
            socket.socketPath,
            """{"toolName":"Bash","input":{"command":"echo ORIGINAL"},"toolUseId":"toolu_01"}""",
        )
        answering.await()

        // 앱이 고친 인자가 그대로 돌아가야 그 인자로 실행된다(실측 3.5).
        assertEquals("allow", answer.getValue("decision"))
        assertEquals("""{"command":"echo EDITED"}""", answer.getValue("updatedInput"))
    }

    @Test
    fun `거부한 이유가 그대로 돌아간다`(): Unit = runBlocking {
        val socket = openSocket()

        val answering = async {
            val asked = withTimeout(TEST_TIMEOUT_MILLIS) { socket.permissionRequests.first() }
            asked.answerWith(PermissionDecision.Deny(reason = "이 파일은 손대지 마세요"))
        }

        val answer = askOverTheSocket(
            socket.socketPath,
            """{"toolName":"Write","input":{"file_path":"/tmp/x"},"toolUseId":"toolu_02"}""",
        )
        answering.await()

        // 이 문구가 tool_result 로 모델에게 그대로 간다(A.5) — 모델이 왜 못 했는지 말할 유일한 통로다.
        assertEquals("deny", answer.getValue("decision"))
        assertEquals("이 파일은 손대지 마세요", answer.getValue("reason"))
    }

    @Test
    fun `겹쳐 온 두 물음이 각자의 답을 받는다`(): Unit = runBlocking {
        // 연결 하나가 물음 하나다. 그 짝을 프로토콜 안의 식별자로 맞추지 않으므로, 늦게 온 물음에
        // 먼저 답해도 답이 뒤바뀌지 않는 것을 여기서 고정한다.
        val socket = openSocket()

        val firstAsking = async(Dispatchers.IO) { askOverTheSocket(socket.socketPath, question("toolu_first")) }
        val secondAsking = async(Dispatchers.IO) { askOverTheSocket(socket.socketPath, question("toolu_second")) }

        val asked = withTimeout(TEST_TIMEOUT_MILLIS) { socket.permissionRequests.take(2).toList() }

        asked.first { it.request.toolUseId == "toolu_second" }
            .answerWith(PermissionDecision.Deny(reason = "둘째"))
        asked.first { it.request.toolUseId == "toolu_first" }
            .answerWith(PermissionDecision.Allow(updatedInput = buildJsonObject { put("command", "첫째") }))

        assertEquals("""{"command":"첫째"}""", firstAsking.await().getValue("updatedInput"))
        assertEquals("둘째", secondAsking.await().getValue("reason"))
    }

    @Test
    fun `읽을 수 없는 줄에는 거부로 답하고 화면에는 올리지 않는다`(): Unit = runBlocking {
        // 물음이 깨진 채로 왔다면 카드가 무엇을 승인하는지 말할 수 없다. 답하지 않으면 그 자식은
        // 영영 기다리므로, 답은 하되 화면에는 아무것도 세우지 않는다.
        val socket = openSocket()

        val answer = askOverTheSocket(socket.socketPath, "이건 JSON 이 아니다")

        assertEquals("deny", answer.getValue("decision"))
        assertNull(
            withTimeoutOrNull(NOTHING_ARRIVED_MILLIS) { socket.permissionRequests.first() },
            "읽을 수 없는 줄이 승인 카드가 되었습니다",
        )
    }

    @Test
    fun `kind 가 permission 인 줄은 승인 물음으로 올라간다`(): Unit = runBlocking {
        // 엔진이 kind 를 달기 시작한 판의 줄이다(mcp_ask.go 의 permissionQuestionKind).
        val socket = openSocket()

        val answering = async {
            val asked = withTimeout(TEST_TIMEOUT_MILLIS) { socket.permissionRequests.first() }
            assertEquals("toolu_kind", asked.request.toolUseId)
            asked.answerWith(PermissionDecision.Deny(reason = "kind 를 보고 받았다"))
        }

        val answer = askOverTheSocket(
            socket.socketPath,
            """{"kind":"permission","toolName":"Bash","input":{"command":"ls"},"toolUseId":"toolu_kind"}""",
        )
        answering.await()

        assertEquals("deny", answer.getValue("decision"))
        assertEquals("kind 를 보고 받았다", answer.getValue("reason"))
    }

    @Test
    fun `모르는 kind 의 요청은 error 로 거절하고 화면에는 올리지 않는다`(): Unit = runBlocking {
        // 이 앱보다 먼저 올라간 엔진이 새 kind 를 보내는 자리다. 승인 카드로 오해하지 않고, 답하지 않아 상대를 영영
        // 세워 두지도 않는다 — 이 앱이 그 요청을 모른다는 것을 그 줄의 답으로 말한다.
        val socket = openSocket()

        val answer = askOverTheSocket(socket.socketPath, """{"kind":"run_in_repo","repo":"proj-a"}""")

        assertTrue(answer.getValue("error").contains("run_in_repo"), "거절 문구에 모르는 kind 가 보이기를 기대: $answer")
        assertFalse("decision" in answer, "모르는 요청에 승인 결정으로 답했습니다: $answer")
        assertNull(
            withTimeoutOrNull(NOTHING_ARRIVED_MILLIS) { socket.permissionRequests.first() },
            "모르는 kind 의 줄이 승인 카드가 되었습니다",
        )
    }

    @Test
    fun `소켓을 닫으면 아직 답하지 않은 물음의 연결이 끊긴다`(): Unit = runBlocking {
        // 세션이 끝나면 그 물음에 답할 사람도 사라진다. 연결이 끊기면 kyu mcp ask 가 EOF 를 받아
        // 거절로 끝내므로(mcp_ask.go), 답을 받지 못한 도구가 실행되는 자리가 없다.
        val socket = openSocket()

        val asking = async(Dispatchers.IO) {
            SocketChannel.open(UnixDomainSocketAddress.of(socket.socketPath)).use { connection ->
                connection.writer().apply {
                    write(question("toolu_dangling"))
                    newLine()
                    flush()
                }
                connection.reader().readLine()
            }
        }
        val asked = withTimeout(TEST_TIMEOUT_MILLIS) { socket.permissionRequests.first() }

        socket.close()

        assertNull(withTimeout(TEST_TIMEOUT_MILLIS) { asking.await() }, "소켓을 닫았는데 답이 돌아왔습니다")
        // 그 물음을 지켜보던 쪽도 풀린다 — 오지 않을 답을 앱이 꺼질 때까지 기다리지 않는다.
        assertTrue(withTimeout(TEST_TIMEOUT_MILLIS) { asked.awaitConnectionClosedBeforeAnswer() })
        assertFalse(socket.socketPath.exists(), "소켓 파일이 남았습니다 — 그 자리가 다음 세션의 발에 걸린다")
    }

    @Test
    fun `답을 받기 전에 상대가 연결을 닫으면 그 요청이 끊겼다고 알린다`(): Unit = runBlocking {
        // 상위의 await_session 이 중단되면 kyu mcp serve 가 연결을 닫는다(PR 68 · 6.4). 앱은 그것으로 그 기다림만 지운다.
        val socket = openSocket()

        val asking = async(Dispatchers.IO) {
            SocketChannel.open(UnixDomainSocketAddress.of(socket.socketPath)).use { connection ->
                connection.writer().apply {
                    write("""{"kind":"await","ids":null,"waitSeconds":1800}""")
                    newLine()
                    flush()
                }
            }
        }
        val asked = withTimeout(TEST_TIMEOUT_MILLIS) { socket.sessionTreeRequests.first() }
        asking.await()

        assertTrue(withTimeout(TEST_TIMEOUT_MILLIS) { asked.awaitConnectionClosedBeforeAnswer() })
    }

    @Test
    fun `답을 기다리던 승인 물음의 연결이 끊겨도 그 물음이 끊겼다고 알린다`(): Unit = runBlocking {
        val socket = openSocket()

        val asking = async(Dispatchers.IO) {
            SocketChannel.open(UnixDomainSocketAddress.of(socket.socketPath)).use { connection ->
                connection.writer().apply {
                    write(question("toolu_cancelled"))
                    newLine()
                    flush()
                }
            }
        }
        val asked = withTimeout(TEST_TIMEOUT_MILLIS) { socket.permissionRequests.first() }
        asking.await()

        assertTrue(withTimeout(TEST_TIMEOUT_MILLIS) { asked.awaitConnectionClosedBeforeAnswer() })
    }

    @Test
    fun `답한 요청은 끊긴 것으로 알리지 않는다`(): Unit = runBlocking {
        // 답을 쓴 뒤 앱이 연결을 닫는 것은 정상의 끝이다. 그것을 상대가 닫은 것으로 읽으면 이미 답한 기다림을 또 지운다.
        val socket = openSocket()

        val answering = async {
            val asked = withTimeout(TEST_TIMEOUT_MILLIS) { socket.sessionTreeRequests.first() }
            asked.answerWith(SessionTreeAnswer.Awaited(emptyList(), emptyList()))
            asked
        }
        askOverTheSocket(socket.socketPath, """{"kind":"await","ids":null,"waitSeconds":1800}""")

        assertFalse(withTimeout(TEST_TIMEOUT_MILLIS) { answering.await().awaitConnectionClosedBeforeAnswer() })
    }

    @Test
    fun `소켓 파일은 이 사용자만 읽고 쓴다`(): Unit = runBlocking {
        // 같은 머신의 다른 사용자가 이 소켓에 붙으면 남의 세션의 승인에 답할 수 있다(설계 5.4).
        val socket = openSocket()

        assertEquals("rw-------", PosixFilePermissions.toString(socket.socketPath.getPosixFilePermissions()))
    }

    @Test
    fun `spawn 요청을 세션 트리 요청으로 올리고 앱의 답을 그대로 되돌린다`(): Unit = runBlocking {
        // 엔진의 spawnSessionRequest 모양 그대로다(mcp_session_tree.go) — 생략한 선택 인자는 null 로 온다.
        val socket = openSocket()

        val answering = async {
            val asked = withTimeout(TEST_TIMEOUT_MILLIS) { socket.sessionTreeRequests.first() }
            assertEquals(
                SessionTreeRequest.SpawnSession(
                    prompt = "proj-a 테스트를 고쳐 줘",
                    requestedCwd = "proj-a",
                    createDirectory = false,
                    title = null,
                ),
                asked.request,
            )
            asked.answerWith(
                SessionTreeAnswer.Spawned(
                    childId = "child-1",
                    relativeCwd = "proj-a",
                    title = "proj-a",
                    conversationId = "5e0c",
                    notice = null,
                ),
            )
        }

        val answer = askOverTheSocket(
            socket.socketPath,
            """{"kind":"spawn","prompt":"proj-a 테스트를 고쳐 줘","cwd":"proj-a","createDirectory":false,"title":null}""",
        )
        answering.await()

        // 6.2 의 spawn_session 답 모양이다. 엔진은 이 줄을 모델에게 그대로 옮긴다.
        assertEquals(
            mapOf(
                "id" to "child-1",
                "cwd" to "proj-a",
                "title" to "proj-a",
                "conversationId" to "5e0c",
                "state" to "running",
            ),
            answer,
        )
        assertNull(
            withTimeoutOrNull(NOTHING_ARRIVED_MILLIS) { socket.permissionRequests.first() },
            "spawn 요청이 승인 카드가 되었습니다",
        )
    }

    @Test
    fun `end 와 list 요청도 세션 트리 요청으로 올라간다`(): Unit = runBlocking {
        val socket = openSocket()

        // 요청 하나가 연결 하나이고 답을 받아야 끝나므로, 하나씩 받아 그때그때 답한다.
        val answering = async {
            socket.sessionTreeRequests.take(2).collect { asked ->
                when (asked.request) {
                    SessionTreeRequest.EndSession("child-2") -> asked.answerWith(SessionTreeAnswer.Ended("child-2"))
                    SessionTreeRequest.ListSessions -> asked.answerWith(SessionTreeAnswer.Refused("목록을 만들지 못했습니다"))
                    else -> asked.answerWith(SessionTreeAnswer.Refused("뜻밖의 요청: ${asked.request}"))
                }
            }
        }

        val endAnswer = withTimeout(TEST_TIMEOUT_MILLIS) {
            askOverTheSocket(socket.socketPath, """{"kind":"end","id":"child-2"}""")
        }
        val listAnswer = withTimeout(TEST_TIMEOUT_MILLIS) { askOverTheSocket(socket.socketPath, """{"kind":"list"}""") }
        withTimeout(TEST_TIMEOUT_MILLIS) { answering.await() }

        assertEquals(mapOf("id" to "child-2", "state" to "ended"), endAnswer)
        // 거절은 {"error"} 한 모양이다 — 엔진이 그것을 도구 오류로 올린다(relaySessionTreeRequest).
        assertEquals(mapOf("error" to "목록을 만들지 못했습니다"), listAnswer)
    }

    @Test
    fun `send 요청도 세션 트리 요청으로 올라간다`(): Unit = runBlocking {
        // 엔진의 sendToSessionRequest 모양 그대로다(mcp_session_tree.go).
        val socket = openSocket()

        val answering = async {
            val asked = withTimeout(TEST_TIMEOUT_MILLIS) { socket.sessionTreeRequests.first() }
            assertEquals(SessionTreeRequest.SendToSession(childId = "child-1", message = "5433 을 써"), asked.request)
            asked.answerWith(SessionTreeAnswer.DeliveredAsMessage)
        }

        val answer = askOverTheSocket(socket.socketPath, """{"kind":"send","id":"child-1","message":"5433 을 써"}""")
        answering.await()

        assertEquals(mapOf("delivered" to "as_message"), answer)
    }

    @Test
    fun `await 요청은 기다릴 하위와 시간을 들고 올라간다`(): Unit = runBlocking {
        // 엔진의 awaitSessionRequest 모양 그대로다(mcp_session_tree.go) — ids 를 생략하면 null 로 오고 waitSeconds 는 늘 실린다.
        val socket = openSocket()

        // 요청 하나가 연결 하나이고 답을 받아야 끝나므로, 하나씩 받아 그때그때 답한다.
        val answering = async {
            socket.sessionTreeRequests.take(2)
                .onEach { it.answerWith(SessionTreeAnswer.Awaited(emptyList(), emptyList())) }
                .toList()
        }

        withTimeout(TEST_TIMEOUT_MILLIS) {
            askOverTheSocket(socket.socketPath, """{"kind":"await","ids":["child-1","child-2"],"waitSeconds":60}""")
        }
        withTimeout(TEST_TIMEOUT_MILLIS) {
            askOverTheSocket(socket.socketPath, """{"kind":"await","ids":null,"waitSeconds":1800}""")
        }
        val requests = withTimeout(TEST_TIMEOUT_MILLIS) { answering.await() }

        assertEquals(
            listOf(
                SessionTreeRequest.AwaitSessions(childIds = listOf("child-1", "child-2"), waitSeconds = 60),
                SessionTreeRequest.AwaitSessions(childIds = null, waitSeconds = 1800),
            ),
            requests.map { it.request },
        )
    }

    @Test
    fun `필드가 빠진 세션 트리 요청에는 승인 거부가 아니라 error 로 답한다`(): Unit = runBlocking {
        // 이 줄은 승인 물음이 아니다 — deny 로 답하면 엔진은 그것을 앱의 답으로 읽지 못하고 판이 어긋났다고 말한다.
        val socket = openSocket()

        val answer = askOverTheSocket(socket.socketPath, """{"kind":"end"}""")

        assertTrue(answer.getValue("error").contains("end"), "거절 문구에 요청 kind 가 보이기를 기대: $answer")
        assertNull(
            withTimeoutOrNull(NOTHING_ARRIVED_MILLIS) { socket.sessionTreeRequests.first() },
            "읽을 수 없는 요청이 트리로 올라갔습니다",
        )
    }

    private fun openSocket(): AppSocket =
        AppSocket.openAt(socketDirectory.resolve(newAppSocketFileName()))
            .also { openedSockets += it }

    private fun question(toolUseId: String): String =
        """{"toolName":"Bash","input":{"command":"echo $toolUseId"},"toolUseId":"$toolUseId"}"""

    /**
     * `kyu mcp ask` 노릇 — 물음 한 줄을 보내고 답 한 줄을 되읽는다.
     *
     * 답의 필드를 문자열 맵으로 되돌린다. 객체·배열 필드(updatedInput · events)는 원문 그대로 담으므로, 앱이
     * 보낸 인자가 이 자리에서 한 번 더 손질되지 않는 것도 함께 확인된다.
     */
    private suspend fun askOverTheSocket(socketPath: Path, questionLine: String): Map<String, String> {
        // 소켓을 읽고 쓰는 것은 막는 일이라 IO 로 내보낸다. runBlocking 의 한 스레드에서 그대로
        // 부르면 답할 코루틴이 돌 자리가 없어져 시험이 통째로 멎는다 — 실제로 한 번 그랬다.
        val answerLine = withContext(Dispatchers.IO) {
            SocketChannel.open(UnixDomainSocketAddress.of(socketPath)).use { connection ->
                connection.writer().apply {
                    write(questionLine)
                    newLine()
                    flush()
                }
                connection.reader().readLine()
            }
        }

        assertTrue(answerLine != null, "앱이 답하지 않았습니다")
        val answer = assertIs<JsonObject>(Json.parseToJsonElement(answerLine))
        return answer.mapValues { (_, value) ->
            if (value is JsonObject || value is JsonArray) value.toString() else value.jsonPrimitive.content
        }
    }

    private fun SocketChannel.writer(): BufferedWriter =
        Channels.newOutputStream(this).bufferedWriter(Charsets.UTF_8)

    private fun SocketChannel.reader(): BufferedReader =
        Channels.newInputStream(this).bufferedReader(Charsets.UTF_8)

    private companion object {
        const val TEST_TIMEOUT_MILLIS = 5_000L

        /** "아무것도 오지 않았다" 를 확인하는 데 기다리는 시간. 왔다면 이 안에 온다. */
        const val NOTHING_ARRIVED_MILLIS = 500L
    }
}
