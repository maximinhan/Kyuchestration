package com.kyuchestration.desktop.claudeauth

import com.kyuchestration.desktop.diagnostics.RecordingDiagnosticLog
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/**
 * 인증 홀더가 걸음마다 어느 상태로 옮겨 가는가.
 *
 * 프로세스도 저장소도 띄우지 않는다. 여기서 확인하는 것은 순서와 갈래이고, 진짜 claude 를 부르는
 * 자리는 그 자체로 값을 쓰는 일이라 시험이 함부로 들어갈 곳이 아니다(RealClaudeChatSession… 과
 * 같은 규율이다 — 그쪽은 환경 변수로 일부러 켠다).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ClaudeAuthenticationStateHolderTest {

    @Test
    fun `자격 증명이 있으면 곧바로 통과시킨다`() = runTest {
        val holder = holderWith(
            claudeCliAuthentication = FakeClaudeCliAuthentication(credentialsPresent = true),
            claudeTokenStore = FakeClaudeTokenStore(),
            testScope = this,
        )

        holder.checkCredentials()
        testScheduler.advanceUntilIdle()

        assertEquals(ClaudeAuthenticationState.CredentialsReady, holder.state.value)
    }

    @Test
    fun `자격 증명이 없으면 고를 자리를 보여준다`() = runTest {
        val holder = holderWith(
            claudeCliAuthentication = FakeClaudeCliAuthentication(credentialsPresent = false),
            claudeTokenStore = FakeClaudeTokenStore(newTokenWouldBeStoredInPlaintext = true),
            testScope = this,
        )

        holder.checkCredentials()
        testScheduler.advanceUntilIdle()

        val state = assertIs<ClaudeAuthenticationState.CredentialsMissing>(holder.state.value)
        assertNull(state.lastFailure)
        assertTrue(state.tokenWouldBeStoredInPlaintext, "평문 경고를 낼 근거가 화면까지 오지 않았습니다")
    }

    @Test
    fun `맡아 둔 토큰을 실어서 묻는다`() {
        // 세션에 실을 것과 다른 환경으로 물으면, 화면이 "로그인됨" 이라고 말한 뒤 세션이
        // 로그인되지 않은 채로 뜬다 — 사용자가 그 어긋남을 발견하는 자리는 첫 대화다.
        runTest {
            val claude = FakeClaudeCliAuthentication(credentialsPresent = true)
            val holder = holderWith(claude, FakeClaudeTokenStore(storedToken = STORED_TOKEN), this)

            holder.checkCredentials()
            testScheduler.advanceUntilIdle()

            assertEquals(listOf<String?>(STORED_TOKEN), claude.tokensAskedWith)
            assertEquals(STORED_TOKEN, holder.tokenForSessions.value)
        }
    }

    @Test
    fun `claude 를 띄우지 못하면 로그인 버튼이 아니라 설치 안내로 간다`() = runTest {
        val claude = FakeClaudeCliAuthentication(
            credentialsPresent = false,
            failureWhenAsked = ClaudeAuthenticationFailure.ClaudeCliCouldNotRun(IOException("claude 없음")),
        )
        val holder = holderWith(claude, FakeClaudeTokenStore(), this)

        holder.checkCredentials()
        testScheduler.advanceUntilIdle()

        assertIs<ClaudeAuthenticationState.ClaudeCliMissing>(holder.state.value)
    }

    @Test
    fun `브라우저 로그인은 주소를 받아 화면에 세운다`() = runTest {
        val claude = FakeClaudeCliAuthentication(credentialsPresent = false)
        val holder = holderWith(claude, FakeClaudeTokenStore(), this)

        holder.startBrowserLogin()
        testScheduler.advanceUntilIdle()

        val state = assertIs<ClaudeAuthenticationState.BrowserLoginWaitingForCode>(holder.state.value)
        assertEquals(FakeClaudeBrowserLogin.URL, state.authorizationUrl)
    }

    @Test
    fun `코드를 넣어 로그인이 끝나면 claude 에게 다시 물어서 통과시킨다`() {
        // 걸음의 성공을 걸음 자신이 선언하지 않는다. claude auth login 이 0 으로 끝났다는 사실과
        // 이 머신의 claude 가 이제 답한다는 사실은 다른 것이다.
        runTest {
            val claude = FakeClaudeCliAuthentication(credentialsPresent = false)
            val holder = holderWith(claude, FakeClaudeTokenStore(), this)

            holder.startBrowserLogin()
            testScheduler.advanceUntilIdle()

            claude.credentialsPresent = true
            holder.submitBrowserLoginCode("붙여넣은-코드")
            testScheduler.advanceUntilIdle()

            assertEquals(ClaudeAuthenticationState.CredentialsReady, holder.state.value)
            assertEquals(listOf("붙여넣은-코드"), claude.startedLogin.receivedCodes)
        }
    }

    @Test
    fun `거절당한 코드는 이유와 함께 고르는 자리로 되돌린다`() = runTest {
        val claude = FakeClaudeCliAuthentication(credentialsPresent = false)
        claude.startedLogin.failureWhenCompleting =
            ClaudeAuthenticationFailure.BrowserLoginRefused(exitCode = 1, claudeMessage = "Login failed: 400")
        val holder = holderWith(claude, FakeClaudeTokenStore(), this)

        holder.startBrowserLogin()
        testScheduler.advanceUntilIdle()
        holder.submitBrowserLoginCode("이미-쓴-코드")
        testScheduler.advanceUntilIdle()

        val state = assertIs<ClaudeAuthenticationState.CredentialsMissing>(holder.state.value)
        assertIs<ClaudeAuthenticationFailure.BrowserLoginRefused>(state.lastFailure)
    }

    @Test
    fun `로그인을 다시 시작하면 앞서 기다리던 claude 를 끝낸다`() {
        // 남겨 두면 브라우저를 여는 claude 가 하나씩 쌓이고, 그것들은 코드를 기다리며 영영 산다.
        runTest {
            val claude = FakeClaudeCliAuthentication(credentialsPresent = false)
            val holder = holderWith(claude, FakeClaudeTokenStore(), this)

            holder.startBrowserLogin()
            testScheduler.advanceUntilIdle()
            holder.startBrowserLogin()
            testScheduler.advanceUntilIdle()

            assertEquals(1, claude.startedLogin.cancelCallCount)
        }
    }

    @Test
    fun `통하는 토큰만 맡긴다`() = runTest {
        val claude = FakeClaudeCliAuthentication(credentialsPresent = false)
        val store = FakeClaudeTokenStore()
        val holder = holderWith(claude, store, this)

        claude.verdict = ClaudeTokenVerdict.Accepted
        claude.credentialsPresent = true
        holder.useToken(PASTED_TOKEN)
        testScheduler.advanceUntilIdle()

        assertEquals(listOf(PASTED_TOKEN), store.storedTokens)
        assertEquals(ClaudeAuthenticationState.CredentialsReady, holder.state.value)
        assertEquals(PASTED_TOKEN, holder.tokenForSessions.value)
    }

    @Test
    fun `거절당한 토큰은 저장하지 않는다`() {
        // 저장부터 하면 죽은 토큰이 남아 다음 실행에서도 같은 실패가 반복되고, 사용자는 자기가
        // 넣은 것이 문제인지 앱이 문제인지 가리지 못한다.
        runTest {
            val claude = FakeClaudeCliAuthentication(credentialsPresent = false)
            claude.verdict = ClaudeTokenVerdict.Refused("401 OAuth access token is invalid.")
            val store = FakeClaudeTokenStore()
            val holder = holderWith(claude, store, this)

            holder.useToken(PASTED_TOKEN)
            testScheduler.advanceUntilIdle()

            assertEquals(emptyList<String>(), store.storedTokens)
            val state = assertIs<ClaudeAuthenticationState.CredentialsMissing>(holder.state.value)
            assertIs<ClaudeAuthenticationFailure.PastedTokenRefused>(state.lastFailure)
        }
    }

    @Test
    fun `저장소가 답하지 않아도 로그인 자체는 막지 않는다`() = runTest {
        val store = FakeClaudeTokenStore(failureWhenAsked = ClaudeTokenStoreFailure("kyu 를 찾지 못했습니다"))
        val diagnosticLog = RecordingDiagnosticLog()
        val holder = holderWith(
            FakeClaudeCliAuthentication(credentialsPresent = false),
            store,
            this,
            diagnosticLog,
        )

        holder.checkCredentials()
        testScheduler.advanceUntilIdle()

        assertIs<ClaudeAuthenticationState.CredentialsMissing>(holder.state.value)
        // 화면에 뜨지 않는 실패라 기록에 없으면 "왜 매번 다시 묻는가" 의 이유가 어디에도 안 남는다.
        assertTrue(
            diagnosticLog.recordedText().contains("저장소가 답하지 않음"),
            "저장소 실패가 기록되지 않았습니다: ${diagnosticLog.recordedText()}",
        )
    }

    @Test
    fun `토큰도 붙여넣은 코드도 기록에 남지 않는다`() {
        // 이 앱의 기록은 사용자가 통째로 보내는 파일이다. 갈래를 닫아 둔 것이 그 보장이지만
        // (DiagnosticLogEntry), 부르는 자리가 그것을 지키는지는 여기서만 갈린다.
        runTest {
            val claude = FakeClaudeCliAuthentication(credentialsPresent = false)
            claude.verdict = ClaudeTokenVerdict.Refused("401 OAuth access token is invalid.")
            claude.startedLogin.failureWhenCompleting =
                ClaudeAuthenticationFailure.BrowserLoginRefused(exitCode = 1, claudeMessage = "코드 $PASTED_CODE 거절")
            val diagnosticLog = RecordingDiagnosticLog()
            val holder = holderWith(claude, FakeClaudeTokenStore(storedToken = STORED_TOKEN), this, diagnosticLog)

            holder.checkCredentials()
            testScheduler.advanceUntilIdle()
            holder.useToken(PASTED_TOKEN)
            testScheduler.advanceUntilIdle()
            holder.startBrowserLogin()
            testScheduler.advanceUntilIdle()
            holder.submitBrowserLoginCode(PASTED_CODE)
            testScheduler.advanceUntilIdle()

            val recorded = diagnosticLog.recordedText()
            assertFalse(recorded.contains(STORED_TOKEN), "맡아 둔 토큰이 기록에 있습니다:\n$recorded")
            assertFalse(recorded.contains(PASTED_TOKEN), "붙여넣은 토큰이 기록에 있습니다:\n$recorded")
            assertFalse(recorded.contains(PASTED_CODE), "붙여넣은 코드가 기록에 있습니다:\n$recorded")
        }
    }

    private fun holderWith(
        claudeCliAuthentication: ClaudeCliAuthentication,
        claudeTokenStore: ClaudeTokenStore,
        testScope: TestScope,
        diagnosticLog: RecordingDiagnosticLog = RecordingDiagnosticLog(),
    ) = ClaudeAuthenticationStateHolder(
        claudeCliAuthentication = claudeCliAuthentication,
        claudeTokenStore = claudeTokenStore,
        coroutineScope = testScope,
        diagnosticLog = diagnosticLog,
        // 앱에서는 창이 멎지 않도록 IO 로 나가는 자리다. 여기서도 나가는 것은 같고,
        // 다른 것은 그 차례를 시험의 시계가 진행시킨다는 것뿐이다.
        tokenStoreDispatcher = StandardTestDispatcher(testScope.testScheduler),
    )
}

private const val STORED_TOKEN = "sk-ant-oat01-맡아-둔-가짜"
private const val PASTED_TOKEN = "sk-ant-oat01-붙여넣은-가짜"
private const val PASTED_CODE = "붙여넣은-일회용-코드#state"

/** 정해진 답만 돌려주고 무엇을 물었는지 적어 두는 claude 대역. */
private class FakeClaudeCliAuthentication(
    var credentialsPresent: Boolean,
    private val failureWhenAsked: ClaudeAuthenticationFailure? = null,
) : ClaudeCliAuthentication {

    val tokensAskedWith = mutableListOf<String?>()
    val startedLogin = FakeClaudeBrowserLogin()
    var verdict: ClaudeTokenVerdict = ClaudeTokenVerdict.Accepted

    override suspend fun credentialsArePresent(tokenToInject: String?): Boolean {
        tokensAskedWith += tokenToInject
        failureWhenAsked?.let { throw it }
        return credentialsPresent
    }

    override suspend fun startBrowserLogin(): ClaudeBrowserLogin = startedLogin

    override suspend fun tokenIsAccepted(token: String): ClaudeTokenVerdict = verdict
}

private class FakeClaudeBrowserLogin : ClaudeBrowserLogin {

    override val authorizationUrl = URL
    val receivedCodes = mutableListOf<String>()
    var cancelCallCount = 0
    var failureWhenCompleting: ClaudeAuthenticationFailure? = null

    override suspend fun completeWithCode(code: String) {
        receivedCodes += code
        failureWhenCompleting?.let { throw it }
    }

    override fun cancel() {
        cancelCallCount++
    }

    companion object {
        const val URL = "https://claude.com/cai/oauth/authorize?code=true"
    }
}

private class FakeClaudeTokenStore(
    private val storedToken: String? = null,
    private val newTokenWouldBeStoredInPlaintext: Boolean = false,
    private val failureWhenAsked: ClaudeTokenStoreFailure? = null,
) : ClaudeTokenStore {

    val storedTokens = mutableListOf<String>()

    override fun storedCredential(): StoredClaudeCredential {
        failureWhenAsked?.let { throw it }
        return StoredClaudeCredential(
            token = storedTokens.lastOrNull() ?: storedToken,
            newTokenWouldBeStoredInPlaintext = newTokenWouldBeStoredInPlaintext,
        )
    }

    override fun storeToken(token: String) {
        storedTokens += token
    }
}
