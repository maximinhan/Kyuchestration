package com.kyuchestration.desktop.terminal.chat

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * 앱이 세션 트리 요청에 답하는 한 줄(session-tree-design.md 6.2 · 6.4).
 *
 * 엔진은 이 줄을 모델에게 그대로 옮긴다(relaySessionTreeRequest) — 필드 이름이 곧 모델이 읽는 낱말이다.
 */
class SessionTreeAnswerLineTest {

    @Test
    fun `사람이 연 세션과 같은 자리면 그 사실을 답에 싣는다`() {
        val line = answerLineFor(
            SessionTreeAnswer.Spawned(
                childId = "child-1",
                relativeCwd = "proj-a",
                title = "proj-a",
                conversationId = "5e0c",
                notice = "사람이 연 레포 세션 proj-a 가 같은 자리에서 살아 있습니다",
            ),
        )

        assertEquals(
            Json.parseToJsonElement(
                """{"id":"child-1","cwd":"proj-a","title":"proj-a","conversationId":"5e0c","state":"running",
                   "notice":"사람이 연 레포 세션 proj-a 가 같은 자리에서 살아 있습니다"}""",
            ),
            Json.parseToJsonElement(line),
        )
    }

    @Test
    fun `목록은 끝난 하위까지 상태와 기다리는 것을 담는다`() {
        val line = answerLineFor(
            SessionTreeAnswer.Listed(
                listOf(
                    childNode("child-1", "proj-a").copy(waitingFor = ChildWaitingFor.Approval),
                    childNode("child-2", "scratch-x").copy(
                        lifecycle = ChildLifecycle.Ended(ChildEndReason.EndedByParent, exitCode = 0),
                    ),
                ),
            ),
        )

        assertEquals(
            Json.parseToJsonElement(
                """{"sessions":[
                     {"id":"child-1","title":"child-1","cwd":"proj-a","state":"running","waitingFor":"approval"},
                     {"id":"child-2","title":"child-2","cwd":"scratch-x","state":"ended","waitingFor":"nothing",
                      "endedReason":"ended_by_parent","exitCode":0}
                   ]}""",
            ),
            Json.parseToJsonElement(line),
        )
    }

    @Test
    fun `하위가 없으면 빈 목록이다`() {
        assertEquals("""{"sessions":[]}""", answerLineFor(SessionTreeAnswer.Listed(emptyList())))
    }

    @Test
    fun `하위의 stdin 에 넣은 말은 as_message 로 답한다`() {
        assertEquals("""{"delivered":"as_message"}""", answerLineFor(SessionTreeAnswer.DeliveredAsMessage))
    }

    @Test
    fun `거절은 error 한 모양이다`() {
        val answer = Json.parseToJsonElement(answerLineFor(SessionTreeAnswer.Refused("그런 하위 세션이 없습니다"))) as JsonObject

        assertEquals(setOf("error"), answer.keys)
    }

    private fun childNode(childId: String, relativeCwd: String) = ChildSessionNode(
        childId = childId,
        title = childId,
        relativeCwd = relativeCwd,
        workingDirectory = Path.of("/w").resolve(relativeCwd),
        conversationId = "conversation-$childId",
    )
}
