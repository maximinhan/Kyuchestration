package com.kyuchestration.desktop.terminal.chat

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 승인 소켓을 어디에 둘지를 본다(chat-ui-design.md 5.4).
 *
 * **재려는 것은 하나다 — 이 자리가 짧고 이 사용자만의 것인가.** `AF_UNIX` 경로는 107 바이트가
 * 한계이고(실측 3.7), 워크디렉토리 아래에 두었다가 그 한계에 걸리는 것이 이 기능이 조용히 죽는
 * 방식이다. 그래서 이 함수는 워크디렉토리를 아예 받지 않는다.
 */
class PermissionSocketLocationTest {

    @Test
    fun `리눅스에서는 런타임 디렉토리 아래에 둔다`() {
        val directory = permissionSocketDirectory(
            osName = "Linux",
            lookUpEnvironmentVariable = { name -> "/run/user/1000".takeIf { name == "XDG_RUNTIME_DIR" } },
            userName = "yawn",
        )

        assertEquals(Path.of("/run/user/1000/kyuchestration"), directory)
    }

    @Test
    fun `런타임 디렉토리가 없으면 tmp 아래 이 사용자의 자리를 쓴다`() {
        // 데스크톱 런처가 아닌 자리에서 띄운 앱과 컨테이너가 이 갈래를 지난다. 공용 /tmp 에 그대로
        // 놓으면 같은 머신의 다른 사용자가 그 이름을 먼저 차지할 수 있다.
        val directory = permissionSocketDirectory(
            osName = "Linux",
            lookUpEnvironmentVariable = { null },
            userName = "yawn",
        )

        assertEquals(Path.of("/tmp/kyuchestration-yawn"), directory)
    }

    @Test
    fun `빈 값으로 설정된 런타임 디렉토리는 없는 것으로 읽는다`() {
        // 설정되어 있으나 비어 있는 환경 변수는 실제로 있다. 그것을 그대로 이으면 소켓이
        // 상대경로 자리에 열리고, 그 자리는 앱의 작업 디렉토리에 따라 매번 달라진다.
        val directory = permissionSocketDirectory(
            osName = "Linux",
            lookUpEnvironmentVariable = { "" },
            userName = "yawn",
        )

        assertEquals(Path.of("/tmp/kyuchestration-yawn"), directory)
    }

    @Test
    fun `맥에서는 TMPDIR 아래에 둔다`() {
        // 맥에는 XDG_RUNTIME_DIR 이 없고, launchd 가 사용자마다 다른 TMPDIR 을 준다.
        val directory = permissionSocketDirectory(
            osName = "Mac OS X",
            lookUpEnvironmentVariable = { name -> "/var/folders/x1/T".takeIf { name == "TMPDIR" } },
            userName = "yawn",
        )

        assertEquals(Path.of("/var/folders/x1/T/kyuchestration"), directory)
    }

    @Test
    fun `소켓 이름은 세션마다 다르고 짧다`() {
        // 세션 이름이나 레포 이름을 쓰지 않는다. 길어지면 107 바이트에 가까워지고, 같은 이름의
        // 세션을 다른 워크디렉토리에서 열면 두 세션이 한 소켓을 두고 다툰다.
        val firstName = newPermissionSocketFileName()
        val secondName = newPermissionSocketFileName()

        assertNotEquals(firstName, secondName)
        assertTrue(firstName.endsWith(".sock"), "소켓 이름 = $firstName")
        assertTrue(firstName.length <= "12345678.sock".length, "소켓 이름이 깁니다: $firstName")
    }

    @Test
    fun `아무리 긴 워크디렉토리에서 열어도 소켓 경로는 한계 안에 있다`() {
        // 실측 3.7 이 만난 실패가 이것이다 — 워크디렉토리 경로를 소켓 자리로 쓰면 사용자가 지은
        // 긴 이름 하나가 승인 기능 전체를 조용히 죽인다. 이 함수가 워크디렉토리를 받지 않는 것이
        // 그 실패를 구조적으로 막는다.
        val socketPath = permissionSocketDirectory(
            osName = "Linux",
            lookUpEnvironmentVariable = { name -> "/run/user/1000".takeIf { name == "XDG_RUNTIME_DIR" } },
            userName = "yawn",
        ).resolve(newPermissionSocketFileName())

        assertTrue(
            socketPath.toString().toByteArray().size <= 107,
            "소켓 경로가 107 바이트를 넘습니다: $socketPath",
        )
    }
}
