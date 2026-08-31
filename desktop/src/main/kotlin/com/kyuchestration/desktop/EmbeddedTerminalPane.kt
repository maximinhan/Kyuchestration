package com.kyuchestration.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jediterm.terminal.TtyConnector
import com.kyuchestration.desktop.terminal.EmbeddedTerminalState
import com.kyuchestration.desktop.terminal.TerminalSessionFailure

/**
 * 앱이 보유한 세션 중 지금 보고 있는 하나가 앱 안에서 보이는 자리.
 */
@Composable
fun EmbeddedTerminalPane(
    terminalState: EmbeddedTerminalState,
    heldSessionTerminalWidgets: HeldSessionTerminalWidgets,
    onEndSessionRequested: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        EmbeddedTerminalHeader(terminalState, onEndSessionRequested)

        when (terminalState) {
            // 이 자리가 그려지는 것은 화면이 터미널을 보여주기로 한 뒤다. 그 판단은 상태 홀더가
            // 이미 했으므로 여기서는 아무것도 그리지 않는다.
            is EmbeddedTerminalState.NoTerminalOpen -> Unit

            is EmbeddedTerminalState.SessionEntryRunning ->
                CenteredNotice {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Text(
                        text = "${terminalState.target.label} 세션을 띄우는 중입니다",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

            is EmbeddedTerminalState.SessionOnScreen ->
                SessionTerminal(terminalState.ttyConnector, heldSessionTerminalWidgets)

            // 끝난 세션도 같은 위젯으로 그린다. 마지막 화면이 사용자가 왜 끝났는지 아는 유일한
            // 자리이고, 머리말이 그 위에 한 줄을 덧붙인다.
            is EmbeddedTerminalState.SessionEndedOnScreen ->
                SessionTerminal(terminalState.ttyConnector, heldSessionTerminalWidgets)

            is EmbeddedTerminalState.SessionEntryFailed ->
                SessionEntryFailureNotice(terminalState.failure)
        }
    }
}

@Composable
private fun EmbeddedTerminalHeader(
    terminalState: EmbeddedTerminalState,
    onEndSessionRequested: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = terminalState.target?.label.orEmpty(),
            style = MaterialTheme.typography.titleSmall,
            fontFamily = FontFamily.Monospace,
        )

        headerNotice(terminalState)?.let { notice ->
            Spacer(Modifier.width(12.dp))
            Text(
                text = notice,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.weight(1f))

        TextButton(onClick = onEndSessionRequested) { Text("세션 끝내기") }
    }
}

/**
 * 머리말이 세션에 대해 하는 말.
 *
 * 세션이 실제로 있을 때만 말한다. 진입에 실패한 자리에서는 끝낼 세션이 아예 없어서, 그때도 같은
 * 문구를 띄우면 없는 것을 있다고 말하게 된다.
 */
private fun headerNotice(terminalState: EmbeddedTerminalState): String? = when (terminalState) {
    // 버튼을 누르기 전에 두 가지를 말한다 — 이 세션은 앱이 보유하므로 끝내는 길이 여기뿐이고,
    // 그래서 터미널에서 kyu list 를 쳐도 이 세션은 보이지 않는다(설계 원칙 12).
    is EmbeddedTerminalState.SessionOnScreen ->
        "이 세션은 앱이 보유합니다 — 끝내면 되돌릴 수 없고, kyu list 에는 보이지 않습니다"

    is EmbeddedTerminalState.SessionEndedOnScreen ->
        "세션이 끝났습니다${terminalState.exitCode?.let { " (종료 코드 $it)" }.orEmpty()} — " +
            "카드를 다시 누르면 그 대화를 이어서 새로 엽니다"

    is EmbeddedTerminalState.NoTerminalOpen,
    is EmbeddedTerminalState.SessionEntryRunning,
    is EmbeddedTerminalState.SessionEntryFailed,
    -> null
}

/**
 * JediTerm 의 터미널 위젯을 Compose 안에 끼운다.
 *
 * 위젯은 Swing 컴포넌트라 Compose 가 직접 그리지 못한다. SwingPanel 이 그 자리를 비워 두고 실제
 * 컴포넌트를 그 위에 얹는다.
 *
 * **위젯을 여기서 만들지도 닫지도 않는다.** 위젯의 수명은 화면에 보이는 동안이 아니라 앱이 그
 * 세션을 보유하는 동안이고(HeldSessionTerminalWidgets), 여기서 닫으면 카드를 옮겼다 돌아왔을 때
 * 화면이 비어 있다.
 */
@Composable
private fun SessionTerminal(
    ttyConnector: TtyConnector,
    heldSessionTerminalWidgets: HeldSessionTerminalWidgets,
) {
    DisposableEffect(ttyConnector) {
        heldSessionTerminalWidgets.showSession(ttyConnector)
        // 화면에서 내려가는 것은 이 세션을 끝낸다는 뜻이 아니다. 치울 것이 없다.
        onDispose { }
    }

    SwingPanel(
        // 컨테이너는 늘 같은 것 하나다. 보이는 카드가 바뀌어도 Swing 컴포넌트가 부모를 옮겨
        // 다니지 않는다.
        factory = { heldSessionTerminalWidgets.terminalContainer },
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun SessionEntryFailureNotice(failure: TerminalSessionFailure) {
    CenteredNotice {
        Text(
            text = failure.message.orEmpty(),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            text = failure.guidance,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun CenteredNotice(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            content()
        }
    }
}
