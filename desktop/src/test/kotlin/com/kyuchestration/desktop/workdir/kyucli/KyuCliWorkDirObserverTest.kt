package com.kyuchestration.desktop.workdir.kyucli

import com.kyuchestration.desktop.kyu.KyuCommandFailure
import com.kyuchestration.desktop.kyu.KyuCommandResult
import com.kyuchestration.desktop.kyu.RecordingKyuCommandRunner
import com.kyuchestration.desktop.kyu.succeedingKyuCommandResult
import com.kyuchestration.desktop.workdir.WorkDirObservationFailure
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KyuCliWorkDirObserverTest {

    @Test
    fun `워크디렉토리 절대경로를 붙여 kyu list --json 을 부른다`() {
        val runner = RecordingKyuCommandRunner { succeedingKyuCommandResult(EMPTY_WORK_DIR_OUTPUT) }

        KyuCliWorkDirObserver(runner).observe(Path.of("/home/me/work/WorkDir-featureX"))

        assertEquals(
            listOf(listOf("list", "/home/me/work/WorkDir-featureX", "--json")),
            runner.receivedArguments,
        )
    }

    @Test
    fun `상대 경로로 열어도 kyu 에는 절대경로로 넘긴다`() {
        val runner = RecordingKyuCommandRunner { succeedingKyuCommandResult(EMPTY_WORK_DIR_OUTPUT) }

        KyuCliWorkDirObserver(runner).observe(Path.of("work/WorkDir-featureX"))

        // 같은 이름의 레포가 여러 워크디렉토리에 있을 수 있다(설계 문서 11 의 4번). 상대 경로를
        // 그대로 넘기면 kyu 가 자기 작업 디렉토리 기준으로 엉뚱한 곳을 본다.
        assertEquals(
            listOf(Path.of("work/WorkDir-featureX").absolutePathString()),
            runner.receivedArguments.single().filterNot { it == "list" || it == "--json" },
        )
    }

    @Test
    fun `볼 자리를 인자로 못 박았으므로 작업 디렉토리는 정하지 않는다`() {
        val runner = RecordingKyuCommandRunner { succeedingKyuCommandResult(EMPTY_WORK_DIR_OUTPUT) }

        KyuCliWorkDirObserver(runner).observe(Path.of("/w/없는곳"))

        // 없는 경로를 열었을 때 kyu 가 "워크디렉토리 읽기 실패" 라고 답할 수 있는 이유다. 그 자리를
        // 작업 디렉토리로 주면 프로세스가 뜨지도 못해, kyu 의 안내 대신 실행 실패만 남는다.
        assertEquals(listOf<Path?>(null), runner.receivedWorkingDirectories)
    }

    @Test
    fun `정상 출력은 스냅샷이 된다`() {
        val runner = RecordingKyuCommandRunner { succeedingKyuCommandResult(EMPTY_WORK_DIR_OUTPUT) }

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
    fun `kyu 를 부르지 못했다는 사실을 관찰 실패로 옮긴다`() {
        val runner = RecordingKyuCommandRunner { throw KyuCommandFailure.ExecutableNotFound() }

        // 실행기가 던지는 것은 "부를 kyu 가 없다" 는 사실뿐이다. 목록을 보려던 사람에게 할 말
        // ("설치한 뒤 새로고침하세요")로 옮기는 것은 이 어댑터의 몫이다.
        assertFailsWith<WorkDirObservationFailure.KyuExecutableNotFound> {
            KyuCliWorkDirObserver(runner).observe(Path.of("/w"))
        }
    }

    @Test
    fun `kyu 를 띄우지 못한 원인은 관찰 실패의 안내 문구에 남는다`() {
        val runner = RecordingKyuCommandRunner {
            throw KyuCommandFailure.FailedToStart(java.io.IOException("Permission denied"))
        }

        val failure = assertFailsWith<WorkDirObservationFailure.KyuFailedToStart> {
            KyuCliWorkDirObserver(runner).observe(Path.of("/w"))
        }

        assertTrue("Permission denied" in failure.guidance)
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
