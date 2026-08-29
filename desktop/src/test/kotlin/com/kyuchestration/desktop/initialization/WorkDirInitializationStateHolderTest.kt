package com.kyuchestration.desktop.initialization

import com.kyuchestration.desktop.workdir.WorkDirInitializationResult
import com.kyuchestration.desktop.workdir.WorkDirInitializer
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class WorkDirInitializationStateHolderTest {

    @Test
    fun `시키는 일이 없는 채로 시작한다`() = runTest {
        val holder = stateHolder(RecordingWorkDirInitializer())

        assertEquals(WorkDirInitializationState.Idle, holder.state.value)
    }

    @Test
    fun `이미 계획이 있는 자리는 초기화하지 않고 그대로 연다`() = runTest {
        val initializer = RecordingWorkDirInitializer()
        val holder = stateHolder(initializer, planFileExists = { true })
        val openedWorkDirPaths = mutableListOf<Path>()

        holder.openChosenDirectoryAsWorkDir(CHOSEN_DIRECTORY, openedWorkDirPaths::add)
        runCurrent()

        // 그대로 kyu init 을 부르면 "이미 계획 파일이 있습니다" 로 거절당한다. 멀쩡히 열리던
        // 워크디렉토리가 실패 문구를 달고 열리지 않게 되는 자리다.
        assertEquals(emptyList(), initializer.initializedWorkDirPaths)
        assertEquals(listOf(CHOSEN_DIRECTORY), openedWorkDirPaths)
        assertEquals(WorkDirInitializationState.Idle, holder.state.value)
    }

    @Test
    fun `계획이 없는 자리는 그 자리에서 초기화한 뒤 연다`() = runTest {
        val initializer = RecordingWorkDirInitializer()
        val holder = stateHolder(initializer, planFileExists = { false })
        val openedWorkDirPaths = mutableListOf<Path>()

        holder.openChosenDirectoryAsWorkDir(CHOSEN_DIRECTORY, openedWorkDirPaths::add)
        runCurrent()

        // 고른 자리 아래에 또 디렉토리를 만들지 않는다. 사용자가 고른 그 자리가 워크디렉토리다.
        assertEquals(listOf(CHOSEN_DIRECTORY), initializer.initializedWorkDirPaths)
        assertEquals(listOf(CHOSEN_DIRECTORY), openedWorkDirPaths)
        assertEquals(WorkDirInitializationState.Idle, holder.state.value)
    }

    @Test
    fun `여는 동안은 진행 중으로 둔다`() = runTest {
        val holder = stateHolder(RecordingWorkDirInitializer())

        holder.openChosenDirectoryAsWorkDir(CHOSEN_DIRECTORY) {}

        assertEquals(WorkDirInitializationState.Running, holder.state.value)
    }

    @Test
    fun `여는 중에 다시 고르면 두 번 초기화하지 않는다`() = runTest {
        val initializer = RecordingWorkDirInitializer()
        val holder = stateHolder(initializer)

        holder.openChosenDirectoryAsWorkDir(CHOSEN_DIRECTORY) {}
        holder.openChosenDirectoryAsWorkDir(CHOSEN_DIRECTORY) {}
        runCurrent()

        // 두 번 부르면 두 번째는 "이미 계획 파일이 있습니다" 로 거절당한다. 방금 성공한 사람에게
        // 실패 문구를 보여주게 되므로, 도는 동안의 요청은 흘려보낸다.
        assertEquals(listOf(CHOSEN_DIRECTORY), initializer.initializedWorkDirPaths)
    }

    @Test
    fun `초기화가 거절당하면 열지 않고 그 이유를 들고 있는다`() = runTest {
        val initializer = RecordingWorkDirInitializer().apply {
            respondWith { WorkDirInitializationResult.NotInitialized("조율 디렉토리 생성 실패: permission denied") }
        }
        val holder = stateHolder(initializer)
        val openedWorkDirPaths = mutableListOf<Path>()

        holder.openChosenDirectoryAsWorkDir(CHOSEN_DIRECTORY, openedWorkDirPaths::add)
        runCurrent()

        assertEquals(emptyList(), openedWorkDirPaths)
        assertEquals(
            WorkDirInitializationState.Failed("조율 디렉토리 생성 실패: permission denied"),
            holder.state.value,
        )
    }

    @Test
    fun `홈 디렉토리는 자동으로 초기화하지 않는다`() = runTest {
        val initializer = RecordingWorkDirInitializer()
        val holder = stateHolder(initializer, userHomeDirectory = temporaryHomeDirectory)
        val openedWorkDirPaths = mutableListOf<Path>()

        holder.openChosenDirectoryAsWorkDir(temporaryHomeDirectory, openedWorkDirPaths::add)
        runCurrent()

        // 홈 전체가 워크디렉토리가 되면 홈 아래 모든 레포가 메인 세션에 --add-dir 로 붙고
        // .coord/plan.md 가 홈에 남는다. 실수의 대가가 큰 자리라 자동으로는 만들지 않는다.
        assertEquals(emptyList(), initializer.initializedWorkDirPaths)
        assertEquals(emptyList(), openedWorkDirPaths)
        val failed = assertIs<WorkDirInitializationState.Failed>(holder.state.value)
        assertTrue("홈 디렉토리" in failed.reason, failed.reason)
    }

    @Test
    fun `홈으로 이어진 심볼릭 링크도 홈으로 본다`() = runTest {
        val holder = stateHolder(RecordingWorkDirInitializer(), userHomeDirectory = temporaryHomeDirectory)
        val linkToHome = temporaryHomeDirectory.parent.resolve("링크로-이어진-홈")
            .createSymbolicLinkPointingTo(temporaryHomeDirectory)

        holder.openChosenDirectoryAsWorkDir(linkToHome) {}
        runCurrent()

        // 경로 문자열로 비교하면 같은 자리인데도 다른 문자열이라 안전장치가 그대로 통과시킨다.
        assertIs<WorkDirInitializationState.Failed>(holder.state.value)
    }

    @Test
    fun `홈 아래의 다른 디렉토리는 막지 않는다`() = runTest {
        val initializer = RecordingWorkDirInitializer()
        val holder = stateHolder(initializer, userHomeDirectory = temporaryHomeDirectory)
        val workDirInsideHome = temporaryHomeDirectory.resolve("WorkDir-featureX").createDirectories()

        holder.openChosenDirectoryAsWorkDir(workDirInsideHome) {}
        runCurrent()

        // 막는 것은 홈 그 자체뿐이다. 홈 아래에 워크디렉토리를 두는 것은 흔한 자리다.
        assertEquals(listOf(workDirInsideHome), initializer.initializedWorkDirPaths)
    }

    @Test
    fun `파일시스템 루트는 자동으로 초기화하지 않는다`() = runTest {
        val initializer = RecordingWorkDirInitializer()
        val holder = stateHolder(initializer)

        holder.openChosenDirectoryAsWorkDir(FILESYSTEM_ROOT) {}
        runCurrent()

        assertEquals(emptyList(), initializer.initializedWorkDirPaths)
        val failed = assertIs<WorkDirInitializationState.Failed>(holder.state.value)
        assertTrue("루트" in failed.reason, failed.reason)
    }

    @Test
    fun `홈이 어디인지 모르면 안전장치가 판단하지 않는다`() = runTest {
        val initializer = RecordingWorkDirInitializer()
        val holder = stateHolder(initializer, userHomeDirectory = null)

        holder.openChosenDirectoryAsWorkDir(CHOSEN_DIRECTORY) {}
        runCurrent()

        // 안전장치가 도구를 못 쓰게 만드는 쪽이 더 나쁘다 — 홈과 상관없는 자리까지 함께 막힌다.
        assertEquals(listOf(CHOSEN_DIRECTORY), initializer.initializedWorkDirPaths)
    }

    @Test
    fun `다른 자리를 다시 고르면 지난 거절 이유가 걷힌다`() = runTest {
        val holder = stateHolder(RecordingWorkDirInitializer(), userHomeDirectory = temporaryHomeDirectory)

        holder.openChosenDirectoryAsWorkDir(temporaryHomeDirectory) {}
        runCurrent()
        assertIs<WorkDirInitializationState.Failed>(holder.state.value)

        holder.openChosenDirectoryAsWorkDir(CHOSEN_DIRECTORY) {}

        assertEquals(WorkDirInitializationState.Running, holder.state.value)
    }

    @Test
    fun `열어 둔 워크디렉토리의 초기화도 같은 자리에서 진행과 실패를 보여준다`() = runTest {
        val initializer = RecordingWorkDirInitializer()
        val holder = stateHolder(initializer)

        holder.initializeOpenedWorkDir(CHOSEN_DIRECTORY)
        assertEquals(WorkDirInitializationState.Running, holder.state.value)

        runCurrent()

        assertEquals(WorkDirInitializationState.Idle, holder.state.value)
        assertEquals(listOf(CHOSEN_DIRECTORY), initializer.initializedWorkDirPaths)
    }

    @Test
    fun `열어 둔 워크디렉토리의 초기화가 거절당하면 그 이유를 들고 있는다`() = runTest {
        val initializer = RecordingWorkDirInitializer().apply {
            respondWith { WorkDirInitializationResult.NotInitialized("계획 파일 생성 실패") }
        }
        val holder = stateHolder(initializer)

        holder.initializeOpenedWorkDir(CHOSEN_DIRECTORY)
        runCurrent()

        assertEquals(WorkDirInitializationState.Failed("계획 파일 생성 실패"), holder.state.value)
    }

    @Test
    fun `워크디렉토리를 놓으면 지난 실패 문구를 거둔다`() = runTest {
        val initializer = RecordingWorkDirInitializer().apply {
            respondWith { WorkDirInitializationResult.NotInitialized("계획 파일 생성 실패") }
        }
        val holder = stateHolder(initializer)

        holder.initializeOpenedWorkDir(CHOSEN_DIRECTORY)
        runCurrent()
        holder.forgetLastFailure()

        // 그대로 두면 워크디렉토리 A 의 거절 이유가 B 의 배너에 뜬다.
        assertEquals(WorkDirInitializationState.Idle, holder.state.value)
    }

    @Test
    fun `도는 중에는 그만두라고 해도 진행 표시를 지우지 않는다`() = runTest {
        val holder = stateHolder(RecordingWorkDirInitializer())

        holder.openChosenDirectoryAsWorkDir(CHOSEN_DIRECTORY) {}
        holder.forgetLastFailure()

        // 진행 표시만 지우면 결과가 돌아왔을 때 기다린 적도 없는 문구가 튀어나온다.
        assertEquals(WorkDirInitializationState.Running, holder.state.value)
    }

    private val temporaryHomeDirectory = createTempDirectory("kyu-initialization-state-holder-test")
        .resolve("홈")
        .createDirectories()

    @AfterTest
    fun 임시_디렉토리를_지운다() {
        temporaryHomeDirectory.parent.toFile().deleteRecursively()
    }

    private fun TestScope.stateHolder(
        workDirInitializer: WorkDirInitializer,
        // 열기는 계획 파일이 있는지부터 본다. 홈·루트 거절과 초기화 실패는 계획이 없을 때만
        // 지나는 길이라, 그쪽을 보는 시험은 이 답이 false 인 채로 시작한다.
        planFileExists: (Path) -> Boolean = { false },
        // 홈을 이 머신의 실제 홈으로 두면 시험이 돌리는 사람의 사정을 탄다. 홈을 고르는 시험은
        // 아예 쓸 수 없게 되고(정말 홈에 파일을 만들 수는 없다), 나머지 시험은 그 사람의 홈이
        // 어디냐에 따라 다른 길을 지난다.
        userHomeDirectory: Path? = null,
    ) = WorkDirInitializationStateHolder(
        workDirInitializer = workDirInitializer,
        planFileExists = planFileExists,
        userHomeDirectory = userHomeDirectory,
        coroutineScope = backgroundScope,
        // 초기화도 프로세스를 띄우고 기다리는 일이라 앱에서는 IO 디스패처로 나간다. 여기서는
        // 가상 시간을 쓰는 디스패처로 바꿔 진행 중인 한순간을 붙잡아 볼 수 있게 한다.
        initializationDispatcher = StandardTestDispatcher(testScheduler),
    )

    private class RecordingWorkDirInitializer : WorkDirInitializer {

        val initializedWorkDirPaths = mutableListOf<Path>()

        private var respond: (Path) -> WorkDirInitializationResult = { WorkDirInitializationResult.Initialized(it) }

        fun respondWith(respond: (Path) -> WorkDirInitializationResult) {
            this.respond = respond
        }

        override fun initializeInPlace(workDirPath: Path): WorkDirInitializationResult {
            // add 로 적는다. Path 는 자기 조각들의 Iterable 이라 `list += path` 는 원소 하나를
            // 더하는 것이 아니라 경로를 풀어헤친 새 리스트를 만든다.
            initializedWorkDirPaths.add(workDirPath)
            return respond(workDirPath)
        }
    }

    private companion object {
        val CHOSEN_DIRECTORY: Path = Path.of("/home/me/work/WorkDir-featureX")
        val FILESYSTEM_ROOT: Path = Path.of("/")
    }
}
