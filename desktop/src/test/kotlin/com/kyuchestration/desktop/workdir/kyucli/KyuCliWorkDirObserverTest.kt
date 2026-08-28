package com.kyuchestration.desktop.workdir.kyucli

import com.kyuchestration.desktop.workdir.WorkDirObservationFailure
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class KyuCliWorkDirObserverTest {

    @Test
    fun `워크디렉토리 절대경로를 붙여 kyu list --json 을 부른다`() {
        val runner = RecordingKyuCommandRunner { succeedingResult(EMPTY_WORK_DIR_OUTPUT) }

        KyuCliWorkDirObserver(runner).observe(Path.of("/home/me/work/WorkDir-featureX"))

        assertEquals(
            listOf(listOf("list", "/home/me/work/WorkDir-featureX", "--json")),
            runner.receivedArguments,
        )
    }

    @Test
    fun `상대 경로로 열어도 kyu 에는 절대경로로 넘긴다`() {
        val runner = RecordingKyuCommandRunner { succeedingResult(EMPTY_WORK_DIR_OUTPUT) }

        KyuCliWorkDirObserver(runner).observe(Path.of("work/WorkDir-featureX"))

        // 같은 이름의 레포가 여러 워크디렉토리에 있을 수 있다(설계 문서 11 의 4번). 상대 경로를
        // 그대로 넘기면 kyu 가 자기 작업 디렉토리 기준으로 엉뚱한 곳을 본다.
        assertEquals(
            listOf(Path.of("work/WorkDir-featureX").absolutePathString()),
            runner.receivedArguments.single().filterNot { it == "list" || it == "--json" },
        )
    }

    @Test
    fun `정상 출력은 스냅샷이 된다`() {
        val runner = RecordingKyuCommandRunner { succeedingResult(EMPTY_WORK_DIR_OUTPUT) }

        val snapshot = KyuCliWorkDirObserver(runner).observe(Path.of("/w"))

        assertEquals("WorkDir-featureX", snapshot.name)
    }

    @Test
    fun `비정상 종료는 종료 코드와 kyu 가 남긴 이유를 담아 실패한다`() {
        val runner = RecordingKyuCommandRunner {
            KyuCommandResult(
                exitCode = 1,
                standardOutput = "",
                standardError = "워크디렉토리 읽기 실패: /w/없는곳\n",
            )
        }

        val failure = assertFailsWith<WorkDirObservationFailure.KyuExitedWithFailure> {
            KyuCliWorkDirObserver(runner).observe(Path.of("/w/없는곳"))
        }

        assertEquals(1, failure.exitCode)
        assertEquals("워크디렉토리 읽기 실패: /w/없는곳", failure.standardError)
    }

    @Test
    fun `kyu 를 찾지 못했다는 실패는 손대지 않고 그대로 올린다`() {
        val runner = RecordingKyuCommandRunner { throw WorkDirObservationFailure.KyuExecutableNotFound() }

        assertFailsWith<WorkDirObservationFailure.KyuExecutableNotFound> {
            KyuCliWorkDirObserver(runner).observe(Path.of("/w"))
        }
    }

    private fun succeedingResult(standardOutput: String) =
        KyuCommandResult(exitCode = 0, standardOutput = standardOutput, standardError = "")

    private class RecordingKyuCommandRunner(
        private val respondTo: (List<String>) -> KyuCommandResult,
    ) : KyuCommandRunner {

        val receivedArguments = mutableListOf<List<String>>()

        override fun run(arguments: List<String>): KyuCommandResult {
            receivedArguments += arguments
            return respondTo(arguments)
        }
    }

    private companion object {
        val EMPTY_WORK_DIR_OUTPUT = """
            {
              "schemaVersion": 1,
              "workDir": { "name": "WorkDir-featureX", "absolutePath": "/home/me/work/WorkDir-featureX" },
              "repos": [],
              "mainSession": { "alive": false },
              "planWarnings": []
            }
        """.trimIndent()
    }
}
