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
    /**
     * 없으면 "이어갈 대화 없음" 으로 읽는다.
     *
     * 기본값을 두는 것이 판 규약의 나머지 반쪽이다. 계약은 필드가 늘어도 판을 올리지 않으므로,
     * 이 앱이 그 필드를 더하기 전의 kyu 와 만나는 일이 실제로 있다 — 엔진과 앱은 따로 배포된다.
     * 기본값이 없으면 그때 목록이 통째로 "읽을 수 없는 출력" 이 된다.
     */
    val hasRecordedConversation: Boolean = false,
)

@Serializable
private data class KyuListTask(val id: String, val status: String, val blockedBy: List<String>)

/**
 * `alive` 는 읽지 않는다. 계약은 그 필드를 그대로 두지만 세션을 띄우는 것이 앱뿐이라 값이 늘 거짓이고,
 * 무엇이 돌고 있는지는 앱이 자기 보유 목록에서 답한다. 읽어 두기만 하면 화면이 언젠가 그 값을 쓰게
 * 되고, 그때 카드는 "엔진이 모르는 사실" 을 엔진에게 물은 셈이 된다.
 */
@Serializable
private data class KyuListMainSession(
    /** 레포의 같은 이름 필드와 같은 이유로 기본값을 둔다 — 뒤처진 엔진이 이 필드를 내지 않는다. */
    val hasRecordedConversation: Boolean = false,
)

private fun KyuListDocument.toWorkDirSnapshot(): WorkDirSnapshot = WorkDirSnapshot(
    name = workDir.name,
    absolutePath = workDir.absolutePath,
    repos = repos.map { it.toRepoSnapshot() },
    mainSessionHasRecordedConversation = mainSession.hasRecordedConversation,
    planWarnings = planWarnings,
)

private fun KyuListRepo.toRepoSnapshot(): RepoSnapshot = RepoSnapshot(
    name = name,
    absolutePath = absolutePath,
    state = RepoState.ofRawValue(state),
    task = task?.let { RepoTask(id = it.id, status = it.status, blockedBy = it.blockedBy) },
    doneTaskCount = doneTaskCount,
    totalTaskCount = totalTaskCount,
    hasRecordedConversation = hasRecordedConversation,
)
