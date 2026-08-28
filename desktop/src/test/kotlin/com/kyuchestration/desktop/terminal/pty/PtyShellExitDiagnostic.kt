package com.kyuchestration.desktop.terminal.pty

import com.kyuchestration.desktop.terminal.SessionTarget
import com.pty4j.PtyProcessBuilder
import java.util.concurrent.TimeUnit
import kotlin.test.Test

/**
 * 임시 진단 — 원인 확정용 대조 실험. 확인되면 지운다(PR 28).
 *
 * 앞선 회차에서 열 가지 조건을 재 본 결과 두 가지가 갈렸다: 로케일과 글자의 종류.
 * 이번에는 한 번에 한 변수만 바꿔 그 둘이 정말 원인인지 못 박는다.
 *
 * 기대: A 는 깨지고(맥), B 와 C 는 성한다. 리눅스는 셋 다 성한다.
 */
class PtyShellExitDiagnostic {

    @Test
    fun `로케일과 글자 종류를 한 번에 하나씩 바꿔 본다`() {
        println("PTY-DIAG === 시작 === os.name=${System.getProperty("os.name")}")

        probe("A-로케일없음-한글", environmentWithoutLocale = true, input = "echo 뀨케스트레이션-확인; exit\n")
        probe("B-로케일있음-한글", environmentWithoutLocale = false, input = "echo 뀨케스트레이션-확인; exit\n")
        probe("C-로케일없음-ASCII", environmentWithoutLocale = true, input = "echo PLAIN-OK; exit\n")

        println("PTY-DIAG === 끝 ===")
    }

    private fun probe(label: String, environmentWithoutLocale: Boolean, input: String) {
        val environment = mutableMapOf("TERM" to "xterm-256color", "PATH" to System.getenv("PATH"))
        if (!environmentWithoutLocale) {
            environment["LANG"] = "en_US.UTF-8"
        }

        val ptyProcess = PtyProcessBuilder(arrayOf("sh"))
            .setEnvironment(environment)
            .setInitialColumns(80)
            .setInitialRows(24)
            .start()
        val connector = KyuAttachTtyConnector(ptyProcess, SessionTarget.Repo("proj-a"))

        connector.write(input)

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

        val exited = ptyProcess.waitFor(10, TimeUnit.SECONDS)
        val exitCode = if (exited) ptyProcess.exitValue() else null
        if (!exited) {
            ptyProcess.destroyForcibly()
        }

        val output = synchronized(collected) { collected.toString() }
        println("PTY-DIAG [$label] exitCode=$exitCode exited=$exited")
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
