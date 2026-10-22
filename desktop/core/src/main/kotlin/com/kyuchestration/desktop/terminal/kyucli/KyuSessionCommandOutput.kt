package com.kyuchestration.desktop.terminal.kyucli

import com.kyuchestration.desktop.kyu.KyuDocumentFailure
import com.kyuchestration.desktop.kyu.readKyuJsonDocument
import com.kyuchestration.desktop.terminal.AssignedChildSession
import com.kyuchestration.desktop.terminal.ChildSessionCommandAnswer
import com.kyuchestration.desktop.terminal.ChildSessionGate
import com.kyuchestration.desktop.terminal.GatedExecutionConfig
import com.kyuchestration.desktop.terminal.SessionCommandAnswer
import com.kyuchestration.desktop.terminal.TerminalSessionFailure
import java.nio.file.Path
import kotlinx.serialization.Serializable

/**
 * 이 앱이 읽을 줄 아는 `kyu session-command --json` 문서의 판.
 *
 * 계약(internal/cli/session_command_json.go)은 필드가 늘어도 이 수를 올리지 않고, 필드가 사라지거나
 * 뜻이 바뀔 때만 올린다. 그 규약 덕에 3 단계가 resume 실패를 가려낼 필드를 나중에 더해도 이미 도는
 * 앱이 깨지지 않는다.
 *
 * 판을 세는 자리를 `kyu list` 와 따로 둔다. 판은 명령마다 따로 세므로(json_output.go 의 판 규약),
 * 한 수를 함께 쓰면 한쪽 문서의 판이 오르는 날 다른 문서까지 읽기를 멈춘다.
 */
private const val SUPPORTED_SCHEMA_VERSION = 1

/**
 * `kyu session-command --json` 의 stdout 한 벌을 엔진의 답으로 옮긴다.
 *
 * 이 문서는 다른 기계용 문서와 무게가 다르다 — 다른 것들은 화면에 그릴 사실이지만 이것은 앱이
 * 그대로 실행할 것이다. 필드를 잘못 읽으면 화면이 어긋나는 것이 아니라 엉뚱한 디렉토리에서
 * 세션이 열린다.
 */
internal fun parseKyuSessionCommandOutput(rawJson: String, engineWarnings: List<String>): SessionCommandAnswer =
    readSessionCommandDocument(rawJson).asSessionCommandAnswer(engineWarnings)

/**
 * `kyu session-command --chat --child … --json` 의 stdout 을 하위 세션의 답으로 옮긴다.
 *
 * 같은 문서에 두 필드가 더해진 모양이다(session_command_json.go). 둘 중 하나는 반드시 있다 — 둘 다 없으면
 * 이 필드를 모르는 엔진이 사람 세션의 답을 한 것이라, 그 명령을 하위 자리에 띄우지 않고 읽지 못한 답으로 올린다.
 */
internal fun parseKyuChildSessionCommandOutput(rawJson: String, engineWarnings: List<String>): ChildSessionCommandAnswer {
    val document = readSessionCommandDocument(rawJson)

    document.approvalRequired?.let { gate ->
        return ChildSessionCommandAnswer.ApprovalRequired(
            ChildSessionGate(
                relativeCwd = gate.cwd,
                fingerprint = gate.fingerprint,
                executionConfigs = gate.executionConfigs.map {
                    GatedExecutionConfig(it.relativePath, it.absolutePath, it.content)
                },
            ),
        )
    }

    val childSession = document.childSession
        ?: throw TerminalSessionFailure.UnreadableKyuOutput(
            IllegalStateException("하위 세션을 물었는데 답에 childSession 도 approvalRequired 도 없습니다"),
        )

    return ChildSessionCommandAnswer.Ready(
        sessionCommandAnswer = document.asSessionCommandAnswer(engineWarnings),
        assignedChildSession = AssignedChildSession(
            childId = childSession.id,
            relativeCwd = childSession.cwd,
            title = childSession.title,
            conversationId = childSession.conversationId,
        ),
    )
}

private fun readSessionCommandDocument(rawJson: String): KyuSessionCommandDocument = try {
    readKyuJsonDocument(rawJson, SUPPORTED_SCHEMA_VERSION, KyuSessionCommandDocument.serializer())
} catch (failure: KyuDocumentFailure) {
    // 문서를 읽는 쪽은 사실만 던진다. 그 사실을 세션에 들어가려던 사람에게 할 말로 옮기는
    // 것이 이 어댑터의 몫이다 — 관찰 어댑터가 같은 선에서 하는 일이다.
    throw when (failure) {
        is KyuDocumentFailure.UnsupportedSchemaVersion -> TerminalSessionFailure.UnsupportedSchemaVersion(
            actualSchemaVersion = failure.actualSchemaVersion,
            supportedSchemaVersion = failure.supportedSchemaVersion,
        )

        is KyuDocumentFailure.Unreadable -> TerminalSessionFailure.UnreadableKyuOutput(failure.parseFailure)
    }
}

private fun KyuSessionCommandDocument.asSessionCommandAnswer(engineWarnings: List<String>) = SessionCommandAnswer(
    command = command,
    workingDirectory = Path.of(cwd),
    environmentToAdd = env,
    resumedConversationId = resumedConversationId,
    engineWarnings = engineWarnings,
)

@Serializable
private data class KyuSessionCommandDocument(
    val command: List<String>,
    val cwd: String,
    /** 더할 것이 없어도 엔진은 null 이 아니라 빈 객체로 답한다 — "없음" 이 한 모양이다. */
    val env: Map<String, String>,
    /**
     * 이어간 대화의 ID. 새 대화면 엔진이 null 로 답한다.
     *
     * 기본값을 두는 이유는 뒤처진 엔진이다. 계약은 필드가 늘어도 판을 올리지 않으므로, 이 필드를
     * 내지 않는 kyu 와 만나는 일이 실제로 있다 — 그때 세션이 아예 열리지 않으면 안 된다.
     * 그 앱이 잃는 것은 이어가기 실패를 가려내는 눈 하나뿐이고, 그것은 이 필드가 생기기 전의
     * 처지와 같다.
     */
    val resumedConversationId: String? = null,
    /** 하위 세션 답(`--child`)에서만 있다. 다른 답에서는 null 이거나, 이 필드를 모르는 엔진이면 아예 없다. */
    val childSession: KyuChildSessionDocument? = null,
    /** 하위 세션 답이 관문에 걸렸을 때만 있다(6.9). 그때 [command] 는 빈 배열이다. */
    val approvalRequired: KyuApprovalRequiredDocument? = null,
)

@Serializable
private data class KyuChildSessionDocument(
    val id: String,
    val cwd: String,
    val title: String,
    val conversationId: String,
)

@Serializable
private data class KyuApprovalRequiredDocument(
    val cwd: String,
    val fingerprint: String,
    val executionConfigs: List<KyuExecutionConfigDocument>,
)

@Serializable
private data class KyuExecutionConfigDocument(
    val relativePath: String,
    val absolutePath: String,
    val content: String,
)
