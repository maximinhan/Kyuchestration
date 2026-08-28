package com.kyuchestration.desktop.dashboard

import com.kyuchestration.desktop.workdir.WorkDirObservationFailure
import com.kyuchestration.desktop.workdir.WorkDirObserver
import com.kyuchestration.desktop.workdir.WorkDirSnapshot
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class WorkDirDashboardStateHolderTest {

    @Test
    fun `아무것도 열지 않은 채로 시작한다`() = runTest {
        val holder = stateHolder(RecordingWorkDirObserver())

        assertEquals(WorkDirDashboardState.NoWorkDirOpened, holder.state.value)
    }

    @Test
    fun `첫 관찰이 끝나기 전까지는 진행 중으로 둔다`() = runTest {
        val holder = stateHolder(RecordingWorkDirObserver())

        holder.openWorkDir(WORK_DIR_PATH)

        assertEquals(WorkDirDashboardState.FirstObservationRunning(WORK_DIR_PATH), holder.state.value)
    }

    @Test
    fun `첫 관찰이 끝나면 그 스냅샷을 들고 있는다`() = runTest {
        val observer = RecordingWorkDirObserver()
        val holder = stateHolder(observer)

        holder.openWorkDir(WORK_DIR_PATH)
        runCurrent()

        val observed = assertIs<WorkDirDashboardState.WorkDirObserved>(holder.state.value)
        assertEquals(WORK_DIR_PATH, observed.workDirPath)
        assertEquals("WorkDir-featureX", observed.snapshot.name)
        assertNull(observed.lastRefreshFailure)
        assertEquals(listOf(WORK_DIR_PATH), observer.observedPaths)
    }

    @Test
    fun `주기가 지날 때마다 다시 관찰한다`() = runTest {
        val observer = RecordingWorkDirObserver()
        val holder = stateHolder(observer)

        holder.openWorkDir(WORK_DIR_PATH)
        runCurrent()
        advanceTimeBy(REFRESH_INTERVAL)
        runCurrent()
        advanceTimeBy(REFRESH_INTERVAL)
        runCurrent()

        assertEquals(3, observer.observedPaths.size)
    }

    @Test
    fun `주기가 다 지나지 않았으면 다시 관찰하지 않는다`() = runTest {
        val observer = RecordingWorkDirObserver()
        val holder = stateHolder(observer)

        holder.openWorkDir(WORK_DIR_PATH)
        runCurrent()
        advanceTimeBy(REFRESH_INTERVAL - 1.seconds)
        runCurrent()

        assertEquals(1, observer.observedPaths.size)
    }

    @Test
    fun `재관찰이 실패해도 이전 스냅샷을 지우지 않고 실패만 덧붙인다`() = runTest {
        val observer = RecordingWorkDirObserver()
        val holder = stateHolder(observer)

        holder.openWorkDir(WORK_DIR_PATH)
        runCurrent()
        val firstSnapshot = assertIs<WorkDirDashboardState.WorkDirObserved>(holder.state.value).snapshot

        observer.respondWith { throw WorkDirObservationFailure.KyuExecutableNotFound() }
        advanceTimeBy(REFRESH_INTERVAL)
        runCurrent()

        val observed = assertIs<WorkDirDashboardState.WorkDirObserved>(holder.state.value)
        // 갱신 한 번 실패했다고 목록이 사라지면, 사용자는 워크디렉토리가 없어진 것과 구분할 수 없다.
        assertSame(firstSnapshot, observed.snapshot)
        assertIs<WorkDirObservationFailure.KyuExecutableNotFound>(observed.lastRefreshFailure)
    }

    @Test
    fun `재관찰이 다시 성공하면 실패 표시가 사라진다`() = runTest {
        val observer = RecordingWorkDirObserver()
        val holder = stateHolder(observer)

        holder.openWorkDir(WORK_DIR_PATH)
        runCurrent()
        observer.respondWith { throw WorkDirObservationFailure.KyuExecutableNotFound() }
        advanceTimeBy(REFRESH_INTERVAL)
        runCurrent()

        observer.respondWith { snapshotNamed("WorkDir-다시-읽힘") }
        advanceTimeBy(REFRESH_INTERVAL)
        runCurrent()

        val observed = assertIs<WorkDirDashboardState.WorkDirObserved>(holder.state.value)
        assertEquals("WorkDir-다시-읽힘", observed.snapshot.name)
        assertNull(observed.lastRefreshFailure)
    }

    @Test
    fun `첫 관찰이 실패하면 보여줄 스냅샷 없이 실패한 상태가 된다`() = runTest {
        val observer = RecordingWorkDirObserver().apply {
            respondWith { throw WorkDirObservationFailure.KyuExecutableNotFound() }
        }
        val holder = stateHolder(observer)

        holder.openWorkDir(WORK_DIR_PATH)
        runCurrent()

        val failed = assertIs<WorkDirDashboardState.FirstObservationFailed>(holder.state.value)
        assertEquals(WORK_DIR_PATH, failed.workDirPath)
        assertIs<WorkDirObservationFailure.KyuExecutableNotFound>(failed.failure)
    }

    @Test
    fun `실패한 뒤에도 주기 갱신은 계속 돌아 저절로 회복한다`() = runTest {
        val observer = RecordingWorkDirObserver().apply {
            respondWith { throw WorkDirObservationFailure.KyuExecutableNotFound() }
        }
        val holder = stateHolder(observer)

        holder.openWorkDir(WORK_DIR_PATH)
        runCurrent()
        // 사용자가 그 사이에 kyu 를 설치했다.
        observer.respondWith { snapshotNamed("WorkDir-featureX") }
        advanceTimeBy(REFRESH_INTERVAL)
        runCurrent()

        assertIs<WorkDirDashboardState.WorkDirObserved>(holder.state.value)
    }

    @Test
    fun `새로고침은 주기를 기다리지 않고 즉시 다시 관찰한다`() = runTest {
        val observer = RecordingWorkDirObserver()
        val holder = stateHolder(observer)

        holder.openWorkDir(WORK_DIR_PATH)
        runCurrent()
        holder.refreshNow()
        runCurrent()

        assertEquals(2, observer.observedPaths.size)
    }

    @Test
    fun `새로고침이 이전 스냅샷을 지우지 않는다`() = runTest {
        val observer = RecordingWorkDirObserver()
        val holder = stateHolder(observer)

        holder.openWorkDir(WORK_DIR_PATH)
        runCurrent()
        val firstSnapshot = assertIs<WorkDirDashboardState.WorkDirObserved>(holder.state.value).snapshot

        holder.refreshNow()

        // 다시 관찰이 끝나기 전의 한 순간이다. 여기서 진행 중 화면으로 돌아가면 화면이 깜빡인다.
        assertSame(firstSnapshot, assertIs<WorkDirDashboardState.WorkDirObserved>(holder.state.value).snapshot)
    }

    @Test
    fun `아무것도 열지 않았을 때의 새로고침은 아무 일도 하지 않는다`() = runTest {
        val observer = RecordingWorkDirObserver()
        val holder = stateHolder(observer)

        holder.refreshNow()
        runCurrent()

        assertEquals(WorkDirDashboardState.NoWorkDirOpened, holder.state.value)
        assertEquals(emptyList(), observer.observedPaths)
    }

    @Test
    fun `닫으면 주기 갱신이 멈춘다`() = runTest {
        val observer = RecordingWorkDirObserver()
        val holder = stateHolder(observer)

        holder.openWorkDir(WORK_DIR_PATH)
        runCurrent()
        holder.closeWorkDir()
        advanceTimeBy(REFRESH_INTERVAL * 5)
        runCurrent()

        assertEquals(WorkDirDashboardState.NoWorkDirOpened, holder.state.value)
        assertEquals(1, observer.observedPaths.size)
    }

    @Test
    fun `다른 워크디렉토리를 열면 이전 워크디렉토리는 더 이상 관찰하지 않는다`() = runTest {
        val observer = RecordingWorkDirObserver()
        val holder = stateHolder(observer)
        val otherWorkDirPath = Path.of("/home/me/work/WorkDir-다른것")

        holder.openWorkDir(WORK_DIR_PATH)
        runCurrent()
        holder.openWorkDir(otherWorkDirPath)
        runCurrent()
        advanceTimeBy(REFRESH_INTERVAL)
        runCurrent()

        assertEquals(listOf(WORK_DIR_PATH, otherWorkDirPath, otherWorkDirPath), observer.observedPaths)
    }

    private fun TestScope.stateHolder(workDirObserver: WorkDirObserver) = WorkDirDashboardStateHolder(
        workDirObserver = workDirObserver,
        coroutineScope = backgroundScope,
        // 관찰은 프로세스를 띄우는 막는 일이라 앱에서는 IO 디스패처로 나간다. 여기서는 가상
        // 시간을 쓰는 디스패처로 바꿔 3 초를 실제로 기다리지 않는다.
        observationDispatcher = StandardTestDispatcher(testScheduler),
    )

    private class RecordingWorkDirObserver : WorkDirObserver {

        val observedPaths = mutableListOf<Path>()

        private var respond: (Path) -> WorkDirSnapshot = { snapshotNamed("WorkDir-featureX") }

        fun respondWith(respond: (Path) -> WorkDirSnapshot) {
            this.respond = respond
        }

        override fun observe(workDirPath: Path): WorkDirSnapshot {
            observedPaths.add(workDirPath)
            return respond(workDirPath)
        }
    }

    private companion object {
        val WORK_DIR_PATH: Path = Path.of("/home/me/work/WorkDir-featureX")
        val REFRESH_INTERVAL = 3.seconds

        fun snapshotNamed(name: String) = WorkDirSnapshot(
            name = name,
            absolutePath = "/home/me/work/$name",
            repos = emptyList(),
            mainSessionAlive = false,
            planWarnings = emptyList(),
        )
    }
}
