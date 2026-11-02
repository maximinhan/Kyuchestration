package com.kyuchestration.desktop.kyu

import com.kyuchestration.desktop.diagnostics.DiagnosticLog
import com.kyuchestration.desktop.diagnostics.DiagnosticLogEntry
import com.kyuchestration.desktop.platform.childProcessEnvironment
import com.kyuchestration.desktop.platform.withEnvironment
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

/**
 * kyu 를 진짜 자식 프로세스로 띄우는 실행기.
 *
 * **엔진 호출 실패를 기록에 남기는 자리가 여기다.** 부르는 어댑터가 여섯이라(관찰 · 초기화 ·
 * 토큰 프로필 · 레포 조회 · 클론 · 세션 명령) 각자 남기게 두면 새 어댑터가 그것을 빠뜨릴 자리가
 * 생기는데, 프로세스를 실제로 띄우는 자리는 이 하나뿐이라 여기서 남기면 빠질 곳이 없다.
 *
 * 어댑터가 아니라 여기가 남기는 데에는 대가가 하나 있다. 0 이 아닌 종료 코드를 실패로 볼지는
 * 명령마다 다른데(kyu clone 은 실패한 레포가 섞여도 문서를 내고 1 로 끝난다 — CloneStepKyuCall)
 * 이 자리는 그 구분을 모른 채 남긴다. 그래도 이쪽을 고른 이유는 기록의 쓰임이 화면과 다르기
 * 때문이다 — 화면에 띄우는 오류는 정확해야 하지만, 진단 기록은 나중에 읽는 사람이 걸러 낼 수
 * 있으므로 빠뜨리는 쪽이 더 나쁘다.
 *
 * @param fixedKyuExecutablePath 부를 실행 파일을 고정한다. 통합 테스트가 설치 여부와 무관하게
 *   자기가 빌드한 바이너리를 겨누기 위한 것이고, 앱은 이 값을 주지 않는다.
 * @param childProcessEnvironment kyu 에게 물려줄 환경. 기본값이 앱이 한 자리에서 정해 둔 환경이라
 *   (ChildProcessEnvironment) 부르는 쪽이 PATH 를 따로 손볼 일이 없다. 검사가 실제로 실려 가는지
 *   확인하기 위한 이음매이기도 하다.
 * @param diagnosticLog 실패를 남길 자리. 기본값이 아무 데도 남기지 않는 것인 이유는 시험이다 —
 *   진짜 파일을 기본값으로 두면 어댑터 시험 하나를 돌릴 때마다 이 머신의 홈에 기록이 쌓인다.
 *   앱은 조립하는 자리(core/InProcessCoreAssembly.kt)에서 진짜 기록을 건넨다.
 */
