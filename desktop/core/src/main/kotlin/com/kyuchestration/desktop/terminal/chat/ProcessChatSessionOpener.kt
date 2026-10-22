package com.kyuchestration.desktop.terminal.chat

import com.kyuchestration.desktop.platform.withEnvironment
import com.kyuchestration.desktop.terminal.SessionEntryPlan
import com.kyuchestration.desktop.terminal.TerminalSessionFailure
import java.io.IOException

/**
 * 엔진이 답한 계획을 파이프 셋에 물려 띄운다.
 *
 * PTY 를 여는 자리(`PtySessionTerminalOpener`)와 하는 일이 같고 무엇에 물리느냐만 다르다 —
 * 명령도 자리도 환경도 엔진의 답 그대로다(원칙 11).
 *
 * `redirectErrorStream` 을 켜지 않는다. 켜면 진단 한 줄이 이벤트 줄 사이에 끼어들어 그 줄이
 * 읽을 수 없는 JSON 이 된다. stderr 는 따로 비운다([ProcessChatSession]).
 */
class ProcessChatSessionOpener : ChatSessionOpener {

    override suspend fun openChatSession(plan: SessionEntryPlan): OpenedChatSession {
        val process = try {
            ProcessBuilder(plan.command)
                .directory(plan.workingDirectory.toFile())
                // 환경을 통째로 갈아 끼운다. 계획이 담은 것이 이미 손본 바탕 환경이라
                // (planSessionEntry), 덧씌우기만 하면 덜어낸 것이 도로 살아난다.
                .withEnvironment(plan.environment)
                .start()
        } catch (failure: IOException) {
            throw TerminalSessionFailure.ChatProcessFailedToStart(failure)
        }

        return ProcessChatSession(process)
    }
}
