package com.kyuchestration.desktop.repoclone.kyucli

import com.kyuchestration.desktop.kyu.KyuCommandFailure
import com.kyuchestration.desktop.kyu.KyuCommandResult
import com.kyuchestration.desktop.kyu.RecordingKyuCommandRunner
import com.kyuchestration.desktop.kyu.succeedingKyuCommandResult
import com.kyuchestration.desktop.repoclone.CloneStepFailure
import com.kyuchestration.desktop.repoclone.OwnerKind
import com.kyuchestration.desktop.repoclone.RemoteRepository
import com.kyuchestration.desktop.repoclone.RepositoryOwner
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KyuCliGitHubRepositoryCatalogTest {

    @Test
    fun `계정 목록은 고른 프로필을 붙여 kyu repos owners 로 묻는다`() {
        val runner = RecordingKyuCommandRunner { succeedingKyuCommandResult(TWO_OWNERS_OUTPUT) }

        KyuCliGitHubRepositoryCatalog(runner).listOwners("개인")

        assertEquals(listOf(listOf("repos", "owners", "--profile", "개인", "--json")), runner.receivedArguments)
        assertEquals(listOf<Path?>(null), runner.receivedWorkingDirectories)
    }

    @Test
    fun `개인 계정과 조직을 계약이 준 순서 그대로 옮긴다`() {
        val runner = RecordingKyuCommandRunner { succeedingKyuCommandResult(TWO_OWNERS_OUTPUT) }

        val owners = KyuCliGitHubRepositoryCatalog(runner).listOwners("개인")

        // 개인 계정이 늘 첫 자리다(repos.go 의 listOwnersTheTokenCanSee). 여기서 다시 정렬하면
        // 사용자가 대개 고르는 것이 목록 가운데로 밀린다.
        assertEquals(
            listOf(
                RepositoryOwner("maximinhan", OwnerKind.Personal),
                RepositoryOwner("acme", OwnerKind.Organization),
            ),
            owners,
        )
    }

    @Test
    fun `모르는 계정 종류를 만나도 멈추지 않고 그 낱말을 들고 있는다`() {
        val runner = RecordingKyuCommandRunner {
            succeedingKyuCommandResult(
                """{ "schemaVersion": 1, "owners": [ { "login": "acme", "kind": "enterprise" } ] }""",
            )
        }

        val owner = KyuCliGitHubRepositoryCatalog(runner).listOwners("개인").single()

        assertEquals(OwnerKind.Unrecognized("enterprise"), owner.kind)
    }

    @Test
    fun `레포 목록은 프로필과 소유자를 함께 붙여 묻는다`() {
        val runner = RecordingKyuCommandRunner { succeedingKyuCommandResult(TWO_REPOS_OUTPUT) }

        KyuCliGitHubRepositoryCatalog(runner).listRepositories("개인", "maximinhan")

        assertEquals(
            listOf(listOf("repos", "list", "--profile", "개인", "--owner", "maximinhan", "--json")),
            runner.receivedArguments,
        )
    }

    @Test
    fun `이름과 전체 이름과 비공개 여부와 갱신 시각을 옮긴다`() {
        val runner = RecordingKyuCommandRunner { succeedingKyuCommandResult(TWO_REPOS_OUTPUT) }

        val repositories = KyuCliGitHubRepositoryCatalog(runner).listRepositories("개인", "maximinhan")

        assertEquals(
            RemoteRepository(
                name = "proj-a",
                fullName = "maximinhan/proj-a",
                isPrivate = true,
                lastUpdatedAt = Instant.parse("2026-08-27T09:30:00Z"),
            ),
            repositories.first(),
        )
    }

    @Test
    fun `갱신 시각을 받지 못한 레포는 시각이 없는 채로 남는다`() {
        val runner = RecordingKyuCommandRunner { succeedingKyuCommandResult(TWO_REPOS_OUTPUT) }

        val repositories = KyuCliGitHubRepositoryCatalog(runner).listRepositories("개인", "maximinhan")

        // 없는 것과 1970 년은 다른 사실이다. 영 값으로 대신하면 방금 만든 레포가 목록 맨 아래로 밀린다.
        assertNull(repositories.last().lastUpdatedAt)
    }

    @Test
    fun `갱신 시각이 계약을 어기면 짐작하지 않고 읽을 수 없는 출력으로 보고한다`() {
        val runner = RecordingKyuCommandRunner {
            succeedingKyuCommandResult(
                """
                {
                  "schemaVersion": 1,
                  "repos": [
                    { "name": "proj-a", "fullName": "me/proj-a", "private": false, "updatedAt": "어제" }
                  ]
                }
                """.trimIndent(),
            )
        }

        assertFailsWith<CloneStepFailure.UnreadableKyuOutput> {
            KyuCliGitHubRepositoryCatalog(runner).listRepositories("개인", "me")
        }
    }

    @Test
    fun `볼 레포가 없으면 빈 목록이다`() {
        val runner = RecordingKyuCommandRunner {
            succeedingKyuCommandResult("""{ "schemaVersion": 1, "repos": [] }""")
        }

        assertEquals(emptyList(), KyuCliGitHubRepositoryCatalog(runner).listRepositories("개인", "me"))
    }

    @Test
    fun `토큰이 거절당하면 kyu 가 남긴 이유를 그대로 싣는다`() {
        val runner = RecordingKyuCommandRunner {
            KyuCommandResult(
                exitCode = 1,
                standardOutput = "",
                standardError = "개인 프로필로 GitHub 에 붙지 못했습니다: GitHub 이 토큰을 거절했습니다\n",
            )
        }

        val failure = assertFailsWith<CloneStepFailure.KyuExitedWithFailure> {
            KyuCliGitHubRepositoryCatalog(runner).listOwners("개인")
        }

        // 401 과 네트워크 끊김과 권한 부족은 종료 코드로 갈리지 않는다 — 셋 다 1 이다. 갈라 적어
        // 놓은 것은 kyu 의 stderr 뿐이라 그것을 그대로 올린다.
        assertTrue("토큰을 거절했습니다" in failure.guidance)
    }

    @Test
    fun `아는 판이 아니면 판이 다르다고 말한다`() {
        val runner = RecordingKyuCommandRunner {
            succeedingKyuCommandResult("""{ "schemaVersion": 2, "owners": [] }""")
        }

        assertFailsWith<CloneStepFailure.UnsupportedSchemaVersion> {
            KyuCliGitHubRepositoryCatalog(runner).listOwners("개인")
        }
    }

    @Test
    fun `kyu 를 부르지 못했다는 사실을 이 걸음의 실패로 옮긴다`() {
        val runner = RecordingKyuCommandRunner { throw KyuCommandFailure.ExecutableNotFound() }

        assertFailsWith<CloneStepFailure.KyuExecutableNotFound> {
            KyuCliGitHubRepositoryCatalog(runner).listOwners("개인")
        }
    }

    private companion object {
        val TWO_OWNERS_OUTPUT = """
            {
              "schemaVersion": 1,
              "owners": [
                { "login": "maximinhan", "kind": "user" },
                { "login": "acme", "kind": "org" }
              ]
            }
        """.trimIndent()

        val TWO_REPOS_OUTPUT = """
            {
              "schemaVersion": 1,
              "repos": [
                {
                  "name": "proj-a",
                  "fullName": "maximinhan/proj-a",
                  "private": true,
                  "updatedAt": "2026-08-27T09:30:00Z"
                },
                {
                  "name": "proj-b",
                  "fullName": "maximinhan/proj-b",
                  "private": false,
                  "updatedAt": null
                }
              ]
            }
        """.trimIndent()
    }
}
