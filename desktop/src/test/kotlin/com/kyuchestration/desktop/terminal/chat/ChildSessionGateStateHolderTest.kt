package com.kyuchestration.desktop.terminal.chat

import com.kyuchestration.desktop.terminal.ChildSessionCommandAnswer
import com.kyuchestration.desktop.terminal.ChildSessionGate
import com.kyuchestration.desktop.terminal.GatedExecutionConfig
import com.kyuchestration.desktop.terminal.SessionConversationChoice
import com.kyuchestration.desktop.terminal.SessionTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * 하위의 자리가 관문에 걸렸을 때 앱이 사람에게 묻는 자리(session-tree-design.md 6.9).
 *
 * `run_in_repo` 는 이 자리에서 "터미널에서 kyu mcp approve 를 실행하세요" 로 거절했다. 하위는 앱이 띄우고 앱은
 * 사람 앞에 있으므로, spawn 은 사람이 답할 때까지 기다린다.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ChildSessionGateStateHolderTest {

    @Test
    fun `관문에 걸리면 걸린 설정을 사람에게 묻고 spawn 은 답을 기다린다`() = runTest {
        val bed = gateTestBed(gates = listOf(GATE))

        val asked = bed.mainChannel.askSessionTree(spawn())
        runCurrent()

        val question = bed.holder.gateQuestions.value.single()
        assertEquals("proj-a 테스트를 고쳐 줘", question.prompt)
        assertEquals(GATE, question.gate)
        assertNull(asked.answerSoFar(), "사람이 답하기 전에 spawn 이 답했습니다")
        // 아무것도 띄우지 않았다 — 사람이 본 적 없는 설정으로 세션이 뜨는 일은 없다.
        assertEquals(1, bed.opener.openedSessions.size)
    }

    @Test
    fun `승인하면 그 지문으로 다시 묻고 띄운다`() = runTest {
        val bed = gateTestBed(gates = listOf(GATE))
        val asked = bed.mainChannel.askSessionTree(spawn())
        runCurrent()

        bed.holder.answerGateQuestion(bed.holder.gateQuestions.value.single(), approved = true)
        runCurrent()

        assertEquals(listOf(null, "sha256:first"), bed.childSource.askedRequests.map { it.approvedFingerprint })
        assertEquals("child-1", assertIs<SessionTreeAnswer.Spawned>(asked.answerSoFar()).childId)
        assertEquals(emptyList(), bed.holder.gateQuestions.value)
    }

    @Test
    fun `거절하면 사람이 승인하지 않았다고 거절하고 아무것도 띄우지 않는다`() = runTest {
        val bed = gateTestBed(gates = listOf(GATE))
        val asked = bed.mainChannel.askSessionTree(spawn())
        runCurrent()

        bed.holder.answerGateQuestion(bed.holder.gateQuestions.value.single(), approved = false)
        runCurrent()

        assertEquals(
            SessionTreeAnswer.Refused("사람이 이 폴더(proj-a)의 설정을 승인하지 않았습니다"),
            asked.answerSoFar(),
        )
        assertEquals(1, bed.childSource.askedRequests.size)
        assertEquals(1, bed.opener.openedSessions.size)
        assertEquals(emptyList(), bed.holder.gateQuestions.value)
        // 물을 때 열어 둔 하위의 소켓도 닫혔다.
        assertEquals(1, bed.channels[1].closeCount)
    }

    @Test
    fun `관문을 묻는 중에 spawn 의 연결이 끊기면 물음을 거두고 아무것도 띄우지 않는다`() = runTest {
        // 상위의 spawn_session 호출이 중단됐다(PR 68). 답을 받을 쪽이 사라졌는데 대화 상자가 남으면, 사람이 승인한 뒤에
        // 상위가 모르는 하위가 뜬다.
        val bed = gateTestBed(gates = listOf(GATE))
        val asked = bed.mainChannel.askSessionTree(spawn())
        runCurrent()

        asked.closeConnection()
        runCurrent()

        assertEquals(emptyList(), bed.holder.gateQuestions.value)
        assertEquals(1, bed.childSource.askedRequests.size)
        assertEquals(1, bed.opener.openedSessions.size)
        assertEquals(emptyList(), bed.holder.sessionTree.value.children)
    }

    @Test
    fun `승인한 뒤 하위를 띄우는 사이 spawn 의 연결이 끊기면 띄운 하위를 끝내고 트리에 세우지 않는다`() = runTest {
        // 사람이 승인한 순간 대화 상자의 감시는 끝나지만, 하위 프로세스가 뜨는 데는 시간이 걸린다. 그 사이 상위의 턴이
        // 중단되면 Spawned 답은 죽은 소켓으로 가고, 상위가 모르는 하위가 남는다.
        val bed = gateTestBed(gates = listOf(GATE))
        val asked = bed.mainChannel.askSessionTree(spawn())
        runCurrent()

        bed.holder.answerGateQuestion(bed.holder.gateQuestions.value.single(), approved = true)
        asked.closeConnection()
        runCurrent()

        assertEquals(2, bed.opener.openedSessions.size, "승인한 지문으로 하위를 띄우기는 했어야 합니다")
        assertEquals(1, bed.opener.openedSessions[1].endSessionCount)
        assertEquals(emptyList(), bed.holder.sessionTree.value.children)
        assertEquals(listOf<SessionTarget>(SessionTarget.Main), bed.holder.heldSessions.value.map { it.target })
    }

    @Test
    fun `사람이 본 뒤 설정이 바뀌었으면 지금 내용으로 다시 묻는다`() = runTest {
        // 엔진은 지문이 지금 내용의 것일 때만 승인을 적는다(childSessionApprovalNeeded) — 바뀐 것은 새 물음이다.
        val changedGate = GATE.copy(
            fingerprint = "sha256:second",
            executionConfigs = listOf(GatedExecutionConfig(".mcp.json", "/w/proj-a/.mcp.json", """{"mcpServers":{"x":{}}}""")),
        )
        val bed = gateTestBed(gates = listOf(GATE, changedGate))
        val asked = bed.mainChannel.askSessionTree(spawn())
        runCurrent()

        bed.holder.answerGateQuestion(bed.holder.gateQuestions.value.single(), approved = true)
        runCurrent()

        assertEquals(changedGate, bed.holder.gateQuestions.value.single().gate)
        assertNull(asked.answerSoFar())

        bed.holder.answerGateQuestion(bed.holder.gateQuestions.value.single(), approved = true)
        runCurrent()

        assertEquals(listOf(null, "sha256:first", "sha256:second"), bed.childSource.askedRequests.map { it.approvedFingerprint })
        assertIs<SessionTreeAnswer.Spawned>(asked.answerSoFar())
    }

    @Test
    fun `이미 답한 물음에 다시 답해도 아무 일도 없다`() = runTest {
        val bed = gateTestBed(gates = listOf(GATE))
        bed.mainChannel.askSessionTree(spawn())
        runCurrent()
        val question = bed.holder.gateQuestions.value.single()
        bed.holder.answerGateQuestion(question, approved = false)
        runCurrent()

        bed.holder.answerGateQuestion(question, approved = true)
        runCurrent()

        assertEquals(1, bed.childSource.askedRequests.size)
    }

    @Test
    fun `같은 자리 같은 일을 다시 물어도 앞서 답한 물음의 답이 되지 않는다`() = runTest {
        // 관문 물음에는 아직 하위의 이름이 없다(이름은 엔진이 띄울 수 있다고 답할 때 짓는다). 물음 하나하나가 열쇠다 —
        // 내용이 같다고 같은 물음으로 읽으면 앞 물음에 늦게 누른 버튼이 다음 물음의 답이 된다.
        val bed = gateTestBed(gates = listOf(GATE, GATE))
        bed.mainChannel.askSessionTree(spawn())
        runCurrent()
        val firstQuestion = bed.holder.gateQuestions.value.single()
        bed.holder.answerGateQuestion(firstQuestion, approved = false)
        runCurrent()
        val asked = bed.mainChannel.askSessionTree(spawn())
        runCurrent()

        bed.holder.answerGateQuestion(firstQuestion, approved = true)
        runCurrent()

        assertNull(asked.answerSoFar())
        assertEquals(1, bed.holder.gateQuestions.value.size)
    }

    private fun spawn() = SessionTreeRequest.SpawnSession(
        prompt = "proj-a 테스트를 고쳐 줘",
        requestedCwd = "proj-a",
        createDirectory = false,
        title = null,
    )

    /** 엔진이 [gates] 를 차례로 관문으로 답하고, 그 뒤로는 띄울 수 있다고 답한다. */
    private fun TestScope.gateTestBed(gates: List<ChildSessionGate>): GateTestBed {
        val opener = RecordingChatSessionOpener()
        val childSource = RecordingChildSessionCommandSource()
        val remainingGates = ArrayDeque(gates)
        childSource.respondWith { request ->
            remainingGates.removeFirstOrNull()?.let(ChildSessionCommandAnswer::ApprovalRequired)
                ?: childSource.readyAnswerFor(request)
        }
        val channels = mutableListOf<FakeAppSocketChannel>()
        val holder = stateHolder(opener = opener, permissionChannels = channels, childSessionCommandSource = childSource)
        holder.enterSession(TEST_WORK_DIR_PATH, SessionTarget.Main, SessionConversationChoice.ContinueRecordedConversation)
        runCurrent()
        return GateTestBed(holder, opener, childSource, channels)
    }

    private class GateTestBed(
        val holder: ChatSessionStateHolder,
        val opener: RecordingChatSessionOpener,
        val childSource: RecordingChildSessionCommandSource,
        val channels: List<FakeAppSocketChannel>,
    ) {
        val mainChannel: FakeAppSocketChannel get() = channels.first()
    }

    private companion object {
        val GATE = ChildSessionGate(
            relativeCwd = "proj-a",
            fingerprint = "sha256:first",
            executionConfigs = listOf(GatedExecutionConfig(".mcp.json", "/w/proj-a/.mcp.json", """{"mcpServers":{}}""")),
        )
    }
}
