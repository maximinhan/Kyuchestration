package com.kyuchestration.desktop.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** JVM 의 os.name 을 읽는 규칙은 여기 하나다 — 틀에 싣는 OS 낱말도, 맥 · 윈도우 갈래도 이것을 쓴다. */
class OperatingSystemTest {

    @Test
    fun `JVM 의 os 이름을 틀의 OS 낱말로 옮긴다`() {
        assertEquals("windows", operatingSystemWordOf("Windows 11"))
        assertEquals("macos", operatingSystemWordOf("Mac OS X"))
        assertEquals("linux", operatingSystemWordOf("Linux"))
        assertEquals(null, operatingSystemWordOf("SunOS"))
    }

    @Test
    fun `맥과 윈도우 갈래는 같은 낱말 규칙으로 가른다`() {
        assertTrue(runningOnMac("Mac OS X"))
        assertTrue(runningOnWindows("Windows Server 2022"))
        assertFalse(runningOnMac("Linux"))
        assertFalse(runningOnWindows("Linux"))
    }
}
