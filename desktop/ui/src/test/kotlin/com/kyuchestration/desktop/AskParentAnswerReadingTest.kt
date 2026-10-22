package com.kyuchestration.desktop

import com.kyuchestration.desktop.terminal.chat.UserMessageSender
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 하위 챗의 "상위에게 물음" 카드와, 세션끼리 오간 말풍선의 이름표(session-tree-design.md 6.12).
 *
 * 카드가 읽는 것은 앱이 소켓으로 답한 줄을 엔진이 들여쓰기만 해서 모델에게 옮긴 것이다(renderToolAnswer).
 */
class AskParentAnswerReadingTest {

    @Test
    fun `상위의 답을 읽는다`() {
        assertEquals(ParentReply.Answered("5433 을 써"), parentReplyOrNull("{\n  \"answer\": \"5433 을 써\"\n}"))
    }

    @Test
    fun `답 없이 끝난 물음은 그 까닭을 읽는다`() {
        assertEquals(ParentReply.Ended("parent_ended"), parentReplyOrNull("""{"ended":"parent_ended"}"""))
        assertEquals(ParentReply.Ended("session_ended"), parentReplyOrNull("""{"ended":"session_ended"}"""))
    }

    @Test
    fun `우리 문서가 아니면 읽지 않는다`() {
        // 도구가 중단되면 claude 가 지은 거절 문장이 결과로 온다 — 카드는 그 문장을 그대로 보인다.
        assertNull(parentReplyOrNull("The user doesn't want to proceed with this tool use."))
        assertNull(parentReplyOrNull("""{"delivered":"as_answer"}"""))
    }

    @Test
    fun `세션이 보낸 말풍선에는 누가 보냈는지 이름표가 붙고 사람의 말에는 붙지 않는다`() {
        assertNull(userMessageSenderLabel(UserMessageSender.Human))
        assertEquals("상위로부터", userMessageSenderLabel(UserMessageSender.ParentSession))
        assertEquals("child-1 로부터", userMessageSenderLabel(UserMessageSender.ChildSession("child-1")))
    }
}
