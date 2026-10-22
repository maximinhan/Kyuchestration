package com.kyuchestration.desktop.core

import com.kyuchestration.desktop.diagnostics.BuildVersion
import com.kyuchestration.desktop.diagnostics.DiagnosticLog
import com.kyuchestration.desktop.diagnostics.DiagnosticLogEntry
import com.kyuchestration.desktop.diagnostics.DiagnosticLogOwner
import com.kyuchestration.desktop.diagnostics.FileDiagnosticLog
import com.kyuchestration.desktop.diagnostics.diagnosticLogFile
import com.kyuchestration.desktop.diagnostics.recordUncaughtFailuresIn
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PrintStream
import kotlin.concurrent.thread
import kotlin.system.exitProcess
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
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
    thread(name = "core-screen-input", isDaemon = true) { readFramesFromScreen(System.`in`, framesFromScreen) }

    val framesToScreen = ScreenFrameOutbox()
    val coreRun = startCore(
        framesFromScreen = framesFromScreen,
        framesToScreen = framesToScreen,
        coreIdentity = coreIdentityOf(versionLabel = versionLabel, diagnosticLogPath = diagnosticLogPath.toString()),
        diagnosticLog = diagnosticLog,
    )

    return runBlocking {
        val coreFailure = deliverFramesToScreen(framesToScreen, frameOutput, diagnosticLog)
        coreRun.join()
        // 코어의 실패는 코어의 스레드가 이미 진단 기록에 남겼다(startCore). 여기서는 종료 코드로만 알린다 — 화면은 그것을 기록한다.
        if (coreFailure == null) 0 else CORE_FAILED_EXIT_CODE
    }
}

/**
 * 화면이 보낸 틀 줄을 [framesFromScreen] 에 싣는다. 줄의 끝(EOF)이 곧 화면이 떠났다는 말이다 — 창을 닫았든 화면이 죽었든 같다.
 *
 * 어떻게 끝나든 [framesFromScreen] 을 닫는다. 읽다 실패한 채 닫지 않으면 코어는 화면이 아직 있다고 알아 세션을 영영 끝내지 않고,
 * 코어가 쥔 claude 들이 주인 없이 남는다. 읽기의 실패는 그대로 던진다 — 이 스레드의 미처리 예외로 진단 기록에 간다.
 */
internal fun readFramesFromScreen(screenInput: InputStream, framesFromScreen: SendChannel<String>) {
    try {
        screenInput.bufferedReader(Charsets.UTF_8).useLines { lines -> lines.forEach { framesFromScreen.trySend(it) } }
    } finally {
        framesFromScreen.close()
    }
}

/**
 * 코어가 내보낸 틀을 [screenOutput] 에 줄마다 써 내보낸다. 코어가 끝나면 코어의 실패(정상이면 null)를 돌려준다.
 *
 * [screenOutput] 이 끊기면(IOException — 화면이 사라졌다) 그 사실을 기록하고 남은 틀은 버린다. 세션은 그래도 stdin 의 끝에서
 * 끝난다 — 화면이 죽으면 stdin 도 곧 닫힌다. 그 밖의 실패는 이 코드의 결함이라 그대로 던진다.
 */
internal suspend fun deliverFramesToScreen(
    framesToScreen: ScreenFrameOutbox,
    screenOutput: OutputStream,
    diagnosticLog: DiagnosticLog,
): Throwable? = try {
    framesToScreen.deliverEach { frameLine ->
        screenOutput.write(frameLine.bytes)
        screenOutput.write(LINE_SEPARATOR)
        // 틀 하나를 쓸 때마다 내보낸다(설계 3.1). 버퍼에 남은 틀은 화면이 받지 못한 틀이다.
        screenOutput.flush()
    }
} catch (screenOutputBroken: IOException) {
    diagnosticLog.record(DiagnosticLogEntry.ScreenOutputBroken(screenOutputBroken.message.orEmpty()))
    framesToScreen.deliverEach { }
}

private val SERVE_OVER_STANDARD_STREAMS = listOf("serve", "--stdio")

private const val USAGE_EXIT_CODE = 2

private const val CORE_FAILED_EXIT_CODE = 1

private const val LINE_SEPARATOR = '\n'.code
