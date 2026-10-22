package com.kyuchestration.desktop.diagnostics

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 파일에 남기는 기록.
 *
 * 이 시험이 지키는 것은 셋이다 — 남는가, 무한히 자라지 않는가, 그리고 **기록하다 실패해도 앱이
 * 멈추지 않는가**. 마지막 것이 특히 중요한 이유는 이 기능이 존재하는 까닭 자체다: 오류를
 * 진단하려고 넣은 장치가 오류를 하나 더 만들면 넣지 않느니만 못하다.
 */
class FileDiagnosticLogTest {

    private val temporaryDirectory: Path = Files.createTempDirectory("kyu-desktop-diagnostic-log")

    @AfterTest
    fun removeTemporaryDirectory() {
        temporaryDirectory.toFile().deleteRecursively()
    }

    @Test
    fun `기록은 아직 없는 디렉토리에도 남는다`() {
        val logFile = temporaryDirectory.resolve("아직/없는/자리/desktop.log")
        val log = FileDiagnosticLog(logFile, now = { Instant.parse("2026-09-01T10:20:30Z") })

        log.record(DiagnosticLogEntry.ApplicationStarted("0.10.0", "Linux", "21.0.12"))

        val recorded = logFile.readText()
        assertContains(recorded, "2026-09-01T10:20:30Z")
        assertContains(recorded, "0.10.0")
        assertContains(recorded, "Linux")
    }

    @Test
    fun `여러 번 기록하면 뒤에 이어 붙는다`() {
        val logFile = temporaryDirectory.resolve("desktop.log")
        val log = FileDiagnosticLog(logFile, now = { Instant.parse("2026-09-01T10:20:30Z") })

        log.record(DiagnosticLogEntry.SessionStarted("main", resumingConversation = false))
        log.record(DiagnosticLogEntry.SessionStarted("Kyuchestration", resumingConversation = true))

        val recorded = logFile.readText()
        assertContains(recorded, "main")
        assertContains(recorded, "Kyuchestration")
    }

    /**
     * 여러 줄짜리 본문은 들여쓴다.
     *
     * kyu 의 stderr 는 여러 줄로 온다. 들여쓰지 않으면 그 줄들이 다음 기록처럼 보여서, 파일을
     * 읽는 사람이 어디까지가 한 사건인지 가리지 못한다.
     */
    @Test
    fun `본문이 여러 줄이어도 한 사건으로 읽힌다`() {
        val logFile = temporaryDirectory.resolve("desktop.log")
        val log = FileDiagnosticLog(logFile, now = { Instant.parse("2026-09-01T10:20:30Z") })

        log.record(
            DiagnosticLogEntry.EngineCallFailed(
                arguments = listOf("auth", "remove", "개인"),
                exitCode = 1,
                standardError = "첫 줄\n둘째 줄",
            ),
        )

        val recorded = logFile.readText()
        assertContains(recorded, "\n    첫 줄")
        assertContains(recorded, "\n    둘째 줄")
    }

    @Test
    fun `상한을 넘으면 앞의 기록을 옆 파일로 옮기고 새로 시작한다`() {
        val logFile = temporaryDirectory.resolve("desktop.log")
        val rolledFile = temporaryDirectory.resolve("desktop.log.1")
        val log = FileDiagnosticLog(
            logFile = logFile,
            maximumFileSizeBytes = 200,
            now = { Instant.parse("2026-09-01T10:20:30Z") },
        )

        repeat(10) { index ->
            log.record(DiagnosticLogEntry.SessionStarted("레포$index", resumingConversation = false))
        }

        assertTrue(rolledFile.exists(), "상한을 넘었는데 옮겨 둔 파일이 없다")
        assertTrue(
            logFile.fileSize() <= 200,
            "새로 시작한 파일이 이미 상한을 넘었다: ${logFile.fileSize()}",
        )
    }

    /**
     * 파일은 둘까지다.
     *
     * 셋째 파일을 두지 않는 이유는 이 기록이 사람에게 건네지는 것이기 때문이다. 파일이 늘수록
     * "어느 것을 보내야 하는가" 가 물음이 되고, 그 물음은 보고를 한 걸음 늦춘다.
     */
    @Test
    fun `옮겨 둘 자리는 하나뿐이라 그 앞의 것은 사라진다`() {
        val logFile = temporaryDirectory.resolve("desktop.log")
        val rolledFile = temporaryDirectory.resolve("desktop.log.1")
        val log = FileDiagnosticLog(
            logFile = logFile,
            maximumFileSizeBytes = 120,
            now = { Instant.parse("2026-09-01T10:20:30Z") },
        )

        repeat(30) { index ->
            log.record(DiagnosticLogEntry.SessionStarted("레포$index", resumingConversation = false))
        }

        assertFalse(
            temporaryDirectory.resolve("desktop.log.2").exists(),
            "셋째 파일이 생겼다 — 옮겨 두는 자리는 하나여야 한다",
        )
        assertTrue(rolledFile.fileSize() > 0, "옮겨 둔 파일이 비어 있다")
    }

