package com.kyuchestration.desktop.core

import com.kyuchestration.desktop.claudeauth.ClaudeAuthenticationState
import com.kyuchestration.desktop.claudeauth.ClaudeTokenStore
import com.kyuchestration.desktop.claudeauth.StoredClaudeCredential
import com.kyuchestration.desktop.diagnostics.DiagnosticLog
import com.kyuchestration.desktop.mirror.CoreMirror
import com.kyuchestration.desktop.protocol.FrameToCore
import com.kyuchestration.desktop.protocol.ScreenCommand
import com.kyuchestration.desktop.repoclone.RegisteredTokenProfile
import com.kyuchestration.desktop.repoclone.RepositoryCloneState
import com.kyuchestration.desktop.repoclone.TokenProfile
import com.kyuchestration.desktop.repoclone.TokenProfileRegistry
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * 비밀이 경계를 건너는 자리(core-ui-split-design.md 6.11).
 *
 * 화면 → 코어로는 토큰이 실린다 — 받는 쪽이 그것을 엔진에게 맡겨야 하므로. 코어 → 화면으로는 실리지 않아야 한다. 화면이
 * 토큰을 다시 받을 까닭이 없고, 그 줄은 언젠가 진단을 위해 기록될 줄이다. 그리고 토큰을 싣는 명령이 문자열로 찍히는 자리
 * (거절 사유 · 시험 실패 문구 · 디버거)에서는 토큰이 가려져야 한다.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SecretsStayOffTheScreenFramesTest {

    private val workDirPath: Path = createTempDirectory("kyu-secrets")

    @AfterTest
    fun 임시_디렉토리를_치운다() {
        workDirPath.toFile().deleteRecursively()
    }

    @Test
    fun `claude 토큰을 쓰고 GitHub 토큰을 등록해도 코어가 화면에 보낸 틀 어디에도 토큰이 없다`() = runTest {
        val tokenStore = MemoryClaudeTokenStore()
        val profileRegistry = MemoryTokenProfileRegistry()
        val bed = inProcessCoreTestBed(claudeTokenStore = tokenStore, tokenProfileRegistry = profileRegistry)
        val screenToCore = Channel<String>(Channel.UNLIMITED)
        val coreToTap = Channel<String>(Channel.UNLIMITED)
        val tapToMirror = Channel<String>(Channel.UNLIMITED)
        val linesSentToScreen = mutableListOf<String>()
        backgroundScope.launch { serveScreen(bed.core, framesFromScreen = screenToCore, framesToScreen = coreToTap) }
        backgroundScope.launch {
            for (line in coreToTap) {
                linesSentToScreen += line
                tapToMirror.send(line)
            }
            tapToMirror.close()
        }
        val mirror = CoreMirror.connect(tapToMirror, screenToCore, backgroundScope, DiagnosticLog.Discarding)

        mirror.useClaudeToken(CLAUDE_TOKEN)
        runCurrent()
        mirror.openWorkDir(workDirPath.toString())
        runCurrent()
        mirror.openRepositoryClone()
        runCurrent()
        mirror.registerTokenProfile("work", GITHUB_TOKEN)
        runCurrent()

        // 토큰은 코어에 닿았고, 그 결과 상태는 화면에 왔다 — 틀이 오가지 않아서 깨끗한 것이 아니다.
        assertEquals(listOf(CLAUDE_TOKEN), tokenStore.storedTokens)
        assertEquals(listOf(GITHUB_TOKEN), profileRegistry.registeredTokens)
        assertEquals(ClaudeAuthenticationState.CredentialsReady, mirror.claudeAuthenticationState.value)
        assertIs<RepositoryCloneState.TokenProfileRegistered>(mirror.repositoryCloneState.value)

        assertTrue(linesSentToScreen.isNotEmpty())
        linesSentToScreen.forEach { line ->
            assertFalse(CLAUDE_TOKEN in line, "claude 토큰이 화면으로 가는 틀에 실렸다: $line")
            assertFalse(GITHUB_TOKEN in line, "GitHub 토큰이 화면으로 가는 틀에 실렸다: $line")
        }
    }

    @Test
    fun `비밀을 싣는 명령은 문자열로 찍힐 때 그 값을 가린다`() {
        val commands = listOf(
            ScreenCommand.UseClaudeToken(CLAUDE_TOKEN),
            ScreenCommand.RegisterTokenProfile("work", GITHUB_TOKEN),
            ScreenCommand.SubmitClaudeBrowserLoginCode(BROWSER_LOGIN_CODE),
        )

        commands.forEach { command ->
            val printed = listOf(command.toString(), FrameToCore.Command("c-1", command).toString())
            printed.forEach { text ->
                assertFalse(CLAUDE_TOKEN in text || GITHUB_TOKEN in text || BROWSER_LOGIN_CODE in text, text)
                assertTrue("***" in text, text)
            }
        }
        assertTrue("work" in ScreenCommand.RegisterTokenProfile("work", GITHUB_TOKEN).toString())
    }

    private class MemoryClaudeTokenStore : ClaudeTokenStore {
        val storedTokens = mutableListOf<String>()

        override fun storedCredential() =
            StoredClaudeCredential(token = storedTokens.lastOrNull(), newTokenWouldBeStoredInPlaintext = true)

        override fun storeToken(token: String) {
            storedTokens += token
        }
    }

    private class MemoryTokenProfileRegistry : TokenProfileRegistry {
        val registeredTokens = mutableListOf<String>()

        override fun listProfiles(): List<TokenProfile> = emptyList()

        override fun registerProfile(profileName: String, token: String): RegisteredTokenProfile {
            registeredTokens += token
            return RegisteredTokenProfile(profileName, gitHubLogin = "maximinhan")
        }

        override fun removeProfile(profileName: String) = error("이 시험은 프로필을 지우지 않는다")
    }

    private companion object {
        const val CLAUDE_TOKEN = "sk-ant-oat01-SECRET-claude-token-value"
        const val GITHUB_TOKEN = "ghp_SECRETgithubTokenValue0123456789"
        const val BROWSER_LOGIN_CODE = "SECRET-one-time-browser-code"
    }
}
