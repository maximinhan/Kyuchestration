package com.kyuchestration.desktop.terminal

import com.jediterm.terminal.TtyConnector

/**
 * 앱이 지금 보유 중인 세션 하나.
 *
 * 화면에 보이는 세션과 다르다. 보이는 것은 한 번에 하나지만 보유하는 것은 여럿이고, 이 목록이
 * 곧 "앱 세션이 몇 개 돌고 있는가" 의 답이다 — 대시보드가 엔진의 사실 위에 얹는 것이 이것이다
 * (설계 문서 5.4).
 *
 * 엔진에게 물어서 얻는 사실이 아니라는 것이 이 타입의 요점이다. 앱 세션은 tmux 세션도 감독
 * 세션도 아니라 `kyu list` 에 보이지 않고, 앱이 자기 것을 스스로 아는 것 말고는 알 길이 없다.
 */
data class HeldSession(
    val target: SessionTarget,
    val ttyConnector: TtyConnector,
)
