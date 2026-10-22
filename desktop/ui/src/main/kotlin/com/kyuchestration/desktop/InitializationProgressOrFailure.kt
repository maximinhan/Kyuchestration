package com.kyuchestration.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kyuchestration.desktop.initialization.WorkDirInitializationState

/**
 * 초기화가 도는 동안과 거절당한 뒤에 보일 것.
 *
 * 시작 화면의 만들기 폼과 대시보드의 초기화 배너가 함께 쓴다. 두 자리는 다르게 생겼지만
 * 기다리는 동안과 거절당했을 때 사람이 봐야 할 것은 똑같다.
 *
 * @param runningLabel 기다리는 동안 보여줄 말. 무엇을 만드는 중인지가 자리마다 다르다.
 */
@Composable
internal fun InitializationProgressOrFailure(
    initializationState: WorkDirInitializationState,
    runningLabel: String,
) {
    when (initializationState) {
        is WorkDirInitializationState.Idle -> Unit

        is WorkDirInitializationState.Running ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp))
                Text(runningLabel, style = MaterialTheme.typography.bodySmall)
            }

        is WorkDirInitializationState.Failed ->
            Text(
                // kyu 가 stderr 에 적은 사람 말 그대로다. 앱이 다시 지어낸 문구보다 정확하다.
                text = initializationState.reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
    }
}
