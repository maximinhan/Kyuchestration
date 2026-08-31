package com.kyuchestration.desktop.terminal

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SessionEntryPlanTest {

    @Test
    fun `엔진이 답한 argv 를 손대지 않고 그대로 띄운다`() {
        val plan = planFor(
            answer = answerWith(
                command = listOf("claude", "--session-id", "d1b9d634", "--add-dir", "/work/proj-a"),
            ),
        )

        // 한 글자라도 고치면 조립 지식이 두 곳에 있게 된다(설계 원칙 11). 이 시험이 그 선을 지킨다.
        assertEquals(listOf("claude", "--session-id", "d1b9d634", "--add-dir", "/work/proj-a"), plan.command)
    }

    @Test
    fun `엔진이 답한 자리에서 띄운다`() {
        val plan = planFor(answer = answerWith(workingDirectory = Path.of("/work/WorkDir-featureX/proj-a")))

        // 워크디렉토리가 아니라 세션의 cwd 다. 레포 세션은 그 레포 디렉토리에서 떠야 .mcp.json 과
        // CLAUDE.md 가 살아난다.
        assertEquals(Path.of("/work/WorkDir-featureX/proj-a"), plan.workingDirectory)
    }

    @Test
    fun `엔진이 더하라고 한 환경을 얹는다`() {
        val plan = planFor(
            answer = answerWith(environmentToAdd = mapOf("CLAUDE_CODE_ADDITIONAL_DIRECTORIES_CLAUDE_MD" to "1")),
            baseEnvironment = mapOf("PATH" to "/usr/bin"),
        )

        assertEquals("1", plan.environment["CLAUDE_CODE_ADDITIONAL_DIRECTORIES_CLAUDE_MD"])
        assertEquals("/usr/bin", plan.environment["PATH"])
    }

    @Test
    fun `엔진의 답이 앱의 손질보다 위다`() {
        val plan = planFor(answer = answerWith(environmentToAdd = mapOf("TERM" to "엔진이-정한-값")))

        // 앱이 다시 덮으면 계약이 "더하라" 고 답한 것이 조용히 무시된다. 실제로 엔진이 TERM 을
        // 답하는 일은 없지만, 순서를 시험으로 적어 두지 않으면 다음 사람이 반대로 놓는다.
        assertEquals("엔진이-정한-값", plan.environment["TERM"])
    }

    @Test
    fun `터미널 종류를 xterm-256color 로 고정한다`() {
        val plan = planFor(baseEnvironment = mapOf("TERM" to "dumb"))

        // 사이에 tmux 가 없어져서 이 값을 claude 가 직접 읽는다.
        assertEquals("xterm-256color", plan.environment["TERM"])
    }

    @Test
    fun `부모에게 붙어 있던 세션 표식은 자식에게 넘기지 않는다`() {
        val plan = planFor(
            baseEnvironment = mapOf(
                "TMUX" to "/tmp/tmux-1000/default,1234,0",
                "KYU_SESSION" to "kyu-WorkDir-featureX-proj-a",
            ),
        )

        // 이 값들은 GUI 프로세스가 어디서 떴는지에 대한 사실이지 앱 안 PTY 의 사실이 아니다.
        // 그대로 넘기면 claude 가 자기가 tmux 안에 있다고 믿는다(claude --help 의 --tmux).
        assertFalse("TMUX" in plan.environment)
        assertFalse("KYU_SESSION" in plan.environment)
    }

    @Test
    fun `앱이 보유한 세션임을 자식 환경에 남긴다`() {
        val plan = planFor(target = SessionTarget.Repo("proj-a"))

        // 앱이 죽었는데 SIGHUP 을 무시하고 살아남은 자식은 kyu list 에도 앱에도 보이지 않는다.
        // 그때 그 프로세스를 찾을 수 있는 자리가 ps 뿐이라 표식을 남긴다(설계 문서 8 절 1 번).
        assertEquals("WorkDir-featureX-proj-a", plan.environment["KYU_APP_SESSION"])
    }

    @Test
    fun `메인 세션의 표식은 라벨이 main 이다`() {
        val plan = planFor(target = SessionTarget.Main)

        assertEquals("WorkDir-featureX-main", plan.environment["KYU_APP_SESSION"])
    }

    @Test
    fun `나머지 바탕 환경은 그대로 물려준다`() {
        val plan = planFor(baseEnvironment = mapOf("PATH" to "/usr/bin", "HOME" to "/home/me"))

        assertEquals("/usr/bin", plan.environment["PATH"])
        assertEquals("/home/me", plan.environment["HOME"])
    }

    private fun answerWith(
        command: List<String> = listOf("claude"),
        workingDirectory: Path = WORK_DIR_PATH,
        environmentToAdd: Map<String, String> = emptyMap(),
    ) = SessionCommandAnswer(command, workingDirectory, environmentToAdd)

    private fun planFor(
        answer: SessionCommandAnswer = answerWith(),
        baseEnvironment: Map<String, String> = emptyMap(),
        target: SessionTarget = SessionTarget.Repo("proj-a"),
    ) = planSessionEntry(
        sessionCommandAnswer = answer,
        baseEnvironment = baseEnvironment,
        workDirPath = WORK_DIR_PATH,
        target = target,
    )

    private companion object {
        val WORK_DIR_PATH: Path = Path.of("/home/me/work/WorkDir-featureX")
    }
}
