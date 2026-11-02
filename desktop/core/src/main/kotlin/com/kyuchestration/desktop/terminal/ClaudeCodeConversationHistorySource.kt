package com.kyuchestration.desktop.terminal

import com.kyuchestration.desktop.terminal.chat.ChatEntry
import java.nio.file.Path

/**
 * 이어가는 대화 하나의 지난 내용을 엔진에게 묻는 자리(app-owned-sessions-design.md 5.5.8).
 *
 * `--resume` 으로 연 세션의 채팅 창이 비어 있지 않도록, 그 대화가 이번 세션 전에 나눈 것을 앞에 깔려고 묻는다. **읽기만 한다.**
 * [ClaudeCodeConversationSource] 옆에 따로 두는 것은 묻는 때가 달라서다 — 저쪽은 이어가기를 누른 순간 고르기를 세울지 정하려고,
 * 이쪽은 세션이 뜨는 자리에서 엔진이 답한 대화 id 로 묻는다. 시험의 대역도 각자 한 가지 물음만 흉내 내면 된다.
 */
fun interface ClaudeCodeConversationHistorySource {

    /**
     * 엔진의 기본 수(마지막 200개)만큼 답한다. 몇 개를 볼지는 코어가 정하지 않는다 — 전사를 읽는 비용과 화면의 길이를 함께 아는
     * 것은 엔진의 기본값이다.
     *
     * @throws ClaudeCodeConversationHistoryFailure 묻지 못했거나, 엔진이 거절했거나, 답을 읽지 못했을 때.
     */
    fun conversationHistoryOf(
        workDirPath: Path,
        target: PersonOpenedSessionTarget,
        conversationId: String,
    ): ClaudeCodeConversationHistory
}

/**
 * 대화 하나의 지난 내용.
 *
 * @param pastTurns 오래된 것부터. 앱이 모르는 갈래는 이미 빠져 있다.
 * @param omittedBeforeCount 엔진이 앞에서 답하지 않은 항목 수 — 전체 수에서 엔진이 **답한** 수를 뺀 것이다. [pastTurns] 의 수로
 *   빼면 앱이 건너뛴 모르는 갈래까지 "앞에서 생략됨" 으로 센다.
 */
data class ClaudeCodeConversationHistory(
    val pastTurns: List<ChatEntry.PastTurn>,
    val omittedBeforeCount: Int,
)

/**
 * 지난 내용을 받지 못했다.
 *
 * 갈래를 두지 않는다. 이유가 무엇이든 받는 쪽이 하는 일은 같다 — 세션은 그대로 열고, 지난 내용 없이 그 사실을 전사와 기록에 남긴다.
 */
class ClaudeCodeConversationHistoryFailure(message: String, cause: Throwable? = null) : Exception(message, cause)
