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
            bundledEngineCopyPath = BUNDLED_ENGINE_COPY_PATH,
            managedEngineExecutablePath = MANAGED_ENGINE_PATH,
            isExecutableFile = executableFilesAt("/usr/local/bin/kyu", MANAGED_ENGINE_PATH.toString()),
        )

        assertEquals(Path.of("/build/kyu"), found)
    }

    @Test
    fun `못 박은 자리는 실행할 수 없어도 그대로 답한다`() {
        val found = findKyuExecutable(
            lookUpEnvironmentVariable = fakeEnvironment("KYU_BINARY_PATH" to "/build/없는-파일"),
            bundledEngineCopyPath = BUNDLED_ENGINE_COPY_PATH,
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
            bundledEngineCopyPath = BUNDLED_ENGINE_COPY_PATH,
            managedEngineExecutablePath = MANAGED_ENGINE_PATH,
            isExecutableFile = executableFilesAt("/usr/local/bin/kyu"),
        )

        assertEquals(Path.of("/usr/local/bin/kyu"), found)
    }

    @Test
    fun `앱에 동봉된 엔진을 PATH 보다 먼저 쓴다`() {
        val found = findKyuExecutable(
            lookUpEnvironmentVariable = fakeEnvironment("PATH" to "/usr/local/bin"),
            bundledEngineCopyPath = BUNDLED_ENGINE_COPY_PATH,
            managedEngineExecutablePath = MANAGED_ENGINE_PATH,
            isExecutableFile = executableFilesAt(
                BUNDLED_ENGINE_COPY_PATH.toString(),
                "/usr/local/bin/kyu",
                MANAGED_ENGINE_PATH.toString(),
            ),
        )

        // 설치 패키지가 들고 온 엔진은 이 앱 판과 함께 시험된 바로 그 엔진이다. 언젠가 넣어 둔
        // 낡은 kyu 가 PATH 에 남아 있는 머신에서도 방금 설치한 앱이 자기 엔진을 부른다.
        assertEquals(BUNDLED_ENGINE_COPY_PATH, found)
    }

    @Test
    fun `동봉된 엔진이 있어도 가리킨 자리가 있으면 그쪽이다`() {
        val found = findKyuExecutable(
            lookUpEnvironmentVariable = fakeEnvironment("KYU_BINARY_PATH" to "/build/kyu"),
            bundledEngineCopyPath = BUNDLED_ENGINE_COPY_PATH,
            managedEngineExecutablePath = MANAGED_ENGINE_PATH,
            isExecutableFile = executableFilesAt(BUNDLED_ENGINE_COPY_PATH.toString(), "/build/kyu"),
        )

        // 엔진을 고치는 중인 사람의 길이다. 동봉이 이것까지 이기면 앱 안에서 자기가 방금 만든
        // 엔진을 시험할 방법이 사라진다.
        assertEquals(Path.of("/build/kyu"), found)
    }

    @Test
    fun `PATH 에 있는 것을 앱이 설치해 둔 것보다 먼저 쓴다`() {
        val found = findKyuExecutable(
            lookUpEnvironmentVariable = fakeEnvironment("PATH" to "/opt/bin:/usr/local/bin"),
            bundledEngineCopyPath = BUNDLED_ENGINE_COPY_PATH,
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
            bundledEngineCopyPath = BUNDLED_ENGINE_COPY_PATH,
            managedEngineExecutablePath = MANAGED_ENGINE_PATH,
            isExecutableFile = executableFilesAt(MANAGED_ENGINE_PATH.toString()),
        )

        assertEquals(MANAGED_ENGINE_PATH, found)
    }

    @Test
    fun `네 곳 어디에도 없으면 답하지 않는다`() {
        val found = findKyuExecutable(
            lookUpEnvironmentVariable = fakeEnvironment("PATH" to "/opt/bin:/usr/local/bin"),
            bundledEngineCopyPath = BUNDLED_ENGINE_COPY_PATH,
            managedEngineExecutablePath = MANAGED_ENGINE_PATH,
            isExecutableFile = executableFilesAt(),
        )

        assertNull(found)
    }

    @Test
    fun `PATH 자체가 없어도 앱이 설치해 둔 자리는 본다`() {
        val found = findKyuExecutable(
            lookUpEnvironmentVariable = { null },
            bundledEngineCopyPath = BUNDLED_ENGINE_COPY_PATH,
            managedEngineExecutablePath = MANAGED_ENGINE_PATH,
            isExecutableFile = executableFilesAt(MANAGED_ENGINE_PATH.toString()),
        )

        assertEquals(MANAGED_ENGINE_PATH, found)
    }

    @Test
    fun `동봉 엔진의 복사본은 관리 디렉토리 아래 bundled 에 둔다`() {
        val copyPath = bundledEngineCopyPath(
            engineDirectory = Path.of("/home/me/.local/share/kyuchestration/engine"),
        )

        // 앱이 받아 둔 것과 자리를 나눈다. 한 자리를 함께 쓰면 앱을 새로 설치할 때마다 사용자가
        // 받으라고 시킨 엔진이 말없이 덮인다.
        assertEquals(Path.of("/home/me/.local/share/kyuchestration/engine/bundled/kyu"), copyPath)
    }

    @Test
    fun `앱 패키지가 알려 준 리소스 자리에서 동봉 엔진을 찾는다`() {
        val resourcePath = bundledEngineResourcePath(
            lookUpSystemProperty = fakeEnvironment(
                "compose.application.resources.dir" to "/opt/kyuchestration/lib/app/resources",
            ),
            isExistingFile = { it == Path.of("/opt/kyuchestration/lib/app/resources/kyu") },
        )

        assertEquals(Path.of("/opt/kyuchestration/lib/app/resources/kyu"), resourcePath)
    }

    @Test
    fun `리소스 자리를 알려 주지 않으면 동봉이 없는 것으로 본다`() {
        // 소스에서 띄우는 흐름(IDE 에서 main 을 실행)이 여기다. 이때는 엔진을 PATH 에 두거나
        // 첫 화면에서 받는 예전 길이 그대로 열려 있어야 한다.
        val resourcePath = bundledEngineResourcePath(
            lookUpSystemProperty = fakeEnvironment(),
            isExistingFile = { false },
        )

        assertNull(resourcePath)
    }

    @Test
    fun `리소스 자리에 엔진이 없으면 동봉이 없는 것으로 본다`() {
        // gradlew run 은 리소스 자리를 늘 알려 준다. 거기에 엔진을 만들어 두지 않은 사람에게
        // "동봉이 있다" 고 답하면, 그 실행은 없는 파일을 부르며 끝난다.
        val resourcePath = bundledEngineResourcePath(
            lookUpSystemProperty = fakeEnvironment("compose.application.resources.dir" to "/build/resources"),
            isExistingFile = { false },
        )

        assertNull(resourcePath)
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
        val BUNDLED_ENGINE_COPY_PATH: Path =
            Path.of("/home/me/.local/share/kyuchestration/engine/bundled/kyu")
    }
}
