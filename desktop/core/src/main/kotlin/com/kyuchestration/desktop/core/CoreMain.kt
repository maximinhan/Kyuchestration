package com.kyuchestration.desktop.core

import com.kyuchestration.desktop.diagnostics.BuildVersion
import com.kyuchestration.desktop.diagnostics.DiagnosticLogEntry
import com.kyuchestration.desktop.diagnostics.DiagnosticLogOwner
import com.kyuchestration.desktop.diagnostics.FileDiagnosticLog
import com.kyuchestration.desktop.diagnostics.diagnosticLogFile
import com.kyuchestration.desktop.diagnostics.recordUncaughtFailuresIn
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.IOException
import java.io.PrintStream
import kotlin.concurrent.thread
import kotlin.system.exitProcess
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking

/** `kyuchestration-core serve --stdio`(core-ui-split-design.md 6.12). */
fun main(arguments: Array<String>) {
    exitProcess(runCoreCommand(arguments.toList()))
}

/**
 * 코어의 동사를 돌리고 종료 코드를 돌려준다. 지금 동사는 `serve --stdio` 하나다 — 화면이 이 프로세스를 자식으로 띄우고 stdio 로
 * 틀을 주고받으며, stdin 이 닫히면 모든 세션을 끝내고 끝난다(6.13 의 2~4 단계). 데몬(`serve`)과 중계(`attach`)는 5 단계다.
 *
 * 앱의 실행기(app 모듈)도 같은 함수를 부른다 — 설치 패키지 안에서는 화면이 자기 실행기를 이 동사로 다시 띄운다.
 */
fun runCoreCommand(arguments: List<String>): Int {
    if (arguments != SERVE_OVER_STANDARD_STREAMS) {
        System.err.println("쓰는 법: kyuchestration-core ${SERVE_OVER_STANDARD_STREAMS.joinToString(" ")}")
        return USAGE_EXIT_CODE
    }
    // 코어는 창을 띄우지 않는다. 설치 패키지에서는 화면과 같은 실행기로 뜨는데, 맥에서 AWT 가 깨어나면 독에 아이콘이 하나 더 선다.
    System.setProperty("java.awt.headless", "true")
    return serveScreenOverStandardStreams()
}

private fun serveScreenOverStandardStreams(): Int {
    // stdout 은 틀 전용이다(6.4). 원래의 stdout 을 틀을 쓰는 자리로 따로 쥐고, System.out 은 stderr 로 돌린다 — 어느 라이브러리가
    // println 한 줄을 남겨도 화면이 그 줄을 틀로 읽다 깨지지 않는다.
    val frameOutput = FileOutputStream(FileDescriptor.out).buffered()
    System.setOut(PrintStream(FileOutputStream(FileDescriptor.err), true, Charsets.UTF_8))

    val diagnosticLogPath = diagnosticLogFile(DiagnosticLogOwner.Core)
    val diagnosticLog = FileDiagnosticLog(diagnosticLogPath)
    recordUncaughtFailuresIn(diagnosticLog)
    val versionLabel = BuildVersion.labelOf(CoreIdentity::class.java)
    diagnosticLog.record(
        DiagnosticLogEntry.ApplicationStarted(
            versionLabel = versionLabel,
            operatingSystemName = System.getProperty("os.name").orEmpty(),
            javaRuntimeVersion = System.getProperty("java.version").orEmpty(),
        ),
    )

    val framesFromScreen = Channel<String>(Channel.UNLIMITED)
    // stdin 을 읽는 것은 막히는 일이라 코어의 스레드가 아닌 자기 스레드에서 한다. 줄의 끝(EOF)이 곧 화면이 떠났다는 말이다 —
    // 창을 닫았든 화면이 죽었든 같다.
    thread(name = "core-screen-input", isDaemon = true) {
        System.`in`.bufferedReader(Charsets.UTF_8).useLines { lines -> lines.forEach { framesFromScreen.trySend(it) } }
        framesFromScreen.close()
    }

    val framesToScreen = ScreenFrameOutbox()
    val coreRun = startCore(
        framesFromScreen = framesFromScreen,
        framesToScreen = framesToScreen,
        coreIdentity = CoreIdentity(versionLabel = versionLabel, diagnosticLogPath = diagnosticLogPath.toString()),
        diagnosticLog = diagnosticLog,
    )

    return runBlocking {
        // 줄이 실패로 닫히는 것은 코어가 실패로 끝났다는 뜻이다(startCore). 그 실패는 코어의 스레드가 이미 진단 기록에 남겼다.
        var delivery = runCatching {
            framesToScreen.deliverEach { line ->
                frameOutput.write(line.toByteArray(Charsets.UTF_8))
                frameOutput.write(LINE_SEPARATOR)
                // 틀 하나를 쓸 때마다 내보낸다(설계 3.1). 버퍼에 남은 틀은 화면이 받지 못한 틀이다.
                frameOutput.flush()
            }
        }
        val screenOutputBroken = delivery.exceptionOrNull() as? IOException
        if (screenOutputBroken != null) {
            // 화면이 사라져 stdout 이 끊겼다. 세션은 그래도 stdin 의 끝에서 끝낸다 — 화면이 죽으면 stdin 도 곧 닫힌다. 그때까지
            // 나올 틀은 받을 쪽이 없으니 버린다.
            diagnosticLog.record(DiagnosticLogEntry.ScreenOutputBroken(screenOutputBroken.message.orEmpty()))
            delivery = runCatching { framesToScreen.deliverEach { } }
        }
        coreRun.join()
        if (delivery.isFailure) CORE_FAILED_EXIT_CODE else 0
    }
}

private val SERVE_OVER_STANDARD_STREAMS = listOf("serve", "--stdio")

private const val USAGE_EXIT_CODE = 2

private const val CORE_FAILED_EXIT_CODE = 1

private const val LINE_SEPARATOR = '\n'.code
