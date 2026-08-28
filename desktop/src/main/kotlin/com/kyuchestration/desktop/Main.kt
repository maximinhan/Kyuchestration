package com.kyuchestration.desktop

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "뀨케스트레이션",
        state = rememberWindowState(size = DpSize(760.dp, 520.dp)),
    ) {
        KyuchestrationDesktopScreen(versionLabel = DesktopBuildVersion.label)
    }
}
