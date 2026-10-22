package com.kyuchestration.desktop.wsl

import com.kyuchestration.desktop.diagnostics.DiagnosticLog
import com.kyuchestration.desktop.diagnostics.DiagnosticLogEntry
import com.kyuchestration.desktop.diagnostics.RecordingDiagnosticLog
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

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
            val chosenDistribution = ChosenWslDistributionFile(settingsDirectory.resolve("Kyuchestration").resolve("wsl-distribution"), DiagnosticLog.Discarding)
            assertEquals(null, chosenDistribution.read())

            chosenDistribution.write("Ubuntu-24.04")

            assertEquals("Ubuntu-24.04", chosenDistribution.read())
        } finally {
            settingsDirectory.toFile().deleteRecursively()
        }
    }

    /**
     * 기억 파일이 있어도 못 읽을 수 있다(잠김 · 권한 · 로밍 동기화 중). 그때 던지면 창 하나 없이 앱이 죽는다 — 기억이 없는 것으로 보고
     * 다시 고르게 하고, 까닭은 진단 기록에 남긴다.
     */
    @Test
    fun `기억 파일을 읽지 못하면 기억이 없는 것으로 보고 까닭을 기록한다`() {
        val settingsDirectory = createTempDirectory("kyu-screen-settings")
        try {
            // 디렉토리를 파일로 읽으면 IOException 이 난다 — 읽기 실패를 세우는 가장 이식성 있는 길이다.
            val unreadable = settingsDirectory.resolve("wsl-distribution").createDirectories()
            val diagnosticLog = RecordingDiagnosticLog()

            assertEquals(null, ChosenWslDistributionFile(unreadable, diagnosticLog).read())
            assertIs<DiagnosticLogEntry.ChosenWslDistributionUnreadable>(diagnosticLog.recordedEntries.single())
        } finally {
            settingsDirectory.toFile().deleteRecursively()
        }
    }
}
