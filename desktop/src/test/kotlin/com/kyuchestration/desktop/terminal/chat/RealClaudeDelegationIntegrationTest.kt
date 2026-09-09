package com.kyuchestration.desktop.terminal.chat

import com.kyuchestration.desktop.RUN_IN_REPO_TOOL_NAME
import com.kyuchestration.desktop.delegationAnswerOrNull
import com.kyuchestration.desktop.kyu.realKyuCommandRunnerOrSkip
import com.kyuchestration.desktop.terminal.SessionConversationChoice
import com.kyuchestration.desktop.terminal.SessionTarget
import com.kyuchestration.desktop.terminal.kyucli.KyuCliSessionCommandSource
import java.nio.file.Path
import java.util.concurrent.Executors
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assumptions.abort

/**
 * 진짜 `claude` 를 띄워 6 단계의 완료 확인 둘을 그대로 해 본다(chat-ui-design.md 9 절).
 *
 * > 메인에게 레포 작업을 시키면 위임 카드가 뜨고 경과 시간이 흐른다.
 * > 서브에이전트를 띄우면 안쪽 대화가 접힌 채로 보인다.
 *
 * **녹화 자료가 답하지 못하는 물음이 여기 있다.** 접기 규칙은 녹화한 줄로 확인되지만
 * (ChatConversationFoldingTest), 그 줄이 **앱이 실제로 조립한 명령에서 나오는지**는 이쪽만 안다 —
 * 서브에이전트의 말이 오게 하는 플래그가 엔진의 답에 실려 있는지, 위임 도구가 메인 세션에
 * 실제로 붙는지가 그렇다.
 *
 * **부르면 토큰을 쓴다.** 위임 쪽은 특히 무겁다 — `run_in_repo` 가 레포 안에서 `claude` 를 또
 * 띄우므로 한 턴에 두 세션이 돈다. 그래서 환경 변수로 일부러 켜야 돈다.
 *
 * ```
 * KYU_CLAUDE_CHAT_INTEGRATION=1 KYU_BINARY_PATH=<이 브랜치에서 빌드한 kyu> \
 *   ./gradlew test --tests '*RealClaudeDelegationIntegrationTest*'
 * ```
 */
class RealClaudeDelegationIntegrationTest {

    private val temporaryDirectory = createTempDirectory("kyu-real-delegation-test")

