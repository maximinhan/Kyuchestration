package com.kyuchestration.desktop.mirror

import java.io.IOException
import kotlin.concurrent.thread
import kotlin.time.Duration
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 화면이 자식으로 띄운 코어 프로세스와 그 stdio 의 틀 줄 두 가닥(core-ui-split-design.md 6.12 · 6.13 의 2~4 단계).
 *
 * 같은 머신이어도 화면은 언제나 이 길로 코어에 닿는다(원칙 20). 거울(CoreMirror)은 이 두 가닥 위에 선다 — 그 위에서는 코어가 어디서
 * 도는지 모른다.
 *
 * 줄을 나르는 것은 각자의 스레드다. stdout 을 읽고 stdin 에 쓰는 것은 막히는 일이고, 창을 닫는 자리가 화면 스레드를 붙잡고 코어가
 * 끝나기를 기다리므로 그 둘이 화면 스레드에 있으면 안 된다.
 */
class CoreChildProcess private constructor(
    private val process: Process,
    /** 코어가 쓴 틀 줄. stdout 이 닫히면(코어가 끝났거나 죽었다) 이 줄도 닫힌다. */
    val framesFromCore: ReceiveChannel<String>,
    /** 코어에 보낼 틀 줄. 닫으면 코어의 stdin 이 닫힌다 — 코어는 그것을 화면이 떠났다는 말로 듣는다. */
    val framesToCore: SendChannel<String>,
) {

    val pid: Long get() = process.pid()

    /** 코어 프로세스가 끝나면 그 종료 코드로 [action] 을 부른다. 화면이 떠나서든 코어가 뜻밖에 끝나서든. */
    fun whenExited(action: (exitCode: Int) -> Unit) {
        process.onExit().thenAccept { action(it.exitValue()) }
    }

    /**
     * 화면이 떠난다. 코어의 stdin 을 닫고, 코어가 모든 세션을 끝내고 나가기를 [timeout] 까지 기다린다. 그 안에 나가지 않으면 강제로
     * 끝낸다 — 그때는 끝내지 못한 세션의 claude 가 남을 수 있어 화면이 그 사실을 기록한다.
     *
     * 끝내는 것을 명령으로 시키지 않는다. 크래시한 화면은 명령을 보낼 수 없지만 stdin 의 끝은 언제나 간다 — 닫기와 크래시가 같은 길을
     * 가야 코어가 화면의 작별 인사에 기대지 않는다(원칙 21).
     */
    suspend fun leave(mirror: CoreMirror, timeout: Duration): Exit {
        val exitCode = withTimeoutOrNull(timeout) {
            mirror.endAllSessions()
            process.onExit().await().exitValue()
        }
        if (exitCode != null) {
            return Exit.EndedByItself(exitCode)
        }
        process.destroyForcibly()
        return Exit.KilledAfterTimeout
    }

    sealed interface Exit {
        data class EndedByItself(val exitCode: Int) : Exit

        data object KilledAfterTimeout : Exit
    }

    companion object {

        /**
         * [command] 를 셸 없이 띄운다(설계 6.12 — 셸은 바이트와 인용을 고친다). stderr 는 화면의 stderr 로 그대로 흘린다 — 코어의
         * 진단은 코어의 기록 파일에 있고, stderr 는 터미널에서 화면을 띄운 사람이 보는 자리다.
         *
         * @param configureProcess 환경을 다듬는 자리. 시험이 코어의 기록 자리와 엔진을 격리한다.
         */
        fun start(command: List<String>, configureProcess: ProcessBuilder.() -> Unit = {}): CoreChildProcess {
            val process = ProcessBuilder(command)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .apply(configureProcess)
                .start()

            val framesFromCore = Channel<String>(Channel.UNLIMITED)
            thread(name = "core-frames-in", isDaemon = true) {
                val readFailure = try {
                    process.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                        lines.forEach { framesFromCore.trySend(it) }
                    }
                    null
                } catch (brokenPipe: IOException) {
                    brokenPipe
                }
                framesFromCore.close(readFailure)
            }

            val framesToCore = Channel<String>(Channel.UNLIMITED)
            thread(name = "core-frames-out", isDaemon = true) {
                val toCore = process.outputStream.bufferedWriter(Charsets.UTF_8)
                try {
                    runBlocking {
                        for (line in framesToCore) {
                            toCore.write(line)
                            toCore.newLine()
                            toCore.flush()
                        }
                    }
                    toCore.close()
                } catch (coreAlreadyGone: IOException) {
                    // 코어가 이미 끝나 stdin 이 닫혔다. 그 사실은 stdout 의 끝으로 거울에 이미 닿았고, 보내려던 틀은 받을 쪽이 없다.
                    framesToCore.cancel()
                }
            }

            return CoreChildProcess(process, framesFromCore, framesToCore)
        }
    }
}
