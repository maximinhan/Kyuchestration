package com.kyuchestration.desktop.repoclone

import com.kyuchestration.desktop.protocol.RawValueSerializer
import kotlinx.serialization.Serializable

/**
 * 등록된 토큰 프로필 하나. 토큰 값은 여기 없다 — 목록은 화면에 뜬 채로 캡처되기 가장 쉬운 것이다.
 */
@Serializable
data class TokenProfile(val name: String, val storage: TokenStorage)

/**
 * 토큰이 실제로 놓인 자리.
 *
 * 화면에 보여주는 이유는 평문 파일이다. 키체인이 없는 머신에서는 토큰이 설정 파일에 그대로
 * 적히는데(secretstore 의 폴백), 사용자가 그 사실을 알고 쓰는 것과 모른 채 쓰는 것은 다르다.
 *
 * 낱말을 타입으로 좁히되 모르는 값에서 멈추지 않는다 — RepoState 와 같은 이유다. 저장 자리가
 * 하나 늘었다고 GUI 가 프로필 목록을 통째로 못 보여주는 편이 더 나쁘다.
 */
@Serializable(with = TokenStorageAsRawValueSerializer::class)
sealed interface TokenStorage {

    /** kyu 문서에 실려 온 코드. */
    val rawValue: String

    data object Keychain : TokenStorage {
        override val rawValue: String = "keychain"
    }

    data object SecretService : TokenStorage {
        override val rawValue: String = "secret-service"
    }

    data object ConfigFile : TokenStorage {
        override val rawValue: String = "file"
    }

    data class Unrecognized(override val rawValue: String) : TokenStorage

    companion object {
        fun ofRawValue(rawValue: String): TokenStorage = when (rawValue) {
            Keychain.rawValue -> Keychain
            SecretService.rawValue -> SecretService
            ConfigFile.rawValue -> ConfigFile
            else -> Unrecognized(rawValue)
        }
    }
}

/**
 * 방금 등록을 마친 프로필.
 *
 * @param gitHubLogin GitHub 이 확인해준 이 토큰의 주인. 이름만 돌려주면 사용자는 "저장됐다" 까지만
 *   알고 "무엇이 저장됐는지" 는 모른다 — 회사 토큰을 개인 프로필로 등록하는 것은 이 값을 화면에
 *   보여줘야만 그 자리에서 막힌다.
 */
@Serializable
data class RegisteredTokenProfile(val profileName: String, val gitHubLogin: String)

internal object TokenStorageAsRawValueSerializer :
    RawValueSerializer<TokenStorage>("TokenStorage", TokenStorage::rawValue, TokenStorage::ofRawValue)
