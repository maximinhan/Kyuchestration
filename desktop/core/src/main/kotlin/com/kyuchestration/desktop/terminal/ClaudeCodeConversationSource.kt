package com.kyuchestration.desktop.terminal

import java.nio.file.Path
import java.time.Instant

/**
 * 세션 자리 하나에서 사람이 Claude Code 로 나눈 대화들을 엔진에게 묻는 자리(app-owned-sessions-design.md 5.5.7).
 *
 * 이어가기를 누른 자리에서 앱 밖(터미널)의 대화를 고르게 할지 정하려고 묻는다. **읽기만 한다** — 엔진도 이 물음에는 아무것도
 * 적지 않는다. 그래서 [SessionCommandSource] 와 갈라 둔다: 저쪽은 묻는 것이 곧 기록하는 것이라 화면을 그리려고 부를 수 없다.
 *
 * 전사가 어디에 어떤 모양으로 있는지는 이 포트 뒤의 엔진만 안다(원칙 11). 앱이 받는 것은 고르는 데 필요한 사실뿐이다.
 */
fun interface ClaudeCodeConversationSource {

    /**
     * @return 마지막 활동이 늦은 것부터. 없으면 빈 목록이다 — 전사 디렉토리가 없는 것도 "대화가 없다" 다.
     * @throws ClaudeCodeConversationListingFailure 묻지 못했거나, 엔진이 거절했거나, 답을 읽지 못했을 때.
     */
    fun conversationsAt(workDirPath: Path, target: PersonOpenedSessionTarget): List<ClaudeCodeConversation>
}

/**
 * 그 자리에서 나눈 대화 하나.
 *
 * @param recordedForLabel 이 대화가 워크디렉토리의 대화 기록에 어느 세션의 것으로 적혀 있는지. 앱 밖의 대화면 null 이다.
 *   이 값으로 "앱이 하던 대화" 와 "바깥 대화" 를 가른다 — 앱이 기록 파일을 직접 읽지 않고도.
 */
data class ClaudeCodeConversation(
    val id: String,
    val title: String,
    val startedAt: Instant?,
    val lastActiveAt: Instant,
    val recordedForLabel: String?,
)

/**
 * 대화 목록을 받지 못했다.
 *
 * 갈래를 두지 않는다. 받지 못한 이유가 무엇이든 받는 쪽이 하는 일은 같다 — 묻지 않고 오늘처럼 이어가며 그 사실을 남긴다.
 * 세션 진입을 막지 않으므로 [TerminalSessionFailure] 의 안내 문구도 필요 없다.
 */
class ClaudeCodeConversationListingFailure(message: String, cause: Throwable? = null) : Exception(message, cause)
