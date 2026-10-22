package com.kyuchestration.desktop

import com.kyuchestration.desktop.terminal.chat.AppSocketChannel
import com.kyuchestration.desktop.terminal.chat.AskedPermission
import com.kyuchestration.desktop.terminal.chat.ChatSessionOpener
import com.kyuchestration.desktop.terminal.chat.HeldChatSession
import com.kyuchestration.desktop.terminal.chat.OpenedChatSession
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import kotlin.test.Test

/**
 * 화면이 코어에 닿는 길은 [com.kyuchestration.desktop.core.ScreenFacingCore] 하나다(core-ui-split-design.md 6.2).
 *
 * 화면 코드가 홀더나 프로세스를 쥔 타입을 다시 부르기 시작하면, 다음 걸음(1b)에서 그 경계를 직렬화로 바꿀 때 그 자리만
 * 조용히 같은 JVM 의 지름길로 남는다(원칙 20). 모듈이 갈리는 2 단계부터는 빌드가 이 방향을 막고, 그때까지는 이 시험이
 * 바이트코드의 의존을 읽어 막는다.
 *
 * 화면은 루트 패키지(컴포저블들과 Main)와 theme 이다. 코어 쪽 패키지의 값 타입(대화 · 트리 · 상태 갈래)을 읽는 것은
 * 괜찮다 — 그것들이 경계를 건너는 값이다(6.3).
 */
class ScreenBoundaryArchitectureTest {

    private val productionClasses = ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages("com.kyuchestration.desktop")

    @Test
    fun `화면은 상태 홀더에 닿지 않는다`() {
        noClasses().that().resideInAnyPackage(*SCREEN_PACKAGES)
            .should().dependOnClassesThat().haveSimpleNameEndingWith("StateHolder")
            .because("화면은 ScreenFacingCore 로만 코어에 닿는다(core-ui-split-design.md 6.2)")
            .check(productionClasses)
    }

    @Test
    fun `화면은 프로세스와 소켓을 쥔 타입에 닿지 않는다`() {
        // 경계를 건너는 것은 값뿐이다(원칙 22). 이 타입들은 프로세스 · 소켓 연결 · 답을 기다리는 Deferred 를 쥔다.
        noClasses().that().resideInAnyPackage(*SCREEN_PACKAGES)
            .should().dependOnClassesThat().belongToAnyOf(
                HeldChatSession::class.java,
                OpenedChatSession::class.java,
                ChatSessionOpener::class.java,
                AppSocketChannel::class.java,
                AskedPermission::class.java,
            )
            .because("프로세스 핸들 · 소켓 · Deferred 는 경계를 건너지 못한다(원칙 22)")
            .check(productionClasses)
    }

    @Test
    fun `화면은 kyu 를 부르는 어댑터에 닿지 않는다`() {
        noClasses().that().resideInAnyPackage(*SCREEN_PACKAGES)
            .should().dependOnClassesThat().resideInAnyPackage("..kyucli..", "com.kyuchestration.desktop.kyu..")
            .because("kyu 를 부르는 것은 코어의 일이다(원칙 19)")
            .check(productionClasses)
    }

    private companion object {
        val SCREEN_PACKAGES = arrayOf("com.kyuchestration.desktop", "com.kyuchestration.desktop.theme")
    }
}
