package com.kyuchestration.desktop.mirror

import com.kyuchestration.desktop.claudeauth.ClaudeAuthenticationState
import com.kyuchestration.desktop.core.TEST_SCREEN_VERSION_LABEL
import com.kyuchestration.desktop.core.coreProcessCommandForTests
import com.kyuchestration.desktop.core.isolatedCoreEnvironment
import com.kyuchestration.desktop.dashboard.WorkDirDashboardState
import com.kyuchestration.desktop.diagnostics.DiagnosticLog
import com.kyuchestration.desktop.engine.EngineInstallationState
import com.kyuchestration.desktop.terminal.SessionConversationChoice
import com.kyuchestration.desktop.terminal.SessionTarget
import com.kyuchestration.desktop.terminal.chat.ChatEntry
import com.kyuchestration.desktop.terminal.chat.SessionEntryState
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assumptions.abort
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS

/**
 * 터미널에서 `claude` 로 나눈 대화를 앱이 고르게 하고, 고른 대화가 그 맥락 그대로 이어지는지 진짜 코어 프로세스로 잰다
 * (app-owned-sessions-design.md 5.5.7 의 완료 확인).
 *
 * 사용자가 만난 결함이 이 시험의 모양이다 — 폴더에서 터미널로 대화해 오다 앱으로 열고 이어가기를 누르면, 그 대화가 아니라
 * 앱이 만든 빈 대화가 열렸다. 여기서는 터미널 대화가 고르기의 기본으로 서고, 고르면 그 대화가 앞서 들은 암호를 기억한다.
 *
 * **부르면 토큰을 쓰고, 이 머신의 `~/.claude/projects` 에 임시 자리의 전사 하나를 남긴다.** 그래서 환경 변수로 일부러 켜야 돈다.
 *
 * ```
 * KYU_CLAUDE_CHAT_INTEGRATION=1 KYU_BINARY_PATH=<이 브랜치에서 빌드한 kyu> \
 *   ./gradlew :ui:test --tests '*RealClaudeTerminalConversationChoiceIntegrationTest'
 * ```
 */
@DisabledOnOs(OS.WINDOWS, disabledReason = "코어는 리눅스 · 맥에서만 돈다 — 윈도우의 코어는 WSL 안에 있다(core-ui-split-design.md 6.16)")
class RealClaudeTerminalConversationChoiceIntegrationTest {

    private val scratchDirectory: Path = createTempDirectory("kyu-real-terminal-conversation")

    private val workDirPath: Path = scratchDirectory.resolve("workdir").createDirectories()

    private val readingScope = CoroutineScope(Dispatchers.Default)

    @AfterTest
    fun cleanUp() {
        readingScope.cancel()
        scratchDirectory.toFile().deleteRecursively()
    }

