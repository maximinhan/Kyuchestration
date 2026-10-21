package com.kyuchestration.desktop.terminal.kyucli

import com.kyuchestration.desktop.kyu.KyuCommandResult
import com.kyuchestration.desktop.kyu.RecordingKyuCommandRunner
import com.kyuchestration.desktop.kyu.succeedingKyuCommandResult
import com.kyuchestration.desktop.terminal.AssignedChildSession
import com.kyuchestration.desktop.terminal.ChildSessionCommandAnswer
import com.kyuchestration.desktop.terminal.ChildSessionCommandRequest
import com.kyuchestration.desktop.terminal.ChildSessionGate
import com.kyuchestration.desktop.terminal.GatedExecutionConfig
import com.kyuchestration.desktop.terminal.TerminalSessionFailure
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * `kyu session-command --chat --child` 를 묻고 그 답을 읽는다(session-tree-design.md 7 절 3 단계의 계약).
 *
 * 옵션 이름과 답의 필드 이름이 곧 엔진과 맺은 계약이다(session_command_child.go · session_command_json.go).
 */
class KyuCliChildSessionCommandSourceTest {

    @Test
    fun `상위가 고른 것과 앱이 아는 살아 있는 하위들을 옵션으로 옮긴다`() {
        val runner = RecordingKyuCommandRunner { succeedingKyuCommandResult(READY_DOCUMENT) }

        KyuCliChildSessionCommandSource(runner).childSessionCommandFor(
            WORK_DIR_PATH,
            ChildSessionCommandRequest(
                requestedCwd = "scratch-x",
                createDirectory = true,
                title = "실험",
                liveChildRelativeCwds = listOf("proj-a", "proj-b"),
                approvedFingerprint = null,
            ),
            appSocketPath = SOCKET_PATH,
        )

        assertEquals(
            listOf(
                listOf(
                    "session-command", "--chat", "--child",
                    "--cwd", "scratch-x", "--create-directory", "--title", "실험",
                    "--live-child-cwd", "proj-a", "--live-child-cwd", "proj-b",
                    "--app-socket", SOCKET_PATH.toString(), "--json",
                ),
            ),
            runner.receivedArguments,
        )
        assertEquals(listOf<Path?>(WORK_DIR_PATH), runner.receivedWorkingDirectories)
    }

    @Test
    fun `상위가 생략한 것은 옵션도 생략한다`() {
        // 빈 --cwd 를 붙이면 엔진은 그것을 "루트" 가 아니라 "빈 경로" 로 읽을 수 있다 — 생략의 풀이는 엔진의 몫이다.
        val runner = RecordingKyuCommandRunner { succeedingKyuCommandResult(READY_DOCUMENT) }

        KyuCliChildSessionCommandSource(runner).childSessionCommandFor(
            WORK_DIR_PATH,
            ChildSessionCommandRequest(null, false, null, emptyList(), approvedFingerprint = null),
            appSocketPath = SOCKET_PATH,
        )

        assertEquals(
            listOf(listOf("session-command", "--chat", "--child", "--app-socket", SOCKET_PATH.toString(), "--json")),
            runner.receivedArguments,
        )
    }

    @Test
    fun `사람이 승인한 지문을 함께 묻는다`() {
        val runner = RecordingKyuCommandRunner { succeedingKyuCommandResult(READY_DOCUMENT) }

        KyuCliChildSessionCommandSource(runner).childSessionCommandFor(
            WORK_DIR_PATH,
            ChildSessionCommandRequest("proj-a", false, null, emptyList(), approvedFingerprint = "sha256:abc"),
            appSocketPath = SOCKET_PATH,
        )

        assertEquals(
            listOf(
                listOf(
                    "session-command", "--chat", "--child", "--cwd", "proj-a",
                    "--approve-fingerprint", "sha256:abc", "--app-socket", SOCKET_PATH.toString(), "--json",
                ),
            ),
            runner.receivedArguments,
        )
    }

    @Test
    fun `띄울 수 있으면 명령과 배정된 하위를 함께 돌려준다`() {
        val runner = RecordingKyuCommandRunner { succeedingKyuCommandResult(READY_DOCUMENT) }

        val answer = KyuCliChildSessionCommandSource(runner).childSessionCommandFor(
            WORK_DIR_PATH,
            ChildSessionCommandRequest("proj-a", false, null, emptyList(), null),
            SOCKET_PATH,
        )

        val ready = assertIs<ChildSessionCommandAnswer.Ready>(answer)
        assertEquals(listOf("claude", "--session-id", "5e0c"), ready.sessionCommandAnswer.command)
        assertEquals(WORK_DIR_PATH.resolve("proj-a"), ready.sessionCommandAnswer.workingDirectory)
        assertEquals(AssignedChildSession("child-1", "proj-a", "proj-a", "5e0c"), ready.assignedChildSession)
    }

