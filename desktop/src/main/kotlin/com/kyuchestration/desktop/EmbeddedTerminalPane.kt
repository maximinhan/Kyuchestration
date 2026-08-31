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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jediterm.terminal.TtyConnector
import com.jediterm.terminal.ui.JediTermWidget
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider
import com.kyuchestration.desktop.terminal.EmbeddedTerminalState
import com.kyuchestration.desktop.terminal.TerminalSessionFailure
import javax.swing.SwingUtilities

/**
 * 세션 하나가 앱 안에서 보이는 자리.
 */
@Composable
fun EmbeddedTerminalPane(
    terminalState: EmbeddedTerminalState,
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
                SessionTerminal(terminalState.ttyConnector)

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
        // 두 가지를 버튼을 누르기 전에 말한다 — 이 세션은 앱이 보유하므로 끝내는 길이 여기뿐이고,
        // 그래서 터미널에서 kyu list 를 쳐도 이 세션은 보이지 않는다(설계 원칙 12).
        //
        // 세션이 떠 있을 때만 말한다. 진입에 실패한 자리에서는 끝낼 세션이 아예 없어서,
        // 그때도 같은 문구를 띄우면 없는 것을 있다고 말하게 된다.
        if (terminalState is EmbeddedTerminalState.SessionOnScreen) {
            Spacer(Modifier.width(12.dp))
            Text(
                text = "이 세션은 앱이 보유합니다 — 끝내면 되돌릴 수 없고, kyu list 에는 보이지 않습니다",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.weight(1f))

        TextButton(onClick = onEndSessionRequested) { Text("세션 끝내기") }
    }
}

/**
 * JediTerm 의 터미널 위젯을 Compose 안에 끼운다.
 *
 * 위젯은 Swing 컴포넌트라 Compose 가 직접 그리지 못한다. SwingPanel 이 그 자리를 비워 두고 실제
 * 컴포넌트를 그 위에 얹는다.
 *
 * 통로 하나에 위젯 하나다. remember 의 키가 통로인 이유가 그것이다 — 다른 세션으로 옮겨가면
 * 통로가 바뀌고, 그때 위젯도 새로 만들어야 이전 세션의 화면이 남지 않는다.
 */
@Composable
private fun SessionTerminal(ttyConnector: TtyConnector) {
    val terminalWidget = remember(ttyConnector) {
        JediTermWidget(DefaultSettingsProvider()).apply { setTtyConnector(ttyConnector) }
    }

    DisposableEffect(terminalWidget) {
        // 읽기 스레드를 띄운다. 이것을 부르기 전까지 화면은 통로에서 아무것도 읽지 않는다.
        terminalWidget.start()

        // 키를 받을 자리를 이 터미널로 옮긴다. 카드를 누른 사람이 바라는 다음 동작은 타이핑이지
        // 터미널을 한 번 더 누르는 것이 아니다.
        //
        // 지금 부르면 아무 일도 일어나지 않는다 — SwingPanel 이 이 컴포넌트를 창에 붙이는 것은
        // 이 합성이 끝난 뒤이고, 창에 붙지 않은 컴포넌트는 포커스를 받지 못한다.
        SwingUtilities.invokeLater { terminalWidget.requestFocusInWindow() }

        onDispose {
            // 위젯이 화면에서 사라진다는 것은 이 세션을 더 보지 않는다는 뜻이다. 상태 홀더가 이미
            // 통로를 닫았더라도 여기서 한 번 더 닫는 것은 해가 없다 — 프로세스 종료는 멱등이다.
            terminalWidget.close()
        }
    }

    SwingPanel(
        factory = { terminalWidget },
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
