package com.kyuchestration.desktop.engine

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class EngineInstallationStateHolderTest {

    @Test
    fun `엔진이 이미 있으면 설치 화면 없이 시작한다`() = runTest {
        val holder = stateHolder(RecordingEngineInstaller(), engineExecutablePath = INSTALLED_ENGINE_PATH)

        assertEquals(EngineInstallationState.EngineReady, holder.state.value)
    }

    @Test
    fun `엔진이 없으면 설치 화면으로 시작한다`() = runTest {
        val holder = stateHolder(RecordingEngineInstaller())

        assertEquals(EngineInstallationState.EngineMissing, holder.state.value)
    }

    @Test
    fun `받는 동안은 진행 중으로 둔다`() = runTest {
        val holder = stateHolder(RecordingEngineInstaller())

        holder.installEngine()

        assertEquals(EngineInstallationState.InstallationRunning, holder.state.value)
    }

    @Test
    fun `다 받으면 다시 띄우지 않고 곧바로 앱으로 들어간다`() = runTest {
        val installer = RecordingEngineInstaller()
        val holder = stateHolder(installer)

        holder.installEngine()
        runCurrent()

        assertEquals(EngineInstallationState.EngineReady, holder.state.value)
        assertEquals(1, installer.installationCount)
    }

    @Test
    fun `받지 못하면 그 이유를 들고 있는다`() = runTest {
        val installer = RecordingEngineInstaller().apply {
            failWith { EngineInstallationFailure.WindowsNeedsWsl() }
        }
        val holder = stateHolder(installer)

        holder.installEngine()
        runCurrent()

        val failed = assertIs<EngineInstallationState.InstallationFailed>(holder.state.value)
        assertIs<EngineInstallationFailure.WindowsNeedsWsl>(failed.failure)
    }

    @Test
    fun `받는 중에 다시 누르면 두 번 받지 않는다`() = runTest {
        val installer = RecordingEngineInstaller()
        val holder = stateHolder(installer)

        holder.installEngine()
        holder.installEngine()
        runCurrent()

        // 두 번 받으면 같은 이름을 두고 임시 파일 둘이 겨룬다.
        assertEquals(1, installer.installationCount)
    }

    @Test
    fun `실패한 뒤 다시 누르면 지난 이유가 걷힌다`() = runTest {
        val installer = RecordingEngineInstaller().apply {
            failWith { EngineInstallationFailure.GitHubUnreachable(RuntimeException("연결 거부")) }
        }
        val holder = stateHolder(installer)

        holder.installEngine()
        runCurrent()
        holder.installEngine()

        assertEquals(EngineInstallationState.InstallationRunning, holder.state.value)
    }

    @Test
    fun `터미널에서 넣은 엔진을 다시 찾으면 알아본다`() = runTest {
        var engineExecutablePath: Path? = null
        val holder = EngineInstallationStateHolder(
            engineInstaller = RecordingEngineInstaller(),
            coroutineScope = backgroundScope,
            findEngineExecutable = { engineExecutablePath },
            installationDispatcher = StandardTestDispatcher(testScheduler),
        )

        // 설치 화면은 install.sh 로 넣어도 된다고 알린다. 그 길로 넣은 사람이 앱을 다시 띄워야
        // 한다면 그 안내는 반쪽이다.
        engineExecutablePath = INSTALLED_ENGINE_PATH
        holder.lookForEngineAgain()

        assertEquals(EngineInstallationState.EngineReady, holder.state.value)
    }

    @Test
    fun `다시 찾아도 없으면 설치 화면에 머물고 지난 이유는 걷힌다`() = runTest {
        val installer = RecordingEngineInstaller().apply {
            failWith { EngineInstallationFailure.GitHubUnreachable(RuntimeException("연결 거부")) }
        }
        val holder = stateHolder(installer)

        holder.installEngine()
        runCurrent()
        holder.lookForEngineAgain()

        // 방금 찾아 없는 것과 아까 받다 실패한 것은 다른 사실이다.
        assertEquals(EngineInstallationState.EngineMissing, holder.state.value)
    }

    @Test
    fun `받는 중에는 다시 찾으라고 해도 진행 표시를 지우지 않는다`() = runTest {
        val holder = stateHolder(RecordingEngineInstaller())

        holder.installEngine()
        holder.lookForEngineAgain()

        // 받는 중에는 아직 옮기기 전이라 없는 것으로 나온다. 그 답으로 덮으면 받고 있던 것이
        // 사라진 것처럼 보인다.
        assertEquals(EngineInstallationState.InstallationRunning, holder.state.value)
    }

    private fun TestScope.stateHolder(
        engineInstaller: EngineInstaller,
        engineExecutablePath: Path? = null,
    ) = EngineInstallationStateHolder(
        engineInstaller = engineInstaller,
        coroutineScope = backgroundScope,
        findEngineExecutable = { engineExecutablePath },
        // 받아 오는 일도 앱에서는 IO 디스패처로 나간다. 여기서는 가상 시간을 쓰는 디스패처로 바꿔
        // 받는 중인 한순간을 붙잡아 볼 수 있게 한다.
        installationDispatcher = StandardTestDispatcher(testScheduler),
    )

    private class RecordingEngineInstaller : EngineInstaller {

        var installationCount = 0
            private set

        private var failure: (() -> EngineInstallationFailure)? = null

        fun failWith(failure: () -> EngineInstallationFailure) {
            this.failure = failure
        }

        override fun installEngine() {
            installationCount++
            failure?.let { throw it() }
        }
    }

    private companion object {
        val INSTALLED_ENGINE_PATH: Path = Path.of("/home/me/.local/share/kyuchestration/engine/kyu")
    }
}
