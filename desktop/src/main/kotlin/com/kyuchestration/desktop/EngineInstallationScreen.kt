package com.kyuchestration.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kyuchestration.desktop.engine.EngineInstallationState

/**
 * 엔진이 없는 채로 앱을 띄웠을 때 가장 먼저 뜨는 화면.
 *
 * 설치 패키지에는 엔진이 함께 들어 있으므로(desktop/build.gradle.kts 의 appResourcesRootDir),
 * 이 화면을 보는 사람은 소스에서 띄웠거나 동봉된 엔진을 쓸 수 없는 경우다. 그래서 이 화면은
 * 사라지지 않고 남는다 — 동봉이 닿지 않는 자리가 있는 한, 앱 안에서 엔진을 놓는 길도 있어야 한다.
 *
 * 여기서 막는 것이 아니라 여기서 끝낸다. 앱이 시작점이라면 "터미널을 열어 kyu 를 먼저 설치하고
 * 오라" 는 안내는 시작점을 터미널로 되돌리는 말이다. 그래서 이 화면의 중심은 설명이 아니라
 * 버튼 하나다.
 *
 * 터미널로 넣는 길도 함께 적어 둔다. 지우는 것이 아니라 남겨 두는 이유는, 프록시 뒤에 있거나
 * 특정 판을 골라 넣어야 하는 사람이 있기 때문이다 — 그 사람에게 이 화면은 막다른 길이 된다.
 *
 * @param engineDirectoryLabel 받아 둘 자리. 누르기 전에 밝힌다 — 앱이 이 머신에 실행 파일을 하나
 *   놓는 일이라, 어디에 놓는지는 누르고 나서 알 것이 아니다.
 */
@Composable
internal fun EngineInstallationScreen(
    versionLabel: String,
    engineDirectoryLabel: String,
    engineInstallationState: EngineInstallationState,
    onInstallEngineRequested: () -> Unit,
    onLookForEngineAgainRequested: () -> Unit,
) {
    val installing = engineInstallationState == EngineInstallationState.InstallationRunning

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("뀨케스트레이션", style = MaterialTheme.typography.headlineMedium)
        Text(
            text = versionLabel,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))

        Text("엔진(kyu)이 아직 없습니다", style = MaterialTheme.typography.headlineSmall)
        Text(
            text = "이 앱은 워크디렉토리 스캔 · 세션 생성 · 레포 클론을 직접 하지 않고 엔진인 kyu 에게 " +
                "시킵니다. 최신 릴리스에서 이 머신에 맞는 것을 받아 놓겠습니다.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = EXPLANATION_MAX_WIDTH),
        )
        Text(
            text = "설치 패키지(dmg · deb)로 받은 앱에는 엔진이 함께 들어 있습니다. " +
                "이 화면은 소스에서 띄웠거나 동봉된 엔진을 쓸 수 없을 때 뜹니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = EXPLANATION_MAX_WIDTH),
        )
        Text(
            text = "받아 둘 자리: $engineDirectoryLabel",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onInstallEngineRequested, enabled = !installing) { Text("엔진 설치") }
            // 아래의 한 줄로 직접 넣은 사람을 위한 자리. 이것이 없으면 그 사람은 앱을 다시 띄워야
            // 하고, 그러면 "터미널로도 된다" 는 안내가 반쪽이 된다.
            TextButton(onClick = onLookForEngineAgainRequested, enabled = !installing) { Text("다시 찾기") }
        }

        InstallationProgressOrFailure(engineInstallationState)

        Spacer(Modifier.height(8.dp))
        HorizontalDivider(modifier = Modifier.widthIn(max = EXPLANATION_MAX_WIDTH))

        Text(
            text = "kyu 는 세션을 tmux 위에서 돌립니다. tmux 가 없으면 세션이 뜨지 않으므로 먼저 넣으세요 — " +
                "WSL · 리눅스는 sudo apt install tmux, 맥은 brew install tmux 입니다. " +
                "앱이 대신 넣지 않습니다: 시스템 패키지라 관리자 권한이 필요합니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = EXPLANATION_MAX_WIDTH),
        )
        Text(
            text = "터미널이 편하면 이 한 줄로도 넣을 수 있습니다:",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "curl -fsSL https://raw.githubusercontent.com/maximinhan/Kyuchestration/main/install.sh | bash",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = EXPLANATION_MAX_WIDTH),
        )
    }
}

/**
 * 받는 동안과 받지 못했을 때 보일 것.
 *
 * 실패는 두 줄이다 — 무슨 일이 있었는가와 지금 무엇을 할 수 있는가. 뒤엣줄이 실패 타입마다
 * 다른 것이 이 화면의 요점이다. 망이 막힌 사람과 윈도우 네이티브에서 띄운 사람이 같은 말을
 * 들으면, 둘 중 하나는 하지 않아도 될 일을 하게 된다.
 */
@Composable
private fun InstallationProgressOrFailure(engineInstallationState: EngineInstallationState) {
    when (engineInstallationState) {
        // 아직 누르지 않았다.
        EngineInstallationState.EngineMissing -> Unit

        // 엔진이 있으면 이 화면 자체가 뜨지 않는다.
        EngineInstallationState.EngineReady -> Unit

        EngineInstallationState.InstallationRunning ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp))
                Text("최신 릴리스에서 받는 중입니다", style = MaterialTheme.typography.bodySmall)
            }

        is EngineInstallationState.InstallationFailed ->
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = engineInstallationState.failure.message.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = EXPLANATION_MAX_WIDTH),
                )
                Text(
                    text = engineInstallationState.failure.guidance,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = EXPLANATION_MAX_WIDTH),
                )
            }
    }
}

/**
 * 설명 글이 창 폭을 다 쓰지 않게 묶는다. 1100 dp 창에서 한 줄이 끝까지 늘어나면 눈이 다음 줄
 * 첫머리를 찾지 못한다.
 */
private val EXPLANATION_MAX_WIDTH = 560.dp
