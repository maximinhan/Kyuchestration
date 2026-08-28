package com.kyuchestration.desktop.engine

import java.nio.file.Path

/**
 * 이 머신에 엔진(kyu)을 놓는 자리.
 *
 * 어디서 어떻게 받아 오는지는 어댑터의 앎이다. 상태 홀더가 아는 것은 "부르면 쓸 수 있는 kyu 가
 * 생기거나, 이유를 들고 실패한다" 뿐이라, 받아 오는 곳이 GitHub 릴리스에서 다른 데로 바뀌어도
 * 화면과 상태 홀더는 그대로다.
 */
interface EngineInstaller {

    /**
     * 엔진을 받아 앱이 관리하는 자리에 놓고, 정말 도는지 확인한 뒤 그 자리를 돌려준다.
     *
     * @throws EngineInstallationFailure 플랫폼이 대상이 아니거나, 받아 오지 못했거나,
     *   받아 온 것이 돌지 않을 때.
     */
    fun installEngine(): Path
}
