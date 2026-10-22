package com.kyuchestration.desktop.diagnostics

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 기록 파일이 놓이는 자리.
 *
 * 자리를 시험하는 이유는 사용자가 이 파일을 손으로 찾아 건네야 하기 때문이다. 관례에서 벗어난
 * 자리에 두면 "그 파일 주세요" 라는 말이 경로 안내부터 시작해야 한다.
 */
class DiagnosticLogLocationTest {

    @Test
    fun `맥에서는 콘솔 앱이 들여다보는 자리에 둔다`() {
        val logFile = diagnosticLogFile(
            osName = "Mac OS X",
            userHomeDirectory = Path.of("/Users/yawn"),
            lookUpEnvironmentVariable = { null },
        )

        assertEquals(Path.of("/Users/yawn/Library/Logs/Kyuchestration/desktop.log"), logFile)
    }

    @Test
    fun `리눅스에서는 XDG_STATE_HOME 을 따른다`() {
        val logFile = diagnosticLogFile(
            osName = "Linux",
            userHomeDirectory = Path.of("/home/yawn"),
            lookUpEnvironmentVariable = { if (it == "XDG_STATE_HOME") "/home/yawn/.state" else null },
        )

        assertEquals(Path.of("/home/yawn/.state/kyuchestration/desktop.log"), logFile)
    }

    @Test
    fun `XDG_STATE_HOME 이 없으면 그 명세가 정한 기본값을 쓴다`() {
        val logFile = diagnosticLogFile(
            osName = "Linux",
            userHomeDirectory = Path.of("/home/yawn"),
            lookUpEnvironmentVariable = { null },
        )

        assertEquals(Path.of("/home/yawn/.local/state/kyuchestration/desktop.log"), logFile)
    }

    /**
     * 빈 값은 없는 것과 같이 본다.
     *
     * `XDG_STATE_HOME=` 로 비워 둔 셸이 흔하다. 그 빈 문자열을 그대로 이으면 상대 경로가 나오고,
     * 기록 파일이 앱을 띄운 작업 디렉토리에 떨어진다 — 사용자가 찾을 수 없는 자리다.
     */
    @Test
    fun `XDG_STATE_HOME 이 비어 있으면 설정되지 않은 것으로 본다`() {
        val logFile = diagnosticLogFile(
            osName = "Linux",
            userHomeDirectory = Path.of("/home/yawn"),
            lookUpEnvironmentVariable = { "" },
        )

        assertEquals(Path.of("/home/yawn/.local/state/kyuchestration/desktop.log"), logFile)
    }

    /**
     * 맥은 XDG 를 보지 않는다.
     *
     * 맥에도 XDG_STATE_HOME 을 설정해 두는 사람이 있지만(리눅스에서 쓰던 dotfiles 를 그대로
     * 가져오면 그렇게 된다), 맥의 기록을 찾는 사람과 도구가 보는 자리는 ~/Library/Logs 다.
     */
    @Test
    fun `맥에서는 XDG_STATE_HOME 이 설정돼 있어도 무시한다`() {
        val logFile = diagnosticLogFile(
            osName = "Mac OS X",
            userHomeDirectory = Path.of("/Users/yawn"),
            lookUpEnvironmentVariable = { "/Users/yawn/.state" },
        )

        assertEquals(Path.of("/Users/yawn/Library/Logs/Kyuchestration/desktop.log"), logFile)
    }
}
