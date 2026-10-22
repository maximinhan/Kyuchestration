package com.kyuchestration.desktop.wsl

import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

/** 윈도우 화면이 쓰는 자리 — `wsl.exe` 와, 고른 배포판을 기억하는 파일. */
class WindowsScreenPlacesTest {

    /** 윈도우가 C: 가 아닌 드라이브에 깔려 있어도 맞게 SystemRoot 를 따른다. */
    @Test
    fun `wsl 실행 파일은 SystemRoot 의 System32 에 있다`() {
        assertEquals(
            Path.of("D:\\Windows").resolve("System32").resolve("wsl.exe"),
            wslExecutablePath(lookUpEnvironmentVariable = { if (it == "SystemRoot") "D:\\Windows" else null }),
        )
    }

    /** 고른 배포판은 윈도우 쪽의 사실이고 코어를 찾기 전의 일이라 화면이 기억한다(6.17). 사람의 선택이라 로밍되는 APPDATA 에 둔다. */
    @Test
    fun `고른 배포판은 APPDATA 아래 앱 자리의 파일에 둔다`() {
        assertEquals(
            Path.of("C:\\Users\\maxim\\AppData\\Roaming").resolve("Kyuchestration").resolve("wsl-distribution"),
            chosenWslDistributionFilePath(lookUpEnvironmentVariable = {
                if (it == "APPDATA") "C:\\Users\\maxim\\AppData\\Roaming" else null
            }),
        )
    }

    @Test
    fun `고른 배포판을 적어 두면 다음에 그대로 읽고 적은 적이 없으면 없다`() {
        val settingsDirectory = createTempDirectory("kyu-screen-settings")
        try {
            val chosenDistribution = ChosenWslDistributionFile(settingsDirectory.resolve("Kyuchestration").resolve("wsl-distribution"))
            assertEquals(null, chosenDistribution.read())

            chosenDistribution.write("Ubuntu-24.04")

            assertEquals("Ubuntu-24.04", chosenDistribution.read())
        } finally {
            settingsDirectory.toFile().deleteRecursively()
        }
    }
}
