package com.kyuchestration.desktop.terminal.chat

import com.kyuchestration.desktop.kyu.realKyuCommandRunnerOrSkip
import com.kyuchestration.desktop.platform.childProcessEnvironment
import com.kyuchestration.desktop.terminal.SessionConversationChoice
import com.kyuchestration.desktop.terminal.SessionEntryPlan
import com.kyuchestration.desktop.terminal.SessionTarget
import com.kyuchestration.desktop.terminal.kyucli.KyuCliSessionCommandSource
import com.kyuchestration.desktop.terminal.planSessionEntry
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assumptions.abort

/**
 * 진짜 `claude` 로 승인 브리지를 통째로 돌린다(chat-ui-design.md 9 절 5 단계의 완료 확인).
 *
 * **이 시험만 답할 수 있는 물음이 셋이다.**
 *
 * | 물음 | 스텁이 답하지 못하는 이유 |
 * |---|---|
 * | 관문이 우리 도구를 부르는가 | `--permission-prompt-tool mcp__kyu-ask__request_permission` 의 하이픈 하나가 틀리면 아무 일도 일어나지 않는다(A.15) |
 * | 거절이 실제로 파일을 막는가 | 막는 것은 `claude` 이고, 우리가 아는 것은 우리가 보낸 문서뿐이다 |
 * | 고친 인자가 실제로 실행되는가 | 3.5 가 프로브로 쟀지만, 우리 엔진과 우리 소켓을 지나서도 그런지는 여기서만 갈린다 |
 *
 * **부르면 토큰을 쓴다.** 그래서 다른 챗 통합 검증과 같은 규율로 환경 변수로 일부러 켜야 돈다.
 *
 * ```
 * KYU_CLAUDE_CHAT_INTEGRATION=1 KYU_BINARY_PATH=<이 브랜치에서 빌드한 kyu> \
 *   ./gradlew test --tests '*RealClaudePermissionBridgeIntegrationTest*'
 * ```
 *
 * **관문이 열리는 조건은 이 검증이 스스로 만든다.** 관문이 언제 열리는지는 사용자 설정이 정하고
 * (3.6 · 설계 5.2가 그렇게 두기로 했다), 이 검증을 처음 돌린 머신의 설정이 `defaultMode: auto`
 * 라 `Write` 가 묻지 않고 그냥 돌았다 — 실측 3.6 의 표 그대로다. 그래서 아래 두 플래그를 이
 * 검증에서만 덧붙인다([GATE_OPENING_FLAGS]). **앱은 이 플래그를 붙이지 않는다** — 붙이면
 * 사용자가 자기 설정에 적어둔 선택이 화면 종류에 따라 달라진다(설계 5.2).
 */
class RealClaudePermissionBridgeIntegrationTest {

    private val temporaryDirectory = createTempDirectory("kyu-real-permission-test")

    /** 소켓을 놓을 짧은 자리. 앱은 런타임 디렉토리를 쓰고(5.4), 여기서는 그만큼 짧은 임시 자리를 쓴다. */
    private val socketDirectory = createTempDirectory("kyu-perm")

    @AfterTest
    fun 임시_디렉토리를_지운다() {
        temporaryDirectory.toFile().deleteRecursively()
        socketDirectory.toFile().deleteRecursively()
    }

    @Test
    fun `거절하면 파일이 생기지 않고 그 이유가 모델에게 간다`() {
        val turn = askClaudeToWriteAFile { PermissionDecision.Deny(DENY_REASON) }

        assertEquals("Write", turn.askedPermissions.single().toolName)
        assertFalse(
            turn.requestedFilePath().exists(),
            "거절했는데 파일이 만들어졌습니다: ${turn.requestedFilePath()}",
        )

        // 거절의 문구가 tool_result 로 그대로 온다(A.5). 이것이 모델이 "왜 못 했는지" 를
        // 사용자에게 말할 수 있는 유일한 통로다.
        val failedResults = turn.events
            .filterIsInstance<ChatSessionEvent.ToolCallAnswered>()
            .filter { it.failed }
        assertTrue(
            failedResults.any { DENY_REASON in it.modelVisibleText },
            "거절 문구가 모델에게 가지 않았습니다: ${failedResults.map { it.modelVisibleText }}",
        )
    }

