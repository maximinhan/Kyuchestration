package com.kyuchestration.desktop.platform

import java.io.File
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class ChildProcessEnvironmentTest {

    @Test
    fun `로그인 셸이 답한 자리가 물려받은 자리보다 앞에 선다`() {
        val searchPath = searchPathForChildProcesses(
            loginShellSearchPath = searchPathOf("/opt/homebrew/bin", "/usr/bin"),
            inheritedSearchPath = searchPathOf("/usr/bin", "/bin"),
            wellKnownDirectories = emptyList(),
        )

        // 터미널에서 kyu 와 tmux 를 부를 때 실제로 걸리는 순서가 로그인 셸의 것이다. 앱 안에서
        // 부르는 것도 그 순서와 같아야 "터미널에서는 되는데" 가 없어진다.
        assertEquals(searchPathOf("/opt/homebrew/bin", "/usr/bin", "/bin"), searchPath)
    }

    @Test
    fun `같은 자리는 한 번만 남는다`() {
        val searchPath = searchPathForChildProcesses(
            loginShellSearchPath = searchPathOf("/usr/bin", "/bin"),
            inheritedSearchPath = searchPathOf("/bin", "/usr/bin"),
            wellKnownDirectories = listOf("/usr/bin"),
        )

        assertEquals(searchPathOf("/usr/bin", "/bin"), searchPath)
    }

    @Test
    fun `로그인 셸에 묻지 못했으면 물려받은 자리와 잘 알려진 자리로 채운다`() {
        val searchPath = searchPathForChildProcesses(
            loginShellSearchPath = null,
            inheritedSearchPath = searchPathOf("/usr/bin", "/bin"),
            wellKnownDirectories = listOf("/opt/homebrew/bin", "/home/me/.local/bin"),
        )

        assertEquals(
            searchPathOf("/usr/bin", "/bin", "/opt/homebrew/bin", "/home/me/.local/bin"),
            searchPath,
        )
    }

    @Test
    fun `잘 알려진 자리는 언제나 맨 뒤에 붙는다`() {
        val searchPath = searchPathForChildProcesses(
            loginShellSearchPath = searchPathOf("/usr/bin"),
            inheritedSearchPath = searchPathOf("/bin"),
            wellKnownDirectories = listOf("/opt/homebrew/bin"),
        )

        // 맨 뒤라 이미 찾히던 것의 순서를 바꾸지 않는다. brew 가 넣은 자리를 .zshrc 에서만
        // 여는 사람(로그인 셸은 그 파일을 읽지 않는다)에게는 이 한 자리가 tmux 를 찾아 준다.
        assertEquals(searchPathOf("/usr/bin", "/bin", "/opt/homebrew/bin"), searchPath)
    }

    @Test
    fun `빈 항목은 버린다`() {
        val searchPath = searchPathForChildProcesses(
            loginShellSearchPath = searchPathOf("", "/usr/bin", ""),
            inheritedSearchPath = null,
            wellKnownDirectories = emptyList(),
        )

        // 빈 항목은 현재 디렉토리를 뜻한다. 워크디렉토리마다 다른 것이 걸릴 자리를 자식에게
        // 물려줄 이유가 없다.
        assertEquals("/usr/bin", searchPath)
    }

    @Test
    fun `잘 알려진 자리는 실제로 있는 것만 답한다`() {
        val directories = wellKnownSearchPathDirectories(
            userHomeDirectory = Path.of("/Users/me"),
            isExistingDirectory = { it in setOf(Path.of("/opt/homebrew/bin"), Path.of("/Users/me/.local/bin")) },
        )

        // 없는 자리를 PATH 에 넣어도 해롭지는 않지만, 앱이 무엇을 보고 있는지 말할 때
        // 실제로 있는 것만 적혀 있어야 한다.
        assertEquals(listOf("/opt/homebrew/bin", "/Users/me/.local/bin"), directories)
    }

    @Test
    fun `잘 알려진 자리는 brew 와 사용자 설치 자리를 본다`() {
        val directories = wellKnownSearchPathDirectories(
            userHomeDirectory = Path.of("/Users/me"),
            isExistingDirectory = { true },
        )

        // 애플 실리콘 brew · 인텔 brew(와 리눅스 공용) · install.sh 가 kyu 를 놓는 자리.
        assertEquals(
            listOf("/opt/homebrew/bin", "/usr/local/bin", "/Users/me/.local/bin"),
            directories,
        )
    }

    private fun searchPathOf(vararg directories: String): String =
        directories.joinToString(File.pathSeparator)
}
