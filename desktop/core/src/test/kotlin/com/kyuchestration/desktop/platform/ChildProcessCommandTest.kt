package com.kyuchestration.desktop.platform

import java.io.File
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 자식 프로세스의 실행 파일을 자식에게 줄 PATH 에서 찾는다.
 *
 * `ProcessBuilder` 는 이름뿐인 명령을 자식에게 줄 환경이 아니라 이 JVM 이 받은 PATH 로 찾는다(3 단계 실측 — JVM 의 PATH 에 없고
 * 자식 환경의 PATH 에만 있는 claude 는 "No such file or directory"). `wsl.exe -e` 로 뜬 코어의 PATH 에는 `~/.local/bin` 이 없다
 * (설계 3.7). 찾는 PATH 와 물려주는 PATH 가 같아야 한다.
 */
class ChildProcessCommandTest {

    private val executables = setOf(Path.of("/home/me/.local/bin/claude"), Path.of("/usr/bin/claude"))

    @Test
    fun `이름뿐인 명령은 자식에게 줄 PATH 의 앞자리부터 찾아 절대 경로로 바꾼다`() {
        val command = commandResolvedOnChildSearchPath(
            command = listOf("claude", "auth", "status"),
            childEnvironment = mapOf("PATH" to listOf("/home/me/.local/bin", "/usr/bin").joinToString(File.pathSeparator)),
            isExecutableFile = executables::contains,
        )

        assertEquals(listOf("/home/me/.local/bin/claude", "auth", "status"), command)
    }

    /** 찾지 못하면 그대로 둔다 — 띄우는 자리가 "실행하지 못했다" 를 이미 사람의 말로 알린다. */
    @Test
    fun `자식의 PATH 에 없으면 그대로 둔다`() {
        val command = commandResolvedOnChildSearchPath(
            command = listOf("claude"),
            childEnvironment = mapOf("PATH" to "/opt/nothing"),
            isExecutableFile = executables::contains,
        )

        assertEquals(listOf("claude"), command)
    }

    @Test
    fun `경로가 담긴 명령은 찾지 않는다`() {
        val command = commandResolvedOnChildSearchPath(
            command = listOf("/usr/bin/claude", "-p"),
            childEnvironment = mapOf("PATH" to "/home/me/.local/bin"),
            isExecutableFile = executables::contains,
        )

        assertEquals(listOf("/usr/bin/claude", "-p"), command)
    }
}
