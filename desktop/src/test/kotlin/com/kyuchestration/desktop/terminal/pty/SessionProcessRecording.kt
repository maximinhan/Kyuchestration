package com.kyuchestration.desktop.terminal.pty

import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * `claude` 자리에 놓는 기록 스텁과, 그것이 남긴 것을 읽는 자리.
 *
 * PTY 를 실제로 여는 검증이 둘이 되면서 이리로 나왔다 — 하나는 "엔진의 답을 그대로 띄우는가"
 * 를 보고(PtySessionTerminalOpenerTest), 다른 하나는 "보유한 프로세스가 언제 살고 언제 죽는가"
 * 를 본다(HeldSessionProcessLifecycleTest). 같은 스텁을 두 번 적으면 한쪽만 줄이 늘어나는 날이 온다.
 *
 * 진짜 `claude` 를 쓰지 않는다. 확인하려는 것이 "무엇이 어떤 자리에서 어떤 환경으로 떴는가" 와
 * "그 프로세스가 아직 사는가" 라, 그 답을 `claude` 에게 물을 이유가 없다 — 오히려 요구하면 이
 * 검증들이 CI 에서 돌지 못한다.
 */
internal data class SessionReport(
    val workingDirectory: String,
    val processId: Long,
    val terminalType: String,
    val appSessionMarker: String,
    val repoClaudeMd: String,
)

/** 자기가 받은 것을 한 줄씩 적어 두고, 누가 끝낼 때까지 사는 명령. */
internal fun recordAndStayAliveCommand(reportPath: Path): List<String> = listOf(
    "sh", "-c",
    """
    { pwd; echo "${'$'}${'$'}"; echo "${'$'}TERM"; echo "${'$'}KYU_APP_SESSION"; echo "${'$'}CLAUDE_CODE_ADDITIONAL_DIRECTORIES_CLAUDE_MD"; } > "$reportPath"
    while :; do sleep 1; done
    """.trimIndent(),
)

/**
 * 기록이 다 적힐 때까지 기다렸다가 읽는다.
 *
 * 고정 시간을 자지 않는다. PTY 생성과 sh 기동에 걸리는 시간을 상수로 두면 느린 기계에서 헛되이
 * 깨지고 빠른 기계에서는 그만큼 기다린다.
 */
internal fun waitForSessionReport(reportPath: Path): SessionReport {
    repeat(POLL_ATTEMPTS) {
        if (reportPath.exists()) {
            val lines = reportPath.readText().lines()
            if (lines.size >= REPORT_LINE_COUNT) {
                return SessionReport(
                    workingDirectory = lines[0],
                    processId = lines[1].toLong(),
                    terminalType = lines[2],
                    appSessionMarker = lines[3],
                    repoClaudeMd = lines[4],
                )
            }
        }
        Thread.sleep(POLL_INTERVAL_MILLIS)
    }
    error("세션이 기록을 남기지 않았습니다: $reportPath")
}

internal fun processIsAlive(processId: Long): Boolean =
    ProcessHandle.of(processId).map { it.isAlive }.orElse(false)

/** 그 프로세스가 사라질 때까지 기다린다. 끝내 남아 있으면 false. */
internal fun waitUntilProcessIsGone(processId: Long): Boolean {
    repeat(POLL_ATTEMPTS) {
        if (!processIsAlive(processId)) {
            return true
        }
        Thread.sleep(POLL_INTERVAL_MILLIS)
    }
    return false
}

/** 기다리는 자리마다 같은 촘촘함을 쓴다. 100 번 × 50ms 면 아주 느린 기계에서도 5 초가 넘지 않는다. */
private const val POLL_ATTEMPTS = 100
private const val POLL_INTERVAL_MILLIS = 50L
private const val REPORT_LINE_COUNT = 5
