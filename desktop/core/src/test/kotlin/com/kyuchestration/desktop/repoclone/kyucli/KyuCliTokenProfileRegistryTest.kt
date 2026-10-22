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

    /**
     * 제거는 --json 을 붙이지 않는다.
     *
     * kyu 쪽이 그 옵션을 받지 않아서다 — `auth remove` 는 인자를 정확히 하나만 받고, 하나 더
     * 붙이면 "지울 프로필 이름 하나가 필요합니다" 로 거절한다(auth.go 의 ManageTokenProfiles).
     * 붙였다면 모든 제거가 실패하는데, 그 실패는 kyu 가 거절한 것과 종료 코드가 같아서 화면에서
     * 가려지지 않는다.
     */
    @Test
    fun `제거는 kyu auth remove 에 이름 하나만 넘긴다`() {
        val runner = RecordingKyuCommandRunner { succeedingKyuCommandResult("개인 프로필과 그 토큰을 지웠습니다.\n") }

        KyuCliTokenProfileRegistry(runner).removeProfile("개인")

        assertEquals(listOf(listOf("auth", "remove", "개인")), runner.receivedArguments)
        assertEquals(listOf<String?>(null), runner.receivedStandardInputs)
    }

    /**
     * 성공 판정은 종료 코드로 한다.
     *
     * 제거에는 기계용 문서가 없다. 그래도 충분한 이유는 이 걸음이 앱에게 돌려줄 것이 없기
     * 때문이다 — 지워졌는지만 알면 되고, 그 답은 종료 코드가 이미 말한다. 지운 뒤의 목록은
     * 어차피 엔진에게 다시 묻는다.
     */
    @Test
    fun `종료 코드 0 이면 지워진 것으로 본다`() {
        val runner = RecordingKyuCommandRunner { succeedingKyuCommandResult("개인 프로필과 그 토큰을 지웠습니다.\n") }

        KyuCliTokenProfileRegistry(runner).removeProfile("개인")
    }

    @Test
    fun `없는 프로필을 지우려 하면 kyu 가 남긴 이유를 그대로 싣는다`() {
        val runner = RecordingKyuCommandRunner {
            KyuCommandResult(
                exitCode = 1,
                standardOutput = "",
                standardError = "등록되지 않은 프로필입니다: 개인\n등록된 프로필: 회사\n",
            )
        }

        val failure = assertFailsWith<CloneStepFailure.KyuExitedWithFailure> {
            KyuCliTokenProfileRegistry(runner).removeProfile("개인")
        }

        assertEquals(1, failure.exitCode)
        assertTrue("등록된 프로필: 회사" in failure.guidance)
    }

    @Test
    fun `제거도 kyu 를 부르지 못했다는 사실을 이 걸음의 실패로 옮긴다`() {
        val runner = RecordingKyuCommandRunner { throw KyuCommandFailure.ExecutableNotFound() }

        assertFailsWith<CloneStepFailure.KyuExecutableNotFound> {
            KyuCliTokenProfileRegistry(runner).removeProfile("개인")
        }
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
