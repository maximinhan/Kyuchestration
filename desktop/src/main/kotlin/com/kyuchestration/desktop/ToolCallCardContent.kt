package com.kyuchestration.desktop

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * 도구 카드가 무엇을 보일 것인가(설계 6.3).
 *
 * **갈래를 이벤트가 아니라 여기서 가른다.** 어댑터는 `tool_use_result` 곁가지를 손대지 않고
 * 그대로 들고 온다(ChatSessionEvent.ToolCallAnswered.typedResult) — 그 곁가지의 모양이 도구마다
 * 다르고, 어느 모양을 어떤 카드로 그릴지는 화면의 판단이라서다.
 *
 * **갈래는 실측이 정했다**(2026-09-04, `claude` 2.1.259 · RecordedChatStreamLines).
 *
 * | 도구 | 곁가지의 모양 |
 * |---|---|
 * | Bash | `{stdout, stderr, interrupted, isImage, noOutputExpected}` — **종료 코드가 없다** |
 * | Edit | `{filePath, oldString, newString, originalFile, structuredPatch, …}` |
 * | Write | `{type: "create"\|"update", filePath, content, structuredPatch, originalFile, …}` |
 * | Read | `{type: "text", file: {filePath, content, numLines, startLine, totalLines}}` |
 * | 거절된 호출 | 객체가 아니라 **문자열** — `"Error: This command requires approval"` |
 *
 * **Glob·Grep 갈래를 두지 않았다.** 설계 6.3 의 표에는 있지만, 이 판의 `claude` 는 그 둘을
 * 세션의 도구 목록에 두지 않는다(프로브의 `init.tools` 26 개에 없고 `ToolSearch` 뒤로 물러나
 * 있다). 오지 않는 모양을 짐작으로 그리면 그것이 맞는지 아무도 모르는 채 굳는다(원칙 15) —
 * 그 도구들은 [AnyTool] 로 떨어지고, 실제로 오는 것을 잰 날 갈래가 는다.
 */
internal sealed interface ToolCallCardContent {

    /**
     * Bash — 명령과 그 출력.
     *
     * **종료 코드를 담지 않는다.** 곁가지에 그 값이 없다(위 표). 성공·실패는 `is_error` 가
     * 이미 말하므로 카드는 그것으로 그린다 — 없는 숫자를 0 으로 적으면 실패한 명령이 0 으로
     * 끝난 것처럼 보인다.
     */
    data class ShellCommand(
        val command: String,
        val standardOutput: String,
        val standardError: String,
    ) : ToolCallCardContent

    /** Edit · Write — 그 파일이 어떻게 달라졌는가. */
    data class FileChanged(
        val filePath: String,
        val createdFile: Boolean,
        val hunks: List<DiffHunk>,
    ) : ToolCallCardContent {

        val addedLineCount: Int get() = hunks.sumOf { hunk -> hunk.lines.count { it.kind == DiffLineKind.Added } }

        val removedLineCount: Int get() = hunks.sumOf { hunk -> hunk.lines.count { it.kind == DiffLineKind.Removed } }
    }

    /** Read — 읽은 파일과 그 내용. 줄 번호가 붙지 않은 원문이 곁가지에 있다. */
    data class FileRead(
        val filePath: String,
        val totalLineCount: Long?,
        val content: String,
    ) : ToolCallCardContent

    /**
     * 그 밖의 전부 — 아직 결과가 오지 않은 호출과, 모르는 도구와, 거절되어 곁가지가 문자열로
     * 온 호출.
     *
     * **이것이 기본값인 것이 뜻이다.** MCP 도구는 얼마든지 새로 붙고(설계 6.3), 새 도구가 붙는
     * 날 화면이 비는 것보다 인자와 결과를 그대로 보이는 편이 낫다.
     *
     * @param toolLabel 화면에 적을 이름. MCP 도구는 `서버 · 도구` 로 갈라 적는다.
     */
    data class AnyTool(val toolLabel: String) : ToolCallCardContent
}

/** diff 한 덩이. `structuredPatch` 의 항목 하나가 이것이다. */
internal data class DiffHunk(val lines: List<DiffLine>)

internal data class DiffLine(val kind: DiffLineKind, val text: String)

internal enum class DiffLineKind { Added, Removed, Context }

/**
 * 이 도구 호출을 어느 카드로 그릴 것인가.
 *
 * @param typedResult `tool_use_result` 곁가지. 아직 결과가 오지 않았으면 null 이고, 그때는
 *   [ToolCallCardContent.AnyTool] 이다 — 도는 중인 카드가 보여줄 수 있는 것은 이름과 인자뿐이다.
 */
internal fun toolCallCardContentOf(
    toolName: String,
    input: JsonObject,
    typedResult: JsonElement?,
): ToolCallCardContent {
    val typedResultFields = typedResult as? JsonObject

    return when {
        toolName == BASH_TOOL_NAME && typedResultFields != null -> ToolCallCardContent.ShellCommand(
            command = input.stringOrNull("command").orEmpty(),
            standardOutput = typedResultFields.stringOrNull("stdout").orEmpty(),
            standardError = typedResultFields.stringOrNull("stderr").orEmpty(),
        )

        // 도는 중인 Bash 도 명령은 이미 알고 있다. 인자가 완성된 채로 오기 때문이다(3.4) —
        // 결과를 기다리는 동안 JSON 을 보여줄 이유가 없다.
        toolName == BASH_TOOL_NAME -> ToolCallCardContent.ShellCommand(
            command = input.stringOrNull("command").orEmpty(),
            standardOutput = "",
            standardError = "",
        )

        toolName in FILE_CHANGING_TOOL_NAMES && typedResultFields != null ->
            fileChangedIn(typedResultFields) ?: ToolCallCardContent.AnyTool(toolLabel(toolName))

        toolName == READ_TOOL_NAME && typedResultFields != null ->
            fileReadIn(typedResultFields) ?: ToolCallCardContent.AnyTool(toolLabel(toolName))

        else -> ToolCallCardContent.AnyTool(toolLabel(toolName))
    }
}

