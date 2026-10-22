package com.kyuchestration.desktop.repoclone.kyucli

import com.kyuchestration.desktop.kyu.readKyuJsonDocument
import com.kyuchestration.desktop.repoclone.RegisteredTokenProfile
import com.kyuchestration.desktop.repoclone.TokenProfile
import com.kyuchestration.desktop.repoclone.TokenStorage
import kotlinx.serialization.Serializable

/**
 * 이 앱이 읽을 줄 아는 `kyu auth list --json` 문서의 판.
 *
 * 판은 명령마다 따로 센다(auth_json.go). 등록 문서와 목록 문서가 같은 파일에 있어도 두 수를
 * 따로 두는 이유가 그것이다 — 한쪽 계약이 바뀌었다고 다른 쪽을 읽지 못할 이유가 없다.
 */
private const val SUPPORTED_AUTH_LIST_SCHEMA_VERSION = 1

/** 이 앱이 읽을 줄 아는 `kyu auth add --json` 문서의 판. */
private const val SUPPORTED_AUTH_ADD_SCHEMA_VERSION = 1

/**
 * `kyu auth list --json` 의 stdout 한 벌을 프로필 목록으로 옮긴다.
 */
internal fun parseKyuAuthListOutput(rawJson: String): List<TokenProfile> =
    readKyuJsonDocument(rawJson, SUPPORTED_AUTH_LIST_SCHEMA_VERSION, KyuAuthListDocument.serializer())
        .profiles
        .map { TokenProfile(name = it.name, storage = TokenStorage.ofRawValue(it.storage)) }

/**
 * `kyu auth add --json` 의 stdout 한 벌을 등록 결과로 옮긴다.
 */
internal fun parseKyuAuthAddOutput(rawJson: String): RegisteredTokenProfile =
    readKyuJsonDocument(rawJson, SUPPORTED_AUTH_ADD_SCHEMA_VERSION, KyuAuthAddDocument.serializer())
        .let { RegisteredTokenProfile(profileName = it.profile, gitHubLogin = it.login) }

@Serializable
private data class KyuAuthListDocument(val profiles: List<KyuAuthListProfile>)

@Serializable
private data class KyuAuthListProfile(val name: String, val storage: String)

@Serializable
private data class KyuAuthAddDocument(val profile: String, val login: String)