    @Test
    fun `터미널에서 하던 대화가 고르기의 기본으로 서고 고르면 그 맥락으로 이어진다`(): Unit = runBlocking {
        val kyuExecutable = requireRealClaudeAndKyuOrSkip()
        val terminalConversationId = talkInTerminal("이 대화의 암호 단어는 $PASSPHRASE 다. 알겠다고만 답해.")
        println("터미널 대화: $terminalConversationId")

        val coreProcess = CoreChildProcess.start(CorePlace.SameMachine(coreProcessCommandForTests()), DiagnosticLog.Discarding) {
            isolatedCoreEnvironment(scratchDirectory, kyuExecutable)
        }
        val mirror = withTimeout(STEP_TIMEOUT) {
            assertIs<CoreConnection.Connected>(
                coreProcess.connectMirror(readingScope, TEST_SCREEN_VERSION_LABEL, leaveTimeout = LEAVE_TIMEOUT),
            ).mirror
        }
        waitUntil("엔진과 claude 자격 증명이 서기를") {
            mirror.engineInstallationState.value == EngineInstallationState.EngineReady &&
                mirror.claudeAuthenticationState.value == ClaudeAuthenticationState.CredentialsReady
        }
        mirror.openWorkDir(workDirPath.toString())
        waitUntil("워크디렉토리가 열리기를") { mirror.workDirDashboardState.value is WorkDirDashboardState.WorkDirObserved }

        // 카드를 그냥 누른 자리다. 앱에 적힌 대화가 없고 바깥 대화가 하나 있으니 코어가 고르기를 세운다.
        mirror.enterSession(SessionTarget.Main, SessionConversationChoice.ContinueRecordedConversation)
        waitUntil("고르기가 서기를") { mirror.chatSessionEntries.value[SessionTarget.Main] is SessionEntryState.ChoosingConversation }
        val choosing = assertIs<SessionEntryState.ChoosingConversation>(mirror.chatSessionEntries.value[SessionTarget.Main])
        println("고르기: $choosing")
        assertEquals(listOf(terminalConversationId), choosing.candidates.map { it.id })
        assertEquals(null, choosing.recorded)
        assertEquals(SessionConversationChoice.AdoptConversation(terminalConversationId), choosing.defaultChoice)

        // 사람이 기본 선택 그대로 "이어가기" 를 누른다.
        mirror.chooseConversation(SessionTarget.Main, choosing.defaultChoice)
        waitUntil("메인이 서기를") { SessionTarget.Main in mirror.heldChatSessionTargets.value }
        assertEquals(terminalConversationId, mirror.chatConversations.value[SessionTarget.Main]?.resumedConversationId)

        mirror.sendUserMessage(SessionTarget.Main, "이 대화의 암호 단어가 뭐였지? 그 낱말만 답해.")
        waitUntil("메인의 턴이 끝나기를", TURN_TIMEOUT) {
            mirror.chatConversations.value[SessionTarget.Main]?.entries.orEmpty().any { it is ChatEntry.TurnEnded }
        }
        val answer = mirror.chatConversations.value[SessionTarget.Main]?.entries.orEmpty()
            .filterIsInstance<ChatEntry.AssistantSaid>()
            .joinToString("\n") { it.text }
        println("앱에서 받은 답: $answer")
        assertTrue(PASSPHRASE in answer, "터미널 대화의 맥락이 이어지지 않았습니다. 받은 답: $answer")

        assertEquals(CoreChildProcess.Exit.EndedByItself(exitCode = 0), coreProcess.leave(mirror, timeout = LEAVE_TIMEOUT))

        // 채택이 기록으로 남았다 — 다음에 이어가기를 누르면 이 대화가 적힌 대화라 묻지 않고 이어간다.
        val listing = runProcess(listOf(kyuExecutable.toString(), "conversations", "list", workDirPath.toString(), "--json"))
        println("채택 뒤의 목록: $listing")
        val adopted = Json.parseToJsonElement(listing).jsonObject["conversations"].toString()
        assertTrue(""""id":"$terminalConversationId"""" in adopted && """"recordedFor":"main"""" in adopted, adopted)
    }

    /** 사람이 그 폴더의 터미널에서 `claude -p` 로 한 말. 그 대화의 id 를 돌려준다. */
    private fun talkInTerminal(prompt: String): String {
        val output = runProcess(listOf("claude", "-p", prompt, "--output-format", "json"))
        return Json.parseToJsonElement(output).jsonObject.getValue("session_id").jsonPrimitive.content
    }

    private fun runProcess(command: List<String>): String {
        val process = ProcessBuilder(command)
            .directory(workDirPath.toFile())
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()
        process.outputStream.close()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(TURN_TIMEOUT.inWholeSeconds, TimeUnit.SECONDS)) { "$command 가 끝나지 않았습니다" }
        check(process.exitValue() == 0) { "$command 가 종료 코드 ${process.exitValue()} 로 끝났습니다: $output" }
        return output
    }

    private suspend fun waitUntil(what: String, timeout: kotlin.time.Duration = STEP_TIMEOUT, condition: () -> Boolean) {
        withTimeout(timeout) {
            while (!condition()) {
                delay(POLL_INTERVAL)
            }
        }
        println("확인: $what")
    }

    private fun requireRealClaudeAndKyuOrSkip(): Path {
        if (System.getenv(OPT_IN_VARIABLE) != "1") {
            abort<Unit>("$OPT_IN_VARIABLE=1 이 아니라 건너뜁니다 — 이 검증은 진짜 claude 를 부릅니다")
        }
        val kyuExecutable = System.getenv(KYU_BINARY_VARIABLE)?.takeIf { it.isNotBlank() }?.let(Path::of)
            ?: abort<Path>("$KYU_BINARY_VARIABLE 가 없어 건너뜁니다 — 코어가 부를 엔진을 이 판의 소스에서 빌드한 것으로 고정한다")
        if (!Files.isExecutable(kyuExecutable)) {
            abort<Unit>("$kyuExecutable 를 실행할 수 없어 건너뜁니다")
        }
        return kyuExecutable
    }

    private companion object {
        const val OPT_IN_VARIABLE = "KYU_CLAUDE_CHAT_INTEGRATION"
        const val KYU_BINARY_VARIABLE = "KYU_BINARY_PATH"

        /** 터미널 대화에서만 알려 주는 낱말. 앱이 연 대화가 이것을 알면 그 대화가 이어진 것이다. */
        const val PASSPHRASE = "보라색코끼리"

        val STEP_TIMEOUT = 60.seconds
        val TURN_TIMEOUT = 5.minutes
        val LEAVE_TIMEOUT = 60.seconds
        val POLL_INTERVAL = 0.2.seconds
    }
}