class ProcessKyuCommandRunner(
    private val fixedKyuExecutablePath: Path? = null,
    private val childProcessEnvironment: Map<String, String> = childProcessEnvironment(),
    private val diagnosticLog: DiagnosticLog = DiagnosticLog.Discarding,
    private val startProcess: (ProcessBuilder) -> Process = ProcessBuilder::start,
) : KyuCommandRunner {

    override fun run(
        arguments: List<String>,
        workingDirectory: Path?,
        standardInput: String?,
        valuesHiddenFromDiagnostics: Set<String>,
    ): KyuCommandResult {
        val hiding = DiagnosticHiding(valuesHiddenFromDiagnostics)
        val executable = fixedKyuExecutablePath
            ?: findKyuExecutable()
            ?: throw KyuCommandFailure.ExecutableNotFound().also { recordCouldNotStart(arguments, it, hiding) }

        val process = try {
            val processBuilder = ProcessBuilder(listOf(executable.toString()) + arguments)
                .directory(workingDirectory?.toFile())
                // kyu 는 자기 자식으로 git 을 부른다. 그것을 찾는 PATH 가 여기서 정해진다 —
                // Finder 로 띄운 맥 앱이 물려받은 PATH 를 그대로 넘기면 brew 로 넣은 git 이
                // 없는 것이 된다.
                .withEnvironment(childProcessEnvironment)
            startProcess(processBuilder)
        } catch (failure: IOException) {
            // 파일은 있는데 띄우지 못하는 경우다 — 실행 권한이 없거나, 찾은 뒤 지워졌거나,
            // 작업 디렉토리로 준 자리가 없거나. 이것을 실패 타입으로 옮겨 두지 않으면 폴링
            // 코루틴이 IOException 째로 죽는다.
            throw KyuCommandFailure.FailedToStart(failure).also { recordCouldNotStart(arguments, it, hiding) }
        }

        // 여기부터 kyu 가 떠 있다. 주고받다 끊긴 예외를 그대로 내보내면 부르는 쪽 어디에서도 옮기지 않은 예외가 되어, 그것을
        // 부른 코루틴과 그 스코프(코어 전체)를 취소한다. 끊긴 자리가 여기라 여기서 실패 타입으로 옮기고, 그 프로세스를 거둔다.
        val result = try {
            exchangeWithStartedProcess(process, standardInput)
        } catch (pipeFailure: IOException) {
            throw failedWhileRunning(process, pipeFailure, arguments, hiding)
        } catch (readingFailure: ExecutionException) {
            // stderr 를 읽던 스레드가 받은 예외의 껍데기다. 사람이 볼 이유는 그 안의 원인이다.
            throw failedWhileRunning(process, readingFailure.cause ?: readingFailure, arguments, hiding)
        } catch (interrupted: InterruptedException) {
            val failure = failedWhileRunning(process, interrupted, arguments, hiding)
            // 멈추라는 요청은 이 실행 하나로 끝나지 않는다. 예외가 지운 표시를 되살려야 부른 쪽이 그 요청을 본다 — 거두는 일을
            // 마친 뒤에 되살리는 것은, 표시가 선 채로는 거두며 기다리는 자리가 곧바로 다시 인터럽트되기 때문이다.
            Thread.currentThread().interrupt()
            throw failure
        }
        recordIfKyuFailed(arguments, result, hiding)
        return result
    }

    private fun exchangeWithStartedProcess(process: Process, standardInput: String?): KyuCommandResult {
        // 두 파이프를 순서대로 읽으면, 안 읽는 쪽의 버퍼가 먼저 차는 순간 kyu 가 쓰기에서 멈추고
        // 이쪽은 읽기에서 멈춘다. stderr 를 다른 스레드에 맡겨 두 파이프가 동시에 비워지게 한다.
        val standardErrorReading = CompletableFuture.supplyAsync {
            process.errorStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.whenComplete { _, readingFailure ->
            // stderr 를 더 읽지 못하면 kyu 는 그 파이프가 차는 순간 쓰기에서 멈추고, 이쪽은 stdout 이나 종료를 기다리며 함께
            // 멈춘다. 끊긴 쪽이 kyu 를 끝내 그 기다림을 풀고, 실패를 옮기는 자리(failedWhileRunning)가 마저 거둔다.
            if (readingFailure != null) {
                process.destroy()
            }
        }

        // 읽기 전에 쓰고 닫는다. 여기로 가는 것은 토큰 한 줄뿐이라 파이프 버퍼(리눅스 64KB)에
        // 다 들어가므로 이 순서로 굳지 않는다 — 보낼 것이 그보다 커지는 날에는 stderr 처럼
        // 다른 스레드로 빼야 한다.
        //
        // 줄 것이 없어도 닫는 것이 이 블록의 요점이다. 열어 둔 채로 두면 stdin 을 읽는 kyu
        // (auth add)가 영영 끝나지 않고, 화면은 스피너를 돌린 채로 멎는다.
        process.outputStream.use { childStandardInput ->
            standardInput?.let { childStandardInput.write(it.toByteArray(Charsets.UTF_8)) }
        }

        val standardOutput = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        // 종료보다 stderr 를 먼저 받는다. stderr 읽기가 끊겼으면 그 사실을 kyu 가 끝나기를 기다리기 전에 안다.
        val standardError = standardErrorReading.get()

        return KyuCommandResult(
            exitCode = process.waitFor(),
            standardOutput = standardOutput,
            standardError = standardError,
        )
    }

    private fun failedWhileRunning(
        process: Process,
        runFailure: Throwable,
        arguments: List<String>,
        hiding: DiagnosticHiding,
    ): KyuCommandFailure.FailedWhileRunning {
        endAbandonedProcess(process)
        return KyuCommandFailure.FailedWhileRunning(runFailure).also { failure ->
            diagnosticLog.record(
                DiagnosticLogEntry.EngineCallFailedWhileRunning(
                    arguments = arguments.map(hiding::hide),
                    reason = hiding.hide(failureReason(failure)),
                ),
            )
        }
    }

    /**
     * 주고받기를 잃은 kyu 를 끝낸다. 남겨 두면 아무도 읽지 않는 파이프에 매달린 고아가 된다.
     *
     * kyu 가 부른 git 같은 자식까지 함께 끝낸다 — kyu 만 끝내면 그 자식은 부모를 잃은 채 남는다. 목록은 먼저 떠 둔다. kyu 가
     * 끝나면 그 자식들은 kyu 의 자손으로 보이지 않게 된다.
     */
    private fun endAbandonedProcess(process: Process) {
        val descendants = process.descendants().toList()
        process.destroy()
        descendants.forEach(ProcessHandle::destroy)

        val endedWithinGrace = try {
            process.waitFor(ABANDONED_PROCESS_END_GRACE_SECONDS, TimeUnit.SECONDS)
        } catch (interrupted: InterruptedException) {
            // 거두는 사이에 또 멈추라는 요청이 왔다. 기다리지 않고 곧바로 끝내되, 그 요청은 부른 쪽이 보게 남긴다.
            Thread.currentThread().interrupt()
            false
        }
        if (!endedWithinGrace) {
            process.destroyForcibly()
            descendants.forEach(ProcessHandle::destroyForcibly)
        }
    }

    /**
     * 기록에 싣는 것은 인자와 종료 코드와 stderr 뿐이다.
     *
     * **stdin 은 여기 오지 않는다.** 이 앱에서 비밀이 지나는 통로가 그것 하나인데(kyu auth add 의
     * 토큰), 기록 타입이 아예 그 필드를 받지 않으므로 실수로 실을 방법이 없다 —
     * DiagnosticLogEntry 가 갈래를 닫아 둔 것이 이 보장의 근거다.
     *
     * stdout 도 싣지 않는다. 성공한 문서는 진단에 쓸모가 없고, 실패한 실행에서는 애초에 비어 있다
     * (kyu 는 시작조차 못 한 실패에서 stdout 에 아무것도 내지 않는다 — json_output.go).
     */
    private fun recordIfKyuFailed(arguments: List<String>, result: KyuCommandResult, hiding: DiagnosticHiding) {
        if (result.exitCode == 0) {
            return
        }
        diagnosticLog.record(
            DiagnosticLogEntry.EngineCallFailed(
                arguments = arguments.map(hiding::hide),
                exitCode = result.exitCode,
                standardError = hiding.hide(result.standardError),
            ),
        )
    }

    /** 띄우지 못한 이유에는 원인 예외까지 싣는다(failureReason). */
    private fun recordCouldNotStart(arguments: List<String>, failure: KyuCommandFailure, hiding: DiagnosticHiding) {
        diagnosticLog.record(
            DiagnosticLogEntry.EngineCallCouldNotStart(
                arguments = arguments.map(hiding::hide),
                reason = hiding.hide(failureReason(failure)),
            ),
        )
    }
}

/**
 * 실패 타입의 message 만 남기면 "kyu 를 실행하지 못했습니다" 한 줄로 끝나는데, 실제로 알아야 하는 것은 그 아래의 이유다 — 권한이
 * 없는 것과 파일이 사라진 것, 파이프가 닫힌 것과 인터럽트된 것은 사람이 할 일이 다르다.
 */
private fun failureReason(failure: KyuCommandFailure): String =
    listOfNotNull(failure.message, failure.cause?.toString()).joinToString(" — ")

/** kyu 는 SIGTERM 에 곧바로 끝난다(Go 의 기본 동작). 이 안에 끝나지 않으면 굳은 것으로 보고 강제로 끝낸다. */
private const val ABANDONED_PROCESS_END_GRACE_SECONDS = 2L

/**
 * 실패 기록에 남기지 않을 값을 가린다(KyuCommandRunner.run 의 valuesHiddenFromDiagnostics).
 *
 * 인자 하나가 그 값과 같을 때만이 아니라 글 안에 들어 있어도 가린다 — kyu 의 거절 문구가 받은 id 를 되읊는다.
 */
internal class DiagnosticHiding(private val hiddenValues: Set<String>) {
    fun hide(text: String): String =
        hiddenValues.filter { it.isNotEmpty() }.fold(text) { hidden, value -> hidden.replace(value, HIDDEN_VALUE_MARK) }
}

private const val HIDDEN_VALUE_MARK = "(가린 값)"
