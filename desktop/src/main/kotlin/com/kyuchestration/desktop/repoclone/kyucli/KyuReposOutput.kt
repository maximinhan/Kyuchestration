package com.kyuchestration.desktop.repoclone.kyucli

import com.kyuchestration.desktop.kyu.KyuDocumentFailure
import com.kyuchestration.desktop.kyu.readKyuJsonDocument
import com.kyuchestration.desktop.repoclone.OwnerKind
import com.kyuchestration.desktop.repoclone.RemoteRepository
import com.kyuchestration.desktop.repoclone.RepositoryOwner
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 이 앱이 읽을 줄 아는 `kyu repos owners --json` 문서의 판. */
private const val SUPPORTED_REPOS_OWNERS_SCHEMA_VERSION = 1

/** 이 앱이 읽을 줄 아는 `kyu repos list --json` 문서의 판. */
private const val SUPPORTED_REPOS_LIST_SCHEMA_VERSION = 1

/**
 * `kyu repos owners --json` 의 stdout 한 벌을 계정 목록으로 옮긴다.
 */
internal fun parseKyuReposOwnersOutput(rawJson: String): List<RepositoryOwner> =
    readKyuJsonDocument(rawJson, SUPPORTED_REPOS_OWNERS_SCHEMA_VERSION, KyuReposOwnersDocument.serializer())
        .owners
        .map { RepositoryOwner(login = it.login, kind = OwnerKind.ofRawValue(it.kind)) }

/**
 * `kyu repos list --json` 의 stdout 한 벌을 레포 목록으로 옮긴다.
 *
 * 정렬하지 않는다. 계약이 github.com 의 repositories 탭과 같은 최근 갱신 순으로 주고, 그 순서는
 * 사용자가 "지금 작업하던 그 레포" 를 알아보는 단서다(repos_json.go).
 */
internal fun parseKyuReposListOutput(rawJson: String): List<RemoteRepository> =
    readKyuJsonDocument(rawJson, SUPPORTED_REPOS_LIST_SCHEMA_VERSION, KyuReposListDocument.serializer())
        .repos
        .map {
            RemoteRepository(
                name = it.name,
                fullName = it.fullName,
                isPrivate = it.isPrivate,
                lastUpdatedAt = it.updatedAt?.let(::parseUpdatedAt),
            )
        }

/**
 * 계약은 갱신 시각이 RFC3339 아니면 null 이라고 못 박았다(repos_json.go).
 *
 * 그 약속이 깨진 값을 흘려보내지 않는다. null 로 대신하면 화면에는 "갱신 시각 없음" 이 뜨는데,
 * 그것은 GitHub 이 주지 않았다는 뜻으로 이미 쓰이고 있는 표시라 두 사실이 한 얼굴이 된다.
 */
private fun parseUpdatedAt(rawValue: String): Instant = try {
    // 시간대가 붙은 채로 읽는다. 계약은 UTC 로 못 박았지만, 그것을 믿고 Z 를 잘라내는 파싱을 하면
    // 계약이 오프셋을 허용하는 쪽으로 넓어지는 날 조용히 몇 시간 어긋난 시각을 보여준다.
    OffsetDateTime.parse(rawValue).toInstant()
} catch (failure: DateTimeParseException) {
    throw KyuDocumentFailure.Unreadable(failure)
}

@Serializable
private data class KyuReposOwnersDocument(val owners: List<KyuReposOwner>)

@Serializable
private data class KyuReposOwner(val login: String, val kind: String)

@Serializable
private data class KyuReposListDocument(val repos: List<KyuReposListRepo>)

@Serializable
private data class KyuReposListRepo(
    val name: String,
    val fullName: String,
    // private 는 코틀린에서 이름으로 쓸 수 없다. 계약의 이름을 바꾸는 것이 아니라 이쪽 이름만 옮긴다.
    @SerialName("private") val isPrivate: Boolean,
    val updatedAt: String?,
)
