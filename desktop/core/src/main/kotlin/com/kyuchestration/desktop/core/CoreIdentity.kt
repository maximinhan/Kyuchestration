package com.kyuchestration.desktop.core

import com.kyuchestration.desktop.platform.currentOperatingSystemName
import com.kyuchestration.desktop.platform.operatingSystemWordOf

/**
 * 코어가 인사 답(welcome)에 싣는 자기 소개(core-ui-split-design.md 6.4 · 6.14).
 *
 * @param versionLabel 코어 jar 의 판 라벨. 화면과 코어가 따로 깔리면 판이 갈릴 수 있어, 화면이 어느 쪽이 낡았는지 보이는 근거다.
 * @param diagnosticLogPath 코어가 진단을 적는 파일. 화면의 것과 다른 파일이라 오류 자리에서 둘을 함께 알린다(6.2 (다)).
 * @param operatingSystem 코어가 도는 OS 의 낱말(operatingSystemWordOf).
 * @param wslDistributionName 코어가 WSL 안에서 돌면 그 배포판. 윈도우 화면이 경로를 옮기는 근거다(6.9).
 */
data class CoreIdentity(
    val versionLabel: String,
    val diagnosticLogPath: String,
    val operatingSystem: String?,
    val wslDistributionName: String?,
)

/**
 * 이 머신에서 읽어 코어의 자기 소개를 짓는다.
 *
 * 배포판은 `WSL_DISTRO_NAME` 으로 안다. WSL 은 배포판 안의 모든 프로세스에 이 변수를 주고, `wsl.exe -e` 로 셸 없이 뜬 코어도
 * 받는다(3단계 실측).
 */
fun coreIdentityOf(
    versionLabel: String,
    diagnosticLogPath: String,
    osName: String = currentOperatingSystemName(),
    lookUpEnvironmentVariable: (String) -> String? = System::getenv,
): CoreIdentity = CoreIdentity(
    versionLabel = versionLabel,
    diagnosticLogPath = diagnosticLogPath,
    operatingSystem = operatingSystemWordOf(osName),
    wslDistributionName = lookUpEnvironmentVariable(WSL_DISTRIBUTION_NAME_VARIABLE)?.takeIf { it.isNotBlank() },
)

private const val WSL_DISTRIBUTION_NAME_VARIABLE = "WSL_DISTRO_NAME"
