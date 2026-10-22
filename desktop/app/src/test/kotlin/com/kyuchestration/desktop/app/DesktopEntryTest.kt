package com.kyuchestration.desktop.app

import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopEntryTest {

    @Test
    fun `설치 패키지에서는 앱의 실행기를 코어 동사로 다시 띄운다`() {
        val properties = mapOf(
            "jpackage.app-path" to "/opt/kyuchestration/bin/Kyuchestration",
            "java.home" to "/opt/kyuchestration/lib/runtime",
        )

        assertEquals(
            listOf("/opt/kyuchestration/bin/Kyuchestration", "core", "serve", "--stdio"),
            coreLaunchCommand(properties::get),
        )
    }

    /**
     * `./gradlew run` 과 IDE 는 실행기 없이 java 로 띄운다. 같은 java · 같은 클래스패스로 이 main 을 다시 부른다. 앱 리소스 자리는
     * 실어 보낸다 — 코어가 동봉 엔진을 거기서 찾는다(KyuExecutableLocation).
     */
    @Test
    fun `실행기 없이 java 로 떴으면 같은 java 와 클래스패스로 다시 띄우고 앱 리소스 자리를 실어 보낸다`() {
        val properties = mapOf(
            "java.home" to "/usr/lib/jvm/21",
            "java.class.path" to "/w/app.jar:/w/core.jar",
            "compose.application.resources.dir" to "/w/build/compose/tmp/prepareAppResources",
        )

        assertEquals(
            listOf(
                "/usr/lib/jvm/21/bin/java",
                "-Dcompose.application.resources.dir=/w/build/compose/tmp/prepareAppResources",
                "-cp",
                "/w/app.jar:/w/core.jar",
                "com.kyuchestration.desktop.app.DesktopEntryKt",
                "core",
                "serve",
                "--stdio",
            ),
            coreLaunchCommand(properties::get),
        )
    }

    @Test
    fun `앱 리소스 자리가 없으면 싣지 않는다`() {
        val properties = mapOf("java.home" to "/usr/lib/jvm/21", "java.class.path" to "/w/app.jar")

        assertEquals(
            listOf("/usr/lib/jvm/21/bin/java", "-cp", "/w/app.jar", "com.kyuchestration.desktop.app.DesktopEntryKt", "core", "serve", "--stdio"),
            coreLaunchCommand(properties::get),
        )
    }
}
