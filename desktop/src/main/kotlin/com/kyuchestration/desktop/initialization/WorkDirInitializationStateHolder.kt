package com.kyuchestration.desktop.initialization

import com.kyuchestration.desktop.workdir.WorkDirInitializationResult
import com.kyuchestration.desktop.workdir.WorkDirInitializer
import java.io.IOException
import java.nio.file.Files
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
    /**
     * 이 디렉토리에 계획 파일이 있는지 답하는 자리.
     *
     * 대시보드 홀더도 같은 것을 묻지만 물어보는 까닭이 다르다. 저쪽은 배너를 띄울지를 정하려고
     * 관찰마다 묻고, 이쪽은 고른 자리를 초기화할지를 정하려고 열 때 한 번 묻는다.
     */
    private val planFileExists: (Path) -> Boolean,
    private val coroutineScope: CoroutineScope,
    /**
     * 이 사람의 홈 디렉토리. 어디인지 모르면 null 이고, 그러면 홈 안전장치는 판단하지 않는다.
     */
    private val userHomeDirectory: Path? = System.getProperty("user.home")?.let(Path::of),
    // kyu 를 프로세스로 띄우고 기다리는 일이라 화면을 그리는 스레드에서 하면 창이 멎는다.
    private val initializationDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val mutableState = MutableStateFlow<WorkDirInitializationState>(WorkDirInitializationState.Idle)

    val state: StateFlow<WorkDirInitializationState> = mutableState.asStateFlow()

    /**
     * 사용자가 고른 디렉토리를 그 자리에서 워크디렉토리로 삼아 연다.
     *
     * 고른 자리 아래에 또 디렉토리를 만들지 않는다. "여기서 조율한다" 고 고른 자리가 곧
     * 워크디렉토리이고, 새 자리가 필요하면 시스템 선택창의 새 폴더가 이미 그 일을 한다.
     *
     * 계획 파일이 없으면 초기화를 따로 누르게 하지 않고 여는 걸음에 함께 한다. 눌러야만
     * 초기화되던 시절에는 "열었는데 아직 워크디렉토리가 아닌 상태" 가 화면에 남았는데, 그
     * 상태에서 사용자가 할 수 있는 일은 그 버튼을 누르는 것 하나뿐이었다.
     *
     * @param onWorkDirReady 조율할 수 있게 된 워크디렉토리의 자리. 무엇을 "연다" 고 하는지는
     *   대시보드 쪽 일이라 여기서 정하지 않고 넘겨받는다.
     */
    fun openChosenDirectoryAsWorkDir(workDirPath: Path, onWorkDirReady: (Path) -> Unit) {
        // 도는 중인 시도가 있으면 흘려보낸다. 계획 파일을 보는 것도, 안전장치를 따지는 것도
        // 여는 걸음의 일부라 진행 표시를 여기서 세운다 — 뒤로 미루면 그 사이에 들어온 두 번째
        // 요청이 같은 자리를 두 번 초기화하고, 두 번째는 kyu 가 거절해 방금 성공한 사람이
        // 실패 문구를 보게 된다.
        if (mutableState.value == WorkDirInitializationState.Running) {
            return
        }
        mutableState.value = WorkDirInitializationState.Running

        coroutineScope.launch {
            // 디스크를 만지는 두 판단을 한 번에 묶어 내보낸다. 그리는 스레드에서 하면 창이 멎는다.
            val whatOpeningNeeds = withContext(initializationDispatcher) {
                when {
                    planFileExists(workDirPath) -> OpeningNeeds.NothingToInitialize
                    else -> reasonToRefuseAutomaticInitialization(workDirPath, userHomeDirectory)
                        ?.let(OpeningNeeds::Refusal)
                        ?: OpeningNeeds.Initialization
                }
            }

            when (whatOpeningNeeds) {
                // 이미 계획이 있는 자리를 굳이 초기화하지 않는다. 그대로 kyu init 을 부르면
                // "이미 계획 파일이 있습니다" 로 거절당해, 멀쩡히 열리던 워크디렉토리가 실패
                // 문구를 달고 열리지 않는다.
                OpeningNeeds.NothingToInitialize -> {
                    mutableState.value = WorkDirInitializationState.Idle
                    onWorkDirReady(workDirPath)
                }

                is OpeningNeeds.Refusal ->
                    mutableState.value = WorkDirInitializationState.Failed(whatOpeningNeeds.reason)

                OpeningNeeds.Initialization -> initializeAndSettle(workDirPath, onWorkDirReady)
            }
        }
    }

    /**
     * 열어 둔 워크디렉토리를 그 자리에서 초기화한다.
     *
     * 여는 걸음이 이미 초기화까지 하므로, 이 길로 오는 것은 열고 난 뒤에 계획 파일이 사라진
     * 자리뿐이다 — 대시보드의 배너가 부른다.
     *
     * 끝났다고 따로 알리지 않는다. 대시보드는 3 초마다 다시 관찰하므로 계획 파일이 생긴 사실이
     * 다음 주기에 저절로 실려 온다.
     */
    fun initializeOpenedWorkDir(workDirPath: Path) {
        // kyu init 은 두 번째 실행을 "이미 계획 파일이 있습니다" 로 거절한다. 도는 동안의 요청을
        // 그대로 흘려보내면, 방금 성공한 사람이 실패 문구를 보게 된다.
        if (mutableState.value == WorkDirInitializationState.Running) {
            return
        }
        mutableState.value = WorkDirInitializationState.Running

        // 이미 열려 있는 자리라 초기화가 끝나도 새로 열 것이 없다.
        coroutineScope.launch { initializeAndSettle(workDirPath, onInitialized = {}) }
    }

    /**
     * 지난 실패 문구를 거둔다.
     *
     * 실패는 그것을 부른 시도에 속한다. 다른 워크디렉토리로 옮기면 그 시도는 끝난 것인데, 문구가
     * 따라가면 엉뚱한 자리의 실패로 읽힌다 — 워크디렉토리 A 의 초기화가 거절당한 이유가 B 의
     * 배너에 뜨는 식이다.
     *
     * 도는 중에는 아무것도 하지 않는다. 진행 표시만 지우면 결과가 돌아왔을 때 기다린 적도 없는
     * 문구가 튀어나온다.
     */
    fun forgetLastFailure() {
        if (mutableState.value is WorkDirInitializationState.Failed) {
            mutableState.value = WorkDirInitializationState.Idle
        }
    }

    /** kyu 에게 초기화를 시키고 그 답을 상태로 옮긴다. */
    private suspend fun initializeAndSettle(workDirPath: Path, onInitialized: (Path) -> Unit) {
        val result = withContext(initializationDispatcher) { workDirInitializer.initializeInPlace(workDirPath) }

        when (result) {
            is WorkDirInitializationResult.Initialized -> {
                mutableState.value = WorkDirInitializationState.Idle
                onInitialized(result.workDirPath)
            }

            is WorkDirInitializationResult.NotInitialized ->
                mutableState.value = WorkDirInitializationState.Failed(result.reason)
        }
    }

    /** 고른 자리를 열려면 무엇을 먼저 해야 하는지. */
    private sealed interface OpeningNeeds {

        data object NothingToInitialize : OpeningNeeds

        data object Initialization : OpeningNeeds

        data class Refusal(val reason: String) : OpeningNeeds
    }
}