    @Test
    fun `관문에 걸리면 보일 내용을 돌려준다`() {
        val runner = RecordingKyuCommandRunner { succeedingKyuCommandResult(APPROVAL_REQUIRED_DOCUMENT) }

        val answer = KyuCliChildSessionCommandSource(runner).childSessionCommandFor(
            WORK_DIR_PATH,
            ChildSessionCommandRequest("proj-a", false, null, emptyList(), null),
            SOCKET_PATH,
        )

        assertEquals(
            ChildSessionCommandAnswer.ApprovalRequired(
                ChildSessionGate(
                    relativeCwd = "proj-a",
                    fingerprint = "sha256:abc",
                    executionConfigs = listOf(
                        GatedExecutionConfig(".mcp.json", "/home/me/work/WorkDir-featureX/proj-a/.mcp.json", """{"mcpServers":{}}"""),
                    ),
                ),
            ),
            answer,
        )
    }

    @Test
    fun `엔진이 거절하면 그 이유를 새 하위의 것으로 올린다`() {
        // 이름은 엔진이 기록에 적을 때 짓는다 — 거절된 하위에게는 아직 이름이 없다.
        // cwd 규칙의 거절이 여기로 온다(exit 1 + stderr). 그 문구가 그대로 상위 모델에게 간다.
        val runner = RecordingKyuCommandRunner {
            KyuCommandResult(exitCode = 1, standardOutput = "", standardError = "살아 있는 하위가 이미 proj-a 에 있습니다\n")
        }

        val failure = assertFailsWith<TerminalSessionFailure.SessionCommandRefused> {
            KyuCliChildSessionCommandSource(runner).childSessionCommandFor(
                WORK_DIR_PATH,
                ChildSessionCommandRequest("proj-a", false, null, listOf("proj-a"), null),
                SOCKET_PATH,
            )
        }

        assertEquals("새 하위", failure.targetLabel)
        assertEquals("살아 있는 하위가 이미 proj-a 에 있습니다", failure.guidance)
    }

    @Test
    fun `id 를 묻지 않는다 — 하위의 이름은 엔진이 지어 답에 싣는다`() {
        // 앱과 엔진이 각자 이름을 지으면 둘이 어긋날 수 있다(PR #70 — conversations.go 의 nextChildSessionID).
        val runner = RecordingKyuCommandRunner { succeedingKyuCommandResult(READY_DOCUMENT.replace("child-1", "child-7")) }

        val answer = KyuCliChildSessionCommandSource(runner).childSessionCommandFor(
            WORK_DIR_PATH,
            ChildSessionCommandRequest("proj-a", false, null, emptyList(), null),
            SOCKET_PATH,
        )

        val childOptionIndex = runner.receivedArguments.single().indexOf("--child")
        assertEquals("--cwd", runner.receivedArguments.single()[childOptionIndex + 1])
        assertEquals("child-7", assertIs<ChildSessionCommandAnswer.Ready>(answer).assignedChildSession.childId)
    }

    @Test
    fun `성공한 답의 stderr 는 엔진의 경고로 함께 돌려준다`() {
        // 버려진 대화 기록 같은 말이다. 엔진은 stdout 을 문서 하나에 쓰고 사람에게 할 말은 stderr 로 낸다.
        val runner = RecordingKyuCommandRunner {
            KyuCommandResult(exitCode = 0, standardOutput = READY_DOCUMENT, standardError = "대화 기록을 버렸습니다\n")
        }

        val answer = KyuCliChildSessionCommandSource(runner).childSessionCommandFor(
            WORK_DIR_PATH,
            ChildSessionCommandRequest("proj-a", false, null, emptyList(), null),
            SOCKET_PATH,
        )

        assertEquals(
            listOf("대화 기록을 버렸습니다"),
            assertIs<ChildSessionCommandAnswer.Ready>(answer).sessionCommandAnswer.engineWarnings,
        )
    }

    @Test
    fun `거절한 엔진이 최소 판보다 낮은 릴리스면 엔진 갱신이 필요하다고 올린다`() {
        // PR #70 이전 kyu 는 --child 를 모르거나 id 를 요구해 모든 spawn 을 거절한다. 그 거절을 cwd 거절과 같은 모양으로
        // 올리면 사람도 상위 모델도 엔진이 낡았다는 것을 알 길이 없다.
        val runner = RecordingKyuCommandRunner { arguments ->
            if (arguments == listOf("version")) {
                succeedingKyuCommandResult("kyu v0.13.0 (3edd9cf05752)\n")
            } else {
                KyuCommandResult(exitCode = 1, standardOutput = "", standardError = "알 수 없는 옵션: --child\n")
            }
        }

        val failure = assertFailsWith<TerminalSessionFailure.EngineTooOld> {
            KyuCliChildSessionCommandSource(runner).childSessionCommandFor(
                WORK_DIR_PATH,
                ChildSessionCommandRequest("proj-a", false, null, emptyList(), null),
                SOCKET_PATH,
            )
        }

        assertEquals("0.13.0", failure.actualVersion)
        assertEquals("0.14.0", failure.minimumVersion)
        assertContains(failure.message.orEmpty(), "엔진 갱신 필요(최소 0.14.0)")
        assertContains(failure.guidance, "알 수 없는 옵션: --child")
    }

