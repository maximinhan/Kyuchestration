package com.kyuchestration.desktop.terminal.chat

import java.io.BufferedWriter
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 파이프 셋에 물린 `claude` 프로세스 하나를 챗 세션으로 부린다(chat-ui-design.md 5.3.3).
 *
 * 이 클래스가 아는 것은 셋이다.
 *
 * | 아는 것 | 왜 여기인가 |
 * |---|---|
 * | stdin 에 쓰는 두 모양 — 사용자 메시지와 `control_request` | 3.1 · 3.8 |
 * | 파이프 셋을 동시에 비우는 일 | 한쪽만 읽으면 다른 쪽 버퍼가 차는 순간 자식이 쓰기에서 멎는다 |
 * | 프로세스가 아무 때나 끝날 수 있다는 것 | 그것을 [ChatSessionEvent.SessionEnded] 로 바꾼다 |
 *
 * 줄을 이벤트로 옮기는 일은 하지 않는다 — 그 앎은 [chatSessionEventsFrom] 하나에 있다.
 *
 * **화면이 보고 있지 않아도 계속 읽는다.** 이 앱은 그 함정을 이미 한 번 만났다 — 아무 위젯도
 * 읽지 않는 세션이 생기고 PTY 버퍼가 차는 순간 그 안의 `claude` 가 멎었다
 * (`EmbeddedTerminalStateHolder.kt` 의 주석). 그래서 읽기는 프로세스가 뜨는 순간 시작하고,
 * 받은 것은 아무도 모으지 않아도 채널에 쌓인다.
 *
 * @param process 엔진이 답한 명령으로 이미 떠 있는 프로세스. 띄우는 일은 [ProcessChatSessionOpener] 가 한다 —
 *   이 클래스를 시험할 때 진짜 `claude` 를 요구하지 않기 위해서다.
 */
class ProcessChatSession internal constructor(private val process: Process) : OpenedChatSession {

    /**
     * 받은 이벤트가 모으는 쪽을 기다리는 자리.
     *
     * **한계를 두지 않는다.** 한계를 두고 넘칠 때 멈추면 자식의 파이프가 차서 `claude` 가 멎고,
     * 넘칠 때 버리면 전사에 구멍이 난다. 남은 길은 쌓아 두는 것뿐이다 — 쌓이는 양은 어차피
     * 상태 홀더가 전사로 들고 있을 만큼이고, 세션이 끝나면 함께 사라진다.
     */
    private val incoming = Channel<ChatSessionEvent>(Channel.UNLIMITED)

    /**
     * stdin 에 쓰는 순서를 지키는 자물쇠.
     *
     * 사용자 메시지와 중단 요청이 각자 다른 코루틴에서 온다. 둘이 겹쳐 쓰면 한 줄이 다른 줄
     * 가운데로 끼어들고, `claude` 가 받는 것은 JSON 이 아닌 무엇이 된다.
     */
    private val writingStandardInput = Mutex()

    private val standardInput: BufferedWriter = process.outputStream.bufferedWriter(Charsets.UTF_8)

    /**
     * 파이프를 읽는 코루틴들이 사는 자리.
     *
     * 부르는 쪽의 스코프를 쓰지 않는다. 이 읽기는 화면보다 오래 살아야 한다 — 화면이 사라졌다고
     * 멈추면 그 순간부터 자식의 파이프가 차기 시작한다. 대신 [endSession] 이 이 스코프를 접는
     * 유일한 자리다.
     */
    private val readingPipes = CoroutineScope(Dispatchers.IO + SupervisorJob() + CoroutineName("chat-session"))

    /**
     * 프로세스가 뜨는 순간부터 읽는다.
     *
     * 생성자에서 코루틴을 띄우는 것을 아껴 쓰지만 여기서는 그것이 요점이다. "읽기를 시작하라" 를
     * 따로 부르게 두면 그것을 빠뜨린 세션이 생기고, 그 세션은 조용히 멎는다.
     */
    private val streamingPipes: Job = readingPipes.launch {
        coroutineScope {
            launch { runInterruptible { readStandardOutput() } }
            launch { runInterruptible { readStandardError() } }
        }

        // 두 파이프가 다 끝난 뒤에야 종료 코드를 묻는다. 먼저 물으면 마지막 몇 줄이 채널에
        // 실리기 전에 SessionEnded 가 앞질러 나간다.
        val exitCode = runInterruptible { process.waitFor() }
        incoming.send(ChatSessionEvent.SessionEnded(exitCode))
        incoming.close()
    }

    override val events: Flow<ChatSessionEvent> = incoming.receiveAsFlow()

    override suspend fun sendUserMessage(text: String) {
        // 실측이 받은 그 모양이다(3.1). content 를 블록 배열로 두는 것은 claude 의 메시지 모양을
        // 그대로 따르는 것이고, 문자열 하나로 줄여 보내는 길은 재지 않았다.
        writeLine(
            buildJsonObject {
                put("type", "user")
                putJsonObject("message") {
                    put("role", "user")
                    putJsonArray("content") {
                        add(
                            buildJsonObject {
                                put("type", "text")
                                put("text", text)
                            },
                        )
                    }
                }
            }.toString(),
        )
    }

