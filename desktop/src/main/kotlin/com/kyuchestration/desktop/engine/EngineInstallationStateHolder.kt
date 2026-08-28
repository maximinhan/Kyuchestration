package com.kyuchestration.desktop.engine

import com.kyuchestration.desktop.kyu.findKyuExecutable
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
 * @param findEngineExecutable 지금 부를 수 있는 kyu 가 어디 있는지 답하는 자리. 검사가 진짜
 *   PATH 와 홈 디렉토리를 건드리지 않고 있음과 없음을 만들기 위한 이음매다.
 */
class EngineInstallationStateHolder(
    private val engineInstaller: EngineInstaller,
    private val coroutineScope: CoroutineScope,
    private val findEngineExecutable: () -> Path? = { findKyuExecutable() },
    // 받아 오고 프로세스를 띄우는 일이라 화면을 그리는 스레드에서 하면 창이 멎는다.
    private val installationDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    /**
     * 첫 답을 생성자에서 곧바로 낸다.
     *
     * 코루틴으로 미루면 "찾는 중" 이라는 상태가 하나 더 필요하고, 엔진이 이미 있는 사람은 앱을
     * 띄울 때마다 그 화면이 한 번 번쩍이는 것을 보게 된다. 찾는 일은 PATH 의 디렉토리마다 파일
     * 하나를 확인하는 것뿐이라 화면을 그리기 전에 끝난다.
     */
    private val mutableState = MutableStateFlow(stateFromWhatIsInstalled())

    val state: StateFlow<EngineInstallationState> = mutableState.asStateFlow()

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

    private fun stateFromWhatIsInstalled(): EngineInstallationState =
        if (findEngineExecutable() == null) {
            EngineInstallationState.EngineMissing
        } else {
            EngineInstallationState.EngineReady
        }
}
