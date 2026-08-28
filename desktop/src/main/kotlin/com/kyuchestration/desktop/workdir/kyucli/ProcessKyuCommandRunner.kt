package com.kyuchestration.desktop.workdir.kyucli

import com.kyuchestration.desktop.workdir.WorkDirObservationFailure
import java.io.File
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import kotlin.io.path.isExecutable
import kotlin.io.path.isRegularFile

/**
 * kyu 를 진짜 자식 프로세스로 띄우는 실행기.
 *
 * @param fixedKyuExecutablePath 부를 실행 파일을 못 박는다. 통합 테스트가 설치 여부와 무관하게
 *   자기가 빌드한 바이너리를 겨누기 위한 것이고, 앱은 이 값을 주지 않는다 — 앱이 떠 있는 동안
 *   사용자가 kyu 를 설치하고 새로고침하면 그때부터 동작해야 하므로 부를 때마다 PATH 를 다시 뒤진다.
 */
class ProcessKyuCommandRunner(
    private val fixedKyuExecutablePath: Path? = null,
) : KyuCommandRunner {

    override fun run(arguments: List<String>): KyuCommandResult {
        val executable = fixedKyuExecutablePath
            ?: findKyuOnSystemPath()
            ?: throw WorkDirObservationFailure.KyuExecutableNotFound()

        val process = try {
            ProcessBuilder(listOf(executable.toString()) + arguments).start()
        } catch (failure: IOException) {
            // 파일은 있는데 띄우지 못하는 경우다 — 실행 권한이 없거나, 찾은 뒤 지워졌거나.
            // 이것을 관찰 실패로 옮겨 두지 않으면 폴링 코루틴이 IOException 째로 죽는다.
            throw WorkDirObservationFailure.KyuFailedToStart(failure)
        }

        // 두 파이프를 순서대로 읽으면, 안 읽는 쪽의 버퍼가 먼저 차는 순간 kyu 가 쓰기에서 멈추고
        // 이쪽은 읽기에서 멈춘다. stderr 를 다른 스레드에 맡겨 두 파이프가 동시에 비워지게 한다.
        val standardErrorReading = CompletableFuture.supplyAsync {
            process.errorStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
        val standardOutput = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }

        return KyuCommandResult(
            exitCode = process.waitFor(),
            standardOutput = standardOutput,
            standardError = standardErrorReading.get(),
        )
    }
}

/**
 * PATH 를 앞에서부터 훑어 처음 만나는 실행 가능한 kyu 를 돌려준다. 없으면 null.
 *
 * 셸을 거치지 않는다. `sh -c "kyu ..."` 로 부르면 사용자의 셸 설정과 인용 규칙이 끼어들어,
 * 공백이 든 워크디렉토리 경로에서 인자가 쪼개진다.
 */
private fun findKyuOnSystemPath(): Path? {
    val searchPath = System.getenv("PATH") ?: return null

    return searchPath.split(File.pathSeparatorChar)
        .filter { it.isNotBlank() }
        .flatMap { directory -> KYU_EXECUTABLE_NAMES.map { Path.of(directory, it) } }
        .firstOrNull { it.isRegularFile() && it.isExecutable() }
}

/**
 * 윈도우에서는 확장자가 붙은 이름으로만 실행 파일이 잡힌다. 설치 패키지에 Msi 가 들어 있으므로
 * (build.gradle.kts 의 targetFormats) 그쪽에서 도는 것도 이 앱이 감당해야 할 자리다.
 */
private val KYU_EXECUTABLE_NAMES: List<String> =
    if (System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) {
        listOf("kyu.exe", "kyu")
    } else {
        listOf("kyu")
    }
