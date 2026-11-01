// 키 사건을 짓는 생성자가 compose-ui 내부 API 다. 공개 길(ui-test 의 performKeyInput)은 이 저장소에 없는 의존을 더해야 해서,
// 시험 하나를 위해 그것을 들이기보다 같은 판의 compose 에 묶인 이 생성자를 시험 안에서만 쓴다.
@file:OptIn(InternalComposeUiApi::class)

package com.kyuchestration.desktop

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.use
import com.kyuchestration.desktop.terminal.PersonOpenedSessionTarget
import com.kyuchestration.desktop.terminal.SessionConversationChoice
import com.kyuchestration.desktop.terminal.SessionTarget
import com.kyuchestration.desktop.terminal.chat.ExistingConversation
import com.kyuchestration.desktop.terminal.chat.SessionEntryState
import com.kyuchestration.desktop.theme.KyuTheme
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 고르기 화면에서 Enter 가 어디에 포커스가 있든 "이어가기" 인지 — 화면을 진짜로 그리고 키를 보내 본다.
 *
 * 줄마다 고를 수 있는(포커스를 받는) 자리라, 줄에 포커스가 간 채 누른 Enter 를 그 줄이 먹으면 바깥의 Enter 처리가
 * 그것을 받지 못한다. 그 길은 화면을 그리지 않고는 갈리지 않는다.
 */
class ConversationChooserKeyTest {

    @Test
    fun `포커스가 고르기에 막 섰을 때 Enter 는 기본 선택으로 이어간다`() {
        val chosen = renderChooserAndPress(tabsBeforeEnter = 0)

        assertEquals(listOf<Pair<PersonOpenedSessionTarget, SessionConversationChoice>>(SessionTarget.Main to CHOOSING.defaultChoice), chosen)
    }

    @Test
    fun `줄로 포커스를 옮긴 뒤 누른 Enter 도 이어가기다`() {
        val chosen = renderChooserAndPress(tabsBeforeEnter = 1)

        assertEquals(listOf<Pair<PersonOpenedSessionTarget, SessionConversationChoice>>(SessionTarget.Main to CHOOSING.defaultChoice), chosen)
    }

    private fun renderChooserAndPress(tabsBeforeEnter: Int): List<Pair<PersonOpenedSessionTarget, SessionConversationChoice>> {
        val chosen = mutableListOf<Pair<PersonOpenedSessionTarget, SessionConversationChoice>>()
        ImageComposeScene(width = 900, height = 620) {
            KyuTheme(darkTheme = true) {
                ConversationChooser(SessionTarget.Main, CHOOSING, onConversationChosen = { target, choice -> chosen += target to choice }, onConversationChoiceCancelled = {})
            }
        }.use { scene ->
            // 첫 그리기 뒤에 LaunchedEffect 가 포커스를 고르기에 세운다.
            scene.render(0)
            scene.render(FRAME_NANOS)
            repeat(tabsBeforeEnter) {
                scene.sendKeyEvent(KeyEvent(Key.Tab, KeyEventType.KeyDown, codePoint = '\t'.code))
                scene.sendKeyEvent(KeyEvent(Key.Tab, KeyEventType.KeyUp, codePoint = '\t'.code))
                scene.render(FRAME_NANOS * 2)
            }
            scene.sendKeyEvent(KeyEvent(Key.Enter, KeyEventType.KeyDown, codePoint = '\n'.code))
            scene.sendKeyEvent(KeyEvent(Key.Enter, KeyEventType.KeyUp, codePoint = '\n'.code))
            scene.render(FRAME_NANOS * 3)
        }
        return chosen
    }

    private companion object {
        const val FRAME_NANOS = 16_000_000L

        val CHOOSING = SessionEntryState.ChoosingConversation(
            candidates = listOf(
                ExistingConversation("654ce605-b030-40e8-8448-2e9003ad32b1", "터미널 대화", null, Instant.parse("2026-11-01T09:00:56Z")),
                ExistingConversation("1f0c2a7e-0000-4000-8000-000000000001", "", null, Instant.parse("2026-10-30T01:00:00Z")),
            ),
            recorded = null,
            defaultChoice = SessionConversationChoice.AdoptConversation("654ce605-b030-40e8-8448-2e9003ad32b1"),
        )
    }
}
