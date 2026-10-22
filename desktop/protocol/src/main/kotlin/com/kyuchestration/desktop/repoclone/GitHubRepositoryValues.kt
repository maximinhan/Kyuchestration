package com.kyuchestration.desktop.repoclone

import com.kyuchestration.desktop.protocol.InstantAsIsoStringSerializer
import com.kyuchestration.desktop.protocol.RawValueSerializer
import java.time.Instant
import kotlinx.serialization.Serializable

/**
 * 레포를 가진 GitHub 계정 하나.
 *
 * @param login `kyu repos list --owner` 와 `kyu clone --repo` 의 앞자리에 그대로 들어가는 값.
 */
@Serializable
data class RepositoryOwner(val login: String, val kind: OwnerKind)

/**
 * 계정의 종류. 화면에서 개인과 조직을 갈라 보여주는 데 쓴다.
 *
 * 참·거짓이 아니라 낱말로 둔다 — 계약이 그렇게 정한 이유와 같다(repos_json.go). 소유자의 종류가
 * 셋이 되는 날(GitHub 은 Enterprise 계정을 따로 센다) 참·거짓은 늘릴 수 없다.
 */
@Serializable(with = OwnerKindAsRawValueSerializer::class)
sealed interface OwnerKind {

    /** kyu 문서에 실려 온 코드. */
    val rawValue: String

    data object Personal : OwnerKind {
        override val rawValue: String = "user"
    }

    data object Organization : OwnerKind {
        override val rawValue: String = "org"
    }

    data class Unrecognized(override val rawValue: String) : OwnerKind

    companion object {
        fun ofRawValue(rawValue: String): OwnerKind = when (rawValue) {
            Personal.rawValue -> Personal
            Organization.rawValue -> Organization
            else -> Unrecognized(rawValue)
        }
    }
}

/**
 * GitHub 에 있는 레포 하나. 아직 이 머신에 없다.
 *
 * @param name 워크디렉토리에 만들어질 디렉토리 이름. 이미 그 이름이 있는지 대조하는 값이다.
 * @param fullName owner/name. 클론을 시킬 때 그대로 넘기는 값이라 앱이 두 값을 이어 붙이지 않는다.
 * @param lastUpdatedAt GitHub 이 이 레포를 마지막으로 갱신한 시각. 받지 못했으면 null 이다 —
 *   없는 것과 1970 년은 다른 사실이라 영 값으로 대신하지 않는다.
 */
@Serializable
data class RemoteRepository(
    val name: String,
    val fullName: String,
    val isPrivate: Boolean,
    @Serializable(with = InstantAsIsoStringSerializer::class)
    val lastUpdatedAt: Instant?,
)

internal object OwnerKindAsRawValueSerializer :
    RawValueSerializer<OwnerKind>("OwnerKind", OwnerKind::rawValue, OwnerKind::ofRawValue)
