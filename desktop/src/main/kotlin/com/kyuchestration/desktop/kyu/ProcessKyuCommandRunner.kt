package com.kyuchestration.desktop.kyu

import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

/**
 * kyu 를 진짜 자식 프로세스로 띄우는 실행기.
 *
 * @param fixedKyuExecutablePath 부를 실행 파일을 못 박는다. 통합 테스트가 설치 여부와 무관하게
 *   자기가 빌드한 바이너리를 겨누기 위한 것이고, 앱은 이 값을 주지 않는다.
 */
class ProcessKyuCommandRunner(
    private val fixedKyuExecutablePath: Path? = null,
) : KyuCommandRunner {

    override fun run(
        arguments: List<String>,
        workingDirectory: Path?,
        standardInput: String?,
    ): KyuCommandResult {
        val executable = fixedKyuExecutablePath
            ?: findKyuExecutable()
            ?: throw KyuCommandFailure.ExecutableNotFound()

        val process = try {
            ProcessBuilder(listOf(executable.toString()) + arguments)
                .directory(workingDirectory?.toFile())
                .start()
        } catch (failure: IOException) {
            // 파일은 있는데 띄우지 못하는 경우다 — 실행 권한이 없거나, 찾은 뒤 지워졌거나,
            // 작업 디렉토리로 준 자리가 없거나. 이것을 실패 타입으로 옮겨 두지 않으면 폴링
            // 코루틴이 IOException 째로 죽는다.
            throw KyuCommandFailure.FailedToStart(failure)
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
        )
    }
}