    /** 앱에서 이 홀더에 닿는 스코프는 화면을 그리는 단일 스레드다(Main.kt). 여기서도 같은 전제를 지킨다. */
    private val holderThread = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "chat-state-holder-test")
    }
    private val holderScope = CoroutineScope(holderThread.asCoroutineDispatcher())

    @AfterTest
    fun 남은_세션과_임시_디렉토리를_치운다() = runBlocking {
        holderScope.cancel()
        holderThread.shutdownNow()
        temporaryDirectory.toFile().deleteRecursively()
        Unit
    }

    @Test
    fun `서브에이전트를 띄우면 안쪽 대화가 그 카드 안에 접힌다`(): Unit = runBlocking {
        temporaryDirectory.resolve(NOTE_FILE_NAME).writeText("지문: $DELEGATION_PASSPHRASE\n")
        val holder = stateHolderOrSkip()

        holder.enterSession(temporaryDirectory, SessionTarget.Main, SessionConversationChoice.ContinueRecordedConversation)
        waitUntilChatIsOnScreen(holder)

        holder.sendUserMessage(
            "서브에이전트를 하나 띄워서 이 디렉토리의 $NOTE_FILE_NAME 을 읽고 그 안의 지문 문자열을 " +
                "그대로 보고하게 해라. 네가 직접 읽지 말고 반드시 서브에이전트에게 시켜라.",
        )
        waitForFinishedTurns(holder, turnCount = 1)

        val conversation = onScreenConversation(holder)
        holder.endOnScreenSession()

        val subagentCard = conversation.entries
            .filterIsInstance<ChatEntry.ToolCall>()
            .firstOrNull { it.subagentRun != null }
        assertNotNull(subagentCard, "서브에이전트 카드가 서지 않았습니다: ${conversation.entries.map { it::class.simpleName }}")

        // **안쪽 대화가 접혀 있다는 것이 이 검증의 전부다.** 메인 전사에 그대로 쏟아졌다면
        // 여기가 비고, 대신 대화 본문에 안쪽 말풍선과 도구 카드가 서 있다.
        assertTrue(
            subagentCard.nestedEntries.any { it is ChatEntry.ToolCall },
            "안쪽 도구 호출이 카드 밖에 있습니다: ${subagentCard.nestedEntries.map { it::class.simpleName }}",
        )
        assertTrue(
            subagentCard.nestedEntries.any { it is ChatEntry.AssistantSaid },
            "서브에이전트가 한 말이 오지 않았습니다 — 엔진이 --forward-subagent-text 를 붙이는지 보세요",
        )
        assertTrue(
            conversation.entries.none { it is ChatEntry.ToolCall && it.toolUseId in subagentCard.nestedIds() },
            "안쪽 도구 호출이 대화 본문에도 서 있습니다",
        )

        val run = assertNotNull(subagentCard.subagentRun)
        assertTrue(run.subagentType.isNotEmpty(), "서브에이전트의 종류가 비어 있습니다")
        assertNotNull(run.outputFilePath, "그 실행의 원출력 자리가 카드에 없습니다")
        assertNotNull(subagentCard.requestedAt, "경과 시간을 그릴 시작점이 없습니다")
    }

    @Test
    fun `레포에 일을 시키면 위임 카드가 서고 그 결말을 말한다`(): Unit = runBlocking {
        val repositoryPath = gitRepositoryIn(temporaryDirectory.resolve(DELEGATED_REPO_NAME))
        repositoryPath.resolve(NOTE_FILE_NAME).writeText("지문: $DELEGATION_PASSPHRASE\n")

        val holder = stateHolderOrSkip()
        holder.enterSession(temporaryDirectory, SessionTarget.Main, SessionConversationChoice.ContinueRecordedConversation)
        waitUntilChatIsOnScreen(holder)

        holder.sendUserMessage(
            "$DELEGATED_REPO_NAME 레포에 일을 시켜라 — 그 레포의 $NOTE_FILE_NAME 을 읽고 그 안의 지문 " +
                "문자열을 그대로 답하라고. 파일은 고치지 말라고 함께 적어라. 네가 직접 읽지 마라.",
        )
        waitForFinishedTurns(holder, turnCount = 1, timeoutMillis = DELEGATION_TIMEOUT_MILLIS)

        val conversation = onScreenConversation(holder)
        holder.endOnScreenSession()

        val delegationCard = conversation.entries
            .filterIsInstance<ChatEntry.ToolCall>()
            .firstOrNull { it.toolName == RUN_IN_REPO_TOOL_NAME }
        assertNotNull(delegationCard, "위임 카드가 서지 않았습니다: ${conversation.entries.map { it::class.simpleName }}")

        // 도는 동안 카드가 보일 수 있는 것 — 어느 레포에, 무엇을, 얼마나 오래.
        assertNotNull(delegationCard.requestedAt, "경과 시간을 그릴 시작점이 없습니다")

        val answer = assertNotNull(delegationCard.answer, "위임이 끝나지 않았습니다")
        val delegation = assertNotNull(
            delegationAnswerOrNull(answer.modelVisibleText),
            "엔진의 답 문서를 읽지 못했습니다: ${answer.modelVisibleText.take(200)}",
        )

        assertEquals(DELEGATED_REPO_NAME, delegation.repo)
        assertNull(delegation.incomplete, "위임이 완결되지 않았습니다: ${delegation.incomplete}")
        assertEquals(emptyList(), delegation.permissionDeniedTools)
        // 위임된 세션이 그 레포 안에서 실제로 일했다는 근거다 — 지문은 그 레포에만 있다.
        assertTrue(
            DELEGATION_PASSPHRASE in delegation.result,
            "위임의 답에 그 레포의 지문이 없습니다: ${delegation.result}",
        )
        // 카드가 안내하는 원출력 자리가 실제로 열리는 파일이라야 사후 추적이 성립한다(5.4.3 · 6.3).
        assertTrue(Path.of(delegation.logPath).exists(), "원출력이 그 자리에 없습니다: ${delegation.logPath}")
    }

    private fun ChatEntry.ToolCall.nestedIds(): Set<String> =
        nestedEntries.filterIsInstance<ChatEntry.ToolCall>().map { it.toolUseId }.toSet()

    private fun stateHolderOrSkip(): ChatSessionStateHolder {
        if (System.getenv(OPT_IN_VARIABLE) != "1") {
            abort<Unit>("$OPT_IN_VARIABLE=1 이 아니라 건너뜁니다 — 이 검증은 진짜 claude 를 부릅니다")
        }
        requireClaudeIsInstalled()

        return ChatSessionStateHolder(
            sessionCommandSource = KyuCliSessionCommandSource(realKyuCommandRunnerOrSkip()),
            chatSessionOpener = ProcessChatSessionOpener(),
            // 이 시험을 돌리는 사람의 claude 는 이미 로그인돼 있다. 앱이 맡아 둔 토큰을 싣는
            // 자리는 여기가 아니라 SessionEntryPlanTest 가 본다.
            claudeAuthToken = { null },
            coroutineScope = holderScope,
        )
    }

    private fun requireClaudeIsInstalled() {
        val installed = runCatching {
            ProcessBuilder(listOf("claude", "--version")).redirectErrorStream(true).start().waitFor() == 0
        }.getOrDefault(false)

        if (!installed) {
            abort<Unit>("claude 를 부르지 못해 건너뜁니다")
        }
    }

    private suspend fun waitUntilChatIsOnScreen(holder: ChatSessionStateHolder) =
        withTimeout(TURN_TIMEOUT_MILLIS) {
            while (holder.state.value !is ChatScreenState.ChatOnScreen) {
                delay(POLL_INTERVAL_MILLIS)
            }
        }

    /**
     * 그 턴이 끝날 때까지 기다리며, 관문이 열리면 요청한 그대로 허용한다.
     *
     * 사람이 앉아 있는 자리를 대신하는 것이라 이 검증에서는 늘 허용한다. 거절과 인자 수정이
     * 실제로 도는 것은 5 단계가 이미 쟀다(RealClaudePermissionBridgeIntegrationTest).
     */
    private suspend fun waitForFinishedTurns(
        holder: ChatSessionStateHolder,
        turnCount: Int,
        timeoutMillis: Long = TURN_TIMEOUT_MILLIS,
    ) = withTimeout(timeoutMillis) {
        while (onScreenConversation(holder).entries.filterIsInstance<ChatEntry.TurnEnded>().size < turnCount) {
            allowAnyPendingPermission(holder)
            delay(POLL_INTERVAL_MILLIS)
        }
    }

    private fun allowAnyPendingPermission(holder: ChatSessionStateHolder) {
        onScreenConversation(holder).entries
            .filterIsInstance<ChatEntry.PermissionAsked>()
            .filter { it.answer == null }
            .forEach { holder.answerOnScreenPermission(it.toolUseId, PermissionCardChoice.AllowOnce(it.input)) }
    }

    private fun onScreenConversation(holder: ChatSessionStateHolder): ChatConversation =
        assertIs<ChatScreenState.ChatOnScreen>(holder.state.value).conversation

    private fun gitRepositoryIn(repositoryPath: Path): Path {
        repositoryPath.createDirectories()
        git(repositoryPath, "init", "--quiet", "--initial-branch=main")
        git(repositoryPath, "commit", "--quiet", "--allow-empty", "--message=첫 커밋")
        return repositoryPath
    }

    private fun git(repositoryPath: Path, vararg arguments: String) {
        val process = ProcessBuilder(listOf("git") + arguments)
            .directory(repositoryPath.toFile())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        check(process.waitFor() == 0) { "git ${arguments.joinToString(" ")} 실패: $output" }
    }

    private companion object {

        /** 이 검증을 일부러 켜는 자리. 다른 실 claude 검증과 같은 이름을 쓴다 — 켜는 이유가 같다. */
        const val OPT_IN_VARIABLE = "KYU_CLAUDE_CHAT_INTEGRATION"

        const val NOTE_FILE_NAME = "NOTE.md"

        /** 그 파일에만 있는 낱말. 위임된 세션이 실제로 그 자리에서 일했는지를 이것으로 가른다. */
        const val DELEGATION_PASSPHRASE = "DELEGATE-2026"

        const val DELEGATED_REPO_NAME = "proj-a"

        const val TURN_TIMEOUT_MILLIS = 180_000L

        /**
         * 위임이 든 턴을 기다리는 시간.
         *
         * 넉넉한 이유가 이 턴의 모양이다 — 메인 세션이 도는 동안 그 안에서 레포 세션이 또 뜬다.
         * 엔진이 위임 하나에 두는 상한이 10 분이므로(mcp_run_in_repo.go), 그보다 짧게 두면
         * 이 검증이 위임보다 먼저 포기한다.
         */
        const val DELEGATION_TIMEOUT_MILLIS = 660_000L

        const val POLL_INTERVAL_MILLIS = 200L
    }
}
