package com.kyuchestration.desktop.workdir.kyucli

import com.kyuchestration.desktop.workdir.RepoState
import com.kyuchestration.desktop.workdir.WorkDirObservationFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KyuListOutputTest {

    @Test
    fun `문서의 워크디렉토리와 계획 경고를 그대로 옮긴다`() {
        val snapshot = parseKyuListOutput(
            """
            {
              "schemaVersion": 1,
              "workDir": { "name": "WorkDir-featureX", "absolutePath": "/home/me/work/WorkDir-featureX" },
              "repos": [],
              "mainSession": { "alive": false, "hasRecordedConversation": true },
              "planWarnings": ["plan.md 를 읽지 못했습니다"]
            }
            """.trimIndent(),
        )

        assertEquals("WorkDir-featureX", snapshot.name)
        assertEquals("/home/me/work/WorkDir-featureX", snapshot.absolutePath)
        assertTrue(snapshot.mainSessionHasRecordedConversation)
        assertEquals(listOf("plan.md 를 읽지 못했습니다"), snapshot.planWarnings)
        assertEquals(emptyList(), snapshot.repos)
    }

    @Test
    fun `레포의 상태와 작업과 진척을 옮긴다`() {
        val snapshot = parseKyuListOutput(
            """
            {
              "schemaVersion": 1,
              "workDir": { "name": "WorkDir-featureX", "absolutePath": "/w" },
              "repos": [
                {
                  "name": "proj-c",
                  "absolutePath": "/w/proj-c",
                  "state": "IDLE",
                  "task": { "id": "consume", "status": "blocked", "blockedBy": ["commons-schema"] },
                  "doneTaskCount": 0,
                  "totalTaskCount": 1
                }
              ],
              "mainSession": { "alive": false },
              "planWarnings": []
            }
            """.trimIndent(),
        )

        val repo = snapshot.repos.single()
        assertEquals("proj-c", repo.name)
        assertEquals("/w/proj-c", repo.absolutePath)
        assertEquals(RepoState.Idle, repo.state)
        assertEquals("consume", repo.task?.id)
        assertEquals("blocked", repo.task?.status)
        assertEquals(listOf("commons-schema"), repo.task?.blockedBy)
        assertEquals(0, repo.doneTaskCount)
        assertEquals(1, repo.totalTaskCount)
    }

    @Test
    fun `고를 작업이 없는 레포는 작업이 없는 것으로 남는다`() {
        val snapshot = parseKyuListOutput(oneRepoDocument(state = "IDLE", taskJson = "null"))

        assertNull(snapshot.repos.single().task)
    }

    @Test
    fun `계약에 있는 세 가지 상태를 모두 알아본다`() {
        val states = listOf("DIRTY", "AHEAD", "IDLE").map {
            parseKyuListOutput(oneRepoDocument(state = it, taskJson = "null")).repos.single().state
        }

        assertEquals(listOf(RepoState.Dirty, RepoState.Ahead, RepoState.Idle), states)
    }

    @Test
    fun `모르는 상태를 만나면 멈추지 않고 그 낱말을 그대로 들고 있는다`() {
        val state = parseKyuListOutput(oneRepoDocument(state = "REBASING", taskJson = "null")).repos.single().state

        assertEquals(RepoState.Unrecognized("REBASING"), state)
    }

    @Test
    fun `RUNNING 을 답하는 낡은 엔진을 만나도 목록은 산다`() {
        // 세션이 엔진의 일이 아니게 되면서 계약에서 사라진 낱말이다. 그래도 사용자가 PATH 에
        // 놓아 둔 옛 kyu 가 그것을 답하는 머신이 있고, 그때 카드가 하나도 안 뜨는 것보다
        // 회색 칩 하나로 뜨는 편이 낫다.
        val state = parseKyuListOutput(oneRepoDocument(state = "RUNNING", taskJson = "null")).repos.single().state

        assertEquals(RepoState.Unrecognized("RUNNING"), state)
    }

    @Test
    fun `읽지 않는 필드가 섞여 있어도 읽어낸다`() {
        // `alive` 도 이제 이쪽이다. 계약은 그 필드를 그대로 두지만 앱은 읽지 않는다 — 세션이
        // 돌고 있는지는 앱이 자기 보유 목록에서 답한다.
        val snapshot = parseKyuListOutput(
            """
            {
              "schemaVersion": 1,
              "workDir": { "name": "W", "absolutePath": "/w", "createdAt": "2026-01-01" },
              "repos": [],
              "mainSession": { "alive": true, "sessionName": "kyu-W-main" },
              "planWarnings": [],
              "observedAt": "2026-01-01T00:00:00Z"
            }
            """.trimIndent(),
        )

        assertEquals("W", snapshot.name)
    }

    @Test
    fun `아는 판이 아니면 판이 다르다고 말한다`() {
        val failure = assertFailsWith<WorkDirObservationFailure.UnsupportedSchemaVersion> {
            parseKyuListOutput(
                """
                { "schemaVersion": 2, "workDir": { "name": "W" } }
                """.trimIndent(),
            )
        }

        assertEquals(2, failure.actualSchemaVersion)
        assertEquals(1, failure.supportedSchemaVersion)
    }

    @Test
    fun `JSON 이 아닌 출력은 읽을 수 없는 출력으로 보고한다`() {
        assertFailsWith<WorkDirObservationFailure.UnreadableKyuOutput> {
            parseKyuListOutput("kyu: 워크디렉토리 읽기 실패")
        }
    }

    @Test
    fun `판은 맞는데 계약이 요구하는 필드가 없으면 읽을 수 없는 출력이다`() {
        assertFailsWith<WorkDirObservationFailure.UnreadableKyuOutput> {
            parseKyuListOutput("""{ "schemaVersion": 1 }""")
        }
    }

    @Test
    fun `이어갈 대화가 적혀 있는 라벨을 그대로 옮긴다`() {
        val snapshot = parseKyuListOutput(
            """
            {
              "schemaVersion": 1,
              "workDir": { "name": "W", "absolutePath": "/w" },
              "repos": [
                {
                  "name": "proj-a",
                  "absolutePath": "/w/proj-a",
                  "state": "IDLE",
                  "task": null,
                  "doneTaskCount": 0,
                  "totalTaskCount": 0,
                  "hasRecordedConversation": true
                }
              ],
              "mainSession": { "alive": false, "hasRecordedConversation": true },
              "planWarnings": []
            }
            """.trimIndent(),
        )

        assertTrue(snapshot.repos.single().hasRecordedConversation)
        assertTrue(snapshot.mainSessionHasRecordedConversation)
    }

    @Test
    fun `그 필드를 내지 않는 엔진과 만나면 이어갈 대화가 없는 것으로 읽는다`() {
        // 엔진과 앱은 따로 배포된다. 계약은 필드가 늘어도 판을 올리지 않으므로, 앱이 이 필드를
        // 배운 뒤에도 그것을 모르는 kyu 와 만나는 일이 실제로 있다 — 그때 목록이 통째로
        // "읽을 수 없는 출력" 이 되면 카드가 하나도 뜨지 않는다.
        val snapshot = parseKyuListOutput(oneRepoDocument(state = "IDLE", taskJson = "null"))

        assertFalse(snapshot.repos.single().hasRecordedConversation)
        assertFalse(snapshot.mainSessionHasRecordedConversation)
    }

    private fun oneRepoDocument(state: String, taskJson: String): String =
        """
        {
          "schemaVersion": 1,
          "workDir": { "name": "W", "absolutePath": "/w" },
          "repos": [
            {
              "name": "proj-a",
              "absolutePath": "/w/proj-a",
              "state": "$state",
              "task": $taskJson,
              "doneTaskCount": 1,
              "totalTaskCount": 2
            }
          ],
          "mainSession": { "alive": false },
          "planWarnings": []
        }
        """.trimIndent()
}
