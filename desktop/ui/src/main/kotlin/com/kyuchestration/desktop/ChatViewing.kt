package com.kyuchestration.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.kyuchestration.desktop.terminal.PersonOpenedSessionTarget
import com.kyuchestration.desktop.terminal.SessionTarget
import com.kyuchestration.desktop.terminal.chat.ChatConversation
import com.kyuchestration.desktop.terminal.chat.SessionEntryState

/**
 * 화면이 보는 세션 하나(core-ui-split-design.md 6.8 (가) · 원칙 21). 코어는 대화와 진입 상태를 세션마다 맵으로 내놓고,
 * 어느 것을 볼지는 이 자리가 정한다.
 *
 * **맵을 통째로 읽지 않는다.** 대화 맵은 어느 세션의 사건에서든 바뀐다 — 보지 않는 하위의 글자 조각마다. 그 맵을 읽는
 * 자리는 [derivedStateOf] 안뿐이라, 화면은 보는 세션의 몫이 바뀔 때만 다시 그려진다(바뀌지 않은 세션의 대화는 같은 객체라
 * 견주기가 참조 비교로 끝난다).
 *
 * @param dismissSessionEntryFailure 떠나는 세션의 진입 실패를 코어에서 닫는 명령([entryFailureLeftBehind]).
 */
@Stable
class ChatViewing(
    private val chatConversations: State<Map<SessionTarget, ChatConversation>>,
    private val chatSessionEntries: State<Map<PersonOpenedSessionTarget, SessionEntryState>>,
    private val dismissSessionEntryFailure: (PersonOpenedSessionTarget) -> Unit,
) {

    private var chosenTarget by mutableStateOf<SessionTarget?>(null)

    /** 사람이 고른 세션 중 아직 볼 것(대화나 진입 상태)이 있는 것. 사라지면 null 로 접힌다. */
    val viewedTarget: SessionTarget? by derivedStateOf {
        viewedTargetStillThere(chosenTarget, chatConversations.value, chatSessionEntries.value)
    }

    val screenState: ChatScreenState by derivedStateOf {
        chatScreenStateFor(viewedTarget, chatConversations.value, chatSessionEntries.value)
    }

    /** 보는 세션을 옮기는 길은 이것 하나다. 떠나는 세션이 띄우지 못한 세션이면 그 실패를 닫는다. */
    fun moveTo(nextTarget: SessionTarget?) {
        entryFailureLeftBehind(viewedTarget, nextTarget, chatSessionEntries.value)?.let(dismissSessionEntryFailure)
        chosenTarget = nextTarget
    }

    /**
     * 고른 세션이 사라졌으면 고른 것도 놓는다. 접힌 값만 두면 같은 이름(다른 워크디렉토리의 child-1)이 다시 생기는 순간
     * 사람이 고르지 않은 화면으로 건너간다.
     */
    internal fun forgetVanishedChoice() {
        if (viewedTarget == null) {
            chosenTarget = null
        }
    }
}

@Composable
fun rememberChatViewing(
    chatConversations: State<Map<SessionTarget, ChatConversation>>,
    chatSessionEntries: State<Map<PersonOpenedSessionTarget, SessionEntryState>>,
    dismissSessionEntryFailure: (PersonOpenedSessionTarget) -> Unit,
): ChatViewing {
    val viewing = remember(chatConversations, chatSessionEntries) {
        ChatViewing(chatConversations, chatSessionEntries, dismissSessionEntryFailure)
    }
    LaunchedEffect(viewing) {
        snapshotFlow { viewing.viewedTarget }.collect { viewing.forgetVanishedChoice() }
    }
    return viewing
}
