package com.kyuchestration.desktop.terminal.kyucli

import com.kyuchestration.desktop.kyu.KyuCommandFailure
import com.kyuchestration.desktop.kyu.KyuCommandRunner
import com.kyuchestration.desktop.kyu.KyuDocumentFailure
import com.kyuchestration.desktop.kyu.readKyuJsonDocument
import com.kyuchestration.desktop.terminal.ClaudeCodeConversation
import com.kyuchestration.desktop.terminal.ClaudeCodeConversationHistory
import com.kyuchestration.desktop.terminal.ClaudeCodeConversationHistoryFailure
import com.kyuchestration.desktop.terminal.ClaudeCodeConversationHistorySource
import com.kyuchestration.desktop.terminal.ClaudeCodeConversationListingFailure
import com.kyuchestration.desktop.terminal.ClaudeCodeConversationSource
import com.kyuchestration.desktop.terminal.PersonOpenedSessionTarget
import com.kyuchestration.desktop.terminal.SessionTarget
import com.kyuchestration.desktop.terminal.chat.ChatEntry
import com.kyuchestration.desktop.terminal.chat.PastTurnContent
import java.nio.file.Path
import java.time.Instant
import java.time.format.DateTimeParseException
import kotlinx.serialization.Serializable

/**
 * `kyu conversations` 로 그 자리의 대화를 묻는다(internal/cli/conversations.go) — `list` 는 고를 대화 목록을, `show` 는 이어가는
 * 대화 하나의 지난 내용을 답한다.
 *
 * 포트 둘을 한 어댑터가 맡는다. 두 물음이 같은 하위 명령의 두 갈래이고, 세션 자리를 인자로 옮기는 규칙이 같다 — 갈라 두면 그
 * 규칙이 두 벌이 되어 한쪽만 고쳐지는 날 고른 대화와 지난 내용이 다른 자리의 것이 된다.
 *
 * 엔진의 stderr(읽다 버린 전사에 대한 경고)는 싣지 않는다. 목록과 지난 내용은 경고 없이도 그 자체로 참이고(엔진의 주석), 그
 * 경고를 받을 자리가 아직 화면에 없다.
 */
class KyuCliClaudeCodeConversationSource(
    private val kyuCommandRunner: KyuCommandRunner,
) : ClaudeCodeConversationSource, ClaudeCodeConversationHistorySource {

    override fun conversationsAt(workDirPath: Path, target: PersonOpenedSessionTarget): List<ClaudeCodeConversation> {
        val arguments = listOf("conversations", "list", workDirPath.toString()) + repoArgumentFor(target) + "--json"

        val result = try {
            kyuCommandRunner.run(arguments, workingDirectory = workDirPath)
        } catch (failure: KyuCommandFailure) {
            throw ClaudeCodeConversationListingFailure("kyu 를 부르지 못했습니다: ${failure.message}", failure)
        }
        if (result.exitCode != 0) {
            throw ClaudeCodeConversationListingFailure(
                "kyu conversations list 가 종료 코드 ${result.exitCode} 로 끝났습니다: ${result.standardError.trim()}",
            )
        }

        val document = try {
            readKyuJsonDocument(result.standardOutput, SUPPORTED_SCHEMA_VERSION, KyuConversationsListDocument.serializer())
        } catch (failure: KyuDocumentFailure) {
            throw ClaudeCodeConversationListingFailure(failure.message.orEmpty(), failure)
        }
        return document.conversations.map { it.asClaudeCodeConversation() }
    }

    // --last 를 주지 않는다. 몇 개를 답할지는 엔진의 기본값(200)이 정한다 — 앱이 따로 정하면 두 자리의 수가 갈라진다.
    override fun conversationHistoryOf(
        workDirPath: Path,
        target: PersonOpenedSessionTarget,
        conversationId: String,
    ): ClaudeCodeConversationHistory {
        val arguments = listOf("conversations", "show", workDirPath.toString()) + repoArgumentFor(target) + conversationId + "--json"

        val result = try {
            kyuCommandRunner.run(arguments, workingDirectory = workDirPath)
        } catch (failure: KyuCommandFailure) {
            throw ClaudeCodeConversationHistoryFailure("kyu 를 부르지 못했습니다: ${failure.message}", failure)
        }
        if (result.exitCode != 0) {
            throw ClaudeCodeConversationHistoryFailure(
                "kyu conversations show 가 종료 코드 ${result.exitCode} 로 끝났습니다: ${result.standardError.trim()}",
            )
        }

        val document = try {
            readKyuJsonDocument(result.standardOutput, SUPPORTED_SCHEMA_VERSION, KyuConversationHistoryDocument.serializer())
        } catch (failure: KyuDocumentFailure) {
            throw ClaudeCodeConversationHistoryFailure(failure.message.orEmpty(), failure)
        }
        return ClaudeCodeConversationHistory(
            pastTurns = document.entries.mapNotNull { it.asPastTurnOrNullWhenUnknown() },
            omittedBeforeCount = document.totalEntries - document.entries.size,
        )
    }

    // 경로를 인자로 준다. session-command 와 달리 이 명령은 실행된 디렉토리로 짐작하지 않는다. 작업 디렉토리도 같은
    // 자리로 두는 것은 상대 경로가 섞일 일을 처음부터 없애려는 것이다.
    private fun repoArgumentFor(target: PersonOpenedSessionTarget): List<String> = when (target) {
        is SessionTarget.Main -> emptyList()
        is SessionTarget.Repo -> listOf(target.repoName)
    }
}

