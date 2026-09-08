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
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
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
class PermissionRequestSocketTest {

    // 이름을 짧게 둔다. AF_UNIX 경로는 107 바이트가 한계라(실측 3.7), 시험 이름이 그대로 들어가는
    // 디렉토리를 쓰면 시험이 재려는 것과 무관한 이유로 소켓이 열리지 않는다.
    private val socketDirectory = createTempDirectory("kyu-sock")

    private val openedSockets = mutableListOf<PermissionRequestSocket>()

    @AfterTest
    fun 열어_둔_소켓과_임시_디렉토리를_치운다() {
        runBlocking { openedSockets.forEach { it.close() } }
        socketDirectory.toFile().deleteRecursively()
    }

    @Test
    fun `물어 온 것을 그대로 올리고 허용한 인자를 되돌려준다`(): Unit = runBlocking {
        val socket = openSocket()

        val answering = async {
            val asked = withTimeout(TEST_TIMEOUT_MILLIS) { socket.requests.first() }
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
            val asked = withTimeout(TEST_TIMEOUT_MILLIS) { socket.requests.first() }
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

        val asked = withTimeout(TEST_TIMEOUT_MILLIS) { socket.requests.take(2).toList() }

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
            withTimeoutOrNull(NOTHING_ARRIVED_MILLIS) { socket.requests.first() },
            "읽을 수 없는 줄이 승인 카드가 되었습니다",
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
        withTimeout(TEST_TIMEOUT_MILLIS) { socket.requests.first() }

        socket.close()

        assertNull(withTimeout(TEST_TIMEOUT_MILLIS) { asking.await() }, "소켓을 닫았는데 답이 돌아왔습니다")
        assertFalse(socket.socketPath.exists(), "소켓 파일이 남았습니다 — 그 자리가 다음 세션의 발에 걸린다")
    }

    @Test
    fun `소켓 파일은 이 사용자만 읽고 쓴다`(): Unit = runBlocking {
        // 같은 머신의 다른 사용자가 이 소켓에 붙으면 남의 세션의 승인에 답할 수 있다(설계 5.4).
        val socket = openSocket()

        assertEquals("rw-------", PosixFilePermissions.toString(socket.socketPath.getPosixFilePermissions()))
    }

    private fun openSocket(): PermissionRequestSocket =
        PermissionRequestSocket.openAt(socketDirectory.resolve(newPermissionSocketFileName()))
            .also { openedSockets += it }

    private fun question(toolUseId: String): String =
        """{"toolName":"Bash","input":{"command":"echo $toolUseId"},"toolUseId":"$toolUseId"}"""

    /**
     * `kyu mcp ask` 노릇 — 물음 한 줄을 보내고 답 한 줄을 되읽는다.
     *
     * 답의 필드를 문자열 맵으로 되돌린다. 객체 필드(updatedInput)는 원문 그대로 담으므로, 앱이
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
            if (value is JsonObject) value.toString() else value.jsonPrimitive.content
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
