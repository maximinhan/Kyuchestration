package com.kyuchestration.desktop

import com.kyuchestration.desktop.diagnostics.BuildVersion

/** 화면의 판. 화면 jar 의 매니페스트에서 읽는다 — 코어의 판은 코어가 인사 답(welcome)으로 알린다. */
object DesktopBuildVersion {
    val label: String = BuildVersion.labelOf(DesktopBuildVersion::class.java)

    /** 개발 빌드면 null. 화면에 실린 WSL 코어 묶음은 같은 빌드에서 나와 이 판이다(core-ui-split-design.md 6.16 의 4a 정정). */
    val releaseLabel: String? = BuildVersion.releaseLabelOf(DesktopBuildVersion::class.java)
}
