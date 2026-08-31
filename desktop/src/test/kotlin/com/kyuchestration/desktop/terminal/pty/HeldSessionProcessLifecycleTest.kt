package com.kyuchestration.desktop.terminal.pty

import com.kyuchestration.desktop.terminal.EmbeddedTerminalStateHolder
import com.kyuchestration.desktop.terminal.SessionCommandAnswer
import com.kyuchestration.desktop.terminal.SessionCommandSource
import com.kyuchestration.desktop.terminal.SessionConversationChoice
import com.kyuchestration.desktop.terminal.SessionTarget
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel

/**
 * 앱이 보유한 세션의 프로세스가 언제 살고 언제 죽는지를 진짜 프로세스로 잰다.
 *
 * 상태 홀더의 단위 시험은 가짜 통로로 "닫으라고 시켰는가" 까지만 말한다. **이 전환의 약속은 그보다
 * 아래에 있다** — 카드를 옮겨도 앞의 `claude` 가 계속 돌아야 하고, 앱을 끄면 하나도 남지 않아야
 * 한다(설계 문서 7.1 의 8·9 번). 그 둘은 pid 로만 확인할 수 있다.
 *
 * 그래서 여기서는 진짜 상태 홀더와 진짜 PTY 어댑터를 붙여 두고, `claude` 자리에만 기록 스텁을
 * 놓는다(SessionProcessRecording).
 */
class HeldSessionProcessLifecycleTest {

    private val temporaryDirectory = createTempDirectory("kyu-held-session-test")
    private val holderScope = CoroutineScope(Dispatchers.Default)
    private val holder = EmbeddedTerminalStateHolder(
        sessionTerminalOpener = PtySessionTerminalOpener(
            sessionCommandSource = SessionCommandSource { _, target, _ -> answerFor(target) },
            baseEnvironment = mapOf("PATH" to System.getenv("PATH")),
        ),
        coroutineScope = holderScope,
    )

    @AfterTest
    fun 남은_세션과_임시_디렉토리를_치운다() {
        holder.endAllSessions()
        holderScope.cancel()
        temporaryDirectory.toFile().deleteRecursively()
    }

    @Test
    fun `카드를 옮겨도 앞 세션의 프로세스가 계속 돈다`() {
        val firstProcessId = enterSessionAndWaitForItsProcess(SessionTarget.Repo("proj-a"))

        val secondProcessId = enterSessionAndWaitForItsProcess(SessionTarget.Repo("proj-b"))

        // 이 전환의 존재 이유다. 옮길 때마다 앞 세션을 끝내면 사용자가 카드를 한 번 잘못 눌러
        // 하던 작업을 잃고, 그것을 막아 주던 tmux 는 이제 이 경로에 없다(설계 문서 8 절 3 번).
        assertTrue(processIsAlive(firstProcessId), "화면에서 내려간 세션도 계속 돌아야 한다 (pid $firstProcessId)")
        assertTrue(processIsAlive(secondProcessId), "지금 보고 있는 세션이 돌아야 한다 (pid $secondProcessId)")
    }

    @Test
    fun `보고 있는 세션만 끝내면 나머지는 계속 돈다`() {
        val keptProcessId = enterSessionAndWaitForItsProcess(SessionTarget.Repo("proj-a"))
        val endedProcessId = enterSessionAndWaitForItsProcess(SessionTarget.Repo("proj-b"))

        holder.endSession()

        assertTrue(waitUntilProcessIsGone(endedProcessId), "보고 있던 세션은 끝나야 한다 (pid $endedProcessId)")
        assertTrue(processIsAlive(keptProcessId), "보고 있지 않던 세션은 그대로 돌아야 한다 (pid $keptProcessId)")
    }

    @Test
    fun `앱을 끄면 보유하던 프로세스가 하나도 남지 않는다`() {
        val firstProcessId = enterSessionAndWaitForItsProcess(SessionTarget.Repo("proj-a"))
        val secondProcessId = enterSessionAndWaitForItsProcess(SessionTarget.Main)

        // 창을 닫을 때 앱이 부르는 것이 이것이다(Main.kt 의 onCloseRequest).
        holder.endAllSessions()

        // 남으면 kyu list 에도 앱에도 보이지 않는 프로세스가 되고, 찾을 수 있는 자리가 ps 뿐이다.
        assertTrue(waitUntilProcessIsGone(firstProcessId), "pid $firstProcessId 가 남아 있으면 안 된다")
        assertTrue(waitUntilProcessIsGone(secondProcessId), "pid $secondProcessId 가 남아 있으면 안 된다")
    }

    private fun enterSessionAndWaitForItsProcess(target: SessionTarget): Long {
        holder.enterSession(temporaryDirectory, target, SessionConversationChoice.ContinueRecordedConversation)
        return waitForSessionReport(reportPathFor(target)).processId
    }

    /**
     * 세션마다 자기 자리에서 자기 기록을 남긴다.
     *
     * 엔진이 답하는 것과 같은 모양이다 — 레포 세션은 그 레포 디렉토리에서, 메인 세션은
     * 워크디렉토리에서 뜬다.
     */
    private fun answerFor(target: SessionTarget): SessionCommandAnswer = SessionCommandAnswer(
        command = recordAndStayAliveCommand(reportPathFor(target)),
        workingDirectory = when (target) {
            is SessionTarget.Main -> temporaryDirectory
            is SessionTarget.Repo -> temporaryDirectory.resolve(target.repoName).createDirectories()
        },
        environmentToAdd = emptyMap(),
    )

    private fun reportPathFor(target: SessionTarget): Path =
        temporaryDirectory.resolve("${target.label}-세션이-받은-것.txt")
}
