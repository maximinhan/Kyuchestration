package com.kyuchestration.desktop.diagnostics

import com.kyuchestration.desktop.platform.runningOnMac
import java.nio.file.Path

/**
 * 앱이 진단 기록을 남기는 파일.
 *
 * **엔진을 받아 두는 자리(managedEngineDirectory)와 정반대의 판단을 한다.** 그쪽은 환경 변수를
 * 일부러 보지 않는데, 앱이 쓰고 앱이 읽는 자리라 지켜야 할 것이 "쓴 자리와 읽는 자리가 같은가"
 * 하나이기 때문이다. 이 파일은 반대다 — 쓰는 것은 앱이지만 읽는 것은 사람이다. 오류를 보고하는
 * 사람이 이 파일을 손으로 찾아 건네야 하므로, 지켜야 할 것은 "그 사람이 이미 아는 자리인가" 다.
 * 그래서 여기서는 플랫폼 관례를 따르고 XDG_STATE_HOME 도 본다.
 *
 * 맥은 `~/Library/Logs` 다. 애플이 정한 자리이고, 콘솔 앱이 그 아래를 그대로 훑어 보여준다 —
 * 사용자가 경로를 몰라도 앱 이름으로 찾을 수 있는 유일한 자리다.
 *
 * 리눅스는 XDG Base Directory 명세의 상태 디렉토리다. 로그는 데이터도 캐시도 설정도 아니라
 * "다시 만들 수 있지만 지워지면 아쉬운 것" 이고, 명세가 그것을 위해 둔 자리가 state 다.
 * 설정하지 않은 머신의 기본값(`~/.local/state`)도 명세가 정해 둔 것을 그대로 쓴다.
 *
 * 윈도우 갈래는 두지 않는다. 릴리스가 윈도우 설치 패키지를 내지 않아(build.gradle.kts 의
 * targetFormats) 그 갈래를 지날 실행이 없고, 쓰지도 않을 AppData 갈래를 미리 두면 시험할 수
 * 없는 분기가 하나 생긴다 — 엔진 디렉토리가 같은 이유로 두 갈래인 것과 같은 선이다.
 *
 * @param lookUpEnvironmentVariable 환경 변수를 읽는 자리. 검사가 진짜 환경을 건드리지 않고
 *   XDG 규칙을 확인하기 위한 이음매다.
 */
internal fun diagnosticLogFile(
    osName: String = System.getProperty("os.name").orEmpty(),
    userHomeDirectory: Path = Path.of(System.getProperty("user.home").orEmpty()),
    lookUpEnvironmentVariable: (String) -> String? = System::getenv,
): Path = diagnosticLogDirectory(osName, userHomeDirectory, lookUpEnvironmentVariable).resolve(LOG_FILE_NAME)

private fun diagnosticLogDirectory(
    osName: String,
    userHomeDirectory: Path,
    lookUpEnvironmentVariable: (String) -> String?,
): Path {
    if (runningOnMac(osName)) {
        // 창 제목이 아니라 설치 패키지 이름(Kyuchestration)을 쓴다. 콘솔 앱과 Finder 가 이
        // 디렉토리 이름을 그대로 보여주는데, 한글 이름은 그 자리에서 사용자가 앱과 잇기 어렵다.
        return userHomeDirectory.resolve("Library/Logs/Kyuchestration")
    }

    val stateHomeDirectory = lookUpEnvironmentVariable(XDG_STATE_HOME_VARIABLE)
        ?.takeIf { it.isNotBlank() }
        ?.let(Path::of)
        ?: userHomeDirectory.resolve(".local/state")

    return stateHomeDirectory.resolve("kyuchestration")
}

/**
 * 파일 이름이 앱을 가리킨다.
 *
 * 엔진(kyu)도 언젠가 자기 기록을 남기게 되면 같은 디렉토리를 쓸 수 있다. 그때 어느 쪽이 남긴
 * 것인지가 파일 이름에서 갈라지지 않으면, 받은 기록을 읽는 쪽이 먼저 그것부터 가려내야 한다.
 */
private const val LOG_FILE_NAME = "desktop.log"

private const val XDG_STATE_HOME_VARIABLE = "XDG_STATE_HOME"
