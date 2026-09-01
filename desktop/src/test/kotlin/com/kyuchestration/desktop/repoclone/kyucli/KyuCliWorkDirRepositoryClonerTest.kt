package com.kyuchestration.desktop.repoclone.kyucli

import com.kyuchestration.desktop.kyu.KyuCommandResult
import com.kyuchestration.desktop.kyu.RecordingKyuCommandRunner
import com.kyuchestration.desktop.kyu.succeedingKyuCommandResult
import com.kyuchestration.desktop.repoclone.CloneStatus
import com.kyuchestration.desktop.repoclone.CloneStepFailure
import com.kyuchestration.desktop.repoclone.RepositoryCloneResult
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KyuCliWorkDirRepositoryClonerTest {

    @Test
    fun `고른 레포마다 --repo 를 붙여 워크디렉토리 안에서 kyu clone 을 부른다`() {
        val runner = RecordingKyuCommandRunner { succeedingKyuCommandResult(THREE_RESULTS_OUTPUT) }

        KyuCliWorkDirRepositoryCloner(runner).cloneInto(
            workDirPath = WORK_DIR_PATH,
            profileName = "개인",
            repositoryFullNames = listOf("maximinhan/proj-a", "acme/proj-b"),
        )

        assertEquals(
            listOf(
                listOf(
                    "clone",
                    "--profile", "개인",
                    "--repo", "maximinhan/proj-a",
                    "--repo", "acme/proj-b",
                    "--json",
                ),
            ),
            runner.receivedArguments,
        )
    }

    @Test
    fun `받을 자리는 인자가 아니라 작업 디렉토리로 정해진다`() {
        val runner = RecordingKyuCommandRunner { succeedingKyuCommandResult(THREE_RESULTS_OUTPUT) }

        KyuCliWorkDirRepositoryCloner(runner).cloneInto(WORK_DIR_PATH, "개인", listOf("maximinhan/proj-a"))

        // kyu clone 은 받을 자리를 자기 작업 디렉토리로 정한다(clone.go 의 currentWorkDir).
        // 그 자리를 주지 않으면 레포가 앱을 띄운 디렉토리에 떨어진다.
        assertEquals(listOf<Path?>(WORK_DIR_PATH), runner.receivedWorkingDirectories)
    }

    @Test
    fun `레포마다 어떻게 끝났는지를 시킨 순서 그대로 옮긴다`() {
        val runner = RecordingKyuCommandRunner { succeedingKyuCommandResult(THREE_RESULTS_OUTPUT) }

        val outcome = KyuCliWorkDirRepositoryCloner(runner)
            .cloneInto(WORK_DIR_PATH, "개인", listOf("maximinhan/proj-a", "maximinhan/proj-b", "maximinhan/proj-z"))

        assertEquals(
            listOf(
                RepositoryCloneResult("maximinhan/proj-a", CloneStatus.Cloned, ""),
                RepositoryCloneResult("maximinhan/proj-b", CloneStatus.Skipped, "같은 이름의 디렉토리가 이미 있습니다"),
                RepositoryCloneResult(
                    "maximinhan/proj-z",
                    CloneStatus.Failed,
                    "maximinhan 의 레포 목록에 proj-z 가 없습니다",
                ),
            ),
            outcome.results,
        )
    }

    @Test
    fun `실패한 레포가 섞여 종료 코드가 1 이어도 결과 문서를 읽는다`() {
        val runner = RecordingKyuCommandRunner {
            KyuCommandResult(exitCode = 1, standardOutput = THREE_RESULTS_OUTPUT, standardError = "")
        }

        val outcome = KyuCliWorkDirRepositoryCloner(runner).cloneInto(WORK_DIR_PATH, "개인", listOf("maximinhan/proj-z"))

        // 어느 레포가 왜 실패했는지는 이 문서에만 있다(clone_json.go). 종료 코드만 보고 실패로
        // 넘기면 화면은 사용자에게 아무것도 설명하지 못한다.
        assertEquals(3, outcome.results.size)
    }

    @Test
    fun `시작하지도 못한 실패는 kyu 가 남긴 이유로 올린다`() {
        val runner = RecordingKyuCommandRunner {
            KyuCommandResult(
                exitCode = 1,
                standardOutput = "",
                standardError = "등록되지 않은 프로필입니다: 개인\n",
            )
        }

        val failure = assertFailsWith<CloneStepFailure.KyuExitedWithFailure> {
            KyuCliWorkDirRepositoryCloner(runner).cloneInto(WORK_DIR_PATH, "개인", listOf("maximinhan/proj-a"))
        }

        // 시도 자체가 없었던 것과 "전부 실패" 는 다른 사실이고, 그 구분은 문서가 나왔는지로 갈린다.
        assertEquals("등록되지 않은 프로필입니다: 개인", failure.standardError)
    }

    @Test
    fun `모르는 끝맺음을 만나도 멈추지 않고 그 낱말을 들고 있는다`() {
        val runner = RecordingKyuCommandRunner {
            succeedingKyuCommandResult(
                """
                {
                  "schemaVersion": 1,
                  "results": [ { "repo": "me/proj-a", "status": "retried", "message": "" } ]
                }
                """.trimIndent(),
            )
        }

        val outcome = KyuCliWorkDirRepositoryCloner(runner).cloneInto(WORK_DIR_PATH, "개인", listOf("me/proj-a"))

        assertEquals(CloneStatus.Unrecognized("retried"), outcome.results.single().status)
    }

    @Test
    fun `아는 판이 아니면 판이 다르다고 말한다`() {
        val runner = RecordingKyuCommandRunner {
            succeedingKyuCommandResult("""{ "schemaVersion": 2, "results": [] }""")
        }

        assertFailsWith<CloneStepFailure.UnsupportedSchemaVersion> {
            KyuCliWorkDirRepositoryCloner(runner).cloneInto(WORK_DIR_PATH, "개인", listOf("me/proj-a"))
        }
    }

    private companion object {
        val WORK_DIR_PATH: Path = Path.of("/home/me/work/WorkDir-featureX")

        // mainSessionRestartNeeded 를 그대로 둔다. 계약이 아직 내는 필드이고, 앱이 그것을 읽지
        // 않으면서도 문서를 통째로 읽어내는지가 이 문서로 함께 확인된다.
        val THREE_RESULTS_OUTPUT = """
            {
              "schemaVersion": 1,
              "results": [
                { "repo": "maximinhan/proj-a", "status": "cloned",  "message": "" },
                { "repo": "maximinhan/proj-b", "status": "skipped", "message": "같은 이름의 디렉토리가 이미 있습니다" },
                { "repo": "maximinhan/proj-z", "status": "failed",  "message": "maximinhan 의 레포 목록에 proj-z 가 없습니다" }
              ],
              "mainSessionRestartNeeded": true
            }
        """.trimIndent()
    }
}
