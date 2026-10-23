package com.kyuchestration.desktop.claudeauth.process

import java.io.File
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * `claude` 라는 이름을 바탕 환경의 PATH 에서 찾는다 — 세션을 띄우는 자리와 같은 PATH 다.
 *
 * `wsl.exe -e` 로 뜬 코어의 JVM PATH 에는 `~/.local/bin` 이 없다(설계 3.7). 바탕 환경(childProcessEnvironment)은 로그인 셸과 잘
 * 알려진 자리로 그것을 채우지만, ProcessBuilder 는 이름을 이 JVM 의 PATH 로 찾는다(3 단계 실측). 그래서 WSL 의 코어가 "claude 를
 * 실행하지 못했습니다" 로 멈췄다.
 */
class ProcessClaudeCliAuthenticationTest {

    private val temporaryDirectory = createTempDirectory("kyu-claude-auth-path")

    @AfterTest
    fun cleanUp() {
        temporaryDirectory.toFile().deleteRecursively()
    }

    @Test
    fun `claude 는 바탕 환경의 PATH 에 있는 것을 부른다`(): Unit = runBlocking {
        val binDirectory = temporaryDirectory.resolve("bin-only-in-base-environment").createDirectories()
        val calledMarker = temporaryDirectory.resolve("불렸다")
        binDirectory.resolve("claude").apply {
            writeText("#!/bin/sh\ntouch '$calledMarker'\necho '{\"loggedIn\":true}'\n")
            toFile().setExecutable(true)
        }
        val authentication = ProcessClaudeCliAuthentication(
            baseEnvironment = mapOf("PATH" to "$binDirectory${File.pathSeparator}/usr/bin:/bin"),
        )

        authentication.credentialsArePresent(tokenToInject = null)

        assertTrue(calledMarker.exists(), "바탕 환경의 PATH 에 둔 claude 가 아니라 다른 claude 를 불렀거나 아무것도 부르지 못했다")
    }
}
