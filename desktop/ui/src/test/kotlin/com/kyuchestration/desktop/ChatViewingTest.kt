package com.kyuchestration.desktop

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import com.kyuchestration.desktop.protocol.toFailureView
import com.kyuchestration.desktop.terminal.PersonOpenedSessionTarget
import com.kyuchestration.desktop.terminal.SessionTarget
import com.kyuchestration.desktop.terminal.TerminalSessionFailure
import com.kyuchestration.desktop.terminal.chat.ChatConversation
import com.kyuchestration.desktop.terminal.chat.SessionEntryState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * 화면이 보는 세션 하나를 쥐는 자리(core-ui-split-design.md 6.8 (가)).
 *
 * 코어는 대화를 세션마다 맵 하나로 내놓는다. 화면이 그 맵을 통째로 읽으면 보지 않는 하위의 글자 조각마다 창 전체가 다시
 * 그려진다 — 홀더가 보이는 대화 하나만 내놓던 때는 없던 일이다. 그래서 다시 그려지는 횟수를 진짜 컴포지션으로 센다.
 * Compose UI 시험 도구 없이 런타임만으로 세운다(화면도 창도 없다).
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ChatViewingTest {

    @Test
    fun `보지 않는 세션의 대화가 바뀌어도 화면은 다시 그리지 않고 보는 세션이 바뀌면 다시 그린다`() = runTest {
        val bed = viewingBed(conversations = mapOf(MAIN to MAIN_CONVERSATION, CHILD to CHILD_CONVERSATION))
        bed.change { bed.viewing.moveTo(MAIN) }
        val recompositionsWhileViewingMain = bed.recompositions

        bed.change { bed.conversations.value += CHILD to CHILD_CONVERSATION.copy(streamingText = "보지 않는 하위의 글자 조각") }

        assertEquals(recompositionsWhileViewingMain, bed.recompositions)

        val changedMain = MAIN_CONVERSATION.copy(streamingText = "보는 메인의 글자 조각")
        bed.change { bed.conversations.value += MAIN to changedMain }

        assertEquals(recompositionsWhileViewingMain + 1, bed.recompositions)
        assertEquals(ChatScreenState.ChatOnScreen(MAIN, changedMain), bed.viewing.screenState)
    }

    @Test
    fun `보던 세션이 사라지면 보는 세션이 저절로 비고 다시 생겨도 돌아가지 않는다`() = runTest {
        // 워크디렉토리를 닫으면 대화가 전부 놓인다. 보는 자리를 손으로 비우지 않아도 그 순간 빈다 — 그리고 다른
        // 워크디렉토리에서 같은 이름(child-1)이 다시 생겨도 사람이 고르지 않은 화면으로 건너가지 않는다.
        val bed = viewingBed(conversations = mapOf(MAIN to MAIN_CONVERSATION, CHILD to CHILD_CONVERSATION))
        bed.change { bed.viewing.moveTo(CHILD) }

        bed.change { bed.conversations.value = emptyMap() }

        assertEquals(null, bed.viewing.viewedTarget)
        assertEquals(ChatScreenState.NoChatOpen, bed.viewing.screenState)

        bed.change { bed.conversations.value = mapOf(MAIN to MAIN_CONVERSATION, CHILD to CHILD_CONVERSATION) }

        assertEquals(null, bed.viewing.viewedTarget)
    }

    @Test
    fun `띄우지 못한 세션을 떠나면 그 실패를 닫는다`() = runTest {
        val failed = SessionEntryState.Failed(TerminalSessionFailure.KyuExecutableNotFound().toFailureView())
        val bed = viewingBed(conversations = mapOf(MAIN to MAIN_CONVERSATION), entries = mapOf(REPO to failed))
        bed.change { bed.viewing.moveTo(REPO) }

        bed.change { bed.viewing.moveTo(MAIN) }

        assertEquals(listOf(REPO), bed.dismissedEntryFailures)
    }

    private fun TestScope.viewingBed(
        conversations: Map<SessionTarget, ChatConversation>,
        entries: Map<PersonOpenedSessionTarget, SessionEntryState> = emptyMap(),
    ): ViewingBed {
        val bed = ViewingBed(mutableStateOf(conversations), mutableStateOf(entries), this)
        bed.compose()
        return bed
    }

    /**
     * 창 없이 도는 컴포지션 하나. 보는 세션의 화면을 읽고, 다시 그려질 때마다 센다.
     *
     * 프레임은 시험이 보낸다 — 상태를 바꾼 뒤 [change] 가 스냅숏을 알리고 프레임 하나를 보내 그 바뀜이 다시 그리기로
     * 이어질 기회를 준다.
     */
    private class ViewingBed(
        val conversations: MutableState<Map<SessionTarget, ChatConversation>>,
        val entries: MutableState<Map<PersonOpenedSessionTarget, SessionEntryState>>,
        private val testScope: TestScope,
    ) {
        private val frameClock = BroadcastFrameClock()
        private val recomposer = Recomposer(testScope.backgroundScope.coroutineContext + frameClock)
        val dismissedEntryFailures = mutableListOf<PersonOpenedSessionTarget>()
        var recompositions = 0
            private set
        lateinit var viewing: ChatViewing

        fun compose() {
            testScope.backgroundScope.launch(frameClock) { recomposer.runRecomposeAndApplyChanges() }
            Composition(UnitApplier, recomposer).setContent { ViewedChat() }
            sendFrame()
        }

        @Composable
        private fun ViewedChat() {
            viewing = rememberChatViewing(conversations, entries, dismissedEntryFailures::add)
            viewing.screenState
            SideEffect { recompositions++ }
        }

        fun change(change: () -> Unit) {
            Snapshot.withMutableSnapshot(change)
            sendFrame()
        }

        private fun sendFrame() {
            Snapshot.sendApplyNotifications()
            testScope.runCurrent()
            frameClock.sendFrame(0L)
            testScope.runCurrent()
        }
    }

    private object UnitApplier : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) = Unit

        override fun insertBottomUp(index: Int, instance: Unit) = Unit

        override fun remove(index: Int, count: Int) = Unit

        override fun move(from: Int, to: Int, count: Int) = Unit

        override fun onClear() = Unit
    }

    private companion object {
        val MAIN: PersonOpenedSessionTarget = SessionTarget.Main
        val REPO: PersonOpenedSessionTarget = SessionTarget.Repo("proj-a")
        val CHILD: SessionTarget = SessionTarget.Child("child-1")
        val MAIN_CONVERSATION = ChatConversation(target = SessionTarget.Main)
        val CHILD_CONVERSATION = ChatConversation(target = SessionTarget.Child("child-1"))
    }
}
