package com.kyuchestration.desktop

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
import androidx.compose.ui.unit.dp

@Composable
fun KyuchestrationDesktopScreen(versionLabel: String) {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "뀨케스트레이션",
                    style = MaterialTheme.typography.headlineMedium,
                )
                Text(
                    text = versionLabel,
                    style = MaterialTheme.typography.bodyMedium,
                )
                // 워크디렉토리를 고르는 조작은 다음 PR 에서 kyu CLI 를 붙이면서 들어온다. 지금 누를 수
                // 없는 버튼을 두면 "고장난 앱" 으로 읽히므로, 자리와 예정만 글로 밝혀 둔다.
                Text(
                    text = "워크디렉토리 열기 — 다음 단계에서 이 자리에 붙습니다",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
