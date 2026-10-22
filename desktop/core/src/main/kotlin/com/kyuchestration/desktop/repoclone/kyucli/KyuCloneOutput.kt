package com.kyuchestration.desktop.repoclone.kyucli

import com.kyuchestration.desktop.kyu.readKyuJsonDocument
import com.kyuchestration.desktop.repoclone.CloneOutcome
import com.kyuchestration.desktop.repoclone.CloneStatus
import com.kyuchestration.desktop.repoclone.RepositoryCloneResult
import kotlinx.serialization.Serializable

/** 이 앱이 읽을 줄 아는 `kyu clone --json` 문서의 판. */
private const val SUPPORTED_CLONE_SCHEMA_VERSION = 1

/**
 * `kyu clone --json` 의 stdout 한 벌을 클론 결과로 옮긴다.
 */
internal fun parseKyuCloneOutput(rawJson: String): CloneOutcome =
    readKyuJsonDocument(rawJson, SUPPORTED_CLONE_SCHEMA_VERSION, KyuCloneDocument.serializer())
        .let { document ->
            CloneOutcome(
                results = document.results.map {
                    RepositoryCloneResult(
                        repositoryFullName = it.repo,
                        status = CloneStatus.ofRawValue(it.status),
                        message = it.message,
                    )
                },
            )
        }

/**
 * `mainSessionRestartNeeded` 는 읽지 않는다. 엔진이 자기가 띄운 메인 세션을 들여다보고 답하던
 * 값인데, 세션을 띄우는 것이 앱뿐이라 엔진에게는 볼 세션이 없다. 필드를 계속 읽으면 앱은
 * "언제나 거짓" 을 물어보느라 한 번 더 왕복하는 셈이고, 그 답이 사라지는 날 목록이 통째로
 * 읽히지 않게 된다.
 */
@Serializable
private data class KyuCloneDocument(
    val results: List<KyuCloneResult>,
)

@Serializable
private data class KyuCloneResult(val repo: String, val status: String, val message: String)
