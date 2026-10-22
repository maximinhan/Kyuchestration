package com.kyuchestration.desktop.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch

/**
 * 시험이 코어의 틀 줄을 메모리 줄로 받는다 — 코어 프로세스에서 stdout 에 쓰는 자리를 대신한다. 코어가 실패로 닫으면 받는 줄도 그
 * 실패로 닫힌다(거울이 같은 실패를 받는다).
 */
fun ScreenFrameOutbox.linesDeliveredIn(scope: CoroutineScope): ReceiveChannel<String> {
    val lines = Channel<String>(Channel.UNLIMITED)
    scope.launch {
        val failure = runCatching { deliverEach { lines.send(it) } }.exceptionOrNull()
        lines.close(failure)
    }
    return lines
}
