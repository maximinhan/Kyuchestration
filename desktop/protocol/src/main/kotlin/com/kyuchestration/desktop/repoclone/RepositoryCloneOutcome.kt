package com.kyuchestration.desktop.repoclone

import com.kyuchestration.desktop.protocol.RawValueSerializer
import kotlinx.serialization.Serializable

/**
 * 클론 한 번의 결과 전부.
 *
 * 세션에 대한 것은 담지 않는다. 문서의 `mainSessionRestartNeeded` 를 읽던 자리였는데, 그것은
 * 엔진이 자기가 띄운 메인 세션을 들여다보고 답하던 값이다. 세션을 띄우는 것이 앱뿐인 지금
 * 그 사실을 아는 것도 앱이라, 화면이 자기 보유 목록에서 직접 답한다.
 */
@Serializable
data class CloneOutcome(
    val results: List<RepositoryCloneResult>,
)

/**
 * 레포 하나가 어떻게 끝났는지.
 *
 * @param repositoryFullName 시킬 때 넘긴 그대로. 앱이 자기가 보낸 줄과 이 결과를 맞추는 열쇠다.
 * @param message 왜 그렇게 끝났는지. 받아 온 레포에서는 빈 문자열이다.
 */
@Serializable
data class RepositoryCloneResult(
    val repositoryFullName: String,
    val status: CloneStatus,
    val message: String,
)

/**
 * 레포 하나의 끝. 화면에서 색과 기호가 갈리므로 낱말이 아니라 타입으로 좁힌다.
 *
 * 모르는 낱말에서 멈추지 않는 것은 RepoState 와 같은 이유다 — GUI 가 엔진보다 늦게 배포되는 일은
 * 흔하고, 그때 결과를 통째로 못 보여주는 것보다 낯선 낱말 하나를 그대로 보여주는 편이 낫다.
 */
@Serializable(with = CloneStatusAsRawValueSerializer::class)
sealed interface CloneStatus {

    /** kyu 문서에 실려 온 낱말. */
    val rawValue: String

    data object Cloned : CloneStatus {
        override val rawValue: String = "cloned"
    }

    /** 같은 이름의 디렉토리가 이미 있어 받지 않았다. 실패가 아니다. */
    data object Skipped : CloneStatus {
        override val rawValue: String = "skipped"
    }

    data object Failed : CloneStatus {
        override val rawValue: String = "failed"
    }

    data class Unrecognized(override val rawValue: String) : CloneStatus

    companion object {
        fun ofRawValue(rawValue: String): CloneStatus = when (rawValue) {
            Cloned.rawValue -> Cloned
            Skipped.rawValue -> Skipped
            Failed.rawValue -> Failed
            else -> Unrecognized(rawValue)
        }
    }
}

internal object CloneStatusAsRawValueSerializer :
    RawValueSerializer<CloneStatus>("CloneStatus", CloneStatus::rawValue, CloneStatus::ofRawValue)
