package com.kyuchestration.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kyuchestration.desktop.platform.runningOnMac
import com.kyuchestration.desktop.theme.ThemePreference

/**
 * 셸의 왼쪽 기둥. 워크디렉토리 하나를 두고 할 수 있는 일들이 여기 모인다.
 *
 * **`NavigationRail` 을 쓰지 않는다.** Material 3 의 그것은 화면을 갈아 끼우는 목적지들을 위한
 * 것이고 고른 항목에 표시가 남는다. 여기 있는 것은 목적지가 아니라 **하는 일**이다 — 눌러도
 * 화면이 바뀌지 않고 대화상자가 뜨거나 목록이 다시 읽힌다. 목적지용 부품을 쓰면 고른 표시가
 * 붙었다 곧 사라지고, 사용자는 자기가 어디에 있는지를 그 표시에서 읽으려다 헛읽는다.
 *
 * 지금 어느 세션을 보고 있는가는 옆의 세션 패널이 말한다. 그것이 이 앱의 유일한 "어디" 다.
 */
@Composable
internal fun SessionNavigationRail(
    diagnosticLogPathLabel: String,
    themePreference: ThemePreference,
    onThemePreferenceChosen: (ThemePreference) -> Unit,
    onOpenAnotherWorkDirRequested: () -> Unit,
    onCloneRepositoriesRequested: () -> Unit,
    onRefreshRequested: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxHeight().width(RAIL_WIDTH),
    ) {
        Column(
            modifier = Modifier.fillMaxHeight().padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            RailAction(
                label = "워크디렉토리",
                onClick = onOpenAnotherWorkDirRequested,
                icon = { tint -> WorkDirFolderIcon(tint) },
            )
            RailAction(
                label = "클론",
                onClick = onCloneRepositoriesRequested,
                icon = { tint -> CloneDownloadIcon(tint) },
            )
            RailAction(
                label = "새로고침",
                onClick = onRefreshRequested,
                icon = { tint -> RefreshArcIcon(tint) },
            )

            // 아래쪽은 앱 자신에 대한 것들이다. 워크디렉토리를 다루는 위쪽과 사이를 벌려 두면
            // 사용자가 그 경계를 배우지 않아도 눈이 먼저 나눈다.
            Box(modifier = Modifier.weight(1f))

            DiagnosticLogRailAction(diagnosticLogPathLabel)
            ThemeRailAction(themePreference, onThemePreferenceChosen)
        }
    }
}

/**
 * 기록 파일이 어디 있는지를 늘 물어볼 수 있는 자리.
 *
 * 그 경로는 지금까지 오류가 난 자리에만 떴다. 그런데 사용자가 기록을 찾는 때는 대개 오류 화면이
 * 이미 지나간 뒤다 — 세션이 이상했는데 화면에는 아무 말도 없는 경우가 그렇다. 여기 두면 그때도
 * 건넬 것을 찾을 수 있다.
 *
 * 파일을 열어 주지는 않는다. 어느 프로그램으로 열지는 이 앱이 정할 일이 아니고, 사용자가 하려는
 * 것은 대개 여는 것이 아니라 **보내는 것**이라 경로가 그 일의 전부다.
 */
@Composable
private fun DiagnosticLogRailAction(diagnosticLogPathLabel: String) {
    var showingPath by remember { mutableStateOf(false) }

    Box {
        RailAction(
            label = "로그",
            onClick = { showingPath = true },
            icon = { tint -> DiagnosticLogIcon(tint) },
        )
        DropdownMenu(expanded = showingPath, onDismissRequest = { showingPath = false }) {
            Column(
                modifier = Modifier.widthIn(max = POPUP_MAX_WIDTH).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("앱이 남기는 기록", style = MaterialTheme.typography.titleSmall)
                DiagnosticLogPathNotice(diagnosticLogPathLabel)
                Text(
                    text = "다른 머신에서만 나는 오류를 알릴 때 이 파일 하나를 건네면 됩니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 화면을 어둡게 볼지 밝게 볼지 고르는 자리.
 *
 * **레일 아래쪽에 두는 것이 뜻이다.** 워크디렉토리를 다루는 위쪽과 달리 이것은 앱 자신에
 * 대한 것이고, 하루에 한 번 누를까 말까 한 자리다.
 *
 * 고른 것은 앱을 끄면 사라진다(ThemePreference). 기본값이 목업이 확정한 다크라 대부분의
 * 실행에서 여기를 열 이유가 없다.
 */
@Composable
private fun ThemeRailAction(
    themePreference: ThemePreference,
    onThemePreferenceChosen: (ThemePreference) -> Unit,
) {
    var showingChoices by remember { mutableStateOf(false) }

    Box {
        RailAction(
            label = "설정",
            onClick = { showingChoices = true },
            icon = { tint -> SettingsSlidersIcon(tint) },
        )
        DropdownMenu(expanded = showingChoices, onDismissRequest = { showingChoices = false }) {
            Text(
                text = "테마",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
            )
            ThemePreference.entries.forEach { choice ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = choice == themePreference, onClick = null)
                            Spacer(Modifier.width(8.dp))
                            Text(choice.label, style = MaterialTheme.typography.bodyMedium)
                        }
                    },
                    onClick = {
                        onThemePreferenceChosen(choice)
                        showingChoices = false
                    },
                )
            }
            Column(
                modifier = Modifier.widthIn(max = POPUP_MAX_WIDTH).padding(horizontal = 16.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // 재어 보고 아는 사실을 화면이 먼저 말한다(원칙 12). 리눅스에서 이 갈래를 고르면
                // 아무 일도 일어나지 않는데, 그 이유를 화면 밖에 두면 사용자는 앱이 고장 났다고 읽는다.
                if (!runningOnMac()) {
                    Text(
                        text = "이 플랫폼은 시스템 테마를 답하지 않습니다 — 시스템 따르기를 고르면 라이트로 뜹니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = "라이트는 아직 목업이 확정하기 전 색입니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private val ThemePreference.label: String
    get() = when (this) {
        ThemePreference.AlwaysDark -> "다크"
        ThemePreference.AlwaysLight -> "라이트"
        ThemePreference.FollowSystem -> "시스템 따르기"
    }

/**
 * @param icon 색을 받아 그리는 그림. 눌리는 상태에 따라 색이 달라질 자리라 색을 밖에서 준다.
 */
@Composable
private fun RailAction(
    label: String,
    onClick: () -> Unit,
    icon: @Composable (Color) -> Unit,
) {
    val tint = MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp)
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        icon(tint)
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * 레일의 폭.
 *
 * 80dp 는 Material 3 이 내비게이션 레일에 정해 둔 폭이다. 우리 부품이 그것이 아니어도 폭까지
 * 다르게 둘 이유는 없다 — 그림 20dp 와 두 글자 남짓의 이름이 그 안에 든다.
 */
private val RAIL_WIDTH = 80.dp

private val POPUP_MAX_WIDTH = 420.dp