/**
 * 홈 디렉토리와 파일시스템 루트에서 자동 초기화를 거절할 이유. 거절할 것이 없으면 null.
 *
 * 엔진에도 같은 안전장치가 있지만(enter.go 의 refuseAutomaticInitInHomeOrFilesystemRoot) 그쪽은
 * 인자 없는 `kyu` 로 들어가는 길에만 걸려 있다. 앱은 `kyu init` 을 자리와 함께 직접 부르므로
 * 그 길을 지나지 않는다 — 자동으로 초기화하기로 한 쪽이 자기 안전장치를 든다.
 *
 * 막는 것은 자동 초기화뿐이다. 이미 계획이 있는 자리는 여기까지 오지 않으므로, 사용자가
 * `kyu init` 으로 "여기를 워크디렉토리로 쓴다" 고 직접 선언해 둔 뜻은 되돌리지 않는다.
 */
private fun reasonToRefuseAutomaticInitialization(chosenDirectory: Path, userHomeDirectory: Path?): String? {
    val absoluteChosenDirectory = chosenDirectory.toAbsolutePath().normalize()

    // 위로 갈 자리가 없으면 루트다.
    if (absoluteChosenDirectory.parent == null) {
        return "자동으로 초기화하지 않습니다 — 여기는 파일시스템 루트입니다: $absoluteChosenDirectory\n" +
            GUIDANCE_FOR_REFUSED_DIRECTORY
    }

    if (userHomeDirectory != null && isSameDirectory(absoluteChosenDirectory, userHomeDirectory)) {
        // 홈 전체가 워크디렉토리가 되면 홈 아래 모든 레포가 메인 세션에 --add-dir 로 붙고
        // .coord/plan.md 가 홈에 남는다. 되돌리기 번거로운 실수라 자동으로는 만들지 않는다.
        return "자동으로 초기화하지 않습니다 — 여기는 홈 디렉토리입니다: $absoluteChosenDirectory\n" +
            GUIDANCE_FOR_REFUSED_DIRECTORY
    }

    return null
}

/**
 * 두 경로가 같은 자리를 가리키는지. 경로 문자열로 비교하지 않는 이유는 홈이 심볼릭 링크를 거쳐
 * 있거나 끝에 슬래시가 붙어도 같은 자리이기 때문이다 — 문자열로 보면 정작 막아야 할 선택이
 * 그대로 통과한다.
 */
private fun isSameDirectory(one: Path, other: Path): Boolean =
    try {
        Files.isSameFile(one, other)
    } catch (notReachable: IOException) {
        // 한쪽이 없거나 읽히지 않으면 같은 자리라고 말할 근거가 없다. 여기서 여는 것을 막으면
        // 홈과 상관없는 자리까지 함께 막히는데, 정말 읽을 수 없는 자리라면 곧이어 kyu init 이
        // 그 사실을 사람 말로 알려 준다.
        false
    }

/**
 * 거절할 때 함께 내보내는 다음 걸음. 길을 막는 것이 아니라 한 번 더 말하게 하는 장치다.
 */
private const val GUIDANCE_FOR_REFUSED_DIRECTORY =
    "조율할 레포를 담아 둘 디렉토리를 따로 골라 주세요.\n" +
        "정말 여기를 워크디렉토리로 쓰려면 터미널에서 kyu init 을 직접 실행한 뒤 다시 여세요."
