package com.kyuchestration.desktop.wsl

import com.kyuchestration.desktop.diagnostics.DiagnosticLog
import com.kyuchestration.desktop.diagnostics.DiagnosticLogEntry
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * 윈도우 화면이 지난번에 고른 WSL 배포판(core-ui-split-design.md 6.17). 윈도우 쪽의 사실이고 코어를 찾기 전의 일이라 화면이
 * 기억한다 — 코어에게 물을 수 없다. 코어가 어느 배포판에 있는지가 바로 이것이기 때문이다.
 */
class ChosenWslDistributionFile(private val file: Path, private val diagnosticLog: DiagnosticLog) {

    /**
     * 기억한 배포판. 없거나 읽지 못하면 null.
     *
     * 파일이 있어도 못 읽을 수 있다(다른 프로세스가 잠금 · 권한 · 로밍 동기화 중). 그때 던지면 창 하나 없이 앱이 죽는다. 기억이 없는
     * 것으로 보면 사람이 다시 고르는 것으로 끝나고, 까닭은 진단 기록에 남는다.
     */
    fun read(): String? {
        if (!file.exists()) {
            return null
        }
        return try {
            file.readText().trim().ifEmpty { null }
        } catch (unreadable: IOException) {
            diagnosticLog.record(DiagnosticLogEntry.ChosenWslDistributionUnreadable(file.toString(), unreadable.toString()))
            null
        }
    }

    fun write(distributionName: String) {
        Files.createDirectories(file.parent)
        file.writeText(distributionName)
    }
}

/**
 * 고른 배포판을 적는 파일. 로밍되는 APPDATA 에 둔다 — 사람의 선택이라 같은 사람이 다른 윈도우에 들어가도 따라가는 편이 맞다.
 * 기록(LOCALAPPDATA, DiagnosticLogLocation)과 반대인 것은 기록이 그 머신에서 난 일이라서다.
 */
fun chosenWslDistributionFilePath(lookUpEnvironmentVariable: (String) -> String? = System::getenv): Path {
    val applicationData = checkNotNull(lookUpEnvironmentVariable(APPLICATION_DATA_VARIABLE)?.takeIf { it.isNotBlank() }) {
        "윈도우가 언제나 채워 두는 $APPLICATION_DATA_VARIABLE 가 비어 있습니다"
    }
    return Path.of(applicationData).resolve("Kyuchestration").resolve("wsl-distribution")
}

private const val APPLICATION_DATA_VARIABLE = "APPDATA"
