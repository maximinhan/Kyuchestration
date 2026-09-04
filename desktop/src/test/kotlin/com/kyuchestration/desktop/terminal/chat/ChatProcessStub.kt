package com.kyuchestration.desktop.terminal.chat

import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.writeText

/**
 * `claude` 자리에 놓는 스텁과, 그것이 남긴 것을 읽는 자리.
 *
 * **진짜 `claude` 를 쓰지 않는다.** 여기서 갈리는 것은 "파이프에 오는 것을 어떻게 다루는가" 이고,
 * 그 답에 모델도 토큰도 필요하지 않다 — 요구하면 이 검증들이 CI 에서 돌지 못한다. 스텁이 뱉는
 * 줄은 실제 `claude` 가 낸 것이다([RecordedChatStreamLines]).
 *
 * 줄을 셸 스크립트 안에 적지 않고 파일에 적어 `cat` 으로 흘린다. JSON 에는 따옴표와 역슬래시가
 * 가득해서, 스크립트에 그대로 넣으면 시험이 확인하는 것이 파이프 처리가 아니라 셸 이스케이프가 된다.
 */
internal fun emitLinesCommand(
    stdoutLinesFile: Path,
    stderrLinesFile: Path? = null,
    exitCode: Int = 0,
    doneMarkerFile: Path? = null,
): List<String> {
    val script = buildString {
        append("cat '$stdoutLinesFile'; ")
        stderrLinesFile?.let { append("cat '$it' >&2; ") }
        // 다 뱉었다는 표식은 stdout 을 다 흘려보낸 뒤에 남는다. 읽는 쪽이 파이프를 비우지 않으면
        // 자식은 그 전에 쓰기에서 멎으므로, 이 파일이 생겼다는 것이 곧 파이프가 비워졌다는 뜻이다.
        doneMarkerFile?.let { append("echo done > '$it'; ") }
        append("exit $exitCode")
    }
    return listOf("sh", "-c", script)
}

/**
 * 받은 것을 그대로 적어 두고, stdin 이 닫힐 때까지 사는 명령.
 *
 * `cat` 이 읽는 즉시 쓰므로 앱이 한 줄을 흘려보내면 곧바로 파일에 나타난다. 그 줄의 모양이
 * `claude` 가 받는 모양인지가 이 스텁이 답하는 물음이다.
 */
internal fun recordStandardInputCommand(receivedLinesFile: Path, ownProcessIdFile: Path? = null): List<String> {
    val script = buildString {
        ownProcessIdFile?.let { append("echo ${'$'}${'$'} > '$it'; ") }
        append("cat >> '$receivedLinesFile'")
    }
    return listOf("sh", "-c", script)
}

/** 스텁이 흘릴 줄들을 파일 하나로 적어 둔다. */
internal fun linesFile(path: Path, lines: List<String>): Path = path.apply {
    writeText(lines.joinToString(separator = "\n", postfix = "\n"))
}

/**
 * 그 파일이 생길 때까지 기다린다. 끝내 없으면 false.
 *
 * 고정 시간을 자지 않는다. 프로세스 기동에 걸리는 시간을 상수로 두면 느린 기계에서 헛되이
 * 깨지고 빠른 기계에서는 그만큼 기다린다 — PTY 검증이 이미 지키는 규율이다(SessionProcessRecording).
 */
internal fun waitUntilFileAppears(path: Path): Boolean {
    repeat(POLL_ATTEMPTS) {
        if (path.exists()) {
            return true
        }
        Thread.sleep(POLL_INTERVAL_MILLIS)
    }
    return false
}

/** 그 조건이 될 때까지 기다린다. 끝내 안 되면 false. */
internal fun waitUntil(condition: () -> Boolean): Boolean {
    repeat(POLL_ATTEMPTS) {
        if (condition()) {
            return true
        }
        Thread.sleep(POLL_INTERVAL_MILLIS)
    }
    return false
}

private const val POLL_ATTEMPTS = 200
private const val POLL_INTERVAL_MILLIS = 50L