    override suspend fun interruptCurrentTurn() {
        // init 의 capabilities 가 interrupt_receipt_v1 이라고 스스로 말하고, 실측이 이 모양으로
        // 보냈을 때 턴만 끊기는 것을 확인했다(3.8 · A.9).
        //
        // 답(control_response)을 이 id 로 대응시키지 않는다. 중단이 먹었다는 사실은 뒤이어 오는
        // result(Interrupted)가 말하고, 그것이 화면이 실제로 기다리는 것이다.
        writeLine(
            buildJsonObject {
                put("type", "control_request")
                put("request_id", "int_" + UUID.randomUUID().toString().take(INTERRUPT_ID_LENGTH))
                putJsonObject("request") { put("subtype", "interrupt") }
            }.toString(),
        )
    }

    override suspend fun endSession() {
        // stdin 을 닫는 것이 "더 보낼 말이 없다" 는 뜻이다. -p 모드의 claude 는 그때 스스로 끝나므로
        // 먼저 이 길을 준다 — 죽이고 시작하면 마지막 result 가 실리지 못한다.
        closeStandardInput()

        if (waitedForTheStreamToEnd()) {
            readingPipes.cancel()
            return
        }

        // 스스로 끝나지 않았다. 여기서 놓아 주면 앱 어디에도 보이지 않는 프로세스가 남는다.
        process.destroy()
        if (!waitedForTheStreamToEnd()) {
            process.destroyForcibly()
            waitedForTheStreamToEnd()
        }
        readingPipes.cancel()
    }

    /**
     * stdout 한 줄이 이벤트 0 개 이상이 된다.
     *
     * `trySend` 로 쓴다. 한계 없는 채널이라 자리가 없어 실패할 일이 없고, 남은 실패는 채널이
     * 이미 닫힌 경우뿐인데 그때는 이 세션이 이미 끝난 뒤라 실을 곳이 없다.
     *
     * 빈 줄은 넘긴다. 스트림에 실린 사실이 없는 줄이라, 그대로 넣으면 읽을 수 없는 줄 하나가
     * 이벤트로 올라간다.
     */
    private fun readStandardOutput() {
        process.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { streamLine ->
            if (streamLine.isNotBlank()) {
                chatSessionEventsFrom(streamLine).forEach(incoming::trySend)
            }
        }
    }

    /**
     * stderr 를 다른 코루틴이 따로 비운다.
     *
     * 두 파이프를 한 코루틴이 순서대로 읽으면, 안 읽는 쪽 버퍼가 먼저 차는 순간 `claude` 가
     * 쓰기에서 멎고 이쪽은 읽기에서 멎는다 — kyu 를 부르는 실행기가 이미 지키는 규율이다
     * (`ProcessKyuCommandRunner`).
     *
     * 여기 오는 것이 실패의 이유다. 없는 대화를 이어가려 했을 때 `claude` 는 그 사실을 이 통로에
     * 한 줄로 적고 종료 코드 1 로 끝난다(3.9).
     */
    private fun readStandardError() {
        process.errorStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
            if (line.isNotBlank()) {
                incoming.trySend(ChatSessionEvent.EngineSpoke(line))
            }
        }
    }

    private suspend fun writeLine(line: String) = writingStandardInput.withLock {
        runInterruptible {
            standardInput.write(line)
            standardInput.newLine()
            // 흘려보내지 않으면 이 줄은 버퍼에 남고, claude 는 아무 말도 듣지 못한 채 기다린다.
            standardInput.flush()
        }
    }

    private suspend fun closeStandardInput() = writingStandardInput.withLock {
        runInterruptible {
            try {
                standardInput.close()
            } catch (alreadyGone: IOException) {
                // 프로세스가 먼저 끝나 파이프가 닫힌 경우다. 끝내려던 참이므로 이 실패는
                // 바라던 상태와 다르지 않다.
            }
        }
    }

    /** 마지막 이벤트까지 실리기를 기다린다. 시간 안에 끝나지 않으면 false. */
    private suspend fun waitedForTheStreamToEnd(): Boolean =
        withTimeoutOrNull(GRACEFUL_EXIT_TIMEOUT_MILLIS) { streamingPipes.join() } != null

    private companion object {

        /** `int_b8acf0f7` — 실측이 받은 모양이다(A.9). */
        const val INTERRUPT_ID_LENGTH = 8

        /**
         * 스스로 끝나기를 기다리는 시간.
         *
         * `claude` 는 stdin 이 닫히면 곧 끝난다. 이 값은 그 "곧" 이 느린 기계에서 늘어날 자리를
         * 덮는 것뿐이라 넉넉히 두어도 사용자를 기다리게 하지 않는다 — 이 시간을 다 쓰는 경우는
         * 이미 무언가 잘못된 경우다.
         */
        const val GRACEFUL_EXIT_TIMEOUT_MILLIS = 5_000L
    }
}
