package com.kyuchestration.desktop.core

import com.kyuchestration.desktop.protocol.FrameToCore
import com.kyuchestration.desktop.protocol.FrameToScreen
import com.kyuchestration.desktop.protocol.SCREEN_PROTOCOL_VERSION
import com.kyuchestration.desktop.protocol.ScreenFacingCore
import com.kyuchestration.desktop.protocol.operatingSystemWordOf

/** 시험이 세우는 코어가 인사 답에 싣는 자기 소개. */
val TEST_CORE_IDENTITY = CoreIdentity(
    versionLabel = "v0.15.0",
    diagnosticLogPath = "/home/yawn/.local/state/kyuchestration/core.log",
    operatingSystem = "linux",
    wslDistributionName = "Ubuntu-24.04",
)

/** 시험의 화면 판. 코어와 같은 판의 화면이다. */
const val TEST_SCREEN_VERSION_LABEL = "v0.15.0"

/** 같은 규약 판을 말하는, 시험이 도는 이 머신의 화면이 보내는 인사. */
val TEST_SCREEN_HELLO = FrameToCore.Hello(
    protocol = SCREEN_PROTOCOL_VERSION,
    screenVersion = TEST_SCREEN_VERSION_LABEL,
    screenOs = operatingSystemWordOf(System.getProperty("os.name").orEmpty()),
)

/** [TEST_CORE_IDENTITY] 로 선 [core] 가 인사에 답하는 틀. */
fun welcomeFrom(core: ScreenFacingCore): FrameToScreen.Welcome = FrameToScreen.Welcome(
    protocol = SCREEN_PROTOCOL_VERSION,
    coreVersion = TEST_CORE_IDENTITY.versionLabel,
    coreOs = TEST_CORE_IDENTITY.operatingSystem,
    wslDistro = TEST_CORE_IDENTITY.wslDistributionName,
    coreDiagnosticLog = TEST_CORE_IDENTITY.diagnosticLogPath,
    engineDirectory = core.engineDirectoryLabel,
)
