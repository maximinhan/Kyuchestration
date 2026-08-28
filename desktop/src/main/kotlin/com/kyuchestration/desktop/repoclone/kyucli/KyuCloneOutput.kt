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
                mainSessionRestartNeeded = document.mainSessionRestartNeeded,
            )
        }

@Serializable
private data class KyuCloneDocument(
    val results: List<KyuCloneResult>,
    val mainSessionRestartNeeded: Boolean,
)

@Serializable
private data class KyuCloneResult(val repo: String, val status: String, val message: String)
