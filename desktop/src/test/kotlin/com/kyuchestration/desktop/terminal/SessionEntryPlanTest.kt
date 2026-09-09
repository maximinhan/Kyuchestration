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
        val plan = planFor(answer = answerWith(environmentToAdd = mapOf("KYU_APP_SESSION" to "엔진이-정한-값")))

        // 앱이 다시 덮으면 계약이 "더하라" 고 답한 것이 조용히 무시된다. 실제로 엔진이 이 표식을
        // 답하는 일은 없지만, 순서를 시험으로 적어 두지 않으면 다음 사람이 반대로 놓는다.
        assertEquals("엔진이-정한-값", plan.environment["KYU_APP_SESSION"])
    }

    @Test
    fun `터미널 종류는 이제 정하지 않는다`() {
        val plan = planFor(baseEnvironment = mapOf("TERM" to "dumb"))

        // xterm-256color 로 덮던 자리였다. 그 근거는 "앱 안의 JediTerm 이 흉내 내는 것이 xterm
        // 계열이다" 였고, 터미널이 사라지면서 근거가 통째로 없어졌다 — 파이프에 물린 claude 앞에는
        // 그릴 터미널이 없다. 없는 터미널을 있다고 말하지 않고 물려받은 값을 그대로 넘긴다.
        assertEquals("dumb", plan.environment["TERM"])
    }

    @Test
    fun `사용자의 tmux 안에서 앱을 띄웠어도 그 표식은 자식에게 넘기지 않는다`() {
        val plan = planFor(baseEnvironment = mapOf("TMUX" to "/tmp/tmux-1000/default,1234,0"))

        // GUI 프로세스가 어디서 떴는지에 대한 사실이지 앱이 띄운 자식의 사실이 아니다. 그대로
        // 넘기면 claude 가 자기가 tmux 안에 있다고 믿는다(claude --help 의 --tmux).
        assertFalse("TMUX" in plan.environment)
    }

    @Test
    fun `앱이 보유한 세션임을 자식 환경에 남긴다`() {
        val plan = planFor(target = SessionTarget.Repo("proj-a"))

        // 앱이 죽었는데 살아남은 자식은 앱 어디에도 보이지 않는다. 그때 그 프로세스를 찾을 수
        // 있는 자리가 ps 뿐이라 표식을 남긴다(app-owned-sessions-design.md 8 절 1 번).
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

    @Test
    fun `맡아 둔 claude 토큰을 세션 환경에 싣는다`() {
        val plan = planFor(claudeAuthToken = "sk-ant-oat01-맡아-둔-가짜")

        // 브라우저 로그인이 막힌 머신에서 세션이 사는 길이 이 한 줄이다. 이름이 어긋나면 앱은
        // 토큰을 맡아 두고도 세션마다 "Not logged in" 을 만나고, 그 어긋남은 화면에 뜨지 않는다.
        assertEquals("sk-ant-oat01-맡아-둔-가짜", plan.environment["CLAUDE_CODE_OAUTH_TOKEN"])
    }

    @Test
    fun `맡아 둔 것이 없으면 그 환경변수를 아예 두지 않는다`() {
        val plan = planFor(claudeAuthToken = null)

        // 빈 값을 실으면 claude 는 "자격 증명이 있는데 거절당했다"(401)로 끝난다. 실제 사실은
        // "앱이 맡아 둔 것이 없다" 이고, 그때 claude 는 자기 자격 증명(브라우저 로그인이 남긴
        // 것)을 봐야 한다.
        assertFalse("CLAUDE_CODE_OAUTH_TOKEN" in plan.environment)
    }

    @Test
    fun `엔진의 답은 맡아 둔 토큰보다도 위다`() {
        val plan = planFor(
            answer = answerWith(environmentToAdd = mapOf("CLAUDE_CODE_OAUTH_TOKEN" to "엔진이-정한-값")),
            claudeAuthToken = "sk-ant-oat01-맡아-둔-가짜",
        )

        // 엔진이 이 변수를 답하는 일은 없다 — 문서에 비밀을 싣지 않기로 한 것이 그 이유다
        // (ClaudeAuthTokenEnvironment). 그래도 순서를 시험으로 적어 두는 이유는, 답하기 시작하는
        // 날 그 결정이 조용히 무시되면 안 되기 때문이다.
        assertEquals("엔진이-정한-값", plan.environment["CLAUDE_CODE_OAUTH_TOKEN"])
    }

    private fun answerWith(
        command: List<String> = listOf("claude"),
        workingDirectory: Path = WORK_DIR_PATH,
        environmentToAdd: Map<String, String> = emptyMap(),
    ) = SessionCommandAnswer(command, workingDirectory, environmentToAdd, resumedConversationId = null)

    private fun planFor(
        answer: SessionCommandAnswer = answerWith(),
        baseEnvironment: Map<String, String> = emptyMap(),
        target: SessionTarget = SessionTarget.Repo("proj-a"),
        claudeAuthToken: String? = null,
    ) = planSessionEntry(
        sessionCommandAnswer = answer,
        baseEnvironment = baseEnvironment,
        workDirPath = WORK_DIR_PATH,
        target = target,
        claudeAuthToken = claudeAuthToken,
    )

    private companion object {
        val WORK_DIR_PATH: Path = Path.of("/home/me/work/WorkDir-featureX")
    }
}
