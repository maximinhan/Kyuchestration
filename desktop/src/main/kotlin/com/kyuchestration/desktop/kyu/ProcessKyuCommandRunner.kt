package com.kyuchestration.desktop.kyu

import com.kyuchestration.desktop.diagnostics.DiagnosticLog
import com.kyuchestration.desktop.diagnostics.DiagnosticLogEntry
import com.kyuchestration.desktop.platform.childProcessEnvironment
import com.kyuchestration.desktop.platform.withEnvironment
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

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
 *   앱은 조립하는 자리(Main.kt)에서 진짜 기록을 건넨다.
 */
class ProcessKyuCommandRunner(
    private val fixedKyuExecutablePath: Path? = null,
    private val childProcessEnvironment: Map<String, String> = childProcessEnvironment(),
    private val diagnosticLog: DiagnosticLog = DiagnosticLog.Discarding,
) : KyuCommandRunner {

    override fun run(
        arguments: List<String>,
        workingDirectory: Path?,
        standardInput: String?,
    ): KyuCommandResult {
        val executable = fixedKyuExecutablePath
            ?: findKyuExecutable()
            ?: throw KyuCommandFailure.ExecutableNotFound().also { recordCouldNotStart(arguments, it) }

        val process = try {
            ProcessBuilder(listOf(executable.toString()) + arguments)
                .directory(workingDirectory?.toFile())
                // kyu 는 자기 자식으로 git 을 부른다. 그것을 찾는 PATH 가 여기서 정해진다 —
                // Finder 로 띄운 맥 앱이 물려받은 PATH 를 그대로 넘기면 brew 로 넣은 git 이
                // 없는 것이 된다.
                .withEnvironment(childProcessEnvironment)
                .start()
        } catch (failure: IOException) {
            // 파일은 있는데 띄우지 못하는 경우다 — 실행 권한이 없거나, 찾은 뒤 지워졌거나,
            // 작업 디렉토리로 준 자리가 없거나. 이것을 실패 타입으로 옮겨 두지 않으면 폴링
            // 코루틴이 IOException 째로 죽는다.
            throw KyuCommandFailure.FailedToStart(failure).also { recordCouldNotStart(arguments, it) }
        }

        // 두 파이프를 순서대로 읽으면, 안 읽는 쪽의 버퍼가 먼저 차는 순간 kyu 가 쓰기에서 멈추고
        // 이쪽은 읽기에서 멈춘다. stderr 를 다른 스레드에 맡겨 두 파이프가 동시에 비워지게 한다.
        val standardErrorReading = CompletableFuture.supplyAsync {
            process.errorStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
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

        return KyuCommandResult(
            exitCode = process.waitFor(),
            standardOutput = standardOutput,
            standardError = standardErrorReading.get(),
        ).also { result -> recordIfKyuFailed(arguments, result) }
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
    private fun recordIfKyuFailed(arguments: List<String>, result: KyuCommandResult) {
        if (result.exitCode == 0) {
            return
        }
        diagnosticLog.record(
            DiagnosticLogEntry.EngineCallFailed(
                arguments = arguments,
                exitCode = result.exitCode,
                standardError = result.standardError,
            ),
        )
    }

    /**
     * 띄우지 못한 이유에는 원인 예외까지 싣는다.
     *
     * 실패 타입의 message 만 남기면 "kyu 를 실행하지 못했습니다" 한 줄로 끝나는데, 실제로 알아야
     * 하는 것은 그 아래의 이유다 — 권한이 없는 것과 파일이 사라진 것은 사람이 할 일이 다르다.
     */
    private fun recordCouldNotStart(arguments: List<String>, failure: KyuCommandFailure) {
        diagnosticLog.record(
            DiagnosticLogEntry.EngineCallCouldNotStart(
                arguments = arguments,
                reason = listOfNotNull(failure.message, failure.cause?.toString()).joinToString(" — "),
            ),
        )
    }
}
