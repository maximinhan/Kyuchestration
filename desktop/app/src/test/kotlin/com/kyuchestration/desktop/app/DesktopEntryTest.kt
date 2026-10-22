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
            coreLaunchCommand(properties::get, jvmArguments = { error("실행기가 JVM 설정을 다시 읽으므로 묻지 않는다") }),
        )
    }

    /**
     * `./gradlew run` 과 IDE 는 실행기 없이 java 로 띄운다. 같은 java · 같은 클래스패스로 이 main 을 다시 부르고, 이 JVM 이 받은
     * 시스템 프로퍼티(`-D…`)를 그대로 넘긴다 — 코어가 동봉 엔진을 찾는 앱 리소스 자리도 그중 하나다. 무엇을 넘길지 골라 적지 않는다:
     * 골라 적으면 새 프로퍼티가 생길 때마다 이 목록을 고쳐야 하고, 빠뜨린 것은 코어에서만 조용히 다르게 돈다.
     */
    @Test
    fun `실행기 없이 java 로 떴으면 같은 java 와 클래스패스로 다시 띄우고 받은 시스템 프로퍼티를 그대로 넘긴다`() {
        val properties = mapOf("java.home" to "/usr/lib/jvm/21", "java.class.path" to "/w/app.jar:/w/core.jar")
        val jvmArguments = listOf(
            "-Xmx2g",
            "-Dcompose.application.resources.dir=/w/build/compose/tmp/prepareAppResources",
            "-Dfile.encoding=UTF-8",
            "-agentlib:jdwp=transport=dt_socket",
        )

        assertEquals(
            listOf(
                "/usr/lib/jvm/21/bin/java",
                "-Dcompose.application.resources.dir=/w/build/compose/tmp/prepareAppResources",
                "-Dfile.encoding=UTF-8",
                "-cp",
                "/w/app.jar:/w/core.jar",
                "com.kyuchestration.desktop.app.DesktopEntryKt",
                "core",
                "serve",
                "--stdio",
            ),
            coreLaunchCommand(properties::get, jvmArguments = { jvmArguments }),
        )
    }
}
