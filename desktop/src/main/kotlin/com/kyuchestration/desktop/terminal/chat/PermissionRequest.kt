package com.kyuchestration.desktop.terminal.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonObject

/**
 * 승인이 필요한 도구 호출 하나 — `claude` 가 관문을 열며 물어 온 것(chat-ui-design.md 3.5).
 *
 * 세 값이 곧 실측이 잰 인자다(A.5). 화면이 이 셋으로 카드를 그리고, 사용자가 인자를 고치면
 * [PermissionDecision.Allow] 에 그 고친 것이 실려 되돌아간다.
 *
 * @param toolUseId 이 물음이 어느 도구 호출의 것인가. 같은 id 의 도구 카드가 전사에 이미 서 있고
 *   (`ToolCallRequested`), 카드 둘을 잇는 근거가 이 값이다.
 */
data class PermissionRequest(
    val toolName: String,
    val input: JsonObject,
    val toolUseId: String,
)

/**
 * 그 물음에 대한 사람의 답(설계 5.4.1 · 6.4).
 *
 * 참·거짓 하나로 두지 않는다. 허용에는 **실제로 실행될 인자**가 함께 가고, 거절에는 **모델이
 * 읽을 이유**가 함께 간다 — 그 둘은 서로 다른 값이라 한 자리에 담기지 않는다.
 */
sealed interface PermissionDecision {

    /**
     * 이대로 실행해도 된다.
     *
     * @param updatedInput 실행될 인자. 사용자가 고치지 않았으면 물음의 것 그대로다 —
     *   **고쳤으면 고친 것이 실제로 실행된다**(실측 3.5: 모델은 `echo ORIGINAL` 을 요청했는데
     *   결과가 `MODIFIED-BY-APP` 이었다).
     */
    data class Allow(val updatedInput: JsonObject) : PermissionDecision

    /**
     * 실행하지 않는다.
     *
     * @param reason 모델에게 그대로 가는 문구(A.5 의 `tool_result` `is_error: true`). 모델이
     *   "왜 못 했는지" 를 사용자에게 말할 수 있는 유일한 통로다.
     */
    data class Deny(val reason: String) : PermissionDecision
}

/**
 * 아직 답하지 않은 물음 하나 — 그 내용과, 답을 되돌릴 자리.
 *
 * **답을 기다리는 쪽은 소켓이고 답하는 쪽은 화면이다.** 그 둘을 잇는 것이 이 객체이고, 그래서
 * 상태 홀더는 이것을 `toolUseId` 로 들고 있다가 사용자가 버튼을 누를 때 [answerWith] 를 부른다.
 *
 * **답하지 않는 것도 성립한다.** 사람이 커피를 마시러 가면 이 물음은 그대로 남아 있고, `claude`
 * 는 기다린다(실측 3.7 이 150 초를 쟀다). 세션이 끝나면 소켓이 닫히면서 연결이 끊기고, 그때
 * `kyu mcp ask` 가 EOF 를 거절로 읽는다.
 */
class AskedPermission internal constructor(val request: PermissionRequest) {

    private val decided = CompletableDeferred<PermissionDecision>()

    /**
     * 이 물음에 답한다. 두 번 불러도 첫 답만 간다 — 버튼을 두 번 누른 것이 두 답이 되지 않는다.
     */
    fun answerWith(decision: PermissionDecision) {
        decided.complete(decision)
    }

    internal suspend fun awaitDecision(): PermissionDecision = decided.await()
}