/**
 * 이 앱이 읽을 줄 아는 `kyu conversations list --json` · `show --json` 의 판(conversations_json.go 의
 * conversationsListJSONSchemaVersion · conversationsShowJSONSchemaVersion). 두 문서의 판은 따로 세지만 지금은 같은 1 이다.
 */
private const val SUPPORTED_SCHEMA_VERSION = 1

@Serializable
private data class KyuConversationsListDocument(val conversations: List<KyuConversationDocument>)

@Serializable
private data class KyuConversationDocument(
    val id: String,
    val title: String,
    val startedAt: String?,
    val lastActiveAt: String,
    val recordedFor: String?,
) {
    fun asClaudeCodeConversation() = ClaudeCodeConversation(
        id = id,
        title = title,
        startedAt = startedAt?.let(::parseListedInstant),
        lastActiveAt = parseListedInstant(lastActiveAt),
        recordedForLabel = recordedFor,
    )
}

@Serializable
private data class KyuConversationHistoryDocument(
    val totalEntries: Int,
    val entries: List<KyuConversationHistoryEntryDocument>,
)

/**
 * 지난 항목 하나. 엔진은 갈래마다 다른 자리를 내므로(text · toolName+summary · count) 여기서는 모두 비어 있을 수 있게 받고,
 * 갈래를 본 뒤에 그 갈래의 자리가 있는지 따진다.
 */
@Serializable
private data class KyuConversationHistoryEntryDocument(
    val kind: String,
    val text: String? = null,
    val toolName: String? = null,
    val summary: String? = null,
    val at: String? = null,
    val count: Int? = null,
) {
    /** 모르는 갈래는 null — 엔진은 kind 를 더해도 판을 올리지 않으므로(conversations_json.go) 건너뛰는 것이 계약이다. */
    fun asPastTurnOrNullWhenUnknown(): ChatEntry.PastTurn? {
        val content = when (kind) {
            "user" -> PastTurnContent.UserSaid(required(text, "text"))
            "assistant" -> PastTurnContent.AssistantSaid(required(text, "text"))
            "system" -> PastTurnContent.SystemNoted(required(text, "text"))
            "tool_use" -> PastTurnContent.ToolUsed(required(toolName, "toolName"), required(summary, "summary"))
            "omitted" -> PastTurnContent.LinesOmitted(required(count, "count"))
            else -> return null
        }
        return ChatEntry.PastTurn(content, at = at?.let(::parseShownInstant))
    }

    private fun <T : Any> required(value: T?, fieldName: String): T = value
        ?: throw ClaudeCodeConversationHistoryFailure("kyu conversations show 의 $kind 항목에 $fieldName 이 없습니다")
}

/** 엔진은 RFC 3339 UTC 초 단위로 낸다. 그 밖의 모양이면 계약이 깨진 것이라 목록 전체를 읽지 못한 것으로 올린다. */
private fun parseListedInstant(text: String): Instant = try {
    Instant.parse(text)
} catch (unreadable: DateTimeParseException) {
    throw ClaudeCodeConversationListingFailure("kyu conversations list 가 읽을 수 없는 시각을 냈습니다: $text", unreadable)
}

/** 목록과 같은 규칙이다. 한 줄의 시각이 깨졌으면 나머지 줄의 시각도 믿을 근거가 없어 지난 내용 전체를 읽지 못한 것으로 올린다. */
private fun parseShownInstant(text: String): Instant = try {
    Instant.parse(text)
} catch (unreadable: DateTimeParseException) {
    throw ClaudeCodeConversationHistoryFailure("kyu conversations show 가 읽을 수 없는 시각을 냈습니다: $text", unreadable)
}
