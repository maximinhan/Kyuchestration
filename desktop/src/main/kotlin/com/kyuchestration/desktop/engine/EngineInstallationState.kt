package com.kyuchestration.desktop.engine

/**
 * 엔진이 이 머신에 있는가, 없다면 놓으려는 시도가 어디까지 왔는가.
 *
 * 앱의 첫 갈림길이다. 워크디렉토리를 열고 세션에 들어가는 모든 걸음이 kyu 를 부르는 일이라,
 * 엔진이 없는 채로 대시보드를 보여주면 사용자는 무엇을 눌러도 같은 실패를 보게 된다.
 */
sealed interface EngineInstallationState {

    /** 세 자리 어디에도 kyu 가 없다. 설치 화면이 뜬다. */
    data object EngineMissing : EngineInstallationState

    data object InstallationRunning : EngineInstallationState

    data class InstallationFailed(val failure: EngineInstallationFailure) : EngineInstallationState

    /**
     * 부를 kyu 가 있다. 앱의 나머지가 여기서부터다.
     *
     * 방금 받아 놓은 것과 원래 있던 것을 가르지 않는다. 화면이 하는 일이 같기 때문이다 — 받은
     * 직후에 "설치 완료" 를 한 번 더 보여주고 누르게 하면, 앱을 다시 띄우라는 말과 다를 바 없는
     * 걸음이 하나 는다.
     */
    data object EngineReady : EngineInstallationState
}