    @Test
    fun `허용하면 모델이 요청한 그대로 파일이 만들어진다`() {
        val turn = askClaudeToWriteAFile { PermissionDecision.Allow(updatedInput = it) }

        val writtenFile = turn.requestedFilePath()
        assertTrue(writtenFile.exists(), "허용했는데 파일이 없습니다: $writtenFile")
        assertTrue(
            ORIGINAL_CONTENT in writtenFile.readText(),
            "요청한 내용이 아닙니다: ${writtenFile.readText()}",
        )
    }

    @Test
    fun `인자를 고쳐 허용하면 고친 대로 실행된다`() {
        // 3.5 가 프로브로 쟀던 것을 우리 엔진과 우리 소켓을 지나서 다시 잰다 — 모델이 요청한
        // 파일은 만들어지지 않고, 앱이 고쳐 넣은 파일만 만들어진다.
        val editedFilePath = temporaryDirectory.resolve(EDITED_FILE_NAME)

        val turn = askClaudeToWriteAFile {
            PermissionDecision.Allow(
                updatedInput = buildJsonObject {
                    put("file_path", editedFilePath.toString())
                    put("content", EDITED_CONTENT)
                },
            )
        }

        assertTrue(editedFilePath.exists(), "고쳐 넣은 파일이 없습니다: $editedFilePath")
        assertEquals(EDITED_CONTENT, editedFilePath.readText())
        assertFalse(
            turn.requestedFilePath().exists(),
            "모델이 요청한 파일까지 만들어졌습니다: ${turn.requestedFilePath()}",
        )
    }

    /**
     * 파일 하나를 쓰게 시키고, 관문이 열리면 [decide] 가 정한 대로 답한다.
     *
     * **argv 를 여기서 짓지 않는다.** 엔진에게 승인 소켓의 자리를 주고 물으면, 그 답에
     * `--permission-prompt-tool` 과 `--mcp-config` 가 실려 온다(설계 5.2 · 원칙 11).
     */
    private fun askClaudeToWriteAFile(decide: (JsonObject) -> PermissionDecision): OneTurn = runBlocking {
        val socket = openApprovalSocketOrSkip()
        val session = ProcessChatSessionOpener().openChatSession(sessionPlanAskingThrough(socket.socketPath))

        val received = mutableListOf<ChatSessionEvent>()
        val askedPermissions = mutableListOf<PermissionRequest>()
        collectInto(received, session)
        launch {
            socket.requests.collect { asked ->
                askedPermissions += asked.request
                asked.answerWith(decide(asked.request.input))
            }
        }

        try {
            session.sendUserMessage(
                "Write 도구로 이 디렉토리에 $REQUESTED_FILE_NAME 파일을 만들고 " +
                    "안에 $ORIGINAL_CONTENT 라고만 써. 다른 도구는 쓰지 마.",
            )
            waitForFinishedTurns(received, turnCount = 1)
        } finally {
            session.endSession()
            socket.close()
        }

        // 관문이 한 번도 열리지 않았으면 이 검증이 재려던 것이 아무것도 일어나지 않은 것이다.
        // 조용히 통과시키면 브리지가 죽은 날에도 초록불이 뜬다.
        assertTrue(
            askedPermissions.isNotEmpty(),
            "승인 물음이 오지 않았습니다 — 이 머신의 설정이 Write 를 묻지 않고 통과시키거나(3.6), " +
                "관문이 우리 도구를 부르지 못했습니다. 받은 이벤트: ${received.map { it::class.simpleName }}",
        )

        OneTurn(events = received, askedPermissions = askedPermissions)
    }

    /** 한 턴에서 일어난 것 — 받은 이벤트와, 관문이 물어 온 것들. */
    private inner class OneTurn(
        val events: List<ChatSessionEvent>,
        val askedPermissions: List<PermissionRequest>,
    ) {

        /**
         * 모델이 쓰겠다고 한 파일.
         *
         * 물음에 실려 온 인자에서 읽는다 — 모델이 상대경로를 쓸 수도 절대경로를 쓸 수도 있어서,
         * 우리가 지어낸 경로로 확인하면 이 검증이 모델의 표기 방식을 재게 된다.
         */
        fun requestedFilePath(): Path {
            val filePath = askedPermissions.first().input["file_path"]?.jsonPrimitive?.content.orEmpty()
            val requested = Path.of(filePath)
            return if (requested.isAbsolute) requested else temporaryDirectory.resolve(requested)
        }
    }