    @Test
    fun `거절한 엔진이 최소 판 이상이면 엔진의 거절 그대로다`() {
        val runner = RecordingKyuCommandRunner { arguments ->
            if (arguments == listOf("version")) {
                succeedingKyuCommandResult("kyu v0.14.0 (3edd9cf05752)\n")
            } else {
                KyuCommandResult(exitCode = 1, standardOutput = "", standardError = "워크디렉토리 밖입니다: ../x\n")
            }
        }

        val failure = assertFailsWith<TerminalSessionFailure.SessionCommandRefused> {
            KyuCliChildSessionCommandSource(runner).childSessionCommandFor(
                WORK_DIR_PATH,
                ChildSessionCommandRequest("../x", false, null, emptyList(), null),
                SOCKET_PATH,
            )
        }

        assertEquals("워크디렉토리 밖입니다: ../x", failure.guidance)
    }

    @Test
    fun `소스로 빌드한 엔진은 판으로 낡았는지 가를 수 없어 엔진의 거절 그대로다`() {
        // v0.13.0 뒤의 커밋은 PR #70 앞이든 뒤든 모두 v0.13.1-0.<시각>-<커밋> 꼴이다. 이 수로 낡았다고 하면 새 엔진도 낡았다고 한다.
        val runner = RecordingKyuCommandRunner { arguments ->
            if (arguments == listOf("version")) {
                succeedingKyuCommandResult("kyu v0.13.1-0.20261021075557-058baab94744+dirty\n")
            } else {
                KyuCommandResult(exitCode = 1, standardOutput = "", standardError = "알 수 없는 옵션: --child\n")
            }
        }

        assertFailsWith<TerminalSessionFailure.SessionCommandRefused> {
            KyuCliChildSessionCommandSource(runner).childSessionCommandFor(
                WORK_DIR_PATH,
                ChildSessionCommandRequest("proj-a", false, null, emptyList(), null),
                SOCKET_PATH,
            )
        }
    }

    @Test
    fun `엔진이 답하면 판을 묻지 않는다`() {
        val runner = RecordingKyuCommandRunner { succeedingKyuCommandResult(READY_DOCUMENT) }

        KyuCliChildSessionCommandSource(runner).childSessionCommandFor(
            WORK_DIR_PATH,
            ChildSessionCommandRequest("proj-a", false, null, emptyList(), null),
            SOCKET_PATH,
        )

        assertEquals(1, runner.receivedArguments.size)
    }

    @Test
    fun `하위 답인데 childSession 도 approvalRequired 도 없으면 읽지 못한 답이다`() {
        // 이 필드를 모르는 엔진이 사람 세션의 답을 한 것이다. 그 명령을 실행하면 하위가 아닌 것이 하위 자리에 뜬다.
        val runner = RecordingKyuCommandRunner {
            succeedingKyuCommandResult("""{"schemaVersion":1,"command":["claude"],"cwd":"/w","env":{}}""")
        }

        assertFailsWith<TerminalSessionFailure.UnreadableKyuOutput> {
            KyuCliChildSessionCommandSource(runner).childSessionCommandFor(
                WORK_DIR_PATH,
                ChildSessionCommandRequest(null, false, null, emptyList(), null),
                SOCKET_PATH,
            )
        }
    }

    private companion object {
        val WORK_DIR_PATH: Path = Path.of("/home/me/work/WorkDir-featureX")
        val SOCKET_PATH: Path = Path.of("/run/user/1000/kyuchestration/c1.sock")

        val READY_DOCUMENT = """
            {
              "schemaVersion": 1,
              "command": ["claude", "--session-id", "5e0c"],
              "cwd": "/home/me/work/WorkDir-featureX/proj-a",
              "env": {},
              "resumedConversationId": null,
              "childSession": {"id": "child-1", "cwd": "proj-a", "title": "proj-a", "conversationId": "5e0c"},
              "approvalRequired": null
            }
        """.trimIndent()

        val APPROVAL_REQUIRED_DOCUMENT = """
            {
              "schemaVersion": 1,
              "command": [],
              "cwd": "/home/me/work/WorkDir-featureX/proj-a",
              "env": {},
              "resumedConversationId": null,
              "childSession": null,
              "approvalRequired": {
                "cwd": "proj-a",
                "fingerprint": "sha256:abc",
                "executionConfigs": [
                  {"relativePath": ".mcp.json", "absolutePath": "/home/me/work/WorkDir-featureX/proj-a/.mcp.json",
                   "content": "{\"mcpServers\":{}}"}
                ]
              }
            }
        """.trimIndent()
    }
}
