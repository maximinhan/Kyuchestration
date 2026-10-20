package com.kyuchestration.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.kyuchestration.desktop.terminal.chat.ChildSessionGateQuestion

/**
 * 메인이 띄우려는 하위의 자리에 사람이 승인하지 않은 실행 유발 설정이 있다(session-tree-design.md 6.9).
 *
 * **무엇을 승인하는지 보여주는 것이 이 대화 상자의 전부다.** 걸린 파일마다 경로와 내용을 그대로 싣는다 —
 * 내용 없이 "승인할까요?" 만 묻는 상자는 관문이 아니라 클릭 한 번이다.
 *
 * **바깥을 눌러 닫는 것을 거절로 읽지 않는다.** 실수로 바깥을 누른 것이 상위의 일을 막으면 안 되고, 닫힌
 * 상자가 다시 뜨지도 않으면 spawn 은 답 없이 매달린다. 그래서 두 버튼만이 답이다.
 */
@Composable
internal fun ChildSessionGateDialog(
    question: ChildSessionGateQuestion,
    onAnswered: (approved: Boolean) -> Unit,
) {
    AlertDialog(
        onDismissRequest = {},
        modifier = Modifier.width(760.dp),
        title = { Text("${question.childId} 을 ${question.gate.relativeCwd} 에서 띄우기 전에 확인이 필요합니다") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "이 폴더에는 세션이 뜨는 순간 실행되는 설정이 있고, 사람이 아직 본 적이 없습니다. " +
                        "승인하면 이 내용 그대로일 때만 통과합니다 — 파일이 바뀌면 다시 묻습니다.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                MonospaceBlock(question.prompt, detailTitle = "메인이 시키려는 일")
                question.gate.executionConfigs.forEach { config ->
                    Text(
                        text = config.relativePath,
                        style = MaterialTheme.typography.labelLarge,
                        fontFamily = FontFamily.Monospace,
                    )
                    MonospaceBlock(config.content, detailTitle = config.absolutePath)
                }
            }
        },
        confirmButton = {
            Button(onClick = { onAnswered(true) }) { Text("승인하고 띄우기") }
        },
        dismissButton = {
            TextButton(onClick = { onAnswered(false) }) { Text("거절") }
        },
    )
}
