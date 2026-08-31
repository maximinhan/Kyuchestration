package com.kyuchestration.desktop.terminal

import com.jediterm.terminal.TtyConnector

/**
 * TtyConnector 를 손으로 채운다.
 *
 * 여덟 개뿐이고 전부 한 줄이라 가짜를 만들어 주는 라이브러리를 들일 이유가 없다. 상태 홀더의
 * 시험이 실제로 보는 것은 닫혔는지와 살아 있는지 둘뿐이다.
 */
internal class FakeTtyConnector(var connected: Boolean = true) : TtyConnector {

    var closeCount = 0

    override fun read(buf: CharArray, offset: Int, length: Int): Int = -1
    override fun write(bytes: ByteArray) = Unit
    override fun write(string: String) = Unit
    override fun isConnected(): Boolean = connected
    override fun waitFor(): Int = 0
    override fun ready(): Boolean = false
    override fun getName(): String = "가짜 통로"

    override fun close() {
        closeCount++
        connected = false
    }
}
