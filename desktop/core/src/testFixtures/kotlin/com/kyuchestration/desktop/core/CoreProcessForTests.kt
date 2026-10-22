package com.kyuchestration.desktop.core

import java.nio.file.Path
import kotlin.io.path.createDirectories

/**
 * 시험이 코어를 진짜 자식 프로세스로 띄우는 명령 — `kyuchestration-core serve --stdio` 를 이 시험 JVM 과 같은 java 와 클래스패스로.
 *
 * 설치 패키지에서는 앱의 실행기가 같은 일을 한다(app 모듈의 DesktopEntry). 시험은 패키지 없이 같은 main 을 부른다.
 */
fun coreProcessCommandForTests(): List<String> = listOf(
    ProcessHandle.current().info().command().orElseThrow(),
    "-cp",
    System.getProperty("java.class.path"),
    "com.kyuchestration.desktop.core.CoreMainKt",
    "serve",
    "--stdio",
)

/**
 * 띄운 코어가 이 머신의 사용자 자리를 건드리지 않게 하는 환경. 진단 기록은 [scratchDirectory] 아래에, 엔진은 [pinnedKyuExecutable]
 * 하나로 고정한다 — 엔진을 못 찾으면 코어가 첫 화면처럼 릴리스에서 받아 오기 시작한다.
 */
fun ProcessBuilder.isolatedCoreEnvironment(scratchDirectory: Path, pinnedKyuExecutable: Path): ProcessBuilder = apply {
    environment()["XDG_STATE_HOME"] = scratchDirectory.resolve("state").createDirectories().toString()
    environment()["KYU_BINARY_PATH"] = pinnedKyuExecutable.toString()
}
