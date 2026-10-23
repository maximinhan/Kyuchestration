package com.kyuchestration.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kyuchestration.desktop.mirror.WSL_CORE_EXECUTABLE_FROM_HOME
import com.kyuchestration.desktop.theme.KyuTheme
import com.kyuchestration.desktop.theme.ThemePreference
import com.kyuchestration.desktop.theme.resolveDarkTheme
import com.kyuchestration.desktop.wsl.ChosenDistributionProblem
import com.kyuchestration.desktop.wsl.WslCorePlacePreparationState

/**
 * 윈도우 화면이 코어를 띄울 WSL 배포판을 고르는 화면(core-ui-split-design.md 6.17). 코어에 닿기 전의 일이라 코어의 상태가 아니라
 * 화면의 것이다.
 */
@Composable
internal fun WslCorePlaceScreen(
    state: WslCorePlacePreparationState,
    diagnosticLogPathLabel: String,
    onLookAroundAgainRequested: () -> Unit,
    onDistributionChosen: (distributionName: String) -> Unit,
) {
    PreparationSurface {
        when (state) {
            WslCorePlacePreparationState.LookingAround -> Progress("WSL 배포판을 살피는 중입니다")
            is WslCorePlacePreparationState.CheckingCore -> Progress("${state.distributionName} 에 코어가 있는지 보는 중입니다")
            is WslCorePlacePreparationState.Ready -> Progress("${state.distributionName} 의 코어를 띄웁니다")
            WslCorePlacePreparationState.NoDistributions -> {
                Headline("WSL 배포판이 없습니다")
                Guidance(
                    "코어는 WSL 배포판 안에서 돕니다. PowerShell 에서 `wsl --install` 로 Ubuntu 를 설치하고 처음 사용자를 만든 뒤 " +
                        "다시 보기를 누르세요.",
                )
                Button(onClick = onLookAroundAgainRequested) { Text("다시 보기") }
            }
            is WslCorePlacePreparationState.ListingFailed -> {
                Headline("WSL 이 배포판 목록을 주지 않았습니다")
                WslMessage(state.wslMessage)
                Guidance("배포판이 없다면 PowerShell 에서 `wsl --install` 로 설치한 뒤 다시 보기를 누르세요.")
                DiagnosticLogPathNotice(diagnosticLogPathLabel, textAlign = TextAlign.Center)
                Button(onClick = onLookAroundAgainRequested) { Text("다시 보기") }
            }
            is WslCorePlacePreparationState.Choosing -> DistributionChoice(state, onLookAroundAgainRequested, onDistributionChosen)
        }
    }
}

/** `wsl.exe` 가 없다 — WSL 이 설치되어 있지 않다(6.12 의 마지막 줄 · 6.17). 이 화면에서 할 일은 없고 설치 안내만 있다. */
@Composable
internal fun WslMissingScreen(wslExecutableLabel: String) {
    PreparationSurface {
        Headline("WSL 이 필요합니다")
        Guidance(
            "뀨케스트레이션의 세션은 WSL 안에서 돕니다. $wslExecutableLabel 이 없습니다. " +
                "관리자 PowerShell 에서 `wsl --install` 을 돌리고 다시 시작한 뒤 앱을 띄우세요.",
        )
    }
}

@Composable
private fun DistributionChoice(
    state: WslCorePlacePreparationState.Choosing,
    onLookAroundAgainRequested: () -> Unit,
    onDistributionChosen: (distributionName: String) -> Unit,
) {
    var chosenName by remember(state) { mutableStateOf(state.preselectedName) }

    Headline("코어를 띄울 WSL 배포판")
    Guidance("프로젝트 폴더 · claude · kyu 가 있는 배포판을 고르세요. 고른 배포판은 기억해 두고 다음부터 묻지 않습니다.")
    Column {
        state.distributions.names.forEach { name ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.selectable(selected = name == chosenName, onClick = { chosenName = name }),
            ) {
                RadioButton(selected = name == chosenName, onClick = { chosenName = name })
                Text(if (name == state.distributions.defaultName) "$name (기본)" else name)
            }
        }
    }
    state.problem?.let { problem -> ProblemWithChosenDistribution(problem) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { onDistributionChosen(chosenName) }) { Text("이 배포판으로") }
        TextButton(onClick = onLookAroundAgainRequested) { Text("목록 다시 보기") }
    }
}

@Composable
private fun ProblemWithChosenDistribution(problem: ChosenDistributionProblem) {
    when (problem) {
        is ChosenDistributionProblem.CoreNotInstalled -> {
            Text(
                "${problem.distributionName} 에 코어가 없습니다 — ~/$WSL_CORE_EXECUTABLE_FROM_HOME",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
            WslCoreInstallCommand()
            Guidance("그 배포판의 터미널에서 설치한 뒤 '이 배포판으로' 를 다시 누르세요.")
        }
        is ChosenDistributionProblem.DistributionFailed -> {
            Text(
                "${problem.distributionName} 을 띄우지 못했습니다",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
            WslMessage(problem.wslMessage)
        }
    }
}

@Composable
private fun PreparationSurface(content: @Composable () -> Unit) {
    KyuTheme(darkTheme = resolveDarkTheme(ThemePreference.AlwaysDark, systemReportsDark = isSystemInDarkTheme())) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                content()
            }
        }
    }
}

@Composable
private fun Progress(label: String) {
    CircularProgressIndicator(modifier = Modifier.size(32.dp))
    Text(label, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun Headline(text: String) {
    Text(text, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
}

@Composable
private fun Guidance(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

/** `wsl.exe` 의 말은 윈도우의 언어로 온다. 고치지 않고 그대로 보인다 — 사람이 그 문구로 검색한다. */
@Composable
private fun WslMessage(wslMessage: String) {
    Text(
        text = wslMessage,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        textAlign = TextAlign.Center,
    )
}
