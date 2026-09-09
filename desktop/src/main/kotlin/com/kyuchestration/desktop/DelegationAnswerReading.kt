package com.kyuchestration.desktop

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * 위임 하나가 어떻게 끝났는가 — `run_in_repo` 가 모델에게 답한 문서를 화면이 읽는 모양으로 옮긴다.
 *
 * **모양의 출처는 엔진의 계약이다**(`internal/cli/mcp_run_in_repo.go` 의 `runInRepoAnswer` ·
 * orchestration-tools-design.md 5.4.2). 도구 결과의 `tool_use_result` 곁가지가 아니라 **모델이
 * 읽는 텍스트**가 이 JSON 이다 — MCP 도구의 결과는 우리 엔진이 쓴 문자열 하나로 오기 때문이다.
 *
 * **여기 없는 것은 화면에도 없다.** 바뀐 파일 목록 같은 것은 이 문서에 없고, 그것을 위임의 답
 * 본문에서 뽑아내려 들지 않는다 — 모델이 쓴 문장을 파싱해 만든 목록은 사실이 아니다(원칙 15).
 */
internal data class DelegationAnswer(
    val repo: String,

    /** 위임된 세션이 낸 답 본문. */
    val result: String,

    /**
     * 시킨 일을 끝내지 못한 이유. 끝냈으면 null 이다.
     *
     * **종료 코드로는 알 수 없는 사실이라 엔진이 문장으로 적어 보낸다.** 권한에 막힌 실행은
     * 종료 코드 0 · `success` 로 끝나므로(orchestration 3.3), 이 값이 카드가 "완료" 라고 말하지
     * 않게 막는 유일한 근거다.
     */
    val incomplete: String?,

    /** 권한에 막혀 부르지 못한 도구 이름들. */
    val permissionDeniedTools: List<String>,

    /** 앞 위임의 대화를 이어갔는가. */
    val resumedConversation: Boolean,

    val costUsd: Double,
    val durationMillis: Long,
    val turnCount: Int,
    val exitCode: Int,
    val standardError: String,

    /** `claude` 의 답 원문이 통째로 남은 자리(`.coord/runs/`). 남기지 못했으면 비어 있다. */
    val logPath: String,

    /** 원출력을 남기지 못한 이유. 남겼으면 비어 있다. */
    val logFailure: String,

    /** 대화 기록을 그대로 쓰지 못한 이유들 — 앞선 대화를 잃었다는 사실이 여기로 온다. */
    val conversationWarnings: List<String>,
)

/**
 * 위임 도구가 답한 텍스트를 문서로 읽는다. 우리 문서가 아니면 null.
 *
 * **null 이 흔한 갈래다.** 위임이 걸리기도 전에 끝난 물음 — 없는 레포 이름, 아직 승인되지 않은
 * 레포 설정 — 은 사람이 읽을 문장 하나로 온다(`refuseDelegation`). 그때 카드는 그 문장을 그대로
 * 보인다: 지어낸 필드로 채운 문서보다 낫고, 그 문장 자체가 사용자가 해야 할 일을 적고 있다.
 */
internal fun delegationAnswerOrNull(answerText: String): DelegationAnswer? {
    val document = parsedObjectOrNull(answerText) ?: return null

    // 우리 문서인지 가르는 값이다. 위임의 답에는 언제나 이 둘이 있고(엔진이 늘 채운다), 없으면
    // 그것은 다른 도구의 JSON 이거나 우리가 모르는 모양이다.
    val repo = document.stringOrNull("repo") ?: return null
    if ("permissionDenials" !in document) {
        return null
    }

    return DelegationAnswer(
        repo = repo,
        result = document.stringOrNull("result").orEmpty(),
        incomplete = document.stringOrNull("incomplete"),
        permissionDeniedTools = document.stringsIn("permissionDenials"),
        resumedConversation = (document["resumed"] as? JsonPrimitive)?.booleanOrNull ?: false,
        costUsd = (document["costUsd"] as? JsonPrimitive)?.doubleOrNull ?: 0.0,
        durationMillis = (document["durationMs"] as? JsonPrimitive)?.longOrNull ?: 0,
        turnCount = (document["numTurns"] as? JsonPrimitive)?.intOrNull ?: 0,
        exitCode = (document["exitCode"] as? JsonPrimitive)?.intOrNull ?: 0,
        standardError = document.stringOrNull("stderr").orEmpty(),
        logPath = document.stringOrNull("logPath").orEmpty(),
        logFailure = document.stringOrNull("logFailure").orEmpty(),
        conversationWarnings = document.stringsIn("conversationWarnings"),
    )
}

private fun parsedObjectOrNull(answerText: String): JsonObject? = try {
    delegationAnswerJson.parseToJsonElement(answerText) as? JsonObject
} catch (failure: SerializationException) {
    null
} catch (failure: IllegalArgumentException) {
    null
}

private val delegationAnswerJson = Json

private fun JsonObject.stringOrNull(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.stringsIn(key: String): List<String> =
    (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
