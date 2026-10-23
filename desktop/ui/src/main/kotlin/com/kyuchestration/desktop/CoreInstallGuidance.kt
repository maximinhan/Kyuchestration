package com.kyuchestration.desktop

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import com.kyuchestration.desktop.mirror.CoreConnection

/**
 * WSL 터미널에서 돌릴 코어 설치 한 줄(core-ui-split-design.md 6.17). kyu 와 코어를 함께 놓고, 다시 돌리면 최신 릴리스로 갈아 끼운다.
 *
 * 화면이 대신 돌리지 않는다 — 설치는 사람이 WSL 터미널에서 한다(6.17 의 판단, 8 절 5).
 */
internal const val WSL_CORE_INSTALL_COMMAND =
    "curl -fsSL https://raw.githubusercontent.com/maximinhan/Kyuchestration/main/install.sh | bash -s -- --with-core"

/** 규약 판이 갈렸을 때 사람이 할 일. */
internal enum class MismatchRemedy {
    /** 맥 · 리눅스 — 코어가 화면과 한 패키지다. */
    ReinstallTogether,

    /** 윈도우 — WSL 의 코어가 낡았다. 설치 한 줄을 보인다. */
    UpdateWslCore,

    /** 윈도우 — 화면이 낡았다. 새 msi 다. */
    UpdateScreen,
}

/**
 * 판이 낮은 쪽이 낡았다(같을 수는 없다 — 같으면 판 불일치가 아니다). 코어가 WSL 에 따로 깔려 있을 때만 어느 쪽인지가 할 일을 가른다.
 *
 * @param coreInstalledSeparatelyInWsl 코어를 WSL 너머에 띄웠는가.
 */
internal fun mismatchRemedyOf(mismatch: CoreConnection.ProtocolMismatch, coreInstalledSeparatelyInWsl: Boolean): MismatchRemedy = when {
    !coreInstalledSeparatelyInWsl -> MismatchRemedy.ReinstallTogether
    mismatch.coreProtocol < mismatch.screenProtocol -> MismatchRemedy.UpdateWslCore
    else -> MismatchRemedy.UpdateScreen
}

/** 설치 한 줄과 복사 단추. */
@Composable
internal fun WslCoreInstallCommand() {
    val clipboard = LocalClipboardManager.current
    Text(
        text = "WSL 터미널에서 이 한 줄을 돌리세요.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    SelectionContainer {
        Text(WSL_CORE_INSTALL_COMMAND, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center)
    }
    TextButton(onClick = { clipboard.setText(AnnotatedString(WSL_CORE_INSTALL_COMMAND)) }) { Text("명령 복사") }
}
