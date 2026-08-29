package com.kyuchestration.desktop.engine.bundled

import com.kyuchestration.desktop.engine.EngineInstallationFailure
import com.kyuchestration.desktop.engine.reasonEngineDoesNotRun
import com.kyuchestration.desktop.kyu.bundledEngineCopyPath
import com.kyuchestration.desktop.kyu.bundledEngineResourcePath
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.deleteIfExists
import kotlin.io.path.isExecutable
import kotlin.io.path.isRegularFile

/**
 * 설치 패키지가 들고 온 엔진을 앱이 실제로 부를 수 있는 자리에 놓는다.
 *
 * **왜 그대로 부르지 않고 복사하는가.** 동봉된 원본은 실행할 수 없기 때문이다. jpackage 는 앱
 * 리소스를 이미지로 옮기면서 실행 권한을 떼어 내고, 설치된 그 자리는 root 소유라 앱이 자기 손으로
 * 권한을 되돌릴 수도 없다.
 *
 * 실측(리눅스 · 이 저장소의 deb):
 * - `build/compose/tmp/prepareAppResources/kyu` → `-rwxr-xr-x` (Gradle 은 권한을 지킨다)
 * - `build/compose/binaries/main/app/.../lib/app/resources/kyu` → `-rw-r--r--`
 * - deb 안 `/opt/kyuchestration/lib/app/resources/kyu` → `-rw-r--r-- root/root`
 *
 * 맥에서도 같은 길을 쓴다. 권한이 남아 있더라도 받은 dmg 안의 파일에는 격리 표시(quarantine)가
 * 붙어 있어 앱이 그것을 자식 프로세스로 띄우는 순간 막힐 수 있는데, 바이트만 복사하면 그 표시는
 * 따라오지 않는다. 플랫폼마다 다른 길을 두면 한쪽은 릴리스를 내야만 시험된다.
 *
 * 복사는 앱을 띄울 때마다가 아니라 바뀌었을 때만 한다. 같은 바이트가 이미 놓여 있으면 그대로 둔다.
 *
 * @param bundledEnginePath 설치 패키지가 들고 온 원본. null 이면 이 실행에는 동봉이 없다.
 * @param copyPath 부를 수 있게 복사해 둘 자리.
 * @throws EngineInstallationFailure.BundledEngineNotPlaced 복사하지 못했을 때.
 * @throws EngineInstallationFailure.BundledEngineDidNotRun 복사한 것이 돌지 않을 때.
 */
internal fun placeBundledEngineWhereItCanRun(
    bundledEnginePath: Path? = bundledEngineResourcePath(),
    copyPath: Path = bundledEngineCopyPath(),
) {
    if (bundledEnginePath == null) {
        removeCopyLeftByAnEarlierInstallation(copyPath)
        return
    }

    if (copyIsTheSameEngine(bundledEnginePath, copyPath)) {
        return
    }

    replaceCopy(bundledEnginePath, copyPath)
}

/**
 * 동봉이 없는 실행에서는 지난 판이 남긴 복사본을 치운다.
 *
 * 그대로 두면 그것이 탐색에서 PATH 보다 앞에 선다 — 설치 패키지를 지우고 소스로 돌아온 사람이
 * 자기가 방금 빌드해 PATH 에 놓은 엔진 대신 옛 앱이 남긴 판을 부르게 되고, 그 사실은 어디에도
 * 드러나지 않는다. 이 자리의 주인은 지금 설치된 앱이므로, 그 앱이 없으면 이 파일도 없어야 한다.
 *
 * 지우지 못한 것으로 앱을 세우지는 않는다. 남아 있는 것은 한 판 전의 엔진이라 부를 수는 있고
 * 무엇인지는 `kyu version` 이 답하는데, 여기서 끊으면 지울 수 없는 파일 하나 때문에 앱이 통째로
 * 뜨지 않는다. 이 자리에 쓸 수 없는 머신이라면 다음 걸음인 [엔진 설치] 도 같은 이유로 실패하고,
 * 그때는 진짜 이유가 화면에 뜬다.
 */
private fun removeCopyLeftByAnEarlierInstallation(copyPath: Path) {
    try {
        copyPath.deleteIfExists()
    } catch (failure: IOException) {
        // 위 설명대로 여기서는 끊지 않는다.
    }
}

