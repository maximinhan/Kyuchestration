package com.kyuchestration.desktop.workdir.kyucli

import com.kyuchestration.desktop.workdir.RepoState
import com.kyuchestration.desktop.workdir.WorkDirObservationFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KyuListOutputTest {

    @Test
    fun `문서의 워크디렉토리와 메인 세션과 계획 경고를 그대로 옮긴다`() {
        val snapshot = parseKyuListOutput(
            """
            {
              "schemaVersion": 1,
              "workDir": { "name": "WorkDir-featureX", "absolutePath": "/home/me/work/WorkDir-featureX" },
              "repos": [],
              "mainSession": { "alive": true },
              "planWarnings": ["plan.md 를 읽지 못했습니다"]
            }
            """.trimIndent(),
        )

        assertEquals("WorkDir-featureX", snapshot.name)
        assertEquals("/home/me/work/WorkDir-featureX", snapshot.absolutePath)
        assertTrue(snapshot.mainSessionAlive)
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
        val snapshot = parseKyuListOutput(oneRepoDocument(state = "RUNNING", taskJson = "null"))

        assertNull(snapshot.repos.single().task)
    }

    @Test
    fun `계약에 있는 네 가지 상태를 모두 알아본다`() {
        val states = listOf("RUNNING", "DIRTY", "AHEAD", "IDLE").map {
            parseKyuListOutput(oneRepoDocument(state = it, taskJson = "null")).repos.single().state
        }

        assertEquals(listOf(RepoState.Running, RepoState.Dirty, RepoState.Ahead, RepoState.Idle), states)
    }

    @Test
    fun `모르는 상태를 만나면 멈추지 않고 그 낱말을 그대로 들고 있는다`() {
        val state = parseKyuListOutput(oneRepoDocument(state = "REBASING", taskJson = "null")).repos.single().state

        assertEquals(RepoState.Unrecognized("REBASING"), state)
    }

    @Test
    fun `모르는 필드가 늘어도 읽어낸다`() {
        val snapshot = parseKyuListOutput(
            """
            {
              "schemaVersion": 1,
              "workDir": { "name": "W", "absolutePath": "/w", "createdAt": "2026-01-01" },
              "repos": [],
              "mainSession": { "alive": false, "sessionName": "kyu-W-main" },
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
