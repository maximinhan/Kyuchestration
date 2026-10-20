package com.kyuchestration.desktop

import com.kyuchestration.desktop.terminal.chat.ChildEndReason
import com.kyuchestration.desktop.terminal.chat.ChildLifecycle
import com.kyuchestration.desktop.terminal.chat.ChildSessionNode
import com.kyuchestration.desktop.terminal.chat.ChildWaitingFor
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 상위 챗의 spawn 카드가 도구 결과를 읽는다(session-tree-design.md 6.12).
 *
 * 그 결과는 앱이 소켓으로 답한 줄을 엔진이 들여쓰기만 해서 모델에게 옮긴 것이다(renderToolAnswer).
 */
class SpawnSessionAnswerReadingTest {

    @Test
    fun `엔진이 옮긴 spawn 의 답에서 하위의 이름과 자리를 읽는다`() {
        val answerText = """
            {
              "conversationId": "5e0c",
              "cwd": "proj-a",
              "id": "child-1",
              "notice": "사람이 연 레포 세션 proj-a 가 같은 자리에서 살아 있습니다",
              "state": "running",
              "title": "proj-a 포트 수정"
            }
        """.trimIndent()

        assertEquals(
            SpawnedChildSession(
                childId = "child-1",
                relativeCwd = "proj-a",
                title = "proj-a 포트 수정",
                notice = "사람이 연 레포 세션 proj-a 가 같은 자리에서 살아 있습니다",
            ),
            spawnedChildSessionOrNull(answerText),
        )
    }

    @Test
    fun `거절 문장은 우리 문서가 아니다`() {
        // 앱의 error 는 엔진이 도구 오류의 문장으로 옮긴다 — 카드는 그 문장을 그대로 보인다.
        assertNull(spawnedChildSessionOrNull("워크디렉토리 밖입니다: ../elsewhere"))
        assertNull(spawnedChildSessionOrNull("""{"sessions":[]}"""))
    }

    @Test
    fun `하위의 상태는 끝났는지와 무엇을 기다리는지로 말한다`() {
        assertEquals("도는 중", childSessionStatusLabel(node().copy(waitingFor = ChildWaitingFor.Model)))
        assertEquals("쉬는 중", childSessionStatusLabel(node()))
        assertEquals("승인 대기", childSessionStatusLabel(node().copy(waitingFor = ChildWaitingFor.Approval)))
        assertEquals(
            "끝남 · 상위가 끝냄",
            childSessionStatusLabel(node().copy(lifecycle = ChildLifecycle.Ended(ChildEndReason.EndedByParent, 0))),
        )
        assertEquals(
            "끝남 · 비정상 종료(137)",
            childSessionStatusLabel(node().copy(lifecycle = ChildLifecycle.Ended(ChildEndReason.Crashed, 137))),
        )
    }

    private fun node() = ChildSessionNode(
        childId = "child-1",
        title = "proj-a",
        relativeCwd = "proj-a",
        workingDirectory = Path.of("/w/proj-a"),
        conversationId = "5e0c",
        startedAt = Instant.parse("2026-10-20T10:01:12Z"),
    )
}
