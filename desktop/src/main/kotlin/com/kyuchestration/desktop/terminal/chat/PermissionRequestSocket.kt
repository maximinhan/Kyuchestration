package com.kyuchestration.desktop.terminal.chat

import com.kyuchestration.desktop.terminal.TerminalSessionFailure
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * 세션 하나의 승인 물음을 받는 소켓(chat-ui-design.md 5.4).
 *
 * **왜 소켓인가.** 승인 도구를 여는 `kyu mcp ask` 는 `claude` 의 자식이고, 결정을 내리는 사람은
 * 앱 앞에 있다. 자식에서 부모로 물어볼 통로가 있어야 하는데, TCP 루프백은 같은 머신의 다른
 * 프로세스도 붙을 수 있어 비밀 토큰이 필요하고 그 토큰은 `--mcp-config` 를 타고 `ps` 에 보인다.
 * 파일 폴링은 앱이 죽었는지 아직 고민 중인지를 가르지 못한다.
 *
 * **세션마다 하나다.** 하나를 공유하면 어느 세션의 물음인지를 메시지 안에 담아야 하고, 그러면 그
 * 식별자를 누가 정하고 누가 검증하는지가 새로 생긴다 — 세션마다 열면 **연결 자체가 곧 어느
 * 세션인지다.**
 *
 * **물음 하나가 연결 하나다.** `kyu mcp ask` 는 물을 때마다 새로 붙는다(mcp_ask.go). 그래서 답을
 * 물음에 맞추는 짝도 연결이 지고, 이 프로토콜 안에는 요청 ID 가 없다.
 *
 * **답하지 않는 것도 성립한다.** 사람이 오래 고민하면 그 연결은 그대로 열려 있고 `claude` 는
 * 기다린다(실측 3.7 이 150 초를 쟀다). 세션이 끝나 [close] 가 불리면 그 연결들이 끊기고,
 * 그때 `kyu mcp ask` 가 EOF 를 거절로 읽는다 — 답을 받지 못한 도구가 실행되는 자리가 없다.
 */
