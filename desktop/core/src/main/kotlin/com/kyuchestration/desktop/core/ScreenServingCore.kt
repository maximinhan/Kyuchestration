package com.kyuchestration.desktop.core

import com.kyuchestration.desktop.protocol.ScreenFacingCore
import com.kyuchestration.desktop.terminal.SessionTarget
import com.kyuchestration.desktop.terminal.chat.PermissionCardChoice

/**
 * 화면을 받는 자리(serveScreen)가 보는 코어 — 화면이 보는 [ScreenFacingCore] 에 더해, 물음에 대한 답이 실제로 열린 물음에 닿았는지를
 * 돌려준다(core-ui-split-design.md 6.7).
 *
 * 화면 쪽의 거울은 이것을 알 수 없다 — 답은 코어에서 정해진다. 그래서 [ScreenFacingCore] 의 명령은 그대로 결과를 돌려주지 않고, 코어
 * 쪽만 이 면을 더 가진다. 닿지 않은 답은 serveScreen 이 거절로 돌려준다. 물음이 열려 있는지를 아는 자리는 챗 홀더 하나이고, 그 판단도
 * 거기서만 한다.
 */
interface ScreenServingCore : ScreenFacingCore {

    /** @return 그 세션의 승인 물음이 아직 열려 있어 이 답이 닿았는가. */
    fun answerPermissionIfStillAsked(target: SessionTarget, toolUseId: String, choice: PermissionCardChoice): Boolean

    /** @return 그 관문 물음이 아직 열려 있어 이 답이 닿았는가. */
    fun answerGateIfStillOpen(gateQuestionId: String, approved: Boolean): Boolean

    override fun answerPermission(target: SessionTarget, toolUseId: String, choice: PermissionCardChoice) {
        answerPermissionIfStillAsked(target, toolUseId, choice)
    }

    override fun answerGate(gateQuestionId: String, approved: Boolean) {
        answerGateIfStillOpen(gateQuestionId, approved)
    }
}
