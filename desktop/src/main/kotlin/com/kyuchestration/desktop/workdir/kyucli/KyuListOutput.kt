package com.kyuchestration.desktop.workdir.kyucli

import com.kyuchestration.desktop.kyu.KyuDocumentFailure
import com.kyuchestration.desktop.kyu.readKyuJsonDocument
import com.kyuchestration.desktop.workdir.RepoSnapshot
import com.kyuchestration.desktop.workdir.RepoState
import com.kyuchestration.desktop.workdir.RepoTask
import com.kyuchestration.desktop.workdir.WorkDirObservationFailure
import com.kyuchestration.desktop.workdir.WorkDirSnapshot
import kotlinx.serialization.Serializable

/**
 * 이 앱이 읽을 줄 아는 `kyu list --json` 문서의 판.
 *
 * 계약(internal/cli/list_json.go)은 필드가 늘어도 이 수를 올리지 않고, 필드가 사라지거나 뜻이
 * 바뀔 때만 올린다. 그래서 이 수가 다르면 "모르는 문서" 이고, 짐작으로 읽어 화면에 반쯤 맞는
 * 값을 올리는 것보다 멈추는 편이 낫다.
 */
private const val SUPPORTED_SCHEMA_VERSION = 1

/**
 * `kyu list --json` 의 stdout 한 벌을 스냅샷으로 옮긴다.
 */
internal fun parseKyuListOutput(rawJson: String): WorkDirSnapshot {
    val document = try {
        readKyuJsonDocument(rawJson, SUPPORTED_SCHEMA_VERSION, KyuListDocument.serializer())
    } catch (failure: KyuDocumentFailure) {
        // 문서를 읽는 쪽은 "판이 다르다" 는 사실만 던진다. 그 사실을 목록을 보려던 사람에게 할
        // 말로 옮기는 것이 이 어댑터의 몫이다 — 실행기의 실패를 옮기는 것과 같은 선이다.
        throw when (failure) {
            is KyuDocumentFailure.UnsupportedSchemaVersion -> WorkDirObservationFailure.UnsupportedSchemaVersion(
                actualSchemaVersion = failure.actualSchemaVersion,
                supportedSchemaVersion = failure.supportedSchemaVersion,
            )

            is KyuDocumentFailure.Unreadable -> WorkDirObservationFailure.UnreadableKyuOutput(failure.parseFailure)
        }
    }

    return document.toWorkDirSnapshot()
}

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
