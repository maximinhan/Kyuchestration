package com.kyuchestration.desktop.core

import com.kyuchestration.desktop.diagnostics.DiagnosticLog
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.launch

/**
 * 코어를 이 JVM 안의 자기 스레드에 세우고, 화면이 건넨 줄 두 가닥에 잇는다(core-ui-split-design.md 7 절 1 단계).
 *
 * 화면은 이 함수가 세운 코어를 직접 읽지 않는다 — 돌려주는 것이 없다. 화면이 코어에 닿는 길은 [framesFromScreen] ·
 * [framesToScreen] 뿐이고, 그 위에서 거울(CoreMirror)이 코어를 다시 세운다. 2 단계에서 이 함수가 하던 일이 코어 프로세스의
 * main 으로 옮기고, 두 가닥은 그 프로세스의 stdin · stdout 이 된다.
 *
 * **코어는 화면 스레드가 아닌 자기 스레드 하나에서 돈다.** 홀더들은 한 번에 한 코루틴만 상태를 만진다는 전제 위에 있어
 * 스레드 하나를 준다. 화면 스레드를 함께 쓰면 창을 닫는 자리(화면 스레드를 붙잡고 모든 세션이 끝나기를 기다린다)가 그
 * 끝내기를 맡은 코어를 함께 붙잡는다.
 *
 * @param assembleCore 코어의 스레드 위에서 홀더들을 세운다. 앱은 kyu 를 부르는 실제 조립을 쓴다.
 */
fun startCoreInThisProcess(
    framesFromScreen: ReceiveChannel<String>,
    framesToScreen: SendChannel<String>,
    diagnosticLog: DiagnosticLog,
    assembleCore: (CoroutineScope) -> ScreenFacingCore = { coreScope -> assembleInProcessScreenFacingCore(coreScope, diagnosticLog) },
) {
    // 데몬 스레드인 것은 코어가 끝난 뒤 이 스레드가 할 일 없이 남아 JVM 이 끝나는 것을 막지 않게다.
    val coreDispatcher = Executors.newSingleThreadExecutor { task ->
        Thread(task, "kyuchestration-core").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    val coreScope = CoroutineScope(coreDispatcher + CoroutineName("core"))
    val coreRun = coreScope.launch {
        serveScreen(
            core = assembleCore(coreScope),
            framesFromScreen = framesFromScreen,
            framesToScreen = framesToScreen,
        )
        // 화면이 떠나고 세션도 다 끝났다. 홀더들이 걸어 둔 반복(대시보드의 관찰 같은 것)을 함께 거둔다.
        coreScope.cancel()
    }
    // 코어가 어떻게 끝나든 화면 쪽 줄을 그 이유로 닫는다. 조립이 던지면 serveScreen 에 들어가 보지도 못해 줄을 닫을 자리가
    // 없고, 그러면 첫 스냅샷을 기다리는 거울(화면의 시작)이 창 하나 없이 영원히 기다린다. 이유를 실어 닫으면 거울을 세우던
    // 자리가 같은 예외를 받아, 화면이 그 실패를 기록하고 눈에 보이게 끝난다. serveScreen 이 이미 닫았으면 아무 일도 없다.
    coreRun.invokeOnCompletion { cause -> framesToScreen.close(cause) }
}
