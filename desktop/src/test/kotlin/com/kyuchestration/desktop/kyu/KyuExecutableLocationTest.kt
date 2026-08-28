package com.kyuchestration.desktop.kyu

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 진짜 파일을 만들지 않는다. 여기서 시험하는 것은 "어느 자리를 어떤 순서로 보는가" 이지
 * "파일이 실행 가능한지 판정하는 법" 이 아니다 — 후자는 표준 라이브러리의 몫이고, 그것을
 * 끌어들이면 검사마다 임시 디렉토리와 권한 비트를 세워야 한다.
 */
class KyuExecutableLocationTest {

    @Test
    fun `못 박은 자리가 있으면 다른 곳을 보지 않는다`() {
        val found = findKyuExecutable(
            lookUpEnvironmentVariable = fakeEnvironment(
                "KYU_BINARY_PATH" to "/build/kyu",
                "PATH" to "/usr/local/bin",
            ),
            managedEngineExecutablePath = MANAGED_ENGINE_PATH,
            isExecutableFile = executableFilesAt("/usr/local/bin/kyu", MANAGED_ENGINE_PATH.toString()),
        )

        assertEquals(Path.of("/build/kyu"), found)
    }

    @Test
    fun `못 박은 자리는 실행할 수 없어도 그대로 답한다`() {
        val found = findKyuExecutable(
            lookUpEnvironmentVariable = fakeEnvironment("KYU_BINARY_PATH" to "/build/없는-파일"),
            managedEngineExecutablePath = MANAGED_ENGINE_PATH,
            isExecutableFile = executableFilesAt(),
        )

        // 못 박은 것을 조용히 무시하고 다른 자리로 넘어가면, 오타 하나가 "엔진이 없다" 로 뜬다.
        // 실행되지 않는 이유는 실제로 부를 때 실행기가 말한다.
        assertEquals(Path.of("/build/없는-파일"), found)
    }

    @Test
    fun `못 박은 값이 비어 있으면 못 박지 않은 것으로 본다`() {
        val found = findKyuExecutable(
            lookUpEnvironmentVariable = fakeEnvironment(
                "KYU_BINARY_PATH" to "  ",
                "PATH" to "/usr/local/bin",
            ),
            managedEngineExecutablePath = MANAGED_ENGINE_PATH,
            isExecutableFile = executableFilesAt("/usr/local/bin/kyu"),
        )

        assertEquals(Path.of("/usr/local/bin/kyu"), found)
    }

    @Test
    fun `PATH 에 있는 것을 앱이 설치해 둔 것보다 먼저 쓴다`() {
        val found = findKyuExecutable(
            lookUpEnvironmentVariable = fakeEnvironment("PATH" to "/opt/bin:/usr/local/bin"),
            managedEngineExecutablePath = MANAGED_ENGINE_PATH,
            isExecutableFile = executableFilesAt("/usr/local/bin/kyu", MANAGED_ENGINE_PATH.toString()),
        )

        // 사용자가 자기 손으로 설치한 것이 앱이 받아 둔 것을 이긴다. 반대로 두면 소스에서 빌드해
        // PATH 에 놓고 고치는 중인 사람이 영영 앱이 받아 둔 옛 판을 부르게 된다.
        assertEquals(Path.of("/usr/local/bin/kyu"), found)
    }

    @Test
    fun `PATH 에 없으면 앱이 설치해 둔 자리를 본다`() {
        val found = findKyuExecutable(
            lookUpEnvironmentVariable = fakeEnvironment("PATH" to "/opt/bin:/usr/local/bin"),
            managedEngineExecutablePath = MANAGED_ENGINE_PATH,
            isExecutableFile = executableFilesAt(MANAGED_ENGINE_PATH.toString()),
        )

        assertEquals(MANAGED_ENGINE_PATH, found)
    }

    @Test
    fun `세 곳 어디에도 없으면 답하지 않는다`() {
        val found = findKyuExecutable(
            lookUpEnvironmentVariable = fakeEnvironment("PATH" to "/opt/bin:/usr/local/bin"),
            managedEngineExecutablePath = MANAGED_ENGINE_PATH,
            isExecutableFile = executableFilesAt(),
        )

        assertNull(found)
    }

    @Test
    fun `PATH 자체가 없어도 앱이 설치해 둔 자리는 본다`() {
        val found = findKyuExecutable(
            lookUpEnvironmentVariable = { null },
            managedEngineExecutablePath = MANAGED_ENGINE_PATH,
            isExecutableFile = executableFilesAt(MANAGED_ENGINE_PATH.toString()),
        )

        assertEquals(MANAGED_ENGINE_PATH, found)
    }

    @Test
    fun `맥에서는 Application Support 아래를 관리 디렉토리로 삼는다`() {
        val directory = managedEngineDirectory(osName = "Mac OS X", userHomeDirectory = Path.of("/Users/me"))

        assertEquals(Path.of("/Users/me/Library/Application Support/Kyuchestration/engine"), directory)
    }

    @Test
    fun `그 밖에서는 local share 아래를 관리 디렉토리로 삼는다`() {
        val directory = managedEngineDirectory(osName = "Linux", userHomeDirectory = Path.of("/home/me"))

        assertEquals(Path.of("/home/me/.local/share/kyuchestration/engine"), directory)
    }

    @Test
    fun `관리 디렉토리 안에서 부를 파일 이름은 kyu 다`() {
        val executablePath = managedEngineExecutablePath(
            engineDirectory = Path.of("/home/me/.local/share/kyuchestration/engine"),
        )

        assertEquals(Path.of("/home/me/.local/share/kyuchestration/engine/kyu"), executablePath)
    }

    private fun fakeEnvironment(vararg entries: Pair<String, String>): (String) -> String? {
        val values = entries.toMap()
        return { values[it] }
    }

    private fun executableFilesAt(vararg paths: String): (Path) -> Boolean {
        val executablePaths = paths.map(Path::of).toSet()
        return { it in executablePaths }
    }

    private companion object {
        val MANAGED_ENGINE_PATH: Path = Path.of("/home/me/.local/share/kyuchestration/engine/kyu")
    }
}
