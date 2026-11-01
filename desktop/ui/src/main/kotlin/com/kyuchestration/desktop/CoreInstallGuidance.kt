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
 * msi 로 깐 화면은 실린 묶음으로 스스로 깐다(6.17 의 4b 정정). 이 한 줄은 묶음이 없는 화면(소스 실행)과, 묶음으로 깔다 실패했거나 깔 수
 * 없는 배포판(리눅스 홈에 빈칸)에서 사람이 손으로 까는 길이다 — 릴리스에서 받는다.
 */
internal const val WSL_CORE_INSTALL_COMMAND =
    "curl -fsSL https://raw.githubusercontent.com/maximinhan/Kyuchestration/main/install.sh | bash -s -- --with-core"

/** 규약 판이 갈렸을 때 사람이 할 일. */
internal enum class MismatchRemedy {
    /** 맥 · 리눅스 — 코어가 화면과 한 패키지다. */
    ReinstallTogether,

    /**
     * 윈도우 — WSL 의 코어가 낡았다. 설치 한 줄을 보인다. 묶음이 실린 화면은 판이 같은 코어만 띄우므로(6.17 의 4b 정정) 이 갈래는
     * 묶음 없이 소스에서 띄운 화면에서만 난다.
     */
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
