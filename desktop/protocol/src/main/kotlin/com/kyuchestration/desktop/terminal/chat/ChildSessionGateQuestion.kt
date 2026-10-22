package com.kyuchestration.desktop.terminal.chat

import com.kyuchestration.desktop.terminal.ChildSessionGate
import kotlinx.serialization.Serializable

/**
 * 사람에게 보일 관문 하나(6.9) — 무엇을 시키려고, 어떤 설정 때문에 묻는가.
 *
 * 하위의 이름은 없다. 엔진은 띄울 수 있다고 답할 때 이름을 지어 기록에 적고, 관문에 걸린 하위는 아직 그 앞이다.
 *
 * @param gateQuestionId 챗 홀더가 물음마다 새로 짓는 이름. 답은 이것으로 물음을 가리킨다 — 같은 자리 같은 일을 다시
 *   물은 다음 물음은 내용이 같아도 id 가 달라, 앞 물음에 늦게 누른 답을 받지 않는다.
 * @param prompt 상위가 그 하위에게 시키려던 일. 사람이 "이 설정으로 무엇이 돌게 되는가" 를 판단할 재료다.
 */
@Serializable
data class ChildSessionGateQuestion(
    val gateQuestionId: String,
    val prompt: String,
    val gate: ChildSessionGate,
)
