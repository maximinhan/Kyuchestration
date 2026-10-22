package com.kyuchestration.desktop

import com.kyuchestration.desktop.diagnostics.BuildVersion

/** 화면의 판. 화면 jar 의 매니페스트에서 읽는다 — 코어의 판은 코어가 인사 답(welcome)으로 알린다. */
object DesktopBuildVersion {
    val label: String = BuildVersion.labelOf(DesktopBuildVersion::class.java)
}
