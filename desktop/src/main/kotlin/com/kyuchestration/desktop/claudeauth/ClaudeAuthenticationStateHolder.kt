package com.kyuchestration.desktop.claudeauth

import com.kyuchestration.desktop.diagnostics.DiagnosticLog
import com.kyuchestration.desktop.diagnostics.DiagnosticLogEntry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 이 머신의 claude 가 로그인돼 있는지 보고, 아니면 로그인시키는 걸음을 쥔 자리. Compose 를 모른다.
 *
 * `EngineInstallationStateHolder` 옆에 선다 — 앱의 두 번째 갈림길이고, 하는 일의 모양도 같다:
 * 있는지 보고, 없으면 그 자리에서 걸음을 이끈다.
 *
 * **맡아 둔 토큰을 이 홀더가 들고 있는다.** 세션을 열 때마다 저장소에 다시 묻지 않기 위해서다 —
 * 세션 진입은 사용자가 카드를 누르는 자리라 프로세스 하나가 더 붙는 것이 그대로 지연이 되고,
 * 저장소의 답은 이 화면을 지나기 전에는 바뀌지 않는다.
 */
class ClaudeAuthenticationStateHolder(
    private val claudeCliAuthentication: ClaudeCliAuthentication,
    private val claudeTokenStore: ClaudeTokenStore,
    private val coroutineScope: CoroutineScope,
    private val diagnosticLog: DiagnosticLog = DiagnosticLog.Discarding,
    /**
     * 저장소에 묻고 맡기는 일이 나가는 자리.
     *
     * 이 홀더가 받는 범위는 화면을 그리는 스레드다. 저장소 포트는 멈춰 서서 답하는 자리라
     * ([ClaudeTokenStore] 뒤에 kyu 프로세스가 있다) 그대로 부르면 그 몇 백 밀리초 동안 창이 멎는다.
     * claude 에게 묻는 쪽은 자기 어댑터 안에서 이미 나가므로 여기서 감싸지 않는다.
     */
    private val tokenStoreDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val mutableState = MutableStateFlow<ClaudeAuthenticationState>(
        ClaudeAuthenticationState.CheckingCredentials,
    )

    val state: StateFlow<ClaudeAuthenticationState> = mutableState.asStateFlow()

    private val mutableTokenForSessions = MutableStateFlow<String?>(null)

    /**
     * 세션의 claude 에게 물려줄 토큰. 맡아 둔 것이 없으면 null 이다.
     *
     * null 인 것이 정상이다 — 브라우저로 로그인한 사용자는 claude 자신이 자격 증명을 챙기므로
     * 앱이 실어 보낼 것이 없다(chat-ui-design.md 7.3 가).
     */
    val tokenForSessions: StateFlow<String?> = mutableTokenForSessions.asStateFlow()

    /**
     * 지금 토큰을 맡기면 평문 파일에 놓이는가.
     *
     * 화면이 그리는 상태([ClaudeAuthenticationState.CredentialsMissing])에 실어 보내려고 들고 있는다.
     * 걸음 중간(로그인 진행·검증)에는 상태가 그것을 담지 않으므로, 되돌아올 때 쓸 값이 남아 있어야 한다.
     */
    private var tokenWouldBeStoredInPlaintext = false

    /** 지금 도는 브라우저 로그인. 코드를 넣을 상대이기도 하고, 취소할 상대이기도 하다. */
    private var runningBrowserLogin: ClaudeBrowserLogin? = null

    /**
     * 지금 로그인돼 있는지 claude 에게 묻는다.
     *
     * 앱을 띄운 직후와, 로그인 걸음이 끝날 때마다 지난다. **걸음의 성공을 걸음 자신이 선언하지
     * 않는 것**이 이 자리의 요점이다 — `claude auth login` 이 0 으로 끝났다는 사실과 이 머신의
     * claude 가 이제 답한다는 사실은 다른 것이고, 화면이 통과시켜야 하는 것은 뒤쪽이다.
     */
    fun checkCredentials() {
        mutableState.value = ClaudeAuthenticationState.CheckingCredentials

        coroutineScope.launch {
            val storedCredential = try {
                withContext(tokenStoreDispatcher) { claudeTokenStore.storedCredential() }
            } catch (failure: ClaudeTokenStoreFailure) {
                // 저장소가 답하지 않아도 로그인은 할 수 있다. 맡아 둔 것이 없는 것으로 보고 나아가되,
                // 왜 그런지는 기록에 남긴다 — 이 실패는 화면에 뜨지 않으면 어디에도 남지 않는다.
                diagnosticLog.record(DiagnosticLogEntry.ClaudeTokenStoreUnavailable(failure.message.orEmpty()))
                StoredClaudeCredential(token = null, newTokenWouldBeStoredInPlaintext = false)
            }

            tokenWouldBeStoredInPlaintext = storedCredential.newTokenWouldBeStoredInPlaintext
            mutableTokenForSessions.value = storedCredential.token

            mutableState.value = try {
                val credentialsPresent = claudeCliAuthentication.credentialsArePresent(storedCredential.token)
                diagnosticLog.record(
                    DiagnosticLogEntry.ClaudeCredentialsChecked(
                        credentialsPresent = credentialsPresent,
                        storedTokenUsed = storedCredential.token != null,
                    ),
                )

                when {
                    credentialsPresent -> ClaudeAuthenticationState.CredentialsReady
                    else -> credentialsMissing(lastFailure = null)
                }
            } catch (failure: ClaudeAuthenticationFailure) {
                stateFor(failure)
            }
        }
    }

    /**
     * 브라우저 로그인을 시작한다. 주소가 오면 화면이 그것을 보여주고 코드를 기다린다.
     */
    fun startBrowserLogin() {
        endRunningBrowserLogin()
        mutableState.value = ClaudeAuthenticationState.BrowserLoginStarting

        coroutineScope.launch {
            mutableState.value = try {
                val login = claudeCliAuthentication.startBrowserLogin()
                runningBrowserLogin = login
                ClaudeAuthenticationState.BrowserLoginWaitingForCode(login.authorizationUrl)
            } catch (failure: ClaudeAuthenticationFailure) {
                stateFor(failure)
            }
        }
    }

    /**
     * 브라우저에서 받아 온 코드를 넣는다.
     *
     * 도는 로그인이 없으면 아무것도 하지 않는다. 화면이 그 상태에서만 입력 칸을 보여주므로 보통은
     * 닿지 않지만, 닿았을 때 새 프로세스를 띄우면 그 프로세스는 이 코드와 짝이 맞지 않는다.
     */
    fun submitBrowserLoginCode(code: String) {
        val login = runningBrowserLogin ?: return
        mutableState.value = ClaudeAuthenticationState.BrowserLoginFinishing

        coroutineScope.launch {
            try {
                login.completeWithCode(code)
                runningBrowserLogin = null
                // 로그인이 남긴 자격 증명은 claude 자신의 것이라 앱이 저장할 것이 없다.
                // 정말 통하는지는 다시 물어서 확인한다.
                checkCredentials()
            } catch (failure: ClaudeAuthenticationFailure) {
                if (failure is ClaudeAuthenticationFailure.BrowserLoginRefused) {
                    diagnosticLog.record(DiagnosticLogEntry.ClaudeBrowserLoginFailed(failure.exitCode))
                }
                runningBrowserLogin = null
                mutableState.value = stateFor(failure)
            }
        }
    }

    /** 사용자가 브라우저 로그인을 그만둔다. 기다리던 claude 를 끝내고 고르는 자리로 되돌아간다. */
    fun cancelBrowserLogin() {
        endRunningBrowserLogin()
        mutableState.value = credentialsMissing(lastFailure = null)
    }

    /**
     * 붙여넣은 토큰으로 로그인한다 — 확인하고, 통하면 맡긴다.
     *
     * **확인이 먼저다.** 저장부터 하면 거절당한 토큰이 남아 다음 실행에서도 같은 실패가 반복되고,
     * 사용자는 자기가 넣은 것이 문제인지 앱이 문제인지 가리지 못한다(kyu auth add 가 같은 순서다).
     */
    fun useToken(token: String) {
        mutableState.value = ClaudeAuthenticationState.PastedTokenVerifying

        coroutineScope.launch {
            val verdict = try {
                claudeCliAuthentication.tokenIsAccepted(token)
            } catch (failure: ClaudeAuthenticationFailure) {
                mutableState.value = stateFor(failure)
                return@launch
            }

            if (verdict is ClaudeTokenVerdict.Refused) {
                diagnosticLog.record(DiagnosticLogEntry.ClaudePastedTokenRefused(verdict.claudeMessage))
                mutableState.value = credentialsMissing(
                    ClaudeAuthenticationFailure.PastedTokenRefused(verdict.claudeMessage),
                )
                return@launch
            }

            try {
                withContext(tokenStoreDispatcher) { claudeTokenStore.storeToken(token) }
            } catch (failure: ClaudeTokenStoreFailure) {
                diagnosticLog.record(DiagnosticLogEntry.ClaudeTokenStoreUnavailable(failure.message.orEmpty()))
                mutableState.value = credentialsMissing(
                    ClaudeAuthenticationFailure.TokenCouldNotBeStored(failure),
                )
                return@launch
            }

            checkCredentials()
        }
    }

    /**
     * 실패를 화면이 그릴 상태로 옮긴다.
     *
     * claude 자체를 띄우지 못한 것만 갈라 낸다. 그 자리에서 사용자가 할 일은 로그인이 아니라
     * 설치이고, 버튼을 보여주면 눌러도 같은 실패로 돌아온다.
     */
    private fun stateFor(failure: ClaudeAuthenticationFailure): ClaudeAuthenticationState {
        if (failure is ClaudeAuthenticationFailure.ClaudeCliCouldNotRun) {
            diagnosticLog.record(DiagnosticLogEntry.ClaudeCliCouldNotRun(failure.message.orEmpty()))
            return ClaudeAuthenticationState.ClaudeCliMissing(failure)
        }
        return credentialsMissing(failure)
    }

    private fun credentialsMissing(lastFailure: ClaudeAuthenticationFailure?) =
        ClaudeAuthenticationState.CredentialsMissing(
            lastFailure = lastFailure,
            tokenWouldBeStoredInPlaintext = tokenWouldBeStoredInPlaintext,
        )

    /**
     * 도는 로그인이 있으면 끝낸다.
     *
     * 남겨 두면 사용자가 [다시 시도] 를 누를 때마다 브라우저를 여는 claude 가 하나씩 쌓이고,
     * 그것들은 코드를 기다리며 영영 살아 있다.
     */
    private fun endRunningBrowserLogin() {
        runningBrowserLogin?.cancel()
        runningBrowserLogin = null
    }
}
