package com.kyuchestration.desktop.initialization

import com.kyuchestration.desktop.workdir.WorkDirInitializationResult
import com.kyuchestration.desktop.workdir.WorkDirInitializer
import java.nio.file.Path
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 초기화를 시키고 그 결과를 화면에 내보이는 자리. Compose 를 모른다.
 *
 * 대시보드 상태 홀더와 갈라 둔다. 저쪽은 3 초마다 저절로 도는 관찰이고 이쪽은 사람이 눌러야만
 * 한 번 일어나는 쓰기다 — 한 홀더에 묶으면 폴링 루프가 초기화 결과를 덮어쓰지 않도록
 * 관찰 쪽 코드가 매번 초기화 사정을 살펴야 한다.
 */
class WorkDirInitializationStateHolder(
    private val workDirInitializer: WorkDirInitializer,
    private val coroutineScope: CoroutineScope,
    // kyu 를 프로세스로 띄우고 기다리는 일이라 화면을 그리는 스레드에서 하면 창이 멎는다.
    private val initializationDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val mutableState = MutableStateFlow<WorkDirInitializationState>(WorkDirInitializationState.Idle)

    val state: StateFlow<WorkDirInitializationState> = mutableState.asStateFlow()

    /**
     * [parentDirectory] 아래에 [newWorkDirName] 이름의 워크디렉토리를 만든다.
     *
     * @param onWorkDirCreated 다 만든 워크디렉토리의 자리. 만들자마자 그 자리를 여는 것이 이
     *   화면의 유일한 다음 걸음이지만, 무엇을 "연다" 고 하는지는 대시보드 쪽 일이라 여기서
     *   정하지 않고 넘겨받는다.
     */
    fun createWorkDir(parentDirectory: Path, newWorkDirName: String, onWorkDirCreated: (Path) -> Unit) {
        val trimmedName = newWorkDirName.trim()
        if (trimmedName.isEmpty()) {
            // 빈 이름을 그대로 넘기면 kyu 는 그것도 인자 하나로 세어 부모 디렉토리 자신을
            // 초기화한다(init.go 의 workDirPathFromInitArgs). 사용자가 고른 것은 "만들 자리" 이지
            // "초기화할 자리" 가 아니므로, 그 자리에 계획 파일이 생기기 전에 여기서 막는다.
            mutableState.value = WorkDirInitializationState.Failed("만들 워크디렉토리의 이름을 적어 주세요.")
            return
        }

        runInitialization(onInitialized = onWorkDirCreated) {
            workDirInitializer.createWorkDir(parentDirectory, trimmedName)
        }
    }

    /**
     * 이미 열어 둔 워크디렉토리를 그 자리에서 초기화한다.
     *
     * 끝났다고 따로 알리지 않는다. 대시보드는 3 초마다 다시 관찰하므로 계획 파일이 생긴 사실이
     * 다음 주기에 저절로 실려 온다.
     */
    fun initializeExistingWorkDir(workDirPath: Path) {
        runInitialization(onInitialized = {}) { workDirInitializer.initializeExistingWorkDir(workDirPath) }
    }

    private fun runInitialization(
        onInitialized: (Path) -> Unit,
        initialize: () -> WorkDirInitializationResult,
    ) {
        // kyu init 은 두 번째 실행을 "이미 계획 파일이 있습니다" 로 거절한다. 도는 동안의 요청을
        // 그대로 흘려보내면, 방금 성공한 사람이 실패 문구를 보게 된다.
        if (mutableState.value == WorkDirInitializationState.Running) {
            return
        }
        mutableState.value = WorkDirInitializationState.Running

        coroutineScope.launch {
            when (val result = withContext(initializationDispatcher) { initialize() }) {
                is WorkDirInitializationResult.Initialized -> {
                    mutableState.value = WorkDirInitializationState.Idle
                    onInitialized(result.workDirPath)
                }

                is WorkDirInitializationResult.NotInitialized ->
                    mutableState.value = WorkDirInitializationState.Failed(result.reason)
            }
        }
    }
}
