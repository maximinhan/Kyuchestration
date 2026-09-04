package com.kyuchestration.desktop.terminal.kyucli

import com.kyuchestration.desktop.kyu.KyuCommandFailure
import com.kyuchestration.desktop.kyu.KyuCommandResult
import com.kyuchestration.desktop.kyu.RecordingKyuCommandRunner
import com.kyuchestration.desktop.kyu.succeedingKyuCommandResult
import com.kyuchestration.desktop.terminal.SessionConversationChoice
import com.kyuchestration.desktop.terminal.SessionMode
import com.kyuchestration.desktop.terminal.SessionTarget
import com.kyuchestration.desktop.terminal.TerminalSessionFailure
import java.io.IOException
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class KyuCliSessionCommandSourceTest {

    @Test
    fun `레포 세션은 그 이름을 인자로 받는다`() {
        val runner = RecordingKyuCommandRunner { succeedingKyuCommandResult(REPO_SESSION_DOCUMENT) }

        KyuCliSessionCommandSource(runner).sessionCommandFor(WORK_DIR_PATH, SessionTarget.Repo("proj-a"), CONTINUE)

        assertEquals(listOf(listOf("session-command", "proj-a", "--json")), runner.receivedArguments)
    }

    @Test
    fun `메인 세션은 레포 이름 자리를 비운다`() {
        val runner = RecordingKyuCommandRunner { succeedingKyuCommandResult(REPO_SESSION_DOCUMENT) }

        KyuCliSessionCommandSource(runner).sessionCommandFor(WORK_DIR_PATH, SessionTarget.Main, CONTINUE)

        // 메인 세션은 이름이 아니라 인자가 없는 것으로 가리킨다. main 을 붙이면 main 이라는
        // 이름의 레포를 찾다가 "없는 레포입니다" 로 끝난다.
        assertEquals(listOf(listOf("session-command", "--json")), runner.receivedArguments)
    }

    @Test
    fun `챗 화면은 챗 모드로 묻는다`() {
        val runner = RecordingKyuCommandRunner { succeedingKyuCommandResult(REPO_SESSION_DOCUMENT) }

        KyuCliSessionCommandSource(runner, SessionMode.Chat)
            .sessionCommandFor(WORK_DIR_PATH, SessionTarget.Repo("proj-a"), CONTINUE)

        // 붙는 것은 이 한 낱말뿐이다. 어떤 플래그로 옮겨지는지는 엔진만 안다(설계 원칙 11).
        assertEquals(listOf(listOf("session-command", "proj-a", "--chat", "--json")), runner.receivedArguments)
    }

    @Test
    fun `모드를 말하지 않으면 지금까지의 터미널을 묻는다`() {
        // 기본값이 바뀌면 3 단계 전의 앱이 PTY 안에서 JSON 을 그리게 된다.
        val runner = RecordingKyuCommandRunner { succeedingKyuCommandResult(REPO_SESSION_DOCUMENT) }

        KyuCliSessionCommandSource(runner).sessionCommandFor(WORK_DIR_PATH, SessionTarget.Main, CONTINUE)

        assertEquals(listOf(listOf("session-command", "--json")), runner.receivedArguments)
    }

    @Test
    fun `묻는 자리는 워크디렉토리다`() {
        val runner = RecordingKyuCommandRunner { succeedingKyuCommandResult(REPO_SESSION_DOCUMENT) }

        KyuCliSessionCommandSource(runner).sessionCommandFor(WORK_DIR_PATH, SessionTarget.Main, CONTINUE)

        // 이 명령은 경로 인자를 받지 않는다. 자리를 주지 않으면 앱을 띄운 디렉토리가 워크디렉토리로 읽힌다.
        assertEquals(listOf<Path?>(WORK_DIR_PATH), runner.receivedWorkingDirectories)
    }

    @Test
    fun `kyu 가 거절하면 그 이유를 그대로 올린다`() {
        val runner = RecordingKyuCommandRunner {
            KyuCommandResult(exitCode = 1, standardOutput = "", standardError = "없는 레포입니다: proj-z\n")
        }

        val failure = assertFailsWith<TerminalSessionFailure.SessionCommandRefused> {
            KyuCliSessionCommandSource(runner).sessionCommandFor(WORK_DIR_PATH, SessionTarget.Repo("proj-z"), CONTINUE)
        }

        assertEquals(1, failure.exitCode)
        assertEquals("proj-z", failure.targetLabel)
        assertEquals("없는 레포입니다: proj-z", failure.guidance)
    }

    @Test
    fun `거절 이유가 비어 있으면 손으로 다시 부를 명령을 알려준다`() {
        val runner = RecordingKyuCommandRunner {
            KyuCommandResult(exitCode = 1, standardOutput = "", standardError = "")
        }

        val failure = assertFailsWith<TerminalSessionFailure.SessionCommandRefused> {
            KyuCliSessionCommandSource(runner).sessionCommandFor(WORK_DIR_PATH, SessionTarget.Main, CONTINUE)
        }

        // 라벨(main)로 문구를 지어내면 실제로는 부를 수 없는 명령을 알려 주게 된다 —
        // kyu session-command main --json 은 main 이라는 이름의 레포를 찾는다.
        assertEquals("워크디렉토리에서 kyu session-command --json 를 직접 실행해 이유를 확인하세요.", failure.guidance)
    }

    @Test
    fun `부를 kyu 가 없으면 세션 진입 실패로 옮긴다`() {
        val runner = RecordingKyuCommandRunner { throw KyuCommandFailure.ExecutableNotFound() }

        assertFailsWith<TerminalSessionFailure.KyuExecutableNotFound> {
            KyuCliSessionCommandSource(runner).sessionCommandFor(WORK_DIR_PATH, SessionTarget.Main, CONTINUE)
        }
    }

    @Test
    fun `kyu 를 띄우지 못하면 세션 진입 실패로 옮긴다`() {
        val runner = RecordingKyuCommandRunner {
            throw KyuCommandFailure.FailedToStart(IOException("permission denied"))
        }

        assertFailsWith<TerminalSessionFailure.KyuFailedToStart> {
            KyuCliSessionCommandSource(runner).sessionCommandFor(WORK_DIR_PATH, SessionTarget.Main, CONTINUE)
        }
    }

    @Test
    fun `새로 시작을 고르면 기록을 버리라는 옵션이 붙는다`() {
        val runner = RecordingKyuCommandRunner { succeedingKyuCommandResult(REPO_SESSION_DOCUMENT) }

        KyuCliSessionCommandSource(runner).sessionCommandFor(
            WORK_DIR_PATH,
            SessionTarget.Repo("proj-a"),
            SessionConversationChoice.StartNewConversation,
        )

        // 앱이 기록 파일을 직접 지우지 않는다. 버리는 일은 엔진의 것이고, 앱이 하는 말은
        // 이 옵션 하나다(설계 문서 5.5.4 의 마지막 줄).
        assertEquals(
            listOf(listOf("session-command", "proj-a", "--forget-conversation", "--json")),
            runner.receivedArguments,
        )
    }

    private companion object {
        val WORK_DIR_PATH: Path = Path.of("/home/me/work/WorkDir-featureX")

        /** 카드를 그냥 누른 자리. 옵션이 붙지 않는 쪽이 이 표면의 기본이다. */
        val CONTINUE = SessionConversationChoice.ContinueRecordedConversation

        val REPO_SESSION_DOCUMENT = """
            {
              "schemaVersion": 1,
              "command": ["claude", "--session-id", "211f6974-88a8-4453-9248-a02b0d6febae"],
              "cwd": "/home/me/work/WorkDir-featureX/proj-a",
              "env": {}
            }
        """.trimIndent()
    }
}
