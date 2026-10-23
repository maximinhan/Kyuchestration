package com.kyuchestration.desktop.core

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS

/**
 * WSL 에 설치되는 코어 이미지의 실행 파일(core-ui-split-design.md 6.16 의 3 단계 정정).
 *
 * 윈도우 화면은 `wsl.exe --cd ~ -e .local/bin/kyuchestration-core` 로 띄운다(6.12). 그 자리는 설치 스크립트가 걸어 둔 심볼릭 링크이고,
 * 실행 파일은 링크를 풀어 자기 이미지 안의 런타임과 jar 를 찾아야 한다 — 링크가 놓인 `~/.local/bin` 기준으로 찾으면 아무것도 없다.
 */
@DisabledOnOs(OS.WINDOWS, disabledReason = "코어 이미지는 리눅스 전용이다 — 실행 파일이 sh 스크립트다")
class CoreImageLauncherTest {

    private val scratchDirectory: Path = createTempDirectory("kyu-core-image")

    @AfterTest
    fun cleanUp() {
        scratchDirectory.toFile().deleteRecursively()
    }

    @Test
    fun `심볼릭 링크로 불려도 이미지 안의 런타임으로 이미지의 jar 를 싣고 코어 main 에 인자를 그대로 넘긴다`() {
        val imageRoot = scratchDirectory.resolve("share/kyuchestration/core").createDirectories()
        val launcher = imageRoot.resolve("bin").createDirectories().resolve("kyuchestration-core")
        Files.copy(Path.of(LAUNCHER_SOURCE), launcher, StandardCopyOption.COPY_ATTRIBUTES)
        imageRoot.resolve("lib").createDirectories()
        imageRoot.resolve("runtime/bin").createDirectories().resolve("java").apply {
            writeText("#!/bin/sh\nprintf '%s\\n' \"\$0\" \"\$@\"\n")
            toFile().setExecutable(true)
        }
        val linkOnSearchPath = scratchDirectory.resolve("bin").createDirectories().resolve("kyuchestration-core")
        Files.createSymbolicLink(linkOnSearchPath, launcher)

        val process = ProcessBuilder(linkOnSearchPath.toString(), "serve", "--stdio")
            .directory(scratchDirectory.toFile())
            .redirectErrorStream(true)
            .start()
        val printed = process.inputStream.bufferedReader().readLines()

        assertEquals(0, process.waitFor())
        val realImageRoot = imageRoot.toRealPath()
        assertEquals(
            listOf(
                "$realImageRoot/runtime/bin/java",
                "-cp",
                "$realImageRoot/lib/*",
                "com.kyuchestration.desktop.core.CoreMainKt",
                "serve",
                "--stdio",
            ),
            printed,
        )
    }

    private companion object {
        /** 시험은 모듈 디렉토리에서 돈다. */
        const val LAUNCHER_SOURCE = "src/coreImage/bin/kyuchestration-core"
    }
}
