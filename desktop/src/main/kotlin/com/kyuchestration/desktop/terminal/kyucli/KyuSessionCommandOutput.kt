package com.kyuchestration.desktop.terminal.kyucli

import com.kyuchestration.desktop.kyu.KyuDocumentFailure
import com.kyuchestration.desktop.kyu.readKyuJsonDocument
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
internal fun parseKyuSessionCommandOutput(rawJson: String): SessionCommandAnswer {
    val document = try {
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

    return SessionCommandAnswer(
        command = document.command,
        workingDirectory = Path.of(document.cwd),
        environmentToAdd = document.env,
    )
}

@Serializable
private data class KyuSessionCommandDocument(
    val command: List<String>,
    val cwd: String,
    /** 더할 것이 없어도 엔진은 null 이 아니라 빈 객체로 답한다 — "없음" 이 한 모양이다. */
    val env: Map<String, String>,
)
