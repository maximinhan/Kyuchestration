package com.kyuchestration.desktop.terminal.kyucli

import com.kyuchestration.desktop.kyu.KyuCommandFailure
import com.kyuchestration.desktop.kyu.KyuCommandRunner
import com.kyuchestration.desktop.kyu.KyuDocumentFailure
import com.kyuchestration.desktop.kyu.readKyuJsonDocument
import com.kyuchestration.desktop.terminal.ClaudeCodeConversation
import com.kyuchestration.desktop.terminal.ClaudeCodeConversationListingFailure
import com.kyuchestration.desktop.terminal.ClaudeCodeConversationSource
import com.kyuchestration.desktop.terminal.PersonOpenedSessionTarget
import com.kyuchestration.desktop.terminal.SessionTarget
import java.nio.file.Path
import java.time.Instant
import java.time.format.DateTimeParseException
import kotlinx.serialization.Serializable

/**
 * `kyu conversations list <워크디렉토리> [repo] --json` 으로 그 자리의 대화를 묻는다(internal/cli/conversations.go).
 *
 * 엔진의 stderr(읽다 버린 전사에 대한 경고)는 싣지 않는다. 목록은 경고 없이도 그 자체로 참이고(엔진의 주석), 그 경고를 받을
 * 자리가 아직 화면에 없다.
 */
class KyuCliClaudeCodeConversationSource(
    private val kyuCommandRunner: KyuCommandRunner,
) : ClaudeCodeConversationSource {

    override fun conversationsAt(workDirPath: Path, target: PersonOpenedSessionTarget): List<ClaudeCodeConversation> {
        // 경로를 인자로 준다. session-command 와 달리 이 명령은 실행된 디렉토리로 짐작하지 않는다. 작업 디렉토리도 같은
        // 자리로 두는 것은 상대 경로가 섞일 일을 처음부터 없애려는 것이다.
        val repoArgument = when (target) {
            is SessionTarget.Main -> emptyList()
            is SessionTarget.Repo -> listOf(target.repoName)
        }
        val arguments = listOf("conversations", "list", workDirPath.toString()) + repoArgument + "--json"

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
}

/** 이 앱이 읽을 줄 아는 `kyu conversations list --json` 의 판(conversations_json.go 의 conversationsListJSONSchemaVersion). */
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
        startedAt = startedAt?.let(::parseEngineInstant),
        lastActiveAt = parseEngineInstant(lastActiveAt),
        recordedForLabel = recordedFor,
    )
}

/** 엔진은 RFC 3339 UTC 초 단위로 낸다. 그 밖의 모양이면 계약이 깨진 것이라 목록 전체를 읽지 못한 것으로 올린다. */
private fun parseEngineInstant(text: String): Instant = try {
    Instant.parse(text)
} catch (unreadable: DateTimeParseException) {
    throw ClaudeCodeConversationListingFailure("kyu conversations list 가 읽을 수 없는 시각을 냈습니다: $text", unreadable)
}