    /**
     * 기록하지 못하는 자리에서도 앱은 그대로 돈다.
     *
     * 부모가 파일이라 디렉토리를 만들 수 없는 자리를 준다. 홈이 읽기 전용이거나 디스크가 찬
     * 머신에서 실제로 일어나는 일과 같은 모양이다.
     */
    @Test
    fun `기록하지 못해도 예외를 밖으로 내보내지 않는다`() {
        val fileWhereDirectoryShouldBe = temporaryDirectory.resolve("막힌자리")
        fileWhereDirectoryShouldBe.writeText("이것은 디렉토리가 아니다")
        val logFile = fileWhereDirectoryShouldBe.resolve("desktop.log")

        val reportedFailures = mutableListOf<Throwable>()
        val log = FileDiagnosticLog(
            logFile = logFile,
            now = { Instant.parse("2026-09-01T10:20:30Z") },
            reportWriteFailure = { reportedFailures += it },
        )

        // 던지면 이 자리에서 시험이 깨진다. 부르는 쪽은 기록을 남기려다 자기 흐름을 잃어서는 안 된다.
        log.record(DiagnosticLogEntry.ApplicationStarted("0.10.0", "Linux", "21.0.12"))
        log.record(DiagnosticLogEntry.SessionStarted("main", resumingConversation = false))
        log.record(DiagnosticLogEntry.SessionStarted("main", resumingConversation = false))

        assertEquals(
            1,
            reportedFailures.size,
            "쓰기 실패는 한 번만 알린다 — 부를 때마다 알리면 엔진을 부를 때마다 stderr 가 같은 말로 찬다",
        )
    }

    /**
     * 실패 기록에 비밀이 실릴 자리가 없다.
     *
     * 토큰은 인자가 아니라 stdin 으로 간다(KyuCommandRunner). 기록 타입이 stdin 을 받지 않으므로
     * 이 시험은 사실 컴파일 시점에 이미 지켜지지만, 그 결정을 시험으로 적어 두면 뒷날 누가
     * "실패를 더 자세히 남기자" 며 stdin 을 얹을 때 여기가 먼저 붉어진다.
     */
    @Test
    fun `엔진 호출 실패 기록은 인자와 stderr 만 싣는다`() {
        val logFile = temporaryDirectory.resolve("desktop.log")
        val log = FileDiagnosticLog(logFile, now = { Instant.parse("2026-09-01T10:20:30Z") })

        log.record(
            DiagnosticLogEntry.EngineCallFailed(
                arguments = listOf("auth", "add", "개인", "--json"),
                exitCode = 1,
                standardError = "토큰이 거절당했습니다.",
            ),
        )

        val recorded = logFile.readText()
        assertContains(recorded, "auth add 개인 --json")
        assertContains(recorded, "토큰이 거절당했습니다.")
    }

    @Test
    fun `미처리 예외는 스택까지 남는다`() {
        val logFile = temporaryDirectory.resolve("desktop.log")
        val log = FileDiagnosticLog(logFile, now = { Instant.parse("2026-09-01T10:20:30Z") })

        log.record(
            DiagnosticLogEntry.UnhandledFailure(
                threadName = "AWT-EventQueue-0",
                failure = IllegalStateException("화면을 그리다 죽었다"),
            ),
        )

        val recorded = logFile.readText()
        assertContains(recorded, "AWT-EventQueue-0")
        assertContains(recorded, "화면을 그리다 죽었다")
        assertContains(recorded, "IllegalStateException")
        // 스택이 없으면 어느 줄에서 죽었는지 알 수 없어 기록이 "죽었다" 한 줄로 끝난다.
        assertContains(recorded, "at ")
    }

    @Test
    fun `이미 있던 기록 파일을 지우지 않는다`() {
        val logFile = temporaryDirectory.resolve("desktop.log")
        logFile.parent.createDirectories()
        logFile.writeText("앞선 실행이 남긴 것\n")
        val log = FileDiagnosticLog(logFile, now = { Instant.parse("2026-09-01T10:20:30Z") })

        log.record(DiagnosticLogEntry.ApplicationStarted("0.10.0", "Linux", "21.0.12"))

        assertContains(logFile.readText(), "앞선 실행이 남긴 것")
    }
}
