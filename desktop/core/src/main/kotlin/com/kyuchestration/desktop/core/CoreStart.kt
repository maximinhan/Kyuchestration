package com.kyuchestration.desktop.core

import com.kyuchestration.desktop.diagnostics.DiagnosticLog
import com.kyuchestration.desktop.diagnostics.DiagnosticLogEntry
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch

/**
 * 코어를 자기 스레드에 세우고 화면 하나에 잇는다(core-ui-split-design.md 6.13 의 2~4 단계). 돌려주는 Job 은 화면이 떠나 세션이 다
 * 끝나면 끝난다.
 *
 * 코어 프로세스(CoreMain)는 [framesFromScreen] 을 stdin 에서, [framesToScreen] 을 stdout 으로 잇는다. 시험은 같은 함수를 메모리
 * 줄에 잇는다 — 화면이 쓰는 길은 언제나 자식 프로세스의 stdio 다(원칙 20).
 *
 * **코어는 자기 스레드 하나에서 돈다.** 홀더들은 한 번에 한 코루틴만 상태를 만진다는 전제 위에 있어 스레드 하나를 준다.
 *
 * @param assembleCore 코어의 스레드 위에서 홀더들을 세운다. 코어 프로세스는 kyu 를 부르는 실제 조립을 쓴다.
 */
fun startCore(
    framesFromScreen: ReceiveChannel<String>,
    framesToScreen: ScreenFrameOutbox,
    coreIdentity: CoreIdentity,
    diagnosticLog: DiagnosticLog,
    assembleCore: (CoroutineScope) -> ScreenServingCore = { coreScope -> assembleInProcessScreenFacingCore(coreScope, diagnosticLog) },
): Job {
    // 데몬 스레드인 것은 코어가 끝난 뒤 이 스레드가 할 일 없이 남아 JVM 이 끝나는 것을 막지 않게다.
    val coreDispatcher = Executors.newSingleThreadExecutor { task ->
        Thread(task, CORE_THREAD_NAME).apply { isDaemon = true }
    }.asCoroutineDispatcher()
    // 코어가 실패로 끝나면 그 원인은 여기서 진단 기록에 남는다. 화면 쪽은 줄이 그 실패로 닫히는 것을 보고 알린다(아래).
    val recordingCoreFailure = CoroutineExceptionHandler { _, failure ->
        diagnosticLog.record(DiagnosticLogEntry.UnhandledFailure(CORE_THREAD_NAME, failure))
    }
    val coreScope = CoroutineScope(coreDispatcher + CoroutineName("core") + recordingCoreFailure)
    val coreRun = coreScope.launch {
        serveScreen(
            core = assembleCore(coreScope),
            coreIdentity = coreIdentity,
            framesFromScreen = framesFromScreen,
            framesToScreen = framesToScreen,
        )
        // 화면이 떠나고 세션도 다 끝났다. 홀더들이 걸어 둔 반복(대시보드의 관찰 같은 것)을 함께 거둔다. 이 취소는 정상 종료다 —
        // serveScreen 이 화면 쪽 줄을 이미 까닭 없이 닫았으므로, 아래 invokeOnCompletion 이 이 취소를 실어 닫으려 해도 아무 일이
        // 없고 코어 프로세스는 0 으로 끝난다.
        coreScope.cancel()
    }
    // 코어가 어떻게 끝나든 화면 쪽 줄을 그 이유로 닫는다. 조립이 던지면 serveScreen 에 들어가 보지도 못해 줄을 닫을 자리가
    // 없고, 그러면 첫 스냅샷을 기다리는 거울(화면의 시작)이 영원히 기다린다. 이유를 실어 닫으면 코어 프로세스는 실패로 끝나고
    // 화면은 줄의 끝을 본다. serveScreen 이 이미 닫았으면 아무 일도 없다.
    coreRun.invokeOnCompletion { cause -> framesToScreen.close(cause) }
    return coreRun
}

private const val CORE_THREAD_NAME = "kyuchestration-core"
