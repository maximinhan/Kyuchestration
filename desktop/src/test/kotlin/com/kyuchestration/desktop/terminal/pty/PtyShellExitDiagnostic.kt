package com.kyuchestration.desktop.terminal.pty

import com.kyuchestration.desktop.terminal.SessionTarget
import com.pty4j.PtyProcessBuilder
import java.util.concurrent.TimeUnit
import kotlin.test.Test

/**
 * 임시 진단 — PTY 안의 sh 가 어떤 종료 코드로 끝나는지를 OS 별로 실측한다.
 *
 * 원인을 확정하면 지운다. PR 28 의 진단용이다.
 */
class PtyShellExitDiagnostic {

    @Test
    fun `PTY 안의 sh 종료를 여러 조건으로 실측한다`() {
        println("PTY-DIAG === 시작 ===")
        println("PTY-DIAG os.name=${System.getProperty("os.name")} os.arch=${System.getProperty("os.arch")}")

        probe("1-현재시험그대로", arrayOf("sh"), minimalEnvironment, "echo 뀨케스트레이션-확인; exit\n")
        probe("2-부모환경전체", arrayOf("sh"), System.getenv(), "echo 뀨케스트레이션-확인; exit\n")
        probe("3-exit0명시", arrayOf("sh"), minimalEnvironment, "echo 뀨케스트레이션-확인; exit 0\n")
        probe(
            "4-쓰기전500ms대기",
            arrayOf("sh"),
            minimalEnvironment,
            "echo 뀨케스트레이션-확인; exit\n",
            delayBeforeWriteMillis = 500,
        )
        probe("5-셸정보", arrayOf("sh"), minimalEnvironment, "echo FLAGS=[\$-]; echo ZERO=[\$0]; echo BASHV=[\$BASH_VERSION]; exit\n")
        probe("6-비대화형-c", arrayOf("sh", "-c", "echo 뀨케스트레이션-확인"), minimalEnvironment, null)
        probe("7-ASCII만", arrayOf("sh"), minimalEnvironment, "echo PLAIN-OK; exit\n")
        probe("8-stty시험과동일", arrayOf("sh"), minimalEnvironment, "stty size; exit\n")
        probe("9-exit만", arrayOf("sh"), minimalEnvironment, "exit\n")
        probe(
            "10-sh정체",
            arrayOf("sh", "-c", "command -v sh; ls -l /bin/sh; echo BASHV=[\$BASH_VERSION]"),
            minimalEnvironment,
            null,
        )

        println("PTY-DIAG === 끝 ===")
    }

    private val minimalEnvironment: Map<String, String>
        get() = mapOf("TERM" to "xterm-256color", "PATH" to System.getenv("PATH"))

    private fun probe(
        label: String,
        command: Array<String>,
        environment: Map<String, String>,
        input: String?,
        delayBeforeWriteMillis: Long = 0,
    ) {
        val ptyProcess = PtyProcessBuilder(command)
            .setEnvironment(environment)
            .setInitialColumns(80)
            .setInitialRows(24)
            .start()
        val connector = KyuAttachTtyConnector(ptyProcess, SessionTarget.Repo("proj-a"))

        if (input != null) {
            if (delayBeforeWriteMillis > 0) {
                Thread.sleep(delayBeforeWriteMillis)
            }
            connector.write(input)
        }

        val collected = StringBuilder()
        val readerThread = Thread {
            val buffer = CharArray(1024)
            while (true) {
                val readCount = try {
                    connector.read(buffer, 0, buffer.size)
                } catch (failure: java.io.IOException) {
                    synchronized(collected) { collected.append("<<IOException: ${failure.message}>>") }
                    break
                }
                if (readCount < 0) {
                    synchronized(collected) { collected.append("<<EOF>>") }
                    break
                }
                synchronized(collected) { collected.appendRange(buffer, 0, readCount) }
            }
        }
        readerThread.isDaemon = true
        readerThread.start()
        readerThread.join(10_000)
        val readerStillRunning = readerThread.isAlive

        val exited = ptyProcess.waitFor(10, TimeUnit.SECONDS)
        val exitCode = if (exited) ptyProcess.exitValue() else null
        if (!exited) {
            ptyProcess.destroyForcibly()
        }

        val output = synchronized(collected) { collected.toString() }
        println("PTY-DIAG [$label] exitCode=$exitCode exited=$exited readerStuck=$readerStillRunning")
        println("PTY-DIAG [$label] output=${output.escapeForLog()}")
    }

    private fun String.escapeForLog(): String = buildString {
        append('"')
        for (character in this@escapeForLog) {
            when {
                character == '\n' -> append("\\n")
                character == '\r' -> append("\\r")
                character.code < 0x20 || character.code == 0x7f -> append("\\x%02x".format(character.code))
                else -> append(character)
            }
        }
        append('"')
    }
}
