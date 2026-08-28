package com.kyuchestration.desktop.initialization

import com.kyuchestration.desktop.workdir.WorkDirInitializationResult
import com.kyuchestration.desktop.workdir.WorkDirInitializer
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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
    fun `만드는 동안은 진행 중으로 둔다`() = runTest {
        val holder = stateHolder(RecordingWorkDirInitializer())

        holder.createWorkDir(PARENT_DIRECTORY, "WorkDir-featureX") {}

        assertEquals(WorkDirInitializationState.Running, holder.state.value)
    }

    @Test
    fun `다 만들면 그 자리를 열라고 알리고 진행 표시를 거둔다`() = runTest {
        val initializer = RecordingWorkDirInitializer()
        val holder = stateHolder(initializer)
        val openedWorkDirPaths = mutableListOf<Path>()

        holder.createWorkDir(PARENT_DIRECTORY, "WorkDir-featureX", openedWorkDirPaths::add)
        runCurrent()

        assertEquals(listOf(PARENT_DIRECTORY.resolve("WorkDir-featureX")), openedWorkDirPaths)
        assertEquals(WorkDirInitializationState.Idle, holder.state.value)
        assertEquals(listOf(PARENT_DIRECTORY to "WorkDir-featureX"), initializer.createdWorkDirs)
    }

    @Test
    fun `이름 앞뒤의 공백은 떼고 넘긴다`() = runTest {
        val initializer = RecordingWorkDirInitializer()
        val holder = stateHolder(initializer)

        holder.createWorkDir(PARENT_DIRECTORY, "  WorkDir-featureX  ") {}
        runCurrent()

        assertEquals(listOf(PARENT_DIRECTORY to "WorkDir-featureX"), initializer.createdWorkDirs)
    }

    @Test
    fun `이름이 비어 있으면 엔진을 부르지 않고 되묻는다`() = runTest {
        val initializer = RecordingWorkDirInitializer()
        val holder = stateHolder(initializer)

        holder.createWorkDir(PARENT_DIRECTORY, "   ") {}
        runCurrent()

        // 빈 이름을 그대로 넘기면 kyu 는 그것을 "인자가 하나 있다" 로 읽어 부모 디렉토리 자신을
        // 초기화한다. 사용자가 시키지 않은 자리에 계획 파일이 생기는 실행이라 여기서 막는다.
        assertEquals(emptyList(), initializer.createdWorkDirs)
        assertIs<WorkDirInitializationState.Failed>(holder.state.value)
    }

    @Test
    fun `엔진이 거절하면 그 이유를 들고 있는다`() = runTest {
        val initializer = RecordingWorkDirInitializer().apply {
            respondWith { WorkDirInitializationResult.NotInitialized("이미 계획 파일이 있습니다") }
        }
        val holder = stateHolder(initializer)

        holder.createWorkDir(PARENT_DIRECTORY, "WorkDir-featureX") {}
        runCurrent()

        assertEquals(WorkDirInitializationState.Failed("이미 계획 파일이 있습니다"), holder.state.value)
    }

    @Test
    fun `거절당한 자리를 다시 누르면 지난 이유가 걷힌다`() = runTest {
        val initializer = RecordingWorkDirInitializer().apply {
            respondWith { WorkDirInitializationResult.NotInitialized("이미 계획 파일이 있습니다") }
        }
        val holder = stateHolder(initializer)

        holder.createWorkDir(PARENT_DIRECTORY, "WorkDir-featureX") {}
        runCurrent()
        holder.createWorkDir(PARENT_DIRECTORY, "WorkDir-다른이름") {}

        assertEquals(WorkDirInitializationState.Running, holder.state.value)
    }

    @Test
    fun `만드는 중에 다시 누르면 두 번 만들지 않는다`() = runTest {
        val initializer = RecordingWorkDirInitializer()
        val holder = stateHolder(initializer)

        holder.createWorkDir(PARENT_DIRECTORY, "WorkDir-featureX") {}
        holder.createWorkDir(PARENT_DIRECTORY, "WorkDir-featureX") {}
        runCurrent()

        // 두 번 부르면 두 번째는 "이미 계획 파일이 있습니다" 로 거절당한다. 방금 성공한 사람에게
        // 실패 문구를 보여주게 되므로, 도는 동안의 요청은 흘려보낸다.
        assertEquals(listOf(PARENT_DIRECTORY to "WorkDir-featureX"), initializer.createdWorkDirs)
    }

    @Test
    fun `이미 있는 디렉토리의 초기화도 같은 자리에서 진행과 실패를 보여준다`() = runTest {
        val initializer = RecordingWorkDirInitializer()
        val holder = stateHolder(initializer)

        holder.initializeExistingWorkDir(EXISTING_WORK_DIR_PATH)
        assertEquals(WorkDirInitializationState.Running, holder.state.value)

        runCurrent()

        assertEquals(WorkDirInitializationState.Idle, holder.state.value)
        assertEquals(listOf(EXISTING_WORK_DIR_PATH), initializer.initializedWorkDirPaths)
    }

    @Test
    fun `이미 있는 디렉토리의 초기화가 거절당하면 그 이유를 들고 있는다`() = runTest {
        val initializer = RecordingWorkDirInitializer().apply {
            respondWith { WorkDirInitializationResult.NotInitialized("계획 파일 생성 실패") }
        }
        val holder = stateHolder(initializer)

        holder.initializeExistingWorkDir(EXISTING_WORK_DIR_PATH)
        runCurrent()

        assertEquals(WorkDirInitializationState.Failed("계획 파일 생성 실패"), holder.state.value)
    }

    private fun TestScope.stateHolder(workDirInitializer: WorkDirInitializer) = WorkDirInitializationStateHolder(
        workDirInitializer = workDirInitializer,
        coroutineScope = backgroundScope,
        // 초기화도 프로세스를 띄우고 기다리는 일이라 앱에서는 IO 디스패처로 나간다. 여기서는
        // 가상 시간을 쓰는 디스패처로 바꿔 진행 중인 한순간을 붙잡아 볼 수 있게 한다.
        initializationDispatcher = StandardTestDispatcher(testScheduler),
    )

    private class RecordingWorkDirInitializer : WorkDirInitializer {

        val createdWorkDirs = mutableListOf<Pair<Path, String>>()
        val initializedWorkDirPaths = mutableListOf<Path>()

        private var respond: (Path) -> WorkDirInitializationResult = { WorkDirInitializationResult.Initialized(it) }

        fun respondWith(respond: (Path) -> WorkDirInitializationResult) {
            this.respond = respond
        }

        override fun createWorkDir(parentDirectory: Path, newWorkDirName: String): WorkDirInitializationResult {
            createdWorkDirs.add(parentDirectory to newWorkDirName)
            return respond(parentDirectory.resolve(newWorkDirName))
        }

        override fun initializeExistingWorkDir(workDirPath: Path): WorkDirInitializationResult {
            // add 로 적는다. Path 는 자기 조각들의 Iterable 이라 `list += path` 는 원소 하나를
            // 더하는 것이 아니라 경로를 풀어헤친 새 리스트를 만든다.
            initializedWorkDirPaths.add(workDirPath)
            return respond(workDirPath)
        }
    }

    private companion object {
        val PARENT_DIRECTORY: Path = Path.of("/home/me/work")
        val EXISTING_WORK_DIR_PATH: Path = Path.of("/home/me/work/WorkDir-이미-있던-곳")
    }
}
