package com.kyuchestration.desktop.terminal.chat

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
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
    fun `목록은 끝난 하위까지 상태와 기다리는 것과 마지막 턴의 결과와 시각을 담는다`() {
        // 6.2 의 list_sessions 모양이다. 상위가 앱 재시작 뒤 맥락을 되찾는 재료이기도 하다(6.15).
        val line = answerLineFor(
            SessionTreeAnswer.Listed(
                listOf(
                    childNode("child-1", "proj-a").copy(waitingFor = ChildWaitingFor.Approval),
                    childNode("child-2", "scratch-x").copy(
                        lifecycle = ChildLifecycle.Ended(ChildEndReason.EndedByParent, exitCode = 0),
                        lastTurnResult = ChildTurnResult(result = "README 를 고쳤다", isError = false),
                        endedAt = Instant.parse("2026-10-20T10:20:03Z"),
                    ),
                ),
            ),
        )

        assertEquals(
            Json.parseToJsonElement(
                """{"sessions":[
                     {"id":"child-1","title":"child-1","cwd":"proj-a","state":"running","waitingFor":"approval",
                      "lastTurnResult":null,"startedAt":"2026-10-20T10:01:12Z"},
                     {"id":"child-2","title":"child-2","cwd":"scratch-x","state":"ended","waitingFor":"nothing",
                      "lastTurnResult":{"result":"README 를 고쳤다","isError":false},"startedAt":"2026-10-20T10:01:12Z",
                      "endedReason":"ended_by_parent","exitCode":0,"endedAt":"2026-10-20T10:20:03Z"}
                   ]}""",
            ),
            Json.parseToJsonElement(line),
        )
    }

    @Test
    fun `묻는 중인 하위는 상위를 기다린다고 답한다`() {
        val line = answerLineFor(
            SessionTreeAnswer.Listed(listOf(childNode("child-1", "proj-a").copy(waitingFor = ChildWaitingFor.Parent))),
        )

        assertTrue(""""waitingFor":"parent"""" in line, line)
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
    fun `기다림의 답은 쌓인 사건과 아직 살아 있는 하위를 담는다`() {
        val line = answerLineFor(
            SessionTreeAnswer.Awaited(
                events = listOf(
                    SessionTreeEvent.TurnCompleted(
                        childId = "child-1",
                        result = "포트를 5433 으로 고쳤다",
                        isError = false,
                        triggeredBy = TurnTrigger.Parent,
                        costUsd = 0.12,
                        durationMillis = 4200,
                    ),
                    SessionTreeEvent.SessionEnded(childId = "child-2", reason = ChildEndReason.Crashed, exitCode = 137),
                    SessionTreeEvent.SessionEnded(childId = "child-3", reason = ChildEndReason.EndedByHuman, exitCode = null),
                ),
                stillRunning = listOf("child-1"),
            ),
        )

        // 6.6 의 SessionEvent 모양이다. 종류는 type 으로 가른다.
        assertEquals(
            Json.parseToJsonElement(
                """{"events":[
                     {"type":"turn_completed","id":"child-1","result":"포트를 5433 으로 고쳤다","isError":false,
                      "triggeredBy":"parent","costUsd":0.12,"durationMs":4200},
                     {"type":"session_ended","id":"child-2","reason":"crashed","exitCode":137},
                     {"type":"session_ended","id":"child-3","reason":"ended_by_human"}
                   ],"stillRunning":["child-1"]}""",
            ),
            Json.parseToJsonElement(line),
        )
    }

    @Test
    fun `사람이 상위에게 말한 사건에는 내용이 없다`() {
        // 사람의 말은 stdin 으로 가서 도구 결과 바로 뒤에 붙는다(3.1). 여기에도 담으면 같은 말이 두 번 보인다(6.6).
        assertEquals(
            """{"events":[{"type":"user_spoke"}],"stillRunning":[]}""",
            answerLineFor(SessionTreeAnswer.Awaited(events = listOf(SessionTreeEvent.UserSpoke), stillRunning = emptyList())),
        )
    }

    @Test
    fun `아무 일 없이 시간이 지난 기다림은 빈 사건으로 답한다`() {
        assertEquals(
            """{"events":[],"stillRunning":["child-1"]}""",
            answerLineFor(SessionTreeAnswer.Awaited(events = emptyList(), stillRunning = listOf("child-1"))),
        )
    }

    @Test
    fun `하위의 물음은 기다림의 사건으로 그 물음과 askId 를 싣는다`() {
        assertEquals(
            Json.parseToJsonElement(
                """{"events":[{"type":"asked_parent","id":"child-1","message":"포트는?","askId":"ask-1"}],"stillRunning":["child-1"]}""",
            ),
            Json.parseToJsonElement(
                answerLineFor(
                    SessionTreeAnswer.Awaited(
                        events = listOf(SessionTreeEvent.AskedParent("child-1", "포트는?", askId = "ask-1")),
                        stillRunning = listOf("child-1"),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `물음의 답이 된 말은 as_answer 와 그 물음의 askId 로 답한다`() {
        assertEquals(
            """{"delivered":"as_answer","askId":"ask-1"}""",
            answerLineFor(SessionTreeAnswer.DeliveredAsAnswer(askId = "ask-1")),
        )
    }

    @Test
    fun `하위의 물음에는 answer 아니면 ended 로 답한다`() {
        // 6.3 — ask_parent → { answer } | { ended: "parent_ended" | "session_ended" }.
        assertEquals("""{"answer":"5433"}""", answerLineFor(SessionTreeAnswer.ParentAnswered("5433")))
        assertEquals("""{"ended":"parent_ended"}""", answerLineFor(SessionTreeAnswer.AskEnded(AskEndReason.ParentEnded)))
        assertEquals("""{"ended":"session_ended"}""", answerLineFor(SessionTreeAnswer.AskEnded(AskEndReason.SessionEnded)))
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
        workingDirectory = "/w/$relativeCwd",
        conversationId = "conversation-$childId",
        startedAt = Instant.parse("2026-10-20T10:01:12Z"),
    )
}
