package com.kyuchestration.desktop.workdir.kyucli

import com.kyuchestration.desktop.kyu.planFileExistsIn
import com.kyuchestration.desktop.kyu.realKyuCommandRunnerOrSkip
import com.kyuchestration.desktop.workdir.WorkDirInitializationResult
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 진짜 kyu 바이너리로 제자리 초기화를 한 번 통과시킨다.
 *
 * 가짜 실행기는 "종료 코드 0 이면 성공" 까지만 말한다. 그 실행이 고른 자리에 정말
 * .coord/plan.md 를 남기는지, 두 번째 실행이 정말 거절당하는지는 엔진에게 직접 시켜야만 안다 —
 * 앱이 계획 템플릿을 직접 쓰지 않기로 한 결정이 성립하는 자리도 정확히 거기다.
 *
 * kyu 를 찾지 못하면 건너뛴다(realKyuCommandRunnerOrSkip).
 */
class KyuCliWorkDirInitializerIntegrationTest {

    private val temporaryDirectory = createTempDirectory("kyu-init-integration-test")

    @AfterTest
    fun 임시_디렉토리를_지운다() {
        temporaryDirectory.toFile().deleteRecursively()
    }

    @Test
    fun `고른 자리에 계획 파일을 만든다`() {
        val initializer = KyuCliWorkDirInitializer(realKyuCommandRunnerOrSkip())

        val result = initializer.initializeInPlace(temporaryDirectory)

        assertEquals(WorkDirInitializationResult.Initialized(temporaryDirectory), result)
        // 고른 자리 아래에 또 디렉토리가 생기지 않는다. 고른 그 자리가 워크디렉토리다.
        val planFile = temporaryDirectory.resolve(".coord").resolve("plan.md")
        assertTrue(planFile.isRegularFile(), "kyu init 이 고른 자리에 계획 파일을 놓아야 한다")
        assertTrue("tasks:" in planFile.readText(), "빈 파일이 아니라 형식을 가르치는 템플릿이어야 한다")
    }

    @Test
    fun `앱이 아는 계획 파일 자리가 kyu 가 만드는 자리와 같다`() {
        val initializer = KyuCliWorkDirInitializer(realKyuCommandRunnerOrSkip())

        initializer.initializeInPlace(temporaryDirectory)

        // 여는 걸음은 이 판단 하나로 초기화할지를 정한다. 앱이 아는 자리(.coord/plan.md)가 엔진이
        // 만드는 자리와 어긋나면 앱은 이미 초기화된 워크디렉토리를 열 때마다 다시 kyu init 을
        // 부르고, 그때마다 "이미 계획 파일이 있습니다" 로 거절당해 아무것도 열리지 않는다 —
        // 그 어긋남은 계약에 실려 오지 않으므로 여기서 진짜 kyu 에게 물어야만 드러난다.
        assertTrue(planFileExistsIn(temporaryDirectory))
        assertFalse(planFileExistsIn(temporaryDirectory.resolve("아직-만들지-않은-곳")))
    }

    @Test
    fun `이미 계획이 있는 자리를 다시 초기화하면 kyu 가 거절한 이유가 돌아온다`() {
        val initializer = KyuCliWorkDirInitializer(realKyuCommandRunnerOrSkip())
        initializer.initializeInPlace(temporaryDirectory)

        val result = initializer.initializeInPlace(temporaryDirectory)

        val notInitialized = assertIs<WorkDirInitializationResult.NotInitialized>(result)
        // 사람이 쓴 계획을 덮어쓰지 않는 것이 kyu 의 규칙이다(init.go). 그 규칙을 앱이 흉내 내지
        // 않고 엔진의 말을 그대로 화면에 올린다.
        assertTrue("이미 계획 파일이 있습니다" in notInitialized.reason, notInitialized.reason)
    }

    @Test
    fun `이미 레포가 들어 있는 디렉토리도 있던 것을 지우지 않는다`() {
        val initializer = KyuCliWorkDirInitializer(realKyuCommandRunnerOrSkip())
        // kyu clone 이나 git clone 으로 레포부터 받아 둔 디렉토리를 앱에서 고른 상황이다.
        temporaryDirectory.resolve("proj-a").createDirectories()

        val result = initializer.initializeInPlace(temporaryDirectory)

        assertEquals(WorkDirInitializationResult.Initialized(temporaryDirectory), result)
        assertTrue(temporaryDirectory.resolve(".coord").resolve("plan.md").isRegularFile())
        assertTrue(temporaryDirectory.resolve("proj-a").isDirectory(), "있던 것을 지우지 않아야 한다")
    }
}