/**
 * 이미 놓인 것이 지금 동봉된 것과 같은 엔진인가.
 *
 * 바이트로 견준다. `kyu version` 으로 물으면 두 개발 빌드가 똑같이 `dev` 라고 답해 서로 다른
 * 엔진이 같은 것으로 보이고, 애초에 원본은 실행할 수 없어 물어볼 수도 없다.
 * ([Files.mismatch] 는 첫 다른 바이트에서 멈추므로 다른 판끼리는 끝까지 읽지도 않는다.)
 *
 * 실행 권한까지 함께 본다. 바이트가 같아도 권한이 없으면 탐색이 그 자리를 건너뛰어, 동봉된 엔진을
 * 들고 있는 앱이 "엔진이 없다" 는 화면을 띄운다. 다시 놓으면 그 자리에서 풀린다.
 */
private fun copyIsTheSameEngine(bundledEnginePath: Path, copyPath: Path): Boolean = try {
    copyPath.isRegularFile() &&
        copyPath.isExecutable() &&
        Files.mismatch(bundledEnginePath, copyPath) == SAME_CONTENT
} catch (failure: IOException) {
    // 견주지 못했으면 "다르다" 로 본다. 다시 놓아 보고, 그것도 안 되면 그때 이유를 들고 실패한다.
    false
}

/**
 * 복사하고 · 실행 권한을 주고 · 정말 도는지 확인한 뒤에야 엔진의 이름으로 옮긴다.
 *
 * 순서가 이 함수의 전부이고, 릴리스에서 받아 놓는 쪽과 같은 순서다(GitHubReleaseEngineInstaller).
 * 옆자리에 먼저 놓는 이유: 이름에 곧바로 복사하면 앱이 그 사이에 꺼졌을 때 반쪽짜리 파일이 그
 * 이름을 차지한다. 탐색은 "실행 가능한 파일이 있는가" 만 보므로 그것을 엔진으로 여기고, 다음
 * 실행부터는 바이트가 다르니 다시 복사하겠지만 그 사이의 호출은 전부 깨진 엔진으로 나간다.
 */
private fun replaceCopy(bundledEnginePath: Path, copyPath: Path) {
    val copyDirectory = copyPath.parent

    val temporaryCopy = try {
        Files.createDirectories(copyDirectory)
        // 임시 파일도 같은 디렉토리에 만든다. /tmp 를 거치면 파일 시스템이 달라 옮기기가 이름
        // 바꾸기가 아닌 복사가 되고, 원자성이 사라진다.
        Files.createTempFile(copyDirectory, COPY_FILE_PREFIX, "")
    } catch (failure: IOException) {
        throw EngineInstallationFailure.BundledEngineNotPlaced(copyPath, failure)
    }

    try {
        Files.copy(bundledEnginePath, temporaryCopy, StandardCopyOption.REPLACE_EXISTING)
        Files.setPosixFilePermissions(temporaryCopy, PosixFilePermissions.fromString("rwxr-xr-x"))

        reasonEngineDoesNotRun(temporaryCopy)?.let { reason ->
            throw EngineInstallationFailure.BundledEngineDidNotRun(reason)
        }

        Files.move(temporaryCopy, copyPath, StandardCopyOption.ATOMIC_MOVE)
    } catch (failure: Throwable) {
        // 실패로 끝나는 길에서는 복사하다 만 것을 남기지 않는다. 남기면 앱을 띄울 때마다 그
        // 디렉토리가 커지고, 그 파일들은 아무도 다시 보지 않는다.
        try {
            temporaryCopy.deleteIfExists()
        } catch (cleanupFailure: IOException) {
            // 치우다 실패한 것으로 진짜 이유를 덮지 않는다. 화면에 떠야 하는 것은 "왜 엔진을 놓지
            // 못했는가" 이지 "임시 파일을 왜 못 지웠는가" 가 아니다.
            failure.addSuppressed(cleanupFailure)
        }

        if (failure is IOException) {
            throw EngineInstallationFailure.BundledEngineNotPlaced(copyPath, failure)
        }
        throw failure
    }
}

/** [Files.mismatch] 가 "두 파일의 바이트가 같다" 를 답하는 값. */
private const val SAME_CONTENT = -1L

private const val COPY_FILE_PREFIX = "kyu.copy"