    private fun openApprovalSocketOrSkip(): PermissionRequestSocket {
        if (System.getenv(OPT_IN_VARIABLE) != "1") {
            abort<Unit>("$OPT_IN_VARIABLE=1 이 아니라 건너뜁니다 — 이 검증은 진짜 claude 를 부릅니다")
        }
        requireClaudeIsInstalled()

        return PermissionRequestSocket.openAt(socketDirectory.resolve(newPermissionSocketFileName()))
    }

    private fun sessionPlanAskingThrough(approvalSocketPath: Path): SessionEntryPlan {
        val answer = KyuCliSessionCommandSource(realKyuCommandRunnerOrSkip())
            .sessionCommandFor(
                workDirPath = temporaryDirectory,
                target = SessionTarget.Main,
                conversationChoice = SessionConversationChoice.ContinueRecordedConversation,
                approvalSocketPath = approvalSocketPath,
            )

        val plan = planSessionEntry(
            sessionCommandAnswer = answer,
            baseEnvironment = childProcessEnvironment(),
            workDirPath = temporaryDirectory,
            target = SessionTarget.Main,
            // 이 시험을 돌리는 사람의 claude 는 이미 로그인돼 있다. 앱이 맡아 둔 토큰을 실어
            // 보내는 자리는 여기가 아니라 SessionEntryPlanTest 가 본다.
            claudeAuthToken = null,
        )

        // 엔진이 답한 것에 손대는 유일한 자리다(원칙 11 을 어기는 것이 아니라, 이 검증이 재려는
        // 상황을 만드는 것이다). 승인 브리지의 왕복은 관문이 열려야 재는데, 관문이 열리는지는
        // 이 머신의 설정이 정한다 — 그 설정을 고치지 않고 이 실행에서만 조건을 세운다.
        return plan.copy(command = plan.command + GATE_OPENING_FLAGS)
    }

    private fun requireClaudeIsInstalled() {
        val installed = runCatching {
            ProcessBuilder(listOf("claude", "--version")).redirectErrorStream(true).start().waitFor() == 0
        }.getOrDefault(false)

        if (!installed) {
            abort<Unit>("claude 를 부르지 못해 건너뜁니다")
        }
    }

    private fun CoroutineScope.collectInto(received: MutableList<ChatSessionEvent>, session: OpenedChatSession) {
        launch { session.events.collect { received += it } }
    }

    /** 그만큼의 턴이 끝날 때까지 기다린다. `delay` 라 기다리는 동안 받는 쪽이 계속 돈다. */
    private suspend fun waitForFinishedTurns(received: List<ChatSessionEvent>, turnCount: Int) =
        withTimeout(TURN_TIMEOUT_MILLIS) {
            while (received.count { it is ChatSessionEvent.TurnFinished } < turnCount) {
                delay(POLL_INTERVAL_MILLIS)
            }
        }

    private companion object {

        /** 이 검증을 일부러 켜는 자리. 다른 챗 통합 검증과 같은 낱말을 쓴다. */
        const val OPT_IN_VARIABLE = "KYU_CLAUDE_CHAT_INTEGRATION"

        /**
         * 관문을 여는 조건 — **이 검증에서만 붙인다.**
         *
         * `--setting-sources ""` 는 이 머신의 설정을 걷어낸다. 실측이 그 상태에서 쟀고(부록 B),
         * 그렇게 두지 않으면 이 검증의 성패가 그 머신의 `settings.json` 에 달린다.
         * `--permission-mode manual` 은 그 위에서 관문을 켜는 모드다 — 그 모드에서 `Write` 가
         * 승인 도구를 한 번 부르는 것을 실측이 쟀다(A.6 의 첫 줄).
         */
        val GATE_OPENING_FLAGS = listOf("--setting-sources", "", "--permission-mode", "manual")

        const val REQUESTED_FILE_NAME = "probe.txt"

        const val ORIGINAL_CONTENT = "ORIGINAL"

        const val EDITED_FILE_NAME = "edited-by-app.txt"

        const val EDITED_CONTENT = "MODIFIED-BY-APP"

        /** 거절의 이유. 이 낱말이 모델에게 그대로 가는지가 판정의 근거다. */
        const val DENY_REASON = "KYUDENY-7 — 이 세션에서는 파일을 만들지 않습니다"

        const val TURN_TIMEOUT_MILLIS = 180_000L

        const val POLL_INTERVAL_MILLIS = 200L
    }
}
