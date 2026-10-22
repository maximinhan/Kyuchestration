package com.kyuchestration.desktop.wsl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 윈도우 화면의 경로와 WSL 코어의 경로를 서로 옮긴다(core-ui-split-design.md 6.9 의 3 단계 정정).
 *
 * 경로 모양은 2026-10-22 이 머신에서 윈도우 JVM(OpenJDK 23)이 `\\wsl.localhost` 아래를 읽어 준 그대로다.
 */
class WslPathTranslationTest {

    private val distribution = "Ubuntu-24.04"

    @Test
    fun `wsl localhost 아래 코어 배포판의 폴더는 그 배포판의 리눅스 경로가 된다`() {
        assertEquals(
            CoreWorkDirPath.Translated("/home/yawn/워크 디렉토리"),
            coreWorkDirPathOf("\\\\wsl.localhost\\Ubuntu-24.04\\home\\yawn\\워크 디렉토리", distribution),
        )
    }

    /** 윈도우 JVM 은 정규화해도 `\\wsl$` 를 `\\wsl.localhost` 로 맞춰 주지 않는다(설계 3.3). 두 형식을 다 받는다. */
    @Test
    fun `옛 형식 wsl 달러도 같게 옮긴다`() {
        assertEquals(CoreWorkDirPath.Translated("/home/yawn"), coreWorkDirPathOf("\\\\wsl$\\Ubuntu-24.04\\home\\yawn", distribution))
    }

    /** 윈도우는 호스트 이름과 배포판 이름의 대소문자를 가리지 않는다(`wslpath -u` 도 같다 — 설계 3.3). */
    @Test
    fun `호스트와 배포판 이름의 대소문자를 가리지 않는다`() {
        assertEquals(CoreWorkDirPath.Translated("/home"), coreWorkDirPathOf("\\\\WSL.LOCALHOST\\ubuntu-24.04\\home", distribution))
    }

    @Test
    fun `배포판의 뿌리는 리눅스의 뿌리다`() {
        assertEquals(CoreWorkDirPath.Translated("/"), coreWorkDirPathOf("\\\\wsl.localhost\\Ubuntu-24.04\\", distribution))
        assertEquals(CoreWorkDirPath.Translated("/"), coreWorkDirPathOf("\\\\wsl.localhost\\Ubuntu-24.04", distribution))
    }

    /**
     * 윈도우가 이름에 쓰지 못하는 글자(`\ : * ? " < > |`)를 담은 리눅스 이름은 윈도우 쪽에 U+F000 + 그 글자로 보인다. 실측: `a:b` 가
     * `a\uF03Ab` 로 왔고 `wslpath -u` 가 되돌렸다. 되돌리지 않으면 코어는 없는 폴더를 연다.
     */
    @Test
    fun `윈도우가 사설 영역 글자로 바꿔 보인 리눅스 이름을 되돌린다`() {
        val seenFromWindows = listOf("a\uF03Ab", "q\uF03F", "star\uF02A", "pipe\uF07Cx", "lt\uF03Cgt\uF03E", "quote\uF022d", "back\uF05Cslash")

        val translated = seenFromWindows.map { name -> coreWorkDirPathOf("\\\\wsl.localhost\\Ubuntu-24.04\\w\\$name", distribution) }

        assertEquals(
            listOf("/w/a:b", "/w/q?", "/w/star*", "/w/pipe|x", "/w/lt<gt>", "/w/quote\"d", "/w/back\\slash").map(CoreWorkDirPath::Translated),
            translated,
        )
    }

    /** 다른 배포판의 폴더는 이 코어가 볼 수 없다 — `wslpath -u` 도 풀지 못한다(설계 3.3). */
    @Test
    fun `다른 배포판의 폴더는 두 배포판 이름을 들어 거절한다`() {
        val refused = assertIs<CoreWorkDirPath.Refused>(coreWorkDirPathOf("\\\\wsl.localhost\\docker-desktop\\tmp", distribution))

        assertTrue("Ubuntu-24.04" in refused.reason && "docker-desktop" in refused.reason, refused.reason)
    }

    @Test
    fun `윈도우 드라이브의 폴더는 거절한다`() {
        val refused = assertIs<CoreWorkDirPath.Refused>(coreWorkDirPathOf("C:\\Users\\maxim\\work", distribution))

        assertTrue("C:\\Users\\maxim\\work" in refused.reason && "Ubuntu-24.04" in refused.reason, refused.reason)
    }

    @Test
    fun `WSL 이 아닌 네트워크 공유도 거절한다`() {
        assertIs<CoreWorkDirPath.Refused>(coreWorkDirPathOf("\\\\fileserver\\share\\work", distribution))
    }

    /** 맥 · 리눅스, 그리고 WSLg 의 화면은 코어와 같은 OS 를 본다. 옮길 것이 없다. */
    @Test
    fun `코어가 WSL 너머에 있지 않으면 화면 경로가 곧 코어 경로다`() {
        assertEquals(CoreWorkDirPath.Translated("/home/yawn/work"), coreWorkDirPathOf("/home/yawn/work", coreWslDistributionName = null))
    }

    @Test
    fun `코어 경로를 윈도우 화면이 여는 경로로 옮기면 같은 길로 되돌아온다`() {
        val corePath = "/home/yawn/.local/state/kyuchestration/a:b/core.log"

        val screenPath = screenPathOfCorePath(corePath, distribution)

        assertEquals("\\\\wsl.localhost\\Ubuntu-24.04\\home\\yawn\\.local\\state\\kyuchestration\\a\uF03Ab\\core.log", screenPath)
        assertEquals(CoreWorkDirPath.Translated(corePath), coreWorkDirPathOf(screenPath, distribution))
    }

    @Test
    fun `코어가 WSL 너머에 있지 않으면 코어 경로를 그대로 보인다`() {
        assertEquals("/home/yawn/core.log", screenPathOfCorePath("/home/yawn/core.log", coreWslDistributionName = null))
    }
}
