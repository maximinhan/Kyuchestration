package com.kyuchestration.desktop.mirror

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * 화면이 코어를 어디서 띄우는가(core-ui-split-design.md 6.12). 띄우는 명령과 환경만 갈리고, 그 뒤의 stdio 는 같다.
 */
class CorePlaceTest {

    @Test
    fun `같은 머신의 코어는 이 프로그램을 띄운 쪽이 준 명령 그대로 띄운다`() {
        val command = listOf("/opt/kyuchestration/bin/Kyuchestration", "core", "serve", "--stdio")

        val builder = CorePlace.SameMachine(command).coreProcessBuilder()

        assertEquals(command, builder.command())
        assertFalse(WSL_UTF8_VARIABLE in builder.environment())
    }

    /**
     * 셸을 거치지 않는다(설계 3.1 (나) — PowerShell 이 한국어를 `?` 로 바꾸고 `\r` 을 붙인다). `--cd ~` 와 홈 기준 상대 경로로 띄운다
     * (3.7 — 화면은 리눅스 홈을 모르고, `wsl.exe -e` 의 PATH 에는 `~/.local/bin` 이 없다). 인자에는 따옴표가 없는 낱말만 둔다(3.7 —
     * 윈도우 명령줄에서 큰따옴표가 사라진다).
     */
    @Test
    fun `WSL 배포판의 코어는 wsl 실행 파일로 셸 없이 홈에서 코어 실행 파일을 띄운다`() {
        val place = CorePlace.WslDistribution(
            wslExecutable = Path.of("C:\\Windows\\System32\\wsl.exe"),
            distributionName = "Ubuntu-24.04",
        )

        val builder = place.coreProcessBuilder()

        assertEquals(
            listOf(
                "C:\\Windows\\System32\\wsl.exe",
                "-d", "Ubuntu-24.04",
                "--cd", "~",
                "-e", ".local/bin/kyuchestration-core", "serve", "--stdio",
            ),
            builder.command(),
        )
        builder.command().forEach { argument -> assertFalse('"' in argument, "인자에 큰따옴표가 있다: $argument") }
    }

    @Test
    fun `wsl 실행 파일이 내는 글을 UTF-8 로 받는다`() {
        val place = CorePlace.WslDistribution(Path.of("C:\\Windows\\System32\\wsl.exe"), "Ubuntu-24.04")

        assertEquals("1", place.coreProcessBuilder().environment()[WSL_UTF8_VARIABLE])
    }
}
