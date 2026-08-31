package com.kyuchestration.desktop.engine

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
 * 엔진이 있는지 보고, 없으면 놓는 걸음을 쥔 자리. Compose 를 모른다.
 *
 * @param findEngineExecutable 지금 부를 수 있는 kyu 가 어디 있는지 답하는 자리. 어디를 어떤
 *   순서로 뒤지는지는 이 홀더의 앎이 아니라 조립하는 쪽(Main.kt)이 정한다 — 대시보드 홀더가
 *   계획 파일 유무를 함수로 받는 것과 같은 선이다. 검사는 여기에 있음과 없음을 그대로 준다.
 * @param placeBundledEngine 설치 패키지가 들고 온 엔진을 부를 수 있는 자리에 놓는 걸음. 찾기
 *   전에 지난다 — 동봉된 엔진은 놓이고 나서야 탐색에 걸린다.
 */
class EngineInstallationStateHolder(
    private val engineInstaller: EngineInstaller,
    private val coroutineScope: CoroutineScope,
    private val findEngineExecutable: () -> Path?,
    private val placeBundledEngine: () -> Unit,
    // 받아 오고 프로세스를 띄우는 일이라 화면을 그리는 스레드에서 하면 창이 멎는다.
    private val installationDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    /**
     * 첫 답을 생성자에서 곧바로 낸다.
     *
     * 코루틴으로 미루면 "찾는 중" 이라는 상태가 하나 더 필요하고, 엔진이 이미 있는 사람은 앱을
     * 띄울 때마다 그 화면이 한 번 번쩍이는 것을 보게 된다.
     *
     * 여기서 하는 일이 화면을 그리기 전에 끝난다. 찾는 것은 디렉토리마다 파일 하나를 확인하는
     * 일이고, 동봉된 엔진을 놓는 것은 바이트가 같으면 견주기만 하고 끝난다 — 실제로 옮기는 것은
     * 앱을 새로 설치한 다음 첫 실행 한 번뿐이다.
     */
    private val mutableState = MutableStateFlow(stateFromWhatIsInstalled())

    val state: StateFlow<EngineInstallationState> = mutableState.asStateFlow()

    /**
     * 어디에도 엔진이 없으면 누르기를 기다리지 않고 곧바로 받는다.
     *
     * 앱이 시작점이라면, 시작점에 선 사람에게 남는 걸음이 있어서는 안 된다. 버튼 하나를 누르는
     * 일이라 해도 그것은 "앱만 설치하면 끝" 이 아니라 "앱을 설치하고 버튼을 누르면 끝" 이고,
     * 소스에서 띄운 사람은 그 자리에서 자기가 무엇을 잘못했는지부터 의심한다.
     *
     * 어디에도 없을 때만이다. 동봉을 놓지 못한 이유를 들고 있는 상태에서 곧바로 받아 버리면, 그
     * 이유가 화면에 뜨기도 전에 진행 표시로 덮여 못 쓰는 설치 패키지가 나갔다는 사실이 묻힌다.
     *
     * 실패하면 여기서 멈춘다. 스스로 다시 받지 않는 것이 뜻이다 — 망이 막힌 자리에서 되풀이하면
     * 화면은 영영 진행 중이고 왜 안 되는지는 끝내 뜨지 않는다. 다시 받는 것은 [다시 시도] 를
     * 누른 사람의 뜻이다.
     */
    init {
        if (mutableState.value == EngineInstallationState.EngineMissing) {
            installEngine()
        }
    }

    /**
     * 최신 릴리스에서 엔진을 받아 놓는다. 성공하면 곧바로 앱의 나머지로 들어간다.
     */
    fun installEngine() {
        // 도는 동안의 요청은 흘려보낸다. 그대로 받으면 같은 자산을 두 번 받아 두 임시 파일이
        // 같은 이름을 두고 겨루게 된다.
        if (mutableState.value == EngineInstallationState.InstallationRunning) {
            return
        }
        mutableState.value = EngineInstallationState.InstallationRunning

        coroutineScope.launch {
            mutableState.value = try {
                withContext(installationDispatcher) { engineInstaller.installEngine() }
                EngineInstallationState.EngineReady
            } catch (failure: EngineInstallationFailure) {
                EngineInstallationState.InstallationFailed(failure)
            }
        }
    }

    /**
     * 지금 다시 찾아본다.
     *
     * 설치 화면은 "터미널에서 install.sh 로 넣어도 된다" 고 알린다. 그 길로 넣은 사람이 앱을
     * 다시 띄워야 한다면 그 안내는 반쪽이다.
     *
     * 지난 실패 문구는 여기서 걷힌다. 다시 찾아 없는 것과 받다 실패한 것은 다른 사실이라,
     * 문구를 남겨 두면 방금 확인한 결과 위에 지난번 이유가 얹힌다.
     */
    fun lookForEngineAgain() {
        // 받는 중에 찾으면 아직 옮기기 전이라 없는 것으로 나온다. 진행 표시를 그 답으로
        // 덮으면 받고 있던 것이 사라진 것처럼 보인다.
        if (mutableState.value == EngineInstallationState.InstallationRunning) {
            return
        }
        mutableState.value = stateFromWhatIsInstalled()
    }

    private fun stateFromWhatIsInstalled(): EngineInstallationState {
        val bundledEngineFailure = try {
            placeBundledEngine()
            null
        } catch (failure: EngineInstallationFailure) {
            failure
        }

        return when {
            findEngineExecutable() != null -> EngineInstallationState.EngineReady

            // 동봉된 엔진을 들고 있으면서 "엔진이 없다" 고만 말하면, 사용자는 자기가 방금 설치한
            // 앱이 왜 자기 엔진을 못 쓰는지 알 길이 없다. 그 이유를 들고 설치 화면으로 간다 —
            // 거기서 [다시 시도] 로 받아 오는 길은 그대로 열려 있다.
            bundledEngineFailure != null -> EngineInstallationState.InstallationFailed(bundledEngineFailure)

            else -> EngineInstallationState.EngineMissing
        }
    }
}
