package com.kyuchestration.desktop.mirror

/** 코어에 인사한 결과(core-ui-split-design.md 6.14). */
sealed interface CoreConnection {

    data class Connected(val mirror: CoreMirror) : CoreConnection

    /**
     * 코어가 다른 틀 규약을 말한다. 거울은 서지 않는다. 사람에게 보일 것은 어느 쪽이 낡았는가이고, 그것은 두 판이 말한다.
     *
     * @param screenVersion 이 화면의 판 라벨.
     * @param coreVersion 코어가 인사 답에 실은 판 라벨.
     */
    data class ProtocolMismatch(
        val screenVersion: String,
        val screenProtocol: Int,
        val coreVersion: String,
        val coreProtocol: Int,
    ) : CoreConnection
}
