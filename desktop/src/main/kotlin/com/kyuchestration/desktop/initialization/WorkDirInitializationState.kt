package com.kyuchestration.desktop.initialization

/**
 * 초기화를 시킨 일이 지금 어디까지 왔는지.
 *
 * 시작 화면의 "만들기" 와 대시보드의 "초기화" 가 같은 상태를 본다. 둘은 같은 명령(kyu init)을
 * 같은 방식으로 시키는 일이고, 화면에서 동시에 보이지도 않는다 — 각자 상태를 들면 진행 중
 * 표시와 실패 문구를 두 벌 쓰게 된다.
 */
sealed interface WorkDirInitializationState {

    /** 시킨 일이 없거나, 시킨 일이 끝나 더 보여줄 것이 없다. */
    data object Idle : WorkDirInitializationState

    data object Running : WorkDirInitializationState

    /**
     * 초기화가 일어나지 않았다.
     *
     * @param reason 화면이 그대로 보여줄 사람 말. 대부분 kyu 가 stderr 로 남긴 문구다.
     */
    data class Failed(val reason: String) : WorkDirInitializationState
}
