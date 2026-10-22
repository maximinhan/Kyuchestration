package com.kyuchestration.desktop.repoclone.kyucli

import com.kyuchestration.desktop.kyu.realKyuCommandRunnerOrSkip
import com.kyuchestration.desktop.repoclone.CloneStepFailure
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 진짜 kyu 바이너리로 클론 어댑터의 실패 길을 통과시킨다.
 *
 * 성공하는 길은 진짜 토큰과 GitHub 왕복이 있어야 하므로 여기서 지나지 않는다. 대신 시작하지도
 * 못한 실패를 진짜 엔진에게 받아, 문서가 나오지 않는 실행을 어댑터가 실패로 올리는지 본다 —
 * 가짜 실행기로는 "그때 stdout 이 정말 비어 있는가" 를 확인할 수 없다.
 *
 * kyu 를 찾지 못하면 건너뛴다(realKyuCommandRunnerOrSkip).
 */
class KyuCliWorkDirRepositoryClonerIntegrationTest {

    private val temporaryWorkDirPath = createTempDirectory("kyu-clone-integration-test")

    @AfterTest
    fun 임시_워크디렉토리를_지운다() {
        temporaryWorkDirPath.toFile().deleteRecursively()
    }

    @Test
    fun `없는 프로필로 시키면 문서 없이 kyu 가 남긴 이유만 돌아온다`() {
        val cloner = KyuCliWorkDirRepositoryCloner(realKyuCommandRunnerOrSkip())

        val failure = assertFailsWith<CloneStepFailure.KyuExitedWithFailure> {
            cloner.cloneInto(
                workDirPath = temporaryWorkDirPath,
                profileName = "kyu-desktop-통합시험-없는-프로필",
                repositoryFullNames = listOf("maximinhan/proj-a"),
            )
        }

        assertEquals(1, failure.exitCode)
        assertTrue(failure.guidance.isNotBlank(), "kyu 가 stderr 로 남긴 이유가 안내 문구가 되어야 한다")
        // 시작하지 못한 클론은 아무것도 남기지 않는다. 이 자리에 디렉토리가 생겼다면 앱이나
        // 엔진 중 하나가 "받기 전에 자리를 만든다" 를 하고 있다는 뜻이다.
        assertEquals(
            emptyList(),
            temporaryWorkDirPath.toFile().listFiles()?.map { it.name } ?: emptyList(),
        )
    }
}
