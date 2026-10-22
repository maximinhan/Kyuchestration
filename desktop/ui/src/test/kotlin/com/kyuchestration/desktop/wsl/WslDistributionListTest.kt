package com.kyuchestration.desktop.wsl

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `wsl.exe -l -v` 의 출력에서 사람이 고를 배포판을 읽는다(core-ui-split-design.md 3.4 · 6.17).
 *
 * 줄은 2026-10-22 이 머신(윈도우 10.0.26300, WSL 2.4.11)에서 받은 그대로다. 머리줄은 한국어 로케일에서도 영어였다.
 */
class WslDistributionListTest {

    private val listedLines = "  NAME              STATE           VERSION\r\n" +
        "* Ubuntu-24.04      Running         2\r\n" +
        "  docker-desktop    Running         2\r\n"

    @Test
    fun `WSL_UTF8 을 따른 UTF-8 출력에서 배포판과 기본 배포판을 읽고 docker-desktop 은 뺀다`() {
        assertEquals(
            WslDistributions(names = listOf("Ubuntu-24.04"), defaultName = "Ubuntu-24.04"),
            parseWslDistributionList(listedLines.toByteArray(Charsets.UTF_8)),
        )
    }

    /** WSL_UTF8 을 모르는 옛 wsl.exe 는 BOM 없는 UTF-16LE 로 쓴다(3.4 — 켜지 않았을 때의 출력이 그것이다). */
    @Test
    fun `BOM 없는 UTF-16LE 출력도 같게 읽는다`() {
        assertEquals(
            WslDistributions(names = listOf("Ubuntu-24.04"), defaultName = "Ubuntu-24.04"),
            parseWslDistributionList(listedLines.toByteArray(Charsets.UTF_16LE)),
        )
    }

    @Test
    fun `docker-desktop 으로 시작하는 배포판은 모두 빼고 그것이 기본이면 기본은 없다`() {
        val lines = "  NAME                   STATE           VERSION\r\n" +
            "* docker-desktop         Running         2\r\n" +
            "  docker-desktop-data    Stopped         2\r\n" +
            "  Debian                 Stopped         2\r\n" +
            "  Ubuntu-22.04           Stopped         1\r\n"

        assertEquals(
            WslDistributions(names = listOf("Debian", "Ubuntu-22.04"), defaultName = null),
            parseWslDistributionList(lines.toByteArray(Charsets.UTF_8)),
        )
    }

    @Test
    fun `머리줄만 있으면 배포판이 없다`() {
        assertEquals(
            WslDistributions(names = emptyList(), defaultName = null),
            parseWslDistributionList("  NAME    STATE    VERSION\r\n".toByteArray(Charsets.UTF_8)),
        )
    }

    @Test
    fun `wsl 이 스스로 쓴 글은 UTF-16LE 든 UTF-8 이든 같은 문자열로 읽는다`() {
        val message = "제공된 이름의 배포가 없습니다.\r\n오류 코드: Wsl/Service/WSL_E_DISTRO_NOT_FOUND\r\n"

        assertEquals(message, decodeWslOutput(message.toByteArray(Charsets.UTF_16LE)))
        assertEquals(message, decodeWslOutput(message.toByteArray(Charsets.UTF_8)))
    }
}
