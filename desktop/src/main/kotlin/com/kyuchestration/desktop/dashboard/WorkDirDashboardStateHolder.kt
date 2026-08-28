package com.kyuchestration.desktop.dashboard

import com.kyuchestration.desktop.workdir.WorkDirObservationFailure
import com.kyuchestration.desktop.workdir.WorkDirObserver
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 대시보드가 무엇을 보여줄지를 정하는 자리. Compose 를 모른다.
 *
 * 폴링과 오류 전이는 시간에 따라 달라지는 판단이라 화면을 띄우지 않고 시험할 수 있어야 한다.
 * 이 클래스가 Composable 안에 있으면 그 판단을 검사하려고 화면 전체를 세워야 하고, 3 초를
 * 실제로 기다려야 한다. 그래서 상태는 StateFlow 로만 내보내고, Compose 는 그것을 구독만 한다.
 */
class WorkDirDashboardStateHolder(
    private val workDirObserver: WorkDirObserver,
    private val coroutineScope: CoroutineScope,
    // kyu 를 프로세스로 띄우고 기다리는 일이라 화면을 그리는 스레드에서 하면 창이 멎는다.
    private val observationDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val refreshInterval: Duration = DEFAULT_REFRESH_INTERVAL,
) {

    private val mutableState = MutableStateFlow<WorkDirDashboardState>(WorkDirDashboardState.NoWorkDirOpened)

    val state: StateFlow<WorkDirDashboardState> = mutableState.asStateFlow()

    private var observationLoop: Job? = null

    fun openWorkDir(workDirPath: Path) {
        restartObservationLoop(workDirPath, WorkDirDashboardState.FirstObservationRunning(workDirPath))
    }

    /**
     * 주기를 기다리지 않고 지금 다시 관찰한다.
     *
     * 지금 화면을 그대로 두고 시작한다. 새로고침을 누를 때마다 진행 중 화면으로 돌아가면,
     * 사용자가 보고 있던 목록이 매번 한 번씩 사라졌다 돌아온다.
     */
    fun refreshNow() {
        val workDirPath = mutableState.value.workDirPath ?: return
        restartObservationLoop(workDirPath, mutableState.value)
    }

    fun closeWorkDir() {
        observationLoop?.cancel()
        observationLoop = null
        mutableState.value = WorkDirDashboardState.NoWorkDirOpened
    }

    private fun restartObservationLoop(workDirPath: Path, stateWhileFirstObservationRuns: WorkDirDashboardState) {
        // 이전 루프를 반드시 먼저 끊는다. 남겨 두면 다른 워크디렉토리를 두 개 관찰하며 서로의
        // 결과를 번갈아 덮어쓴다.
        observationLoop?.cancel()
        mutableState.value = stateWhileFirstObservationRuns

        observationLoop = coroutineScope.launch {
            while (isActive) {
                observeOnce(workDirPath)
                delay(refreshInterval)
            }
        }
    }

    private suspend fun observeOnce(workDirPath: Path) {
        val observed = try {
            withContext(observationDispatcher) { workDirObserver.observe(workDirPath) }
        } catch (failure: WorkDirObservationFailure) {
            mutableState.value = stateAfterFailure(workDirPath, failure)
            return
        }

        mutableState.value = WorkDirDashboardState.WorkDirObserved(
            workDirPath = workDirPath,
            snapshot = observed,
            lastRefreshFailure = null,
        )
    }

    private fun stateAfterFailure(
        workDirPath: Path,
        failure: WorkDirObservationFailure,
    ): WorkDirDashboardState = when (val current = mutableState.value) {
        // 한 번이라도 본 적이 있으면 그 목록을 지우지 않는다. 갱신 한 번 실패했다고 목록이
        // 사라지면 사용자는 워크디렉토리가 없어진 것과 구분할 수 없다.
        is WorkDirDashboardState.WorkDirObserved -> current.copy(lastRefreshFailure = failure)
        else -> WorkDirDashboardState.FirstObservationFailed(workDirPath, failure)
    }

    companion object {
        /**
         * 사람이 세션을 오가는 사이에 화면이 따라오는 정도. 관찰 한 번은 kyu 프로세스 하나와
         * 레포마다 git 호출 몇 개라, 이보다 촘촘하게 두면 놀고 있는 창이 계속 일을 시킨다.
         */
        val DEFAULT_REFRESH_INTERVAL: Duration = 3.seconds
    }
}
