package com.kyuchestration.desktop.terminal

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SessionEntryPlanTest {

    @Test
    fun `레포 세션은 그 이름을 attach 의 인자로 받는다`() {
        val plan = planFor(SessionTarget.Repo("proj-a"))

        assertEquals(listOf(KYU_PATH.toString(), "attach", "proj-a"), plan.attachCommand)
    }

    @Test
    fun `메인 세션의 attach 인자는 main 이다`() {
        val plan = planFor(SessionTarget.Main)

        assertEquals(listOf(KYU_PATH.toString(), "attach", "main"), plan.attachCommand)
    }

    @Test
    fun `레포 세션은 그 이름을 start 의 인자로도 받는다`() {
        val plan = planFor(SessionTarget.Repo("proj-a"))

        assertEquals(listOf(KYU_PATH.toString(), "start", "proj-a"), plan.startCommand)
    }

    @Test
    fun `메인 세션의 start 는 인자가 없다`() {
        val plan = planFor(SessionTarget.Main)

        // kyu start 는 인자 없는 실행을 메인 세션으로 읽는다. attach 처럼 main 을 붙이면
        // main 이라는 이름의 레포를 찾다가 "없는 레포입니다" 로 끝난다 — 두 명령의 문법이
        // 여기서만 갈라지므로 이 시험이 그 사실을 붙잡아 둔다.
        assertEquals(listOf(KYU_PATH.toString(), "start"), plan.startCommand)
    }

    @Test
    fun `두 명령 모두 워크디렉토리에서 실행한다`() {
        val plan = planFor(SessionTarget.Repo("proj-a"))

        // kyu start 와 kyu attach 는 경로 인자를 받지 않고 실행된 디렉토리를 워크디렉토리로 읽는다.
        assertEquals(WORK_DIR_PATH, plan.workingDirectory)
    }

    @Test
    fun `터미널 종류를 xterm-256color 로 못 박는다`() {
        val plan = planFor(SessionTarget.Repo("proj-a"), parentEnvironment = mapOf("TERM" to "dumb"))

        assertEquals("xterm-256color", plan.environment["TERM"])
    }

    @Test
    fun `부모에게 붙어 있던 TMUX 는 자식에게 넘기지 않는다`() {
        val plan = planFor(
            SessionTarget.Repo("proj-a"),
            parentEnvironment = mapOf("TMUX" to "/tmp/tmux-1000/default,1234,0"),
        )

        // 이 변수가 남아 있으면 kyu attach 가 "이 터미널은 이미 세션 안입니다" 로 거절한다.
        // GUI 를 tmux 안에서 띄웠을 때 물려받은 것일 뿐, 앱 안의 PTY 는 세션 밖이다.
        assertFalse("TMUX" in plan.environment)
    }

    @Test
    fun `나머지 부모 환경은 그대로 물려준다`() {
        val plan = planFor(
            SessionTarget.Repo("proj-a"),
            parentEnvironment = mapOf("PATH" to "/usr/bin", "HOME" to "/home/me"),
        )

        assertEquals("/usr/bin", plan.environment["PATH"])
        assertEquals("/home/me", plan.environment["HOME"])
    }

    private fun planFor(
        target: SessionTarget,
        parentEnvironment: Map<String, String> = emptyMap(),
    ) = planSessionEntry(
        kyuExecutablePath = KYU_PATH,
        workDirPath = WORK_DIR_PATH,
        target = target,
        parentEnvironment = parentEnvironment,
    )

    private companion object {
        val KYU_PATH: Path = Path.of("/home/me/.local/bin/kyu")
        val WORK_DIR_PATH: Path = Path.of("/home/me/work/WorkDir-featureX")
    }
}
