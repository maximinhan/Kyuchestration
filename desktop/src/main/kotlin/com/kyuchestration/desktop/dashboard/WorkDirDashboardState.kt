package com.kyuchestration.desktop.dashboard

import com.kyuchestration.desktop.workdir.WorkDirObservationFailure
import com.kyuchestration.desktop.workdir.WorkDirSnapshot
import java.nio.file.Path

/**
 * 대시보드가 지금 보여줄 것.
 *
 * "스냅샷과 로딩 여부와 오류" 를 각각 따로 든 세 개의 값으로 두지 않는다. 그렇게 두면 오류와
 * 스냅샷이 동시에 있는 상태처럼 뜻이 없는 조합이 표현 가능해지고, 화면이 그 조합마다
 * 어떻게 그릴지 정해야 한다. 갈래로 나눠 두면 있을 수 있는 화면이 딱 넷이다.
 */
sealed interface WorkDirDashboardState {

    /** 열려 있는 워크디렉토리의 경로. 아직 아무것도 열지 않았으면 null. */
    val workDirPath: Path?

    data object NoWorkDirOpened : WorkDirDashboardState {
        override val workDirPath: Path? = null
    }

    /** 처음 여는 중이다 — 대신 보여줄 이전 스냅샷이 아직 없다. */
    data class FirstObservationRunning(
        override val workDirPath: Path,
    ) : WorkDirDashboardState

    /**
     * 관찰이 한 번은 성공했다.
     *
     * @param lastRefreshFailure 마지막 재관찰이 실패했으면 그 이유. 스냅샷은 실패 직전의 것
     *   그대로다 — 화면은 목록을 지우지 않고 이 사실만 덧붙여 알린다.
     */
    data class WorkDirObserved(
        override val workDirPath: Path,
        val snapshot: WorkDirSnapshot,
        val lastRefreshFailure: WorkDirObservationFailure?,
    ) : WorkDirDashboardState

    /** 한 번도 성공하지 못했다. 보여줄 것이 실패의 이유뿐이다. */
    data class FirstObservationFailed(
        override val workDirPath: Path,
        val failure: WorkDirObservationFailure,
    ) : WorkDirDashboardState
}