/**
 * `structuredPatch` 를 화면이 그릴 줄들로 옮긴다.
 *
 * **diff 를 우리가 계산하지 않는다.** `oldString` 과 `newString` 으로 직접 줄 diff 를 짜는 길도
 * 있었지만, 같은 곁가지에 `claude` 가 이미 계산한 패치가 실려 온다(실측). 우리가 다시 계산하면
 * 두 개의 diff 가 생기고, 어긋나는 날 사용자는 앱이 보여준 것과 파일에 실제로 일어난 일이 다른
 * 것을 나중에 발견한다.
 *
 * 새로 만든 파일은 패치가 비어 있다(`type: "create"`, `structuredPatch: []`). 그때는 쓴 내용
 * 전체가 더해진 줄이다 — 그것도 곁가지에 있다.
 */
private fun fileChangedIn(typedResult: JsonObject): ToolCallCardContent.FileChanged? {
    val filePath = typedResult.stringOrNull("filePath") ?: return null
    val patchHunks = typedResult["structuredPatch"] as? kotlinx.serialization.json.JsonArray

    val hunks = patchHunks
        ?.filterIsInstance<JsonObject>()
        ?.mapNotNull { hunk ->
            (hunk["lines"] as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                ?.map(::diffLineOf)
                ?.let(::DiffHunk)
        }
        .orEmpty()

    if (hunks.isNotEmpty()) {
        return ToolCallCardContent.FileChanged(filePath = filePath, createdFile = false, hunks = hunks)
    }

    val writtenContent = typedResult.stringOrNull("content") ?: return null
    return ToolCallCardContent.FileChanged(
        filePath = filePath,
        createdFile = true,
        hunks = listOf(DiffHunk(writtenContent.trimEnd('\n').lines().map { DiffLine(DiffLineKind.Added, it) })),
    )
}

/**
 * 패치 한 줄의 첫 글자가 그 줄이 무엇인지 말한다 — `" 첫째 줄"` · `"-둘째 줄"` · `"+둘째 줄 고침"`.
 *
 * 빈 줄은 맥락이다. 패치에서 빈 줄이 `" "` 가 아니라 `""` 로 오는 경우를 재지 못했으므로 둘 다
 * 맥락으로 둔다 — 첫 글자가 없는 줄을 더해진 줄로 읽으면 안 바뀐 빈 줄이 초록으로 선다.
 */
private fun diffLineOf(patchLine: String): DiffLine = when (patchLine.firstOrNull()) {
    '+' -> DiffLine(DiffLineKind.Added, patchLine.drop(1))
    '-' -> DiffLine(DiffLineKind.Removed, patchLine.drop(1))
    else -> DiffLine(DiffLineKind.Context, patchLine.drop(1))
}

private fun fileReadIn(typedResult: JsonObject): ToolCallCardContent.FileRead? {
    val file = typedResult["file"] as? JsonObject ?: return null
    val filePath = file.stringOrNull("filePath") ?: return null

    return ToolCallCardContent.FileRead(
        filePath = filePath,
        totalLineCount = file.longOrNull("totalLines"),
        // 모델이 읽은 텍스트에는 줄 번호가 붙어 있다(3.4). 곁가지의 이 값은 원문이라, 사용자가
        // 보는 것과 파일에 있는 것이 같아진다.
        content = file.stringOrNull("content").orEmpty(),
    )
}

/**
 * 화면에 적을 도구 이름. MCP 도구는 `서버 · 도구` 로 갈라 적는다.
 *
 * 이름의 모양은 실측이 정했다(A.15) — `mcp__kyu__run_in_repo`. 서버 이름의 하이픈은 그대로
 * 남고 콜론만 밑줄로 바뀌므로, 가운데 마디를 서버 이름으로 그대로 읽어도 된다.
 */
internal fun toolLabel(toolName: String): String {
    if (!toolName.startsWith(MCP_TOOL_NAME_PREFIX)) {
        return toolName
    }

    val serverAndTool = toolName.removePrefix(MCP_TOOL_NAME_PREFIX).split(MCP_TOOL_NAME_SEPARATOR, limit = 2)
    // 마디가 둘이 아니면 우리가 아는 모양이 아니다. 그때는 손대지 않는 편이 낫다 — 반쪽만
    // 갈라 적으면 그 도구를 부른 서버가 화면에서 사라진다.
    return if (serverAndTool.size == 2) serverAndTool.joinToString(" · ") else toolName
}

private const val BASH_TOOL_NAME = "Bash"

private const val READ_TOOL_NAME = "Read"

private val FILE_CHANGING_TOOL_NAMES = setOf("Edit", "Write")

private const val MCP_TOOL_NAME_PREFIX = "mcp__"

private const val MCP_TOOL_NAME_SEPARATOR = "__"

private fun JsonObject.stringOrNull(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.longOrNull(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
