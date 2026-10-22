package com.kyuchestration.desktop.core

/**
 * 코어가 인사 답(welcome)에 싣는 자기 소개(core-ui-split-design.md 6.4 · 6.14).
 *
 * @param versionLabel 코어 jar 의 판 라벨. 화면과 코어가 따로 깔리면 판이 갈릴 수 있어, 화면이 어느 쪽이 낡았는지 보이는 근거다.
 * @param diagnosticLogPath 코어가 진단을 적는 파일. 화면의 것과 다른 파일이라 오류 자리에서 둘을 함께 알린다(6.2 (다)).
 */
data class CoreIdentity(val versionLabel: String, val diagnosticLogPath: String)
