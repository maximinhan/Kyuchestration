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
import com.kyuchestration.desktop.engine.EngineInstallationFailure
import com.kyuchestration.desktop.engine.EngineInstallationState

/**
 * 엔진이 없는 채로 앱을 띄웠을 때 가장 먼저 뜨는 화면.
 *
 * 엔진이 닿는 길이 둘이다. 설치 패키지에는 함께 들어 있고(desktop/build.gradle.kts 의
 * appResourcesRootDir), 소스에서 띄울 때도 Go 가 있으면 옆 Go 모듈에서 만들어 넣는다(같은 파일의
 * run 게이트). 이 화면을 보는 사람은 그 둘 다 닿지 않은 자리에 있다 — 그래서 이 화면은 사라지지
 * 않고 남는다.
 *
 * 여기서 막는 것이 아니라 여기서 끝낸다. 그것도 누르게 하지 않고 끝낸다 — 화면이 뜬 순간 이미
 * 받고 있고, 사람이 누를 것이 생기는 것은 그 자동이 실패했을 때뿐이다. 버튼 하나를 남겨 두면
 * "앱만 설치하면 끝" 이 "앱을 설치하고 버튼을 누르면 끝" 이 되고, 소스에서 띄운 사람은 그 자리에서
 * 자기가 무엇을 빠뜨렸는지부터 의심한다.
 *
 * 터미널로 넣는 길도 함께 적어 둔다. 지우는 것이 아니라 남겨 두는 이유는, 프록시 뒤에 있거나
 * 특정 판을 골라 넣어야 하는 사람이 있기 때문이다 — 그 사람에게 이 화면은 막다른 길이 된다.
 *
 * @param engineDirectoryLabel 받아 둘 자리. 앱이 이 머신에 실행 파일을 하나 놓는 일이라, 어디에
 *   놓는지는 끝나고 나서 알 것이 아니다.
 */
@Composable
internal fun EngineInstallationScreen(
    versionLabel: String,
    engineDirectoryLabel: String,
    engineInstallationState: EngineInstallationState,
    onRetryEngineInstallationRequested: () -> Unit,
    onLookForEngineAgainRequested: () -> Unit,
) {
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

        Text("엔진(kyu)을 준비합니다", style = MaterialTheme.typography.headlineSmall)
        Text(
            // 상태마다 문구를 갈라 놓지 않는다. 이 문단이 말하는 것은 지금 무슨 일이 일어나는지가
            // 아니라 앱이 무엇을 하기로 되어 있는지라, 받는 중에도 · 받지 못한 뒤에도 그대로 참이다.
            // 지금 어디쯤인지는 아래의 진행 표시와 실패 문구가 말한다.
            text = "이 앱은 워크디렉토리 스캔 · 세션 생성 · 레포 클론을 직접 하지 않고 엔진인 kyu 에게 " +
                "시킵니다. 그 kyu 가 이 머신에 없으면 앱이 최신 릴리스에서 맞는 것을 받아 놓습니다 — " +
                "누를 것 없이, 끝나면 다음 화면으로 넘어갑니다.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = EXPLANATION_MAX_WIDTH),
        )
        Text(
            text = "설치 패키지(dmg · deb)로 받은 앱에는 엔진이 함께 들어 있고, 소스에서 띄울 때도 " +
                "Go 가 있으면 ./gradlew run 이 옆 모듈에서 만들어 넣습니다. 이 화면은 그 둘 다 " +
                "아닐 때 뜹니다.",
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

        InstallationProgressOrWhatToDoNext(
            engineInstallationState = engineInstallationState,
            onRetryEngineInstallationRequested = onRetryEngineInstallationRequested,
            onLookForEngineAgainRequested = onLookForEngineAgainRequested,
        )

        Spacer(Modifier.height(8.dp))
        HorizontalDivider(modifier = Modifier.widthIn(max = EXPLANATION_MAX_WIDTH))

        Text(
            text = "앱이 여는 세션에는 tmux 가 필요 없습니다. 다만 kyu 가 워크디렉토리 목록을 낼 때 " +
                "CLI 세션의 생존을 tmux 에게 물으므로, 없으면 대시보드가 서 있습니다 — " +
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
 * 받는 동안 보일 것과, 받지 못했을 때 남는 걸음.
 *
 * 받는 동안에는 누를 것을 두지 않는다. 앱이 알아서 하는 일이라 사람이 고를 것이 없고, 눌러도 되는
 * 것을 옆에 두면 무엇이 진행 중인지가 흐려진다.
 */
@Composable
private fun InstallationProgressOrWhatToDoNext(
    engineInstallationState: EngineInstallationState,
    onRetryEngineInstallationRequested: () -> Unit,
    onLookForEngineAgainRequested: () -> Unit,
) {
    when (engineInstallationState) {
        EngineInstallationState.InstallationRunning ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp))
                Text("최신 릴리스에서 받는 중입니다", style = MaterialTheme.typography.bodySmall)
            }

        // 엔진이 있으면 이 화면 자체가 뜨지 않는다.
        EngineInstallationState.EngineReady -> Unit

        // 한 번 받아 본 뒤에 [다시 찾기] 를 눌러 여전히 없는 자리. 이유를 새로 붙일 것은 없고,
        // 남는 것은 다음 걸음뿐이다.
        EngineInstallationState.EngineMissing ->
            WhatToDoNextButtons(onRetryEngineInstallationRequested, onLookForEngineAgainRequested)

        is EngineInstallationState.InstallationFailed ->
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                InstallationFailureReason(engineInstallationState.failure)
                WhatToDoNextButtons(onRetryEngineInstallationRequested, onLookForEngineAgainRequested)
            }
    }
}

/**
 * 무슨 일이 있었는가와 지금 무엇을 할 수 있는가.
 *
 * 뒤엣줄이 실패 타입마다 다른 것이 이 화면의 요점이다. 망이 막힌 사람과 윈도우 네이티브에서 띄운
 * 사람이 같은 말을 들으면, 둘 중 하나는 하지 않아도 될 일을 하게 된다.
 */
@Composable
private fun InstallationFailureReason(failure: EngineInstallationFailure) {
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = failure.message.orEmpty(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = EXPLANATION_MAX_WIDTH),
        )
        Text(
            text = failure.guidance,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = EXPLANATION_MAX_WIDTH),
        )
    }
}

/**
 * 자동으로 받는 것이 끝내 되지 않았을 때 남는 두 걸음.
 *
 * [다시 시도] 가 앞이다. 여기까지 온 실패는 대개 한때의 것이라(망이 끊겼거나 GitHub 이 한도를
 * 걸었거나) 한 번 더 받아 보는 쪽이 먼저 맞다. [다시 찾기] 는 아래의 한 줄로 직접 넣은 사람의
 * 자리다 — 이것이 없으면 그 사람은 앱을 다시 띄워야 하고, 그러면 "터미널로도 된다" 는 안내가
 * 반쪽이 된다.
 */
@Composable
private fun WhatToDoNextButtons(
    onRetryEngineInstallationRequested: () -> Unit,
    onLookForEngineAgainRequested: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onRetryEngineInstallationRequested) { Text("다시 시도") }
        TextButton(onClick = onLookForEngineAgainRequested) { Text("다시 찾기") }
    }
}

/**
 * 설명 글이 창 폭을 다 쓰지 않게 묶는다. 1100 dp 창에서 한 줄이 끝까지 늘어나면 눈이 다음 줄
 * 첫머리를 찾지 못한다.
 */
private val EXPLANATION_MAX_WIDTH = 560.dp
