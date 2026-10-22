package com.kyuchestration.desktop

import com.kyuchestration.desktop.terminal.PersonOpenedSessionTarget
import com.kyuchestration.desktop.terminal.SessionTarget
import com.kyuchestration.desktop.terminal.TerminalSessionFailure
import com.kyuchestration.desktop.terminal.chat.ChatConversation
import com.kyuchestration.desktop.terminal.chat.ChatEntry
import com.kyuchestration.desktop.terminal.chat.SessionEntryState
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 화면이 고른 세션 하나를 홀더가 내놓은 대화 맵 · 진입 상태에서 짓는 자리(core-ui-split-design.md 6.8 (가)).
 *
 * 홀더가 "보고 있는 세션" 을 들던 때 그 홀더의 시험이 지키던 화면 규칙들이 이리로 왔다 — 하위를 고르면 그 하위가 서고,
 * 하위를 끝내면 메인으로 돌아가고, 다른 세션의 대화는 보는 자리를 흔들지 않는다.
 */
class ChatScreenStateTest {

    @Test
    fun `보는 세션이 없으면 빈 자리다`() {
        assertEquals(
            ChatScreenState.NoChatOpen,
            chatScreenStateFor(viewedTarget = null, conversations = mapOf(MAIN to mainConversation), sessionEntries = emptyMap()),
        )
    }

    @Test
    fun `띄우는 중인 세션을 보면 띄우는 중이다`() {
        assertEquals(
            ChatScreenState.ChatSessionEntryRunning(REPO),
            chatScreenStateFor(REPO, conversations = emptyMap(), sessionEntries = mapOf(REPO to SessionEntryState.Running)),
        )
    }

    @Test
    fun `띄우지 못한 세션을 보면 그 이유가 선다`() {
        val failure = TerminalSessionFailure.KyuExecutableNotFound()

        assertEquals(
            ChatScreenState.ChatSessionEntryFailed(REPO, failure),
            chatScreenStateFor(REPO, conversations = emptyMap(), sessionEntries = mapOf(REPO to SessionEntryState.Failed(failure))),
        )
    }

    @Test
    fun `보는 세션의 대화만 선다`() {
        val childTarget = SessionTarget.Child("child-1")
        val childConversation = ChatConversation(target = childTarget, entries = listOf(ChatEntry.AssistantSaid("하위의 답")))

        assertEquals(
            ChatScreenState.ChatOnScreen(childTarget, childConversation),
            chatScreenStateFor(
                childTarget,
                conversations = mapOf(MAIN to mainConversation, childTarget to childConversation),
                sessionEntries = mapOf(REPO to SessionEntryState.Running),
            ),
        )
    }

    @Test
    fun `대화가 서는 순간에도 진입 줄이 남아 있으면 아직 띄우는 중이다`() {
        // 홀더는 대화를 먼저 세우고 진입 줄을 나중에 뺀다. 그 사이를 띄운 것으로 읽으면 진입 실패가 대화를 덮을 수 있다.
        assertEquals(
            ChatScreenState.ChatSessionEntryRunning(MAIN),
            chatScreenStateFor(
                MAIN,
                conversations = mapOf(MAIN to mainConversation),
                sessionEntries = mapOf(MAIN to SessionEntryState.Running),
            ),
        )
    }

    @Test
    fun `볼 대화가 사라진 세션은 빈 자리다`() {
        // 메인을 새로 띄우면 앞 메인의 하위들은 대화째 놓인다. 화면이 그 하위를 보고 있었어도 빈 자리로 그린다.
        assertEquals(
            ChatScreenState.NoChatOpen,
            chatScreenStateFor(SessionTarget.Child("child-1"), conversations = mapOf(MAIN to mainConversation), sessionEntries = emptyMap()),
        )
    }

    @Test
    fun `하위를 끝내면 메인으로 돌아가고 그 밖의 세션을 끝내면 빈 자리다`() {
        assertEquals(MAIN, viewedTargetAfterEnding(SessionTarget.Child("child-1")))
        assertEquals(null, viewedTargetAfterEnding(MAIN))
        assertEquals(null, viewedTargetAfterEnding(REPO))
    }

    @Test
    fun `보던 세션의 진입 실패는 다른 데로 옮길 때 닫는다`() {
        val failed = mapOf(REPO to SessionEntryState.Failed(TerminalSessionFailure.KyuExecutableNotFound()))

        assertEquals(REPO, entryFailureLeftBehind(from = REPO, to = MAIN, sessionEntries = failed))
        assertEquals(REPO, entryFailureLeftBehind(from = REPO, to = null, sessionEntries = failed))
        // 같은 자리에 머물면(다시 띄우기를 누른 것) 닫지 않는다 — 다시 띄우는 진입이 그 자리를 덮는다.
        assertEquals(null, entryFailureLeftBehind(from = REPO, to = REPO, sessionEntries = failed))
        assertEquals(null, entryFailureLeftBehind(from = MAIN, to = REPO, sessionEntries = failed))
        assertEquals(
            null,
            entryFailureLeftBehind(from = REPO, to = MAIN, sessionEntries = mapOf(REPO to SessionEntryState.Running)),
        )
    }

    private companion object {
        val MAIN: PersonOpenedSessionTarget = SessionTarget.Main
        val REPO: PersonOpenedSessionTarget = SessionTarget.Repo("proj-a")
        val mainConversation = ChatConversation(target = SessionTarget.Main)
    }
}
