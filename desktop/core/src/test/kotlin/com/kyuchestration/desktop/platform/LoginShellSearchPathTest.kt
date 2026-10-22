package com.kyuchestration.desktop.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 진짜 셸을 띄우지 않는다. 여기서 시험하는 것은 "무엇을 물어보고 그 답을 어떻게 읽는가" 이고,
 * 진짜 셸을 부르면 그 답이 검사를 돌리는 머신의 설정 파일에 달린다.
 */
class LoginShellSearchPathTest {

    @Test
    fun `SHELL 이 가리키는 셸에게 로그인 셸로 묻는다`() {
        val command = loginShellSearchPathCommand(shellFromEnvironment = "/bin/zsh", osName = "Mac OS X")

        assertEquals("/bin/zsh", command[0])
        // -l 이 있어야 .zprofile · .profile 이 읽힌다. brew 가 PATH 를 넣는 자리가 거기다.
        assertEquals("-l", command[1])
        assertEquals("-c", command[2])
    }

    @Test
    fun `묻는 명령은 PATH 를 표식 사이에 끼워 찍게 한다`() {
        val command = loginShellSearchPathCommand(shellFromEnvironment = "/bin/zsh", osName = "Mac OS X")

        // 설정 파일이 stdout 에 뭔가를 찍는 머신이 있다. 표식이 없으면 그 줄들과 PATH 를
        // 가려낼 방법이 없다.
        assertTrue(command[3].contains("\${PATH}"), "PATH 를 펴서 찍어야 한다: ${command[3]}")
        assertEquals(2, command[3].split(SEARCH_PATH_MARKER).size - 1, "표식이 앞뒤로 하나씩이어야 한다")
    }

    @Test
    fun `SHELL 이 없는 맥에서는 zsh 로 묻는다`() {
        val command = loginShellSearchPathCommand(shellFromEnvironment = null, osName = "Mac OS X")

        // 카탈리나 이후 맥의 기본 셸이다. GUI 앱이 SHELL 을 물려받지 못한 자리에서도
        // .zprofile 은 읽어야 한다.
        assertEquals("/bin/zsh", command[0])
    }

    @Test
    fun `SHELL 이 없는 리눅스에서는 sh 로 묻는다`() {
        val command = loginShellSearchPathCommand(shellFromEnvironment = null, osName = "Linux")

        // 어느 배포판에나 있는 것은 /bin/sh 하나다. zsh 를 넣어 두면 없는 파일을 부르고 끝난다.
        assertEquals("/bin/sh", command[0])
    }

    @Test
    fun `SHELL 이 비어 있으면 없는 것으로 본다`() {
        val command = loginShellSearchPathCommand(shellFromEnvironment = "   ", osName = "Linux")

        assertEquals("/bin/sh", command[0])
    }

    @Test
    fun `표식 사이의 값만 PATH 로 읽는다`() {
        val searchPath = searchPathInLoginShellOutput(
            "Welcome to my shell\n$SEARCH_PATH_MARKER/opt/homebrew/bin:/usr/bin$SEARCH_PATH_MARKER\n",
        )

        assertEquals("/opt/homebrew/bin:/usr/bin", searchPath)
    }

    @Test
    fun `표식이 없는 출력은 답으로 치지 않는다`() {
        // 셸이 설정 파일에서 죽었거나, 부른 것이 셸이 아니었던 경우다.
        assertNull(searchPathInLoginShellOutput("command not found: --l\n"))
    }

    @Test
    fun `표식 사이가 비어 있으면 답으로 치지 않는다`() {
        assertNull(searchPathInLoginShellOutput("$SEARCH_PATH_MARKER$SEARCH_PATH_MARKER\n"))
    }

    @Test
    fun `셸을 부르지 못하면 답하지 않는다`() {
        val searchPath = loginShellSearchPath(
            lookUpEnvironmentVariable = { "/bin/zsh" },
            osName = "Mac OS X",
            readLoginShellOutput = { null },
        )

        // 이 탐색은 없어도 앱이 도는 보강이다. 여기서 끊으면 셸이 없는 머신에서 앱이 뜨지 않는다.
        assertNull(searchPath)
    }

    @Test
    fun `셸이 답한 PATH 를 그대로 돌려준다`() {
        var askedCommand: List<String>? = null
        val searchPath = loginShellSearchPath(
            lookUpEnvironmentVariable = { name -> "/bin/bash".takeIf { name == "SHELL" } },
            osName = "Mac OS X",
            readLoginShellOutput = { command ->
                askedCommand = command
                "$SEARCH_PATH_MARKER/opt/homebrew/bin:/usr/bin$SEARCH_PATH_MARKER\n"
            },
        )

        assertEquals("/opt/homebrew/bin:/usr/bin", searchPath)
        assertEquals("/bin/bash", askedCommand?.first())
    }
}
