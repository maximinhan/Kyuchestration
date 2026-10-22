package com.kyuchestration.desktop.claudeauth.kyucli

import com.kyuchestration.desktop.claudeauth.ClaudeTokenStore
import com.kyuchestration.desktop.claudeauth.ClaudeTokenStoreFailure
import com.kyuchestration.desktop.claudeauth.StoredClaudeCredential
import com.kyuchestration.desktop.kyu.KyuCommandFailure
import com.kyuchestration.desktop.kyu.KyuCommandResult
import com.kyuchestration.desktop.kyu.KyuCommandRunner
import com.kyuchestration.desktop.kyu.KyuDocumentFailure
import com.kyuchestration.desktop.kyu.readKyuJsonDocument
import kotlinx.serialization.Serializable

/**
 * kyu CLI 를 엔진으로 불러 claude 토큰을 맡기고 찾아온다.
 *
 * `KyuCliTokenProfileRegistry` 와 같은 자세다 — 앱은 토큰을 저장하지도 읽지도 않고, 어디에
 * 놓을지 고르는 규칙은 엔진의 secretstore 안에 있다. 다른 것은 다루는 비밀이 GitHub 이 아니라
 * claude 라는 것이고, 그래서 부르는 명령도 `auth` 가 아니라 `claude-auth` 다.
 */
class KyuCliClaudeTokenStore(private val kyuCommandRunner: KyuCommandRunner) : ClaudeTokenStore {

    /**
     * 상태를 먼저 묻고, 맡긴 것이 있다고 할 때만 값을 꺼낸다.
     *
     * 곧바로 `token` 을 부르지 않는 이유: 엔진은 "맡긴 것이 없다" 도 "저장소가 고장났다" 도
     * 종료 코드 1 로 답한다. 상태를 먼저 물으면 그 둘이 갈린다 — 앞쪽에서 화면이 할 일은
     * 인증을 이끄는 것이고, 뒤쪽에서 할 일은 이유를 보여주는 것이다.
     */
    override fun storedCredential(): StoredClaudeCredential {
        val status = readStatusDocument()

        return StoredClaudeCredential(
            token = if (status.stored) storedToken() else null,
            newTokenWouldBeStoredInPlaintext = status.storageForNewToken == PLAINTEXT_FILE_STORAGE_CODE,
        )
    }

    override fun storeToken(token: String) {
        val result = run(
            arguments = listOf("claude-auth", "set", "--json"),
            // 토큰은 인자가 아니라 stdin 으로 간다. 인자는 같은 머신의 다른 사용자가 ps 로 읽는다.
            standardInput = token,
        )
        if (result.exitCode != 0) {
            throw ClaudeTokenStoreFailure(refusalMessage("claude-auth set", result))
        }
    }

    /**
     * 값을 꺼낸다. 나온 바이트가 곧 맡긴 값이다 — 다듬지 않는다.
     *
     * `trim()` 을 걸지 않는 것이 뜻이다. 엔진은 개행 한 글자도 덧붙이지 않기로 계약했고
     * (claude_auth.go), 여기서 다듬기 시작하면 "무엇이 저장됐는가" 와 "무엇을 실어 보내는가" 가
     * 갈릴 자리가 하나 생긴다.
     */
    private fun storedToken(): String {
        val result = run(listOf("claude-auth", "token"))
        if (result.exitCode != 0) {
            throw ClaudeTokenStoreFailure(refusalMessage("claude-auth token", result))
        }
        if (result.standardOutput.isEmpty()) {
            // 저장돼 있다고 답해 놓고 빈 값을 준 경우다. 그대로 넘기면 앱이 빈 문자열을 세션
            // 환경에 실어, claude 가 "자격 증명이 있는데 거절당했다"(401)로 끝난다.
            throw ClaudeTokenStoreFailure("kyu 가 저장돼 있다고 답한 뒤 빈 토큰을 냈습니다.")
        }
        return result.standardOutput
    }

    private fun readStatusDocument(): KyuClaudeAuthStatusDocument {
        val result = run(listOf("claude-auth", "status", "--json"))
        if (result.exitCode != 0) {
            throw ClaudeTokenStoreFailure(refusalMessage("claude-auth status", result))
        }

        return try {
            readKyuJsonDocument(
                result.standardOutput,
                SUPPORTED_CLAUDE_AUTH_STATUS_SCHEMA_VERSION,
                KyuClaudeAuthStatusDocument.serializer(),
            )
        } catch (failure: KyuDocumentFailure) {
            throw ClaudeTokenStoreFailure(failure.message.orEmpty(), failure)
        }
    }

    private fun run(arguments: List<String>, standardInput: String? = null): KyuCommandResult = try {
        kyuCommandRunner.run(arguments, standardInput = standardInput)
    } catch (failure: KyuCommandFailure) {
        throw ClaudeTokenStoreFailure(failure.message.orEmpty(), failure)
    }

    /**
     * kyu 는 거절 이유를 stderr 에 사람 말로 적는다. 그것을 그대로 올리는 편이 여기서 다시
     * 지어낸 문구보다 정확하다. 토큰은 그 스트림에 실리지 않는다(claude_auth_test.go 가 고정한다).
     */
    private fun refusalMessage(commandDescription: String, result: KyuCommandResult): String =
        result.standardError.trim().ifBlank { "kyu $commandDescription 가 종료 코드 ${result.exitCode} 로 끝났습니다." }
}

/** 이 앱이 읽을 줄 아는 `kyu claude-auth status --json` 문서의 판. */
private const val SUPPORTED_CLAUDE_AUTH_STATUS_SCHEMA_VERSION = 1

/**
 * 평문 파일에 저장된다는 뜻의 코드. 엔진의 StorageKind 와 같은 글자다(store.go).
 *
 * 사람용 문구("설정 파일 (평문, 권한 0600)")로 분기하지 않는다. 그것은 언제든 다듬을 수 있는
 * 화면의 말인데, 여기서 그것으로 갈래를 타면 문구를 고치는 순간 경고가 조용히 사라진다.
 */
private const val PLAINTEXT_FILE_STORAGE_CODE = "file"

@Serializable
private data class KyuClaudeAuthStatusDocument(
    val stored: Boolean,
    val storageForNewToken: String,
)
