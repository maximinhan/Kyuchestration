package com.kyuchestration.desktop.repoclone.kyucli

import com.kyuchestration.desktop.kyu.KyuCommandFailure
import com.kyuchestration.desktop.kyu.KyuCommandResult
import com.kyuchestration.desktop.kyu.RecordingKyuCommandRunner
import com.kyuchestration.desktop.kyu.succeedingKyuCommandResult
import com.kyuchestration.desktop.repoclone.CloneStepFailure
import com.kyuchestration.desktop.repoclone.RegisteredTokenProfile
import com.kyuchestration.desktop.repoclone.TokenProfile
import com.kyuchestration.desktop.repoclone.TokenStorage
import java.io.IOException
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KyuCliTokenProfileRegistryTest {

    @Test
    fun `프로필 목록은 kyu auth list --json 으로 묻는다`() {
        val runner = RecordingKyuCommandRunner { succeedingKyuCommandResult(TWO_PROFILES_OUTPUT) }

        KyuCliTokenProfileRegistry(runner).listProfiles()

        assertEquals(listOf(listOf("auth", "list", "--json")), runner.receivedArguments)
        // 어느 자리에서 실행되든 답이 같은 명령이다. 작업 디렉토리를 정할 이유가 없다.
        assertEquals(listOf<Path?>(null), runner.receivedWorkingDirectories)
    }

    @Test
    fun `이름과 저장 자리를 계약에 적힌 순서 그대로 옮긴다`() {
        val runner = RecordingKyuCommandRunner { succeedingKyuCommandResult(TWO_PROFILES_OUTPUT) }

        val profiles = KyuCliTokenProfileRegistry(runner).listProfiles()

        assertEquals(
            listOf(
                TokenProfile("개인", TokenStorage.Keychain),
                TokenProfile("회사", TokenStorage.ConfigFile),
            ),
            profiles,
        )
    }

    @Test
    fun `등록된 것이 없으면 빈 목록이다`() {
        val runner = RecordingKyuCommandRunner {
            succeedingKyuCommandResult("""{ "schemaVersion": 1, "profiles": [] }""")
        }

        assertEquals(emptyList(), KyuCliTokenProfileRegistry(runner).listProfiles())
    }

    @Test
    fun `모르는 저장 자리를 만나도 멈추지 않고 그 낱말을 들고 있는다`() {
        val runner = RecordingKyuCommandRunner {
            succeedingKyuCommandResult(
                """{ "schemaVersion": 1, "profiles": [ { "name": "개인", "storage": "1password" } ] }""",
            )
        }

        val profile = KyuCliTokenProfileRegistry(runner).listProfiles().single()

        assertEquals(TokenStorage.Unrecognized("1password"), profile.storage)
    }

    @Test
    fun `등록은 이름을 인자로, 토큰을 stdin 으로 넘긴다`() {
        val runner = RecordingKyuCommandRunner { succeedingKyuCommandResult(REGISTERED_OUTPUT) }

        KyuCliTokenProfileRegistry(runner).registerProfile("개인", "ghp_예시토큰")

        // 인자로 넘기면 같은 머신의 다른 사용자가 ps 로 읽는다(auth_add.go). 토큰이 인자 목록
        // 어디에도 없다는 것까지 확인한다 — 통로를 옮겨 두고 옛 통로를 지우지 않으면 그대로 샌다.
        assertEquals(listOf(listOf("auth", "add", "개인", "--json")), runner.receivedArguments)
        assertEquals(listOf<String?>("ghp_예시토큰"), runner.receivedStandardInputs)
        assertTrue(runner.receivedArguments.single().none { "ghp_" in it })
    }

    @Test
    fun `등록이 끝나면 어느 계정의 토큰인지 함께 돌려준다`() {
        val runner = RecordingKyuCommandRunner { succeedingKyuCommandResult(REGISTERED_OUTPUT) }

        val registered = KyuCliTokenProfileRegistry(runner).registerProfile("개인", "ghp_예시토큰")

        assertEquals(RegisteredTokenProfile(profileName = "개인", gitHubLogin = "maximinhan"), registered)
    }

    @Test
    fun `거절당한 등록은 kyu 가 남긴 이유를 그대로 싣는다`() {
        val runner = RecordingKyuCommandRunner {
            KyuCommandResult(
                exitCode = 1,
                standardOutput = "",
                standardError = "개인 프로필에 아무것도 저장하지 않았습니다: 토큰이 거절당했습니다\n",
            )
        }

        val failure = assertFailsWith<CloneStepFailure.KyuExitedWithFailure> {
            KyuCliTokenProfileRegistry(runner).registerProfile("개인", "ghp_틀린토큰")
        }

        assertEquals(1, failure.exitCode)
        assertEquals("개인 프로필에 아무것도 저장하지 않았습니다: 토큰이 거절당했습니다", failure.standardError)
    }

    @Test
    fun `실패 문구 어디에도 토큰은 남지 않는다`() {
        val runner = RecordingKyuCommandRunner {
            KyuCommandResult(exitCode = 1, standardOutput = "", standardError = "토큰이 거절당했습니다")
        }

        val failure = assertFailsWith<CloneStepFailure.KyuExitedWithFailure> {
            KyuCliTokenProfileRegistry(runner).registerProfile("개인", "ghp_틀린토큰")
        }

        // 실패 문구는 로그와 화면 캡처에 가장 잘 남는 자리다.
        assertTrue("ghp_틀린토큰" !in "${failure.message} ${failure.guidance} ${failure.standardError}")
    }

    @Test
    fun `아는 판이 아니면 판이 다르다고 말한다`() {
        val runner = RecordingKyuCommandRunner {
            succeedingKyuCommandResult("""{ "schemaVersion": 2, "profiles": [] }""")
        }

        val failure = assertFailsWith<CloneStepFailure.UnsupportedSchemaVersion> {
            KyuCliTokenProfileRegistry(runner).listProfiles()
        }

        assertEquals(2, failure.actualSchemaVersion)
        assertEquals(1, failure.supportedSchemaVersion)
    }

    @Test
    fun `JSON 이 아닌 출력은 읽을 수 없는 출력으로 보고한다`() {
        val runner = RecordingKyuCommandRunner { succeedingKyuCommandResult("등록된 토큰 프로필이 없습니다.") }

        assertFailsWith<CloneStepFailure.UnreadableKyuOutput> {
            KyuCliTokenProfileRegistry(runner).listProfiles()
        }
    }

    @Test
    fun `kyu 를 부르지 못했다는 사실을 이 걸음의 실패로 옮긴다`() {
        val runner = RecordingKyuCommandRunner { throw KyuCommandFailure.ExecutableNotFound() }

        assertFailsWith<CloneStepFailure.KyuExecutableNotFound> {
            KyuCliTokenProfileRegistry(runner).listProfiles()
        }
    }

    @Test
    fun `kyu 를 띄우지 못한 원인은 안내 문구에 남는다`() {
        val runner = RecordingKyuCommandRunner {
            throw KyuCommandFailure.FailedToStart(IOException("Permission denied"))
        }

        val failure = assertFailsWith<CloneStepFailure.KyuFailedToStart> {
            KyuCliTokenProfileRegistry(runner).listProfiles()
        }

        assertTrue("Permission denied" in failure.guidance)
    }

    private companion object {
        val TWO_PROFILES_OUTPUT = """
            {
              "schemaVersion": 1,
              "profiles": [
                { "name": "개인", "storage": "keychain" },
                { "name": "회사", "storage": "file" }
              ]
            }
        """.trimIndent()

        val REGISTERED_OUTPUT = """
            {
              "schemaVersion": 1,
              "profile": "개인",
              "login": "maximinhan"
            }
        """.trimIndent()
    }
}
