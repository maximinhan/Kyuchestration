package com.kyuchestration.desktop.workdir.kyucli

import com.kyuchestration.desktop.workdir.RepoSnapshot
import com.kyuchestration.desktop.workdir.RepoState
import com.kyuchestration.desktop.workdir.RepoTask
import com.kyuchestration.desktop.workdir.WorkDirObservationFailure
import com.kyuchestration.desktop.workdir.WorkDirSnapshot
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * 이 앱이 읽을 줄 아는 `kyu list --json` 문서의 판.
 *
 * 계약(internal/cli/list_json.go)은 필드가 늘어도 이 수를 올리지 않고, 필드가 사라지거나 뜻이
 * 바뀔 때만 올린다. 그래서 이 수가 다르면 "모르는 문서" 이고, 짐작으로 읽어 화면에 반쯤 맞는
 * 값을 올리는 것보다 멈추는 편이 낫다.
 */
private const val SUPPORTED_SCHEMA_VERSION = 1

private val kyuListJson = Json {
    // 판을 올리지 않고 필드가 느는 것이 계약이 허용한 변경이다. 모르는 필드에서 멈추면
    // kyu 가 한 줄 더 내보내는 순간 GUI 가 통째로 죽는다.
    ignoreUnknownKeys = true
}

/**
 * `kyu list --json` 의 stdout 한 벌을 스냅샷으로 옮긴다.
 */
internal fun parseKyuListOutput(rawJson: String): WorkDirSnapshot {
    val document = try {
        val root = kyuListJson.parseToJsonElement(rawJson)

        // 판을 먼저 본다. 문서 전체를 먼저 읽으면, 판이 올라가며 사라진 필드가 "읽을 수 없는
        // 출력" 으로 보고돼 진짜 이유(판이 다르다)가 가려진다.
        val schemaVersion = kyuListJson.decodeFromJsonElement<KyuListSchemaProbe>(root).schemaVersion
        if (schemaVersion != SUPPORTED_SCHEMA_VERSION) {
            throw WorkDirObservationFailure.UnsupportedSchemaVersion(schemaVersion, SUPPORTED_SCHEMA_VERSION)
        }

        kyuListJson.decodeFromJsonElement<KyuListDocument>(root)
    } catch (failure: SerializationException) {
        throw WorkDirObservationFailure.UnreadableKyuOutput(failure)
    }

    return document.toWorkDirSnapshot()
}

/**
 * 판만 떼어 읽기 위한 최소한의 모양.
 */
@Serializable
private data class KyuListSchemaProbe(val schemaVersion: Int)

@Serializable
private data class KyuListDocument(
    val workDir: KyuListWorkDir,
    val repos: List<KyuListRepo>,
    val mainSession: KyuListMainSession,
    val planWarnings: List<String>,
)

@Serializable
private data class KyuListWorkDir(val name: String, val absolutePath: String)

@Serializable
private data class KyuListRepo(
    val name: String,
    val absolutePath: String,
    val state: String,
    val task: KyuListTask?,
    val doneTaskCount: Int,
    val totalTaskCount: Int,
)

@Serializable
private data class KyuListTask(val id: String, val status: String, val blockedBy: List<String>)

@Serializable
private data class KyuListMainSession(val alive: Boolean)

private fun KyuListDocument.toWorkDirSnapshot(): WorkDirSnapshot = WorkDirSnapshot(
    name = workDir.name,
    absolutePath = workDir.absolutePath,
    repos = repos.map { it.toRepoSnapshot() },
    mainSessionAlive = mainSession.alive,
    planWarnings = planWarnings,
)

private fun KyuListRepo.toRepoSnapshot(): RepoSnapshot = RepoSnapshot(
    name = name,
    absolutePath = absolutePath,
    state = RepoState.ofRawValue(state),
    task = task?.let { RepoTask(id = it.id, status = it.status, blockedBy = it.blockedBy) },
    doneTaskCount = doneTaskCount,
    totalTaskCount = totalTaskCount,
)
