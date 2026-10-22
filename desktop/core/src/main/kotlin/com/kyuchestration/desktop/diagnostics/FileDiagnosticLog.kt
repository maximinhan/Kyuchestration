package com.kyuchestration.desktop.diagnostics

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.fileSize
import kotlin.io.path.notExists

/**
 * 기록을 파일 하나에 이어 붙이고, 너무 커지면 옆 파일로 한 번 옮긴다.
 *
 * 크기 상한이 있어야 하는 이유는 이 파일을 아무도 치우지 않기 때문이다. 앱에는 기록을 지우는
 * 화면이 없고 사용자가 그 자리를 들여다볼 일도 없으므로, 상한이 없으면 몇 달 뒤 홈 디렉토리에
 * 수백 MB 짜리 파일이 조용히 놓인다.
 *
 * @param maximumFileSizeBytes 한 파일이 커질 수 있는 데까지. 이 값을 넘기려는 기록이 오면 지금
 *   파일을 옆으로 옮기고 빈 파일에서 다시 시작한다 — 잘라내지 않는 이유는 방금 일어난 일을
 *   버리지 않기 위해서다. 두 파일이니 실제로 쥐는 것은 이 값의 두 배까지다.
 * @param now 기록에 찍을 시각. 시험이 고정된 값을 넣기 위한 이음매다.
 * @param reportWriteFailure 기록을 남기지 못했다는 사실을 알리는 자리. 기본값이 stderr 인 이유는
 *   그것 말고 알릴 곳이 없어서다 — 기록이 못 써지는 상황을 기록에 남길 수는 없다.
 */
class FileDiagnosticLog(
    private val logFile: Path,
    private val maximumFileSizeBytes: Long = DEFAULT_MAXIMUM_FILE_SIZE_BYTES,
    private val now: () -> Instant = Instant::now,
    private val reportWriteFailure: (Throwable) -> Unit = ::printWriteFailureToStandardError,
) : DiagnosticLog {

    /**
     * 옮겨 둘 자리. 파일은 여기까지 둘뿐이다.
     *
     * 셋째 파일을 두지 않는 것은 이 기록이 사람 손으로 건네지는 것이기 때문이다. 파일이 늘수록
     * "어느 것을 보내야 하는가" 가 물음이 되고, 그 물음은 보고를 한 걸음 늦춘다.
     */
    private val rolledLogFile: Path = logFile.resolveSibling("${logFile.fileName}.1")

    /**
     * 쓰기 실패를 이미 알렸는가.
     *
     * 한 번만 알린다. 홈이 읽기 전용이거나 디스크가 찬 머신에서는 실패가 이어서 나는데, 부를
     * 때마다 알리면 엔진을 부를 때마다 stderr 가 같은 말로 찬다 — 그러면 진짜 봐야 할 말이 묻힌다.
     */
    private var writeFailureAlreadyReported = false

    /**
     * 기록 하나를 남긴다. 남기지 못해도 던지지 않는다.
     *
     * **삼키는 것이 여기서는 의도다.** 진단하려고 넣은 장치가 진단할 오류를 하나 더 만들면
     * 넣지 않느니만 못하다 — 세션에 들어가려던 사용자가 "기록을 남기지 못했다" 는 예외로 세션을
     * 잃어서는 안 된다. 대신 조용히 사라지지도 않게 한 번은 stderr 로 알린다.
     *
     * 잡는 것을 IOException 계열로 좁혀 둔다. 이 클래스의 결함(널 참조·형변환 실패)까지 함께
     * 삼키면 그 결함은 "기록이 안 남는다" 로만 보이고 영영 고쳐지지 않는다.
     */
    @Synchronized
    override fun record(entry: DiagnosticLogEntry) {
        val recordedText = formatEntry(entry)
        try {
            appendToLogFile(recordedText)
        } catch (writeFailure: IOException) {
            reportWriteFailureOnce(writeFailure)
        } catch (writeFailure: SecurityException) {
            // 보안 관리자나 파일 권한이 막은 자리다. IOException 이 아니라 이쪽으로 오는데,
            // 사용자가 할 수 있는 일은 같다.
            reportWriteFailureOnce(writeFailure)
        }
    }

    private fun appendToLogFile(recordedText: String) {
        val recordedBytes = recordedText.toByteArray(StandardCharsets.UTF_8)

        logFile.parent?.createDirectories()
        rollOverIfTooLargeFor(recordedBytes.size)

        Files.write(
            logFile,
            recordedBytes,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
        )
    }

    /**
     * 이번 기록까지 넣으면 상한을 넘는가 — 넘으면 지금 파일을 옆으로 옮긴다.
     *
     * 옮기기 전에 확인하는 것이 "이미 넘었는가" 가 아니라 "넣으면 넘는가" 인 이유는 상한이
     * 지켜지는 값이어야 하기 때문이다. 넣고 나서 재면 파일은 늘 상한을 조금씩 넘긴 채로 있다.
     *
     * 옮기는 것이지 지우는 것이 아니다. 앱이 죽기 직전에 남긴 줄이 마침 상한을 넘긴 그 줄일 수
     * 있는데, 그때 앞의 것을 버리면 진단에 가장 필요한 대목이 사라진다.
     */
    private fun rollOverIfTooLargeFor(incomingByteCount: Int) {
        if (logFile.notExists()) {
            return
        }
        if (logFile.fileSize() + incomingByteCount <= maximumFileSizeBytes) {
            return
        }
        Files.move(logFile, rolledLogFile, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun reportWriteFailureOnce(writeFailure: Throwable) {
        if (writeFailureAlreadyReported) {
            return
        }
        writeFailureAlreadyReported = true
        reportWriteFailure(writeFailure)
    }

    /**
     * 한 사건이 파일에서 차지하는 모양.
     *
     * 본문을 들여쓰는 것이 형식의 전부다. kyu 의 stderr 도 예외의 스택도 여러 줄로 오는데,
     * 들여쓰지 않으면 그 줄들이 다음 사건처럼 보여서 파일을 읽는 사람이 경계를 가리지 못한다.
     */
    private fun formatEntry(entry: DiagnosticLogEntry): String = buildString {
        append(now())
        append("  ")
        append(entry.summary)
        append('\n')

        entry.detail?.lineSequence()?.forEach { detailLine ->
            append(DETAIL_INDENT)
            append(detailLine)
            append('\n')
        }
    }

    private companion object {

        /**
         * 파일 하나가 커질 수 있는 데까지 — 5MB.
         *
         * 이 앱이 남기는 것은 사건마다 몇 줄이라, 5MB 면 오래 쓴 머신에서도 몇 달 치가 들어간다.
         * 반대로 사람이 열어 볼 수 없을 만큼 크지도 않다.
         */
        const val DEFAULT_MAXIMUM_FILE_SIZE_BYTES: Long = 5L * 1024 * 1024

        const val DETAIL_INDENT = "    "
    }
}

private fun printWriteFailureToStandardError(writeFailure: Throwable) {
    System.err.println("진단 기록을 남기지 못했습니다 — 앱은 그대로 돕니다: $writeFailure")
}
