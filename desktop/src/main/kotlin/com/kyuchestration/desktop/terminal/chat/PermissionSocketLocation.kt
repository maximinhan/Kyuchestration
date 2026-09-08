package com.kyuchestration.desktop.terminal.chat

import com.kyuchestration.desktop.platform.runningOnMac
import java.nio.file.Path
import java.util.UUID

/**
 * 세션의 승인 소켓을 놓을 디렉토리(chat-ui-design.md 5.4).
 *
 * **워크디렉토리 아래가 아니다.** 실측 3.7 이 그 함정을 만났다 — `AF_UNIX` 경로는 107 바이트가
 * 한계인데, 워크디렉토리 경로는 사용자가 정하므로 얼마든지 길어질 수 있고 그 길이가 승인 기능
 * 전체를 조용히 죽인다. 그래서 이 함수는 **워크디렉토리를 아예 받지 않는다** — 받지 않는 값은
 * 실수로 섞일 수 없다.
 *
 * 진단 기록의 자리(diagnosticLogFile)와 정반대의 판단을 한다. 그쪽은 사람이 손으로 찾아 건네야
 * 하는 파일이라 플랫폼 관례를 따르지만, 이 자리는 **앱이 쓰고 앱의 자식만 여는** 것이라 지켜야
 * 할 것이 둘뿐이다 — 짧을 것, 그리고 이 사용자만 들어올 수 있을 것.
 *
 * | OS | 자리 |
 * |---|---|
 * | 리눅스 | `$XDG_RUNTIME_DIR/kyuchestration` (없으면 `/tmp/kyuchestration-<사용자>`) |
 * | 맥 | `$TMPDIR/kyuchestration` (없으면 같은 대비) |
 *
 * **윈도우 갈래를 두지 않는다.** 릴리스가 윈도우 설치 패키지를 내지 않아 그 갈래를 지날 실행이
 * 없고(build.gradle.kts 의 targetFormats), 쓰지도 않을 `%LOCALAPPDATA%` 갈래를 미리 두면 시험할
 * 수 없는 분기가 하나 생긴다 — 진단 기록의 자리가 같은 이유로 두 갈래인 것과 같은 선이다.
 * 채널 자체는 윈도우에서도 성립한다(설계 5.4: JDK 16 이상의 `AF_UNIX` · Go 의 `net.Dial`).
 *
 * @param userName `/tmp` 로 내려갈 때 이 사용자의 자리를 가르는 이름. 설계는 `$UID` 라고 적었는데
 *   JVM 에는 그 값을 묻는 표준 통로가 없다. 지키려는 것은 "다른 사용자와 한 디렉토리를 쓰지
 *   않는다" 이고, 그 일은 사용자 이름도 똑같이 한다.
 */
internal fun permissionSocketDirectory(
    osName: String = System.getProperty("os.name").orEmpty(),
    lookUpEnvironmentVariable: (String) -> String? = System::getenv,
    userName: String = System.getProperty("user.name").orEmpty(),
): Path {
    val runtimeHomeVariable = if (runningOnMac(osName)) MAC_RUNTIME_HOME_VARIABLE else LINUX_RUNTIME_HOME_VARIABLE

    val runtimeHomeDirectory = lookUpEnvironmentVariable(runtimeHomeVariable)
        ?.takeIf { it.isNotBlank() }
        ?.let(Path::of)
        // 런타임 디렉토리가 없는 실행이 실제로 있다(데스크톱 런처가 아닌 자리에서 띄운 앱 · 컨테이너).
        // 그때 이 사용자만의 자리를 /tmp 아래에 따로 만든다 — 공용 /tmp 에 그대로 놓으면 같은
        // 머신의 다른 사용자가 그 소켓 이름을 먼저 차지할 수 있다.
        ?: return Path.of("/tmp", "kyuchestration-$userName")

    return runtimeHomeDirectory.resolve(RUNTIME_DIRECTORY_NAME)
}

/**
 * 세션 하나를 가리키는 소켓 파일 이름.
 *
 * **세션 이름이나 레포 이름을 쓰지 않는다.** 이름이 길면 107 바이트에 가까워지고, 같은 이름의
 * 세션을 다른 워크디렉토리에서 열면 두 세션이 한 소켓을 두고 다툰다 — 그때 어느 세션의 물음인지가
 * 다시 문제가 되는데, 세션마다 소켓을 열면 **연결 자체가 곧 어느 세션인지다**(설계 5.4).
 */
internal fun newPermissionSocketFileName(): String =
    UUID.randomUUID().toString().take(SOCKET_NAME_LENGTH) + ".sock"

/** `<8 자>.sock` — 짧게 두는 것이 이 이름의 전부다(107 바이트). */
private const val SOCKET_NAME_LENGTH = 8

private const val RUNTIME_DIRECTORY_NAME = "kyuchestration"

private const val LINUX_RUNTIME_HOME_VARIABLE = "XDG_RUNTIME_DIR"

private const val MAC_RUNTIME_HOME_VARIABLE = "TMPDIR"
