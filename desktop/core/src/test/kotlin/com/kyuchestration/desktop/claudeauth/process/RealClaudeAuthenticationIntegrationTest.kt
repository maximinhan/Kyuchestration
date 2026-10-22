package com.kyuchestration.desktop.claudeauth.process

import com.kyuchestration.desktop.claudeauth.ClaudeTokenVerdict
import com.kyuchestration.desktop.platform.childProcessEnvironment
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.abort

/**
 * 진짜 `claude` 에게 물어, 이 앱이 "로그인 안 됨" 과 "토큰이 거절당했다" 를 실제로 알아보는지 잰다.
 *
 * **사용자의 자격 증명은 건드리지 않는다.** `CLAUDE_CONFIG_DIR` 을 빈 임시 디렉토리로 돌려
 * "로그인한 적 없는 설치" 를 재현한다(chat-ui-design.md 부록 B). `claude auth logout` 은 이
 * 파일 어디에도 없다 — 그것을 부르면 재는 사람의 계정이 실제로 끊긴다.
 *
 * **성공 경로는 여기 없다.** 진짜 계정으로 로그인을 끝내는 것도, 통하는 토큰을 넣는 것도 사람의
 * 손과 사람의 계정이 있어야 하는 일이라 손으로 확인한다(PR 본문의 수동 항목).
 */
class RealClaudeAuthenticationIntegrationTest {

    private val emptyClaudeConfigDirectory = createTempDirectory("kyu-claude-auth-probe")

    @AfterTest
    fun 재현용_설정_디렉토리를_지운다() {
        emptyClaudeConfigDirectory.toFile().deleteRecursively()
    }

    @Test
    fun `자격 증명이 없는 설치를 미인증으로 알아본다`(): Unit = runBlocking {
        val authentication = authenticationAgainstEmptyConfigOrSkip()

        assertFalse(
            authentication.credentialsArePresent(tokenToInject = null),
            "빈 CLAUDE_CONFIG_DIR 인데 로그인됐다고 답했습니다",
        )
    }

    @Test
    fun `실려 온 토큰을 claude 가 자격 증명으로 본다`(): Unit = runBlocking {
        // 이 시험이 확인하는 것은 환경변수 이름 하나다. 이름이 틀리면 앱은 토큰을 맡아 두고도
        // 세션마다 "Not logged in" 을 만나는데, 그 어긋남은 화면 어디에도 뜨지 않는다.
        val authentication = authenticationAgainstEmptyConfigOrSkip()

        assertTrue(
            authentication.credentialsArePresent(tokenToInject = FAKE_TOKEN),
            "토큰을 실었는데 claude 가 자격 증명이 없다고 답했습니다 — 환경변수 이름이 어긋났습니다",
        )
    }

    @Test
    fun `통하지 않는 토큰은 거절로 떨어진다`(): Unit = runBlocking {
        // 위의 것과 짝이다. auth status 는 있는지만 보므로(7.3 나) 가짜 토큰에도 참을 답하고,
        // 통하는지는 이 한 턴만이 가른다.
        if (System.getenv(NETWORK_OPT_IN_VARIABLE) != "1") {
            abort<Unit>("$NETWORK_OPT_IN_VARIABLE=1 이 아니라 건너뜁니다 — 이 검증은 앤트로픽에 한 번 붙습니다")
        }
        val authentication = authenticationAgainstEmptyConfigOrSkip()

        val verdict = authentication.tokenIsAccepted(FAKE_TOKEN)

        val refused = assertIs<ClaudeTokenVerdict.Refused>(verdict, "가짜 토큰이 받아들여졌습니다")
        assertTrue(refused.claudeMessage.isNotBlank(), "거절 이유가 비어 있어 화면에 적을 것이 없습니다")
    }

    /**
     * 빈 설정 디렉토리를 겨눈 어댑터를 만든다. claude 가 없으면 이 검증을 건너뛴다.
     *
     * 바탕 환경을 이 앱이 실제로 쓰는 것에서 시작한다(childProcessEnvironment). PATH 를 새로
     * 지으면 "이 시험에서만 claude 를 찾는" 자리가 생겨, 앱이 못 찾는 결함을 여기서 놓친다.
     */
    private fun authenticationAgainstEmptyConfigOrSkip(): ProcessClaudeCliAuthentication {
        val installed = runCatching {
            ProcessBuilder(listOf("claude", "--version")).redirectErrorStream(true).start().waitFor() == 0
        }.getOrDefault(false)

        if (!installed) {
            abort<Unit>("claude 를 부르지 못해 건너뜁니다")
        }

        return ProcessClaudeCliAuthentication(
            baseEnvironment = childProcessEnvironment() +
                (CLAUDE_CONFIG_DIRECTORY_ENV_NAME to emptyClaudeConfigDirectory.toString()),
        )
    }
}

/** claude 가 자기 자격 증명과 전사를 두는 자리. 이 값을 돌려 실제 자격 증명을 비껴간다. */
private const val CLAUDE_CONFIG_DIRECTORY_ENV_NAME = "CLAUDE_CONFIG_DIR"

/**
 * 아무 계정에도 속하지 않는 값. 모양만 진짜 토큰을 닮게 둔다.
 *
 * 앤트로픽이 이것을 401 로 답하는 것이 이 시험의 판정 근거다.
 */
private const val FAKE_TOKEN = "sk-ant-oat01-kyu-integration-test-not-a-real-token"

/** 앤트로픽에 실제로 붙는 검증을 켜는 스위치. 망이 막힌 자리에서 빨개지지 않게 일부러 켠다. */
private const val NETWORK_OPT_IN_VARIABLE = "KYU_CLAUDE_AUTH_INTEGRATION"
