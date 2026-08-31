package com.kyuchestration.desktop

import com.jediterm.terminal.TtyConnector
import com.jediterm.terminal.ui.JediTermWidget
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider
import java.awt.CardLayout
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * 보유한 세션마다 터미널 위젯 하나를 살려 두고, 그중 한 장을 보여준다.
 *
 * **위젯이 곧 화면 모델이다**(설계 문서 1.3). 셀 격자·터미널 모드 상태·커서·스크롤백을 쥔 것이
 * 이 객체라, 카드를 옮길 때 버렸다가 돌아와서 다시 만들면 화면이 비어 있다 — `claude` 는 아무도
 * 묻지 않으면 자기 화면을 다시 그리지 않는다. 감독 설계의 관문이 걸린 자리가 정확히 그것이었고,
 * 이 전환이 그 문제를 피하는 방식이 "만들지 않고 이미 있는 것을 살려 두는 것" 이다.
 *
 * **CardLayout 한 장에 모아 둔다.** 화면을 옮길 때 Compose 쪽 노드를 갈아 끼우면 Swing 컴포넌트가
 * 부모를 옮겨 다니게 되는데, 여기서는 컨테이너 하나가 늘 같은 자리에 있고 그 안에서 보이는 카드만
 * 바뀐다. 감춰진 카드도 컴포넌트 계층에 남아 있어 자기 통로를 계속 읽는다 — 아무도 읽지 않으면
 * PTY 버퍼가 차고 그 안의 `claude` 가 쓰기에서 멎는다.
 *
 * **통로를 열쇠로 쓴다.** 같은 카드라도 세션이 끝난 뒤 다시 누르면 새 프로세스와 새 통로가 생기고
 * 그때는 위젯도 새것이어야 한다. 라벨을 열쇠로 두면 죽은 세션의 화면이 새 세션 자리에 남는다.
 *
 * 상태 홀더가 아니라 화면 쪽에 둔다. JediTermWidget 은 Swing 컴포넌트라 화면 없는 곳에서는 폰트
 * 계측부터 걸리고, 홀더가 이것을 만들면 그 홀더를 시험할 때마다 화면이 필요해진다.
 */
class HeldSessionTerminalWidgets {

    private val cardLayout = CardLayout()

    /** 터미널 자리가 그리는 컴포넌트. 세션마다 카드 한 장이고 보이는 것은 한 장뿐이다. */
    val terminalContainer: JPanel = JPanel(cardLayout)

    private val cardsByConnector = LinkedHashMap<TtyConnector, SessionCard>()

    private var nextCardNumber = 0

    fun showSession(ttyConnector: TtyConnector) {
        val card = cardsByConnector.getOrPut(ttyConnector) { addCardFor(ttyConnector) }
        cardLayout.show(terminalContainer, card.name)

        // 키를 받을 자리를 이 터미널로 옮긴다. 카드를 누른 사람이 바라는 다음 동작은 타이핑이지
        // 터미널을 한 번 더 누르는 것이 아니다.
        //
        // 지금 부르면 아무 일도 일어나지 않는다 — SwingPanel 이 컨테이너를 창에 붙이는 것은 이
        // 합성이 끝난 뒤이고, 창에 붙지 않은 컴포넌트는 포커스를 받지 못한다.
        SwingUtilities.invokeLater { card.widget.requestFocusInWindow() }
    }

    /**
     * 앱이 더 이상 보유하지도, 화면에 보여주지도 않는 세션의 위젯을 버린다.
     *
     * 화면에서 내려간 것과 보유가 끝난 것은 다르다. 앞의 것은 살려 두어야 하고(그것이 이 클래스의
     * 존재 이유다), 뒤의 것은 통로가 이미 닫혀 읽을 것이 없는 죽은 격자다.
     */
    fun dropWidgetsOtherThan(keptConnectors: Set<TtyConnector>) {
        val droppedConnectors = cardsByConnector.keys - keptConnectors
        droppedConnectors.forEach { connector ->
            val card = cardsByConnector.remove(connector) ?: return@forEach
            terminalContainer.remove(card.widget)
            card.widget.close()
        }
        if (droppedConnectors.isNotEmpty()) {
            terminalContainer.revalidate()
            terminalContainer.repaint()
        }
    }

    private fun addCardFor(ttyConnector: TtyConnector): SessionCard {
        val widget = JediTermWidget(DefaultSettingsProvider()).apply {
            setTtyConnector(ttyConnector)
            // 읽기 스레드를 띄운다. 이것을 부르기 전까지 위젯은 통로에서 아무것도 읽지 않는다.
            start()
        }

        val card = SessionCard(name = (nextCardNumber++).toString(), widget = widget)
        terminalContainer.add(widget, card.name)
        // 이미 창에 붙어 있는 컨테이너에 카드를 더하는 길이 있다 — 두 번째 세션부터가 그렇다.
        // 다시 재라고 말하지 않으면 Swing 이 옛 배치를 그대로 들고 있어 새 카드가 크기 0 으로 남는다.
        terminalContainer.revalidate()
        return card
    }

    private class SessionCard(val name: String, val widget: JediTermWidget)
}
