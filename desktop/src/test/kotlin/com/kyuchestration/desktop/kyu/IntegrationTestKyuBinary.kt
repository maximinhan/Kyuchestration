package com.kyuchestration.desktop.kyu

import java.nio.file.Path
import kotlin.io.path.isExecutable
import org.junit.jupiter.api.Assumptions.abort

/**
 * 통합 검증이 부를 진짜 kyu 를 찾는다. 어디에도 없으면 그 검증을 건너뛴다.
 *
 * 소스에서 빌드한 바이너리를 겨누려면 KYU_BINARY_PATH 를 준다. 없으면 PATH 에 설치된 kyu 를
 * 쓰고, 그것도 없으면 검증을 건너뛴다 — 데스크톱 CI 는 Gradle 만 세우고 Go 는 세우지 않으므로,
 * 여기서 실패로 처리하면 엔진과 무관한 이유로 데스크톱 빌드가 빨개진다.
 *
 * 관찰과 초기화 두 통합 검증이 함께 쓴다. 각자 찾게 두면 환경 변수 이름이나 건너뛰는 조건이
 * 한쪽에서만 바뀌는 날이 온다.
 */
internal fun realKyuCommandRunnerOrSkip(): KyuCommandRunner {
    val pinnedPath = System.getenv(KYU_BINARY_PATH_VARIABLE)?.let(Path::of)
    if (pinnedPath != null) {
        if (!pinnedPath.isExecutable()) {
            abort<Unit>("$KYU_BINARY_PATH_VARIABLE 가 가리키는 $pinnedPath 를 실행할 수 없습니다")
        }
        return ProcessKyuCommandRunner(pinnedPath)
    }

    val runnerFromSystemPath = ProcessKyuCommandRunner()
    try {
        runnerFromSystemPath.run(listOf("version"))
    } catch (failure: KyuCommandFailure.ExecutableNotFound) {
        abort<Unit>("PATH 에도 $KYU_BINARY_PATH_VARIABLE 에도 kyu 가 없어 건너뜁니다 (${failure.message})")
    }
    return runnerFromSystemPath
}

private const val KYU_BINARY_PATH_VARIABLE = "KYU_BINARY_PATH"
