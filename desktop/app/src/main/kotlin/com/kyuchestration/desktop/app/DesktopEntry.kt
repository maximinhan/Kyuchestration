package com.kyuchestration.desktop.app

import com.kyuchestration.desktop.core.runCoreCommand
import com.kyuchestration.desktop.runScreen
import java.lang.management.ManagementFactory
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * 앱의 실행기가 부르는 main. 첫 인자가 [CORE_VERB] 면 코어로(`… core serve --stdio`), 아니면 화면으로 뜬다.
 *
 * 설치 패키지에 실행 파일이 하나뿐이라 이렇게 가른다(app/build.gradle.kts 머리말의 실측). 화면은 이 실행기를 코어 동사로 다시 띄워
 * 자식으로 두고 그 stdio 로만 말한다 — 같은 머신이어도 같은 JVM 지름길은 없다(core-ui-split-design.md 원칙 20).
 */
fun main(arguments: Array<String>) {
    if (arguments.firstOrNull() == CORE_VERB) {
        exitProcess(runCoreCommand(arguments.drop(1)))
    }
    runScreen(coreLaunchCommand = coreLaunchCommand())
}

/**
 * 이 프로그램을 코어 동사로 다시 띄우는 명령.
 *
 * 설치 패키지에서는 jpackage 실행기가 자기 자리를 `jpackage.app-path` 로 알려 준다. 실행기는 앱 리소스 자리 같은 JVM 설정을 자기
 * 설정 파일에서 다시 읽으므로 인자만 붙인다. 실행기 없이 java 로 떴으면(`./gradlew run` · IDE) 같은 java · 같은 클래스패스로 이 main 을
 * 부르고, 이 JVM 이 받은 시스템 프로퍼티(`-D…`)를 그대로 넘긴다 — 코어가 동봉 엔진을 찾는 앱 리소스 자리도 그중 하나다. 넘길 것을
 * 골라 적지 않는 것은 새 프로퍼티가 생길 때 그것만 코어에서 조용히 빠지지 않게다. 힙 크기 · 디버거 같은 다른 JVM 인자는 화면의 것이라
 * 넘기지 않는다.
 *
 * @param jvmArguments 이 JVM 을 띄운 인자. 실행기 없이 뜬 길에서만 묻는다 — 그것을 알려 주는 java.management 는 설치 패키지의 런타임에
 *   없다.
 */
internal fun coreLaunchCommand(
    lookUpSystemProperty: (String) -> String? = System::getProperty,
    jvmArguments: () -> List<String> = { ManagementFactory.getRuntimeMXBean().inputArguments },
): List<String> {
    val coreArguments = listOf(CORE_VERB, "serve", "--stdio")
    val appLauncher = lookUpSystemProperty(JPACKAGE_APP_PATH_PROPERTY)?.takeIf { it.isNotBlank() }
    if (appLauncher != null) {
        return listOf(appLauncher) + coreArguments
    }
    val javaExecutable = Path.of(lookUpSystemProperty("java.home").orEmpty(), "bin", "java").toString()
    val systemProperties = jvmArguments().filter { it.startsWith(SYSTEM_PROPERTY_ARGUMENT_PREFIX) }
    return listOf(javaExecutable) + systemProperties +
        listOf("-cp", lookUpSystemProperty("java.class.path").orEmpty(), ENTRY_CLASS_NAME) +
        coreArguments
}

private const val CORE_VERB = "core"

private const val JPACKAGE_APP_PATH_PROPERTY = "jpackage.app-path"

private const val SYSTEM_PROPERTY_ARGUMENT_PREFIX = "-D"

private const val ENTRY_CLASS_NAME = "com.kyuchestration.desktop.app.DesktopEntryKt"
