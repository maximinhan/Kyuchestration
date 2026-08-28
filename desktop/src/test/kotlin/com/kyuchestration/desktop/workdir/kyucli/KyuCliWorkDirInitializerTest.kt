package com.kyuchestration.desktop.workdir.kyucli

import com.kyuchestration.desktop.kyu.KyuCommandFailure
import com.kyuchestration.desktop.kyu.KyuCommandResult
import com.kyuchestration.desktop.kyu.KyuCommandRunner
import com.kyuchestration.desktop.workdir.WorkDirInitializationResult
import java.io.IOException
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class KyuCliWorkDirInitializerTest {

    @Test
    fun `부모 디렉토리를 작업 디렉토리로 삼아 kyu init 이름 을 부른다`() {
        val runner = RecordingKyuCommandRunner { succeeded() }

        KyuCliWorkDirInitializer(runner).createWorkDir(Path.of("/home/me/work"), "WorkDir-featureX")

        // 사람이 터미널에서 `cd /home/me/work && kyu init WorkDir-featureX` 라고 치는 것과 같은
        // 실행이다. 이름 해석 규칙이 CLI 와 어긋날 자리가 없다.
        assertEquals(listOf(listOf("init", "WorkDir-featureX")), runner.receivedArguments)
        assertEquals(listOf<Path?>(Path.of("/home/me/work")), runner.receivedWorkingDirectories)
    }

    @Test
    fun `만들어진 워크디렉토리의 자리를 돌려준다`() {
        val runner = RecordingKyuCommandRunner { succeeded() }

        val result = KyuCliWorkDirInitializer(runner).createWorkDir(Path.of("/home/me/work"), "WorkDir-featureX")

        assertEquals(
            WorkDirInitializationResult.Initialized(Path.of("/home/me/work/WorkDir-featureX")),
            result,
        )
    }

    @Test
    fun `제자리 초기화는 그 디렉토리에서 인자 없이 부른다`() {
        val runner = RecordingKyuCommandRunner { succeeded() }

        val result = KyuCliWorkDirInitializer(runner).initializeExistingWorkDir(Path.of("/home/me/work/WorkDir-featureX"))

        assertEquals(listOf(listOf("init")), runner.receivedArguments)
        assertEquals(listOf<Path?>(Path.of("/home/me/work/WorkDir-featureX")), runner.receivedWorkingDirectories)
        assertEquals(
            WorkDirInitializationResult.Initialized(Path.of("/home/me/work/WorkDir-featureX")),
            result,
        )
    }

    @Test
    fun `kyu 가 거절하면 그 이유를 그대로 돌려준다`() {
        val runner = RecordingKyuCommandRunner {
            KyuCommandResult(
                exitCode = 1,
                standardOutput = "",
                standardError = "이미 계획 파일이 있습니다: /home/me/work/WorkDir-featureX/.coord/plan.md\n",
            )
        }

        val result = KyuCliWorkDirInitializer(runner).createWorkDir(Path.of("/home/me/work"), "WorkDir-featureX")

        // kyu 는 거절 이유를 사람 말로 적는다. 여기서 다시 지어낸 문구보다 그쪽이 정확하다.
        assertEquals(
            WorkDirInitializationResult.NotInitialized(
                "이미 계획 파일이 있습니다: /home/me/work/WorkDir-featureX/.coord/plan.md",
            ),
            result,
        )
    }

    @Test
    fun `아무 말 없이 실패하면 종료 코드라도 알려준다`() {
        val runner = RecordingKyuCommandRunner { KyuCommandResult(exitCode = 2, standardOutput = "", standardError = "") }

        val result = KyuCliWorkDirInitializer(runner).createWorkDir(Path.of("/home/me/work"), "WorkDir-featureX")

        val notInitialized = assertIs<WorkDirInitializationResult.NotInitialized>(result)
        assertTrue("2" in notInitialized.reason, "이유가 아무것도 없으면 종료 코드가 유일한 단서다: ${notInitialized.reason}")
    }

    @Test
    fun `kyu 를 찾지 못하면 설치하라는 말로 옮긴다`() {
        val runner = RecordingKyuCommandRunner { throw KyuCommandFailure.ExecutableNotFound() }

        val result = KyuCliWorkDirInitializer(runner).createWorkDir(Path.of("/home/me/work"), "WorkDir-featureX")

        val notInitialized = assertIs<WorkDirInitializationResult.NotInitialized>(result)
        assertTrue("설치" in notInitialized.reason, notInitialized.reason)
    }

    @Test
    fun `kyu 를 띄우지 못한 원인은 이유에 그대로 실린다`() {
        val runner = RecordingKyuCommandRunner {
            throw KyuCommandFailure.FailedToStart(IOException("Permission denied"))
        }

        val result = KyuCliWorkDirInitializer(runner).createWorkDir(Path.of("/home/me/work"), "WorkDir-featureX")

        val notInitialized = assertIs<WorkDirInitializationResult.NotInitialized>(result)
        assertTrue("Permission denied" in notInitialized.reason, notInitialized.reason)
    }

    private fun succeeded() = KyuCommandResult(
        exitCode = 0,
        standardOutput = "워크디렉토리를 초기화했습니다: /home/me/work/WorkDir-featureX\n",
        standardError = "",
    )

    private class RecordingKyuCommandRunner(
        private val respondTo: (List<String>) -> KyuCommandResult,
    ) : KyuCommandRunner {

        val receivedArguments = mutableListOf<List<String>>()
        val receivedWorkingDirectories = mutableListOf<Path?>()

        override fun run(arguments: List<String>, workingDirectory: Path?): KyuCommandResult {
            receivedArguments += arguments
            receivedWorkingDirectories += workingDirectory
            return respondTo(arguments)
        }
    }
}
