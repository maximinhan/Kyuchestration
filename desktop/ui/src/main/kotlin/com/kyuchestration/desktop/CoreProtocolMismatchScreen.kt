package com.kyuchestration.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kyuchestration.desktop.mirror.CoreConnection
import com.kyuchestration.desktop.theme.KyuTheme
import com.kyuchestration.desktop.theme.ThemePreference
import com.kyuchestration.desktop.theme.resolveDarkTheme

/**
 * 코어가 다른 틀 규약을 말할 때 서는 화면(core-ui-split-design.md 6.14). 이 화면에서는 아무것도 할 수 없다 — 할 일은 화면과 코어를
 * 같은 판으로 맞추는 것 하나이고, 그 판단의 재료가 두 판이다.
 *
 * 코어가 WSL 안에 따로 깔려 있으면(윈도우) 판이 갈리는 일이 실제로 생긴다 — 둘을 따로 설치하기 때문이다. 그때는 낡은 쪽의 할 일을
 * 하나로 적는다: 코어가 낡았으면 WSL 에서 돌릴 설치 한 줄, 화면이 낡았으면 새 설치 패키지. 맥 · 리눅스의 코어는 화면과 한 패키지라
 * 다시 설치하라는 말로 충분하다.
 *
 * @param coreInstalledSeparatelyInWsl 코어를 WSL 너머에 띄웠는가.
 */
@Composable
internal fun CoreProtocolMismatchScreen(
    mismatch: CoreConnection.ProtocolMismatch,
    coreInstalledSeparatelyInWsl: Boolean,
    diagnosticLogPathLabel: String,
) {
    KyuTheme(darkTheme = resolveDarkTheme(ThemePreference.AlwaysDark, systemReportsDark = isSystemInDarkTheme())) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("코어의 판이 화면과 다릅니다.", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                Text(
                    text = "화면 ${mismatch.screenVersion}(규약 ${mismatch.screenProtocol}) · 코어 ${mismatch.coreVersion}(규약 ${mismatch.coreProtocol})",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
                val remedy = mismatchRemedyOf(mismatch, coreInstalledSeparatelyInWsl)
                Text(
                    text = when (remedy) {
                        MismatchRemedy.ReinstallTogether -> "화면과 코어를 같은 판으로 다시 설치한 뒤 띄우세요."
                        MismatchRemedy.UpdateWslCore -> "WSL 의 코어가 낡았습니다. 코어를 새로 설치한 뒤 다시 띄우세요."
                        MismatchRemedy.UpdateScreen -> "이 화면이 낡았습니다. 릴리스 페이지에서 새 설치 패키지(msi)를 받아 설치한 뒤 다시 띄우세요."
                    } + " 세션은 열지 않았습니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                if (remedy == MismatchRemedy.UpdateWslCore) {
                    WslCoreInstallCommand()
                }
                DiagnosticLogPathNotice(diagnosticLogPathLabel, textAlign = TextAlign.Center)
            }
        }
    }
}
