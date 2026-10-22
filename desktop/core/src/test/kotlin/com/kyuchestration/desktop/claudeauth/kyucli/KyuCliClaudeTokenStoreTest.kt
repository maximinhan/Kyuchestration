package com.kyuchestration.desktop.claudeauth.kyucli

import com.kyuchestration.desktop.claudeauth.ClaudeTokenStoreFailure
import com.kyuchestration.desktop.kyu.KyuCommandResult
import com.kyuchestration.desktop.kyu.RecordingKyuCommandRunner
import com.kyuchestration.desktop.kyu.succeedingKyuCommandResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 앱이 엔진에게 claude 토큰을 맡기고 찾아오는 규칙.
 *
 * 진짜 저장소 왕복은 엔진이 자기 시험에서 확인한다(claude_token_store_test.go). 여기서 보는 것은
 * 그 위의 계약이다 — 어떤 인자로 부르는가, 토큰이 어느 통로로 가는가, 실패를 어떻게 옮기는가.
 */
class KyuCliClaudeTokenStoreTest {

    @Test
    fun `맡긴 것이 없다고 답하면 값을 꺼내러 가지 않는다`() {
        // 곧바로 token 을 부르면 "없다" 와 "저장소가 고장났다" 가 같은 종료 코드로 돌아온다.
        val runner = RecordingKyuCommandRunner { statusDocument(stored = false, storageForNewToken = "keychain") }

        val credential = KyuCliClaudeTokenStore(runner).storedCredential()

        assertNull(credential.token)
        assertEquals(listOf(listOf("claude-auth", "status", "--json")), runner.receivedArguments)
    }

    @Test
    fun `맡긴 것이 있으면 값을 그대로 가져온다`() {
        val runner = RecordingKyuCommandRunner { arguments ->
            when {
                "status" in arguments -> statusDocument(stored = true, storageForNewToken = "keychain")
                else -> succeedingKyuCommandResult(STORED_TOKEN)
            }
        }

        val credential = KyuCliClaudeTokenStore(runner).storedCredential()

        // 다듬지 않는다. 엔진은 개행 한 글자도 덧붙이지 않기로 계약했고, 여기서 손대기 시작하면
        // "무엇이 저장됐는가" 와 "무엇을 실어 보내는가" 가 갈릴 자리가 생긴다.
        assertEquals(STORED_TOKEN, credential.token)
    }

    @Test
    fun `평문 파일에 저장되는 머신을 화면이 알 수 있게 답한다`() {
        val runner = RecordingKyuCommandRunner { statusDocument(stored = false, storageForNewToken = "file") }

        assertTrue(KyuCliClaudeTokenStore(runner).storedCredential().newTokenWouldBeStoredInPlaintext)
    }

    @Test
    fun `키체인이 있는 머신에서는 평문 경고를 내지 않는다`() {
        val runner = RecordingKyuCommandRunner { statusDocument(stored = false, storageForNewToken = "secret-service") }

        assertEquals(false, KyuCliClaudeTokenStore(runner).storedCredential().newTokenWouldBeStoredInPlaintext)
    }

    @Test
    fun `토큰은 인자가 아니라 stdin 으로 간다`() {
        // 인자는 같은 머신의 다른 사용자가 ps 로 읽는다.
        val runner = RecordingKyuCommandRunner { succeedingKyuCommandResult("") }

        KyuCliClaudeTokenStore(runner).storeToken(STORED_TOKEN)

        assertEquals(listOf(listOf("claude-auth", "set", "--json")), runner.receivedArguments)
        assertEquals(listOf<String?>(STORED_TOKEN), runner.receivedStandardInputs)
    }

    @Test
    fun `저장돼 있다고 해 놓고 빈 값을 주면 실패로 끝난다`() {
        // 그대로 넘기면 앱이 빈 문자열을 세션 환경에 실어, claude 가 "자격 증명이 있는데
        // 거절당했다"(401)로 끝난다 — 진짜 이유는 어디에도 뜨지 않는다.
        val runner = RecordingKyuCommandRunner { arguments ->
            when {
                "status" in arguments -> statusDocument(stored = true, storageForNewToken = "file")
                else -> succeedingKyuCommandResult("")
            }
        }

        assertFailsWith<ClaudeTokenStoreFailure> { KyuCliClaudeTokenStore(runner).storedCredential() }
    }

    @Test
    fun `엔진이 거절하면 그 말을 그대로 올린다`() {
        val runner = RecordingKyuCommandRunner {
            KyuCommandResult(exitCode = 1, standardOutput = "", standardError = "설정 디렉토리를 찾지 못했습니다")
        }

        val failure = assertFailsWith<ClaudeTokenStoreFailure> { KyuCliClaudeTokenStore(runner).storedCredential() }

        assertTrue(
            failure.message.orEmpty().contains("설정 디렉토리를 찾지 못했습니다"),
            "실패 문구 = ${failure.message}, kyu 가 적은 이유를 그대로 싣기를 기대",
        )
    }

    @Test
    fun `모르는 판의 문서는 읽지 않는다`() {
        // 반쯤 알아듣고 나아가면 "맡긴 것이 없다" 로 보여, 이미 등록한 사용자에게 인증 화면이 뜬다.
        val runner = RecordingKyuCommandRunner {
            succeedingKyuCommandResult("""{"schemaVersion": 2, "stored": true, "storageForNewToken": "keychain"}""")
        }

        assertFailsWith<ClaudeTokenStoreFailure> { KyuCliClaudeTokenStore(runner).storedCredential() }
    }
}

private const val STORED_TOKEN = "sk-ant-oat01-맡아-둔-가짜"

private fun statusDocument(stored: Boolean, storageForNewToken: String) = succeedingKyuCommandResult(
    """
    {
      "schemaVersion": 1,
      "stored": $stored,
      "storage": "${if (stored) storageForNewToken else ""}",
      "storageForNewToken": "$storageForNewToken"
    }
    """.trimIndent(),
)
