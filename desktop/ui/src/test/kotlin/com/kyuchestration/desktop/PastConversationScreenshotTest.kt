package com.kyuchestration.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.use
import com.kyuchestration.desktop.terminal.SessionTarget
import com.kyuchestration.desktop.terminal.chat.ChatConversation
import com.kyuchestration.desktop.terminal.chat.ChatEntry
import com.kyuchestration.desktop.terminal.chat.PastTurnContent
import com.kyuchestration.desktop.terminal.chat.SessionTree
import com.kyuchestration.desktop.theme.KyuTheme
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertTrue
import org.jetbrains.skia.EncodedImageFormat

/**
 * 이어온 대화가 깔린 채팅 창을 라이트 · 다크로 그린다(app-owned-sessions-design.md 5.5.8).
 *
 * 그리다 죽지 않는지(모든 지난 갈래가 그릴 자리를 갖는지)는 언제나 본다. 사람이 눈으로 볼 그림은 [SCREENSHOT_DIRECTORY_VARIABLE]
 * 을 줬을 때만 남긴다 — 시험이 저장소 안에 파일을 만들지 않게.
 *
 * ```
 * KYU_SCREENSHOT_DIR=/tmp/shots ./gradlew :ui:test --tests '*PastConversationScreenshotTest'
 * ```
 */
class PastConversationScreenshotTest {

    @Test
    fun `이어온 대화를 라이트와 다크로 그린다`() {
        for (darkTheme in listOf(false, true)) {
            val encoded = render(darkTheme)
            assertTrue(encoded.isNotEmpty())
            System.getenv(SCREENSHOT_DIRECTORY_VARIABLE)?.takeIf { it.isNotBlank() }?.let { directory ->
                val path = Path.of(directory).resolve(if (darkTheme) "past-conversation-dark.png" else "past-conversation-light.png")
                Files.createDirectories(path.parent)
                Files.write(path, encoded)
                println("그림: $path")
            }
        }
    }

    private fun render(darkTheme: Boolean): ByteArray =
        ImageComposeScene(width = 900, height = 760) {
            KyuTheme(darkTheme = darkTheme) {
                // 앱의 셸이 까는 바탕과 같다(KyuchestrationDesktopScreen). 이것이 없으면 다크의 글자가 흰 바탕에 그려진다.
                Surface(modifier = Modifier.fillMaxSize()) {
                ChatConversationPane(
                    chatScreenState = ChatScreenState.ChatOnScreen(SessionTarget.Main, CONVERSATION),
                    diagnosticLogPathLabel = "~/.local/state/kyuchestration/core.log",
                    onSendUserMessageRequested = {},
                    onInterruptTurnRequested = {},
                    onPermissionChoiceMade = { _, _ -> },
                    onEndSessionRequested = {},
                    onStartNewConversationRequested = {},
                    onConversationChosen = { _, _ -> },
                    onConversationChoiceCancelled = {},
                    sessionTree = SessionTree(),
                    onOpenChildSessionRequested = {},
                )
                }
            }
        }.use { scene ->
            // 첫 그리기 뒤에 목록이 맨 아래로 내려간다(ChatTranscript 의 LaunchedEffect). 그 뒤의 그림이 사람이 처음 보는 것이다.
            repeat(FRAMES_UNTIL_SETTLED) { frame -> scene.render(frame * FRAME_NANOS) }
            val image = scene.render(FRAMES_UNTIL_SETTLED * FRAME_NANOS)
            checkNotNull(image.encodeToData(EncodedImageFormat.PNG)).bytes
        }

    private companion object {
        const val SCREENSHOT_DIRECTORY_VARIABLE = "KYU_SCREENSHOT_DIR"
        const val FRAME_NANOS = 16_000_000L
        const val FRAMES_UNTIL_SETTLED = 6

        val PAST_TURNS: List<ChatEntry> = buildList {
            // 앞부분은 스크롤 위로 밀려나는 몫이다. 창이 열리면 마지막 몇 개와 이번 세션이 보여야 한다.
            repeat(30) { index ->
                add(ChatEntry.PastTurn(PastTurnContent.UserSaid("앞선 질문 $index"), Instant.parse("2026-10-30T01:00:00Z")))
                add(ChatEntry.PastTurn(PastTurnContent.AssistantSaid("앞선 답 $index"), Instant.parse("2026-10-30T01:00:05Z")))
            }
            add(ChatEntry.PastTurn(PastTurnContent.UserSaid("이 대화의 암호 단어는 보라색코끼리 다. 알겠다고만 답해."), Instant.parse("2026-11-01T09:06:18Z")))
            add(ChatEntry.PastTurn(PastTurnContent.AssistantSaid("알겠습니다. **보라색코끼리** 를 기억하겠습니다."), Instant.parse("2026-11-01T09:06:20Z")))
            add(ChatEntry.PastTurn(PastTurnContent.ToolUsed("Bash", "ls -la desktop/"), Instant.parse("2026-11-01T09:06:21Z")))
            add(ChatEntry.PastTurn(PastTurnContent.LinesOmitted(3)))
            add(ChatEntry.PastTurn(PastTurnContent.SystemNoted("[Request interrupted by user]"), Instant.parse("2026-11-01T09:06:30Z")))
        }

        val CONVERSATION = ChatConversation(
            target = SessionTarget.Main,
            workingDirectory = "/home/me/work/WorkDir-featureX",
            resumedConversationId = "654ce605-b030-40e8-8448-2e9003ad32b1",
            entries = PAST_TURNS + listOf(
                ChatEntry.EngineNotice("대화 기록을 읽지 못해 버렸습니다"),
                ChatEntry.UserSaid("이 대화의 암호 단어가 뭐였지? 그 낱말만 답해."),
                ChatEntry.AssistantSaid("보라색코끼리"),
            ),
            pastTurnsOmittedBefore = 933,
        )
    }
}
