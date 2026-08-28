package com.kyuchestration.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopBuildVersionTest {

    @Test
    fun `패키징된 빌드는 매니페스트 버전에 v 를 붙여 보여준다`() {
        assertEquals("v0.1.0", DesktopBuildVersion.formatVersionLabel("0.1.0"))
    }

    @Test
    fun `매니페스트가 없는 실행은 개발 빌드로 표시한다`() {
        assertEquals("개발 빌드", DesktopBuildVersion.formatVersionLabel(null))
    }

    @Test
    fun `매니페스트 값이 공백뿐이어도 개발 빌드로 떨어진다`() {
        assertEquals("개발 빌드", DesktopBuildVersion.formatVersionLabel("   "))
    }
}
