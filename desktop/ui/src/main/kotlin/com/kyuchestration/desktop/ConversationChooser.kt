package com.kyuchestration.desktop

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.kyuchestration.desktop.terminal.PersonOpenedSessionTarget
import com.kyuchestration.desktop.terminal.SessionConversationChoice
import com.kyuchestration.desktop.terminal.chat.ExistingConversation
import com.kyuchestration.desktop.terminal.chat.SessionEntryState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 고르기 화면의 한 줄. 사람이 이 줄을 고르면 [choice] 가 코어로 간다.
 *
 * @param details 제목 아래 작은 줄들 — 마지막 활동과 시작 시각.
 */
internal data class ConversationChoiceOption(
    val choice: SessionConversationChoice,
    val title: String,
    val details: List<String>,
)

/**
 * 코어가 준 고르기를 사람이 읽을 줄로 옮긴다. 바깥 대화들, 앱에서 하던 대화, 새 대화 순이다.
 *
 * 무엇을 미리 고를지, 어느 대화가 후보인지는 정하지 않는다 — 그것은 코어의 규칙이다(SessionEntryState.ChoosingConversation).
 *
 * @param zone 시각을 보일 시간대. 화면 머신의 것이다 — 코어(WSL)와 화면(윈도우)의 시간대가 다를 수 있다.
 */
internal fun conversationChoiceOptions(
    choosing: SessionEntryState.ChoosingConversation,
    zone: ZoneId,
): List<ConversationChoiceOption> {
    val outsideConversations = choosing.candidates.map { conversation ->
        ConversationChoiceOption(
            choice = SessionConversationChoice.AdoptConversation(conversation.id),
            title = conversation.shownTitle,
            details = conversation.timeDetails(zone),
        )
    }
    val recordedConversation = choosing.recorded?.let { recorded ->
        ConversationChoiceOption(
            choice = SessionConversationChoice.ContinueRecordedConversation,
            title = "앱에서 하던 대화 · ${recorded.shownTitle}",
            details = recorded.timeDetails(zone),
        )
    }
    val newConversation = ConversationChoiceOption(
        choice = SessionConversationChoice.StartNewConversation,
        title = "새 대화",
        details = emptyList(),
    )
    return outsideConversations + listOfNotNull(recordedConversation) + newConversation
}

/** 제목이 비면 id 앞자리로 가른다 — 빈 줄 둘이 나란히 서면 사람이 고를 근거가 없다. */
private val ExistingConversation.shownTitle: String
    get() = title.ifBlank { id.take(UNTITLED_ID_PREFIX_LENGTH) }

private fun ExistingConversation.timeDetails(zone: ZoneId): List<String> =
    listOf("마지막 활동 ${localTimeLabel(lastActiveAt, zone)}") +
        listOfNotNull(startedAt?.let { "시작 ${localTimeLabel(it, zone)}" })

private fun localTimeLabel(instant: Instant, zone: ZoneId): String = LOCAL_TIME_FORMAT.format(instant.atZone(zone))

private val LOCAL_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

private const val UNTITLED_ID_PREFIX_LENGTH = 8

/**
 * 이어가기를 누른 세션 자리에 서는 고르기(app-owned-sessions-design.md 5.5.7). 앱에 적힌 것보다 최근인 바깥 대화가 있을 때만
 * 코어가 이 자리를 세운다.
 *
 * Enter 가 "이어가기" 다. 기본 선택이 가장 최근 대화라, 터미널에서 하던 것을 그대로 잇고 싶은 사람은 Enter 한 번이면 된다.
 */
@Composable
internal fun ConversationChooser(
    target: PersonOpenedSessionTarget,
    choosing: SessionEntryState.ChoosingConversation,
    onConversationChosen: (PersonOpenedSessionTarget, SessionConversationChoice) -> Unit,
    onConversationChoiceCancelled: (PersonOpenedSessionTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    val options = remember(choosing) { conversationChoiceOptions(choosing, ZoneId.systemDefault()) }
    var selectedChoice by remember(choosing) { mutableStateOf(choosing.defaultChoice) }
    val chooserFocus = remember { FocusRequester() }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .widthIn(max = CHOOSER_MAX_WIDTH)
                .padding(32.dp)
                .focusRequester(chooserFocus)
                .focusable()
                // 미리 보기로 받는다. 줄마다 고를 수 있는 자리라 포커스가 줄에 가면 그 줄이 Enter 를 먼저 먹고, 뒤에서 받는
                // 처리로는 그 Enter 가 여기 닿지 않는다 — Enter 는 포커스가 어디 있든 "이어가기" 다.
                .onPreviewKeyEvent { event ->
                    if (event.key == Key.Enter && event.type == KeyEventType.KeyDown) {
                        onConversationChosen(target, selectedChoice)
                        true
                    } else {
                        false
                    }
                },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("이 폴더에서 하던 대화가 있습니다", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "${target.label} 세션을 앱 밖(터미널)에서 이어 온 대화로 열 수 있습니다. 고른 대화가 이 세션의 대화로 적힙니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .selectableGroup(),
            ) {
                options.forEach { option ->
                    ConversationChoiceRow(
                        option = option,
                        selected = option.choice == selectedChoice,
                        onSelected = { selectedChoice = option.choice },
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(onClick = { onConversationChoiceCancelled(target) }) { Text("취소") }
                Button(onClick = { onConversationChosen(target, selectedChoice) }) { Text("이어가기") }
            }
        }
    }

    // 고르기가 서자마자 Enter 가 닿게 한다. 포커스가 없으면 키가 이 자리에 오지 않는다.
    LaunchedEffect(choosing) { chooserFocus.requestFocus() }
}

@Composable
private fun ConversationChoiceRow(
    option: ConversationChoiceOption,
    selected: Boolean,
    onSelected: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelected, role = Role.RadioButton)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 줄 전체가 누르는 자리라 버튼 자체의 클릭은 받지 않는다 — 둘 다 받으면 한 번 누름이 두 번 읽힌다.
        RadioButton(selected = selected, onClick = null)
        Column(modifier = Modifier.padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(option.title, style = MaterialTheme.typography.bodyMedium)
            option.details.forEach { detail ->
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private val CHOOSER_MAX_WIDTH = 560.dp