class PermissionRequestSocket private constructor(
    private val listeningChannel: ServerSocketChannel,

    /** 이 소켓의 자리. 엔진에게 `--approval-socket` 으로 넘어가는 값이 이것이다. */
    override val socketPath: Path,
) : PermissionRequestChannel {

    /**
     * 아직 아무도 답하지 않은 물음들이 쌓이는 자리.
     *
     * 한계를 두지 않는다. 한계에서 멈추면 그동안 붙은 자식들이 답을 못 받은 채 서 있고, 버리면
     * 사용자가 보지 못한 물음이 조용히 거절된다 — 쌓이는 양은 어차피 사람이 답할 수 있는 만큼이다.
     */
    private val incoming = Channel<AskedPermission>(Channel.UNLIMITED)

    /**
     * 이 세션에 올라온 승인 물음들. 받는 쪽은 하나다(상태 홀더).
     */
    override val requests: Flow<AskedPermission> = incoming.receiveAsFlow()

    /**
     * 붙는 것을 기다리고 답을 되돌리는 코루틴들이 사는 자리.
     *
     * 부르는 쪽의 스코프를 쓰지 않는다. 화면이 이 세션을 보고 있지 않아도 물음은 와야 하고,
     * 화면이 사라졌다고 이 자리가 멎으면 그 세션의 `claude` 는 답 없는 승인에 매달려 멈춘다.
     * 접는 자리는 [close] 하나다.
     */
    private val listening = CoroutineScope(Dispatchers.IO + SupervisorJob() + CoroutineName("permission-socket"))

    init {
        listening.launch {
            while (isActive) {
                val connection = try {
                    runInterruptible { listeningChannel.accept() }
                } catch (closed: IOException) {
                    // 소켓이 닫혔다. 세션이 끝난 자리라 알릴 상대가 없다 — 그때 답을 기다리던
                    // 연결들은 각자의 코루틴이 접히면서 함께 닫힌다.
                    break
                }

                launch { answerOnce(connection) }
            }
        }
    }

    /**
     * 이 소켓을 닫는다. 돌아오면 그 자리에 파일이 없다.
     *
     * 파일을 지우는 것까지가 닫는 일이다. JDK 는 바인드한 소켓 파일을 지우지 않으므로, 남겨 두면
     * 런타임 디렉토리에 죽은 소켓이 쌓이고 그중 하나를 다음 세션이 자기 이름으로 고르는 날
     * 바인드가 실패한다.
     */
    override suspend fun close() {
        // 먼저 접는다. 답을 기다리던 코루틴들이 풀리면서 그 연결이 닫히고, 그것이 자식에게는
        // "물어볼 사람이 사라졌다" 는 뜻이 된다(mcp_ask.go 가 EOF 를 거절로 읽는다).
        listening.cancel()
        incoming.close()

        withContext(Dispatchers.IO) {
            runInterruptible {
                listeningChannel.close()
                Files.deleteIfExists(socketPath)
            }
        }
    }

    /**
     * 붙은 연결 하나를 끝까지 다룬다 — 물음 한 줄을 읽고, 답 한 줄을 쓰고, 끊는다.
     *
     * **읽을 수 없는 줄에도 답한다.** 답하지 않으면 그 자식은 영영 기다리고, 그 자식을 기다리는
     * 것은 사용자의 턴이다. 다만 화면에는 세우지 않는다 — 무엇을 승인하는지 말할 수 없는 카드는
     * 사용자에게 물어볼 것이 없는 카드다.
     */
    private suspend fun answerOnce(connection: SocketChannel) {
        connection.use {
            val questionLine = runInterruptible {
                Channels.newInputStream(connection).bufferedReader(Charsets.UTF_8).readLine()
            } ?: return

            val request = permissionRequestFrom(questionLine)
            val decision = if (request == null) {
                PermissionDecision.Deny(
                    reason = "앱이 이 승인 물음을 읽지 못했습니다 — 앱과 kyu 의 판이 어긋났습니다",
                )
            } else {
                val asked = AskedPermission(request)
                incoming.send(asked)
                asked.awaitDecision()
            }

            runInterruptible {
                Channels.newOutputStream(connection).bufferedWriter(Charsets.UTF_8).apply {
                    write(answerLineFor(decision))
                    newLine()
                    flush()
                }
            }
        }
    }

    companion object {

        /**
         * 그 자리에 소켓을 연다.
         *
         * @throws TerminalSessionFailure.ApprovalSocketPathTooLong 경로가 107 바이트를 넘을 때.
         * @throws TerminalSessionFailure.ApprovalSocketFailedToOpen 그 밖의 이유로 열지 못했을 때.
         */
        fun openAt(socketPath: Path): PermissionRequestSocket {
            // 길이를 먼저 본다. 이것을 JDK 에게 맡기면 실패 문구가 "Unix domain path too long" 이라
            // 사용자가 무엇을 어떻게 해야 하는지 알 수 없고, 그 실패는 실측이 실제로 만난 것이다(3.7).
            if (socketPath.toString().toByteArray(Charsets.UTF_8).size > UNIX_SOCKET_PATH_LIMIT_BYTES) {
                throw TerminalSessionFailure.ApprovalSocketPathTooLong(socketPath, UNIX_SOCKET_PATH_LIMIT_BYTES)
            }

            return try {
                prepareSocketDirectory(socketPath.parent)

                // 앞선 실행이 남긴 자리일 수 있다. 그 파일이 있으면 바인드가 "이미 있음" 으로
                // 실패하는데, 이 이름은 이번 세션이 방금 고른 것이라 남의 소켓일 수 없다.
                Files.deleteIfExists(socketPath)

                val listeningChannel = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
                listeningChannel.bind(UnixDomainSocketAddress.of(socketPath))
                restrictToThisUser(socketPath)

                PermissionRequestSocket(listeningChannel, socketPath)
            } catch (failure: IOException) {
                throw TerminalSessionFailure.ApprovalSocketFailedToOpen(socketPath, failure)
            }
        }

        /**
         * `AF_UNIX` 경로의 한계(실측 3.7 이 만난 `OSError: AF_UNIX path too long`).
         *
         * 107 은 리눅스의 값이다(`sun_path` 108 바이트에서 끝의 0 하나를 뺀 것). 맥은 이보다
         * 짧으므로(103) 이 값을 넘지 않는다고 늘 열리는 것은 아니지만, 그 실패는 아래의 IOException
         * 갈래가 받는다 — 두 숫자를 다 아는 척하는 것보다 넘는 것이 확실한 선을 먼저 긋는다.
         */
        private const val UNIX_SOCKET_PATH_LIMIT_BYTES = 107

        /**
         * 소켓을 담을 디렉토리를 이 사용자만 들어올 수 있게 만든다.
         *
         * 소켓 파일의 권한만으로는 부족하다 — 디렉토리에 쓸 수 있는 사람은 그 소켓을 지우고 자기
         * 소켓을 같은 이름으로 걸 수 있고, 그러면 다음 물음은 남의 프로그램에게 간다.
         */
        private fun prepareSocketDirectory(directoryPath: Path?) {
            if (directoryPath == null) {
                return
            }

            Files.createDirectories(directoryPath)
            runCatching { Files.setPosixFilePermissions(directoryPath, PosixFilePermissions.fromString("rwx------")) }
                // 윈도우에는 이 개념이 없고 담는 디렉토리의 ACL 이 그 몫을 한다(설계 5.4).
                // 리눅스·맥에서는 위의 createDirectories 가 이미 성공한 뒤라 여기서 실패할 자리가 없다.
                .onFailure { if (it !is UnsupportedOperationException) throw it }
        }

        private fun restrictToThisUser(socketPath: Path) {
            runCatching { Files.setPosixFilePermissions(socketPath, PosixFilePermissions.fromString("rw-------")) }
                .onFailure { if (it !is UnsupportedOperationException) throw it }
        }
    }
}

/**
 * 엔진이 보내온 물음 한 줄을 읽는다. 읽지 못하면 null.
 *
 * 필드 이름은 엔진과 맺은 계약이다(mcp_ask.go 의 permissionQuestion). `claude` 가 쓰는
 * snake_case 가 아니라 우리 표기인 것을 짚어둔다 — 옮기는 자리는 엔진이고, 이 줄은 우리 둘 사이의 것이다.
 */
internal fun permissionRequestFrom(questionLine: String): PermissionRequest? {
    val question = try {
        permissionSocketJson.parseToJsonElement(questionLine) as? JsonObject
    } catch (failure: SerializationException) {
        null
    } catch (failure: IllegalArgumentException) {
        null
    } ?: return null

    val toolName = (question["toolName"] as? JsonPrimitive)?.contentOrNull ?: return null
    val input = question["input"] as? JsonObject ?: return null

    return PermissionRequest(
        toolName = toolName,
        input = input,
        toolUseId = (question["toolUseId"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
    )
}

/** 사람의 답을 엔진이 읽을 한 줄로 옮긴다(설계 5.4.1). */
internal fun answerLineFor(decision: PermissionDecision): String = when (decision) {
    is PermissionDecision.Allow -> buildJsonObject {
        put("decision", "allow")
        put("updatedInput", decision.updatedInput)
    }

    is PermissionDecision.Deny -> buildJsonObject {
        put("decision", "deny")
        put("reason", decision.reason)
    }
}.toString()

private val permissionSocketJson = Json
