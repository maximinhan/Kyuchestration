package com.kyuchestration.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign

/**
 * 오류 자리마다 붙는 한 줄 — 무엇을 건네면 되는지.
 *
 * **이 줄이 있는 이유는 원격 진단이다.** 실기기에서만 나는 오류는 화면 문구만으로는 잡히지
 * 않는다. 사용자가 캡처를 보내면 그 화면에 보이던 한 문장이 전부이고, 그 앞에 무슨 호출이
 * 실패했고 어떤 종료 코드였는지는 어디에도 남지 않는다. 경로를 여기 적어 두면 사용자가 할 일이
 * "그 파일을 보내는 것" 하나로 정해진다.
 *
 * 네 자리가 함께 쓴다(클론 대화상자 · 워크디렉토리 관찰 실패 · 세션 진입 실패 · 이어가기 실패
 * 머리말). 각자 적어 두면 문구가 자리마다 갈라지고, 그러면 사용자가 받는 안내도 갈라진다.
 */
@Composable
internal fun DiagnosticLogPathNotice(
    diagnosticLogPathLabel: String,
    modifier: Modifier = Modifier,
    textAlign: TextAlign? = null,
) {
    Text(
        text = "자세한 기록: $diagnosticLogPathLabel",
        style = MaterialTheme.typography.labelSmall,
        // 경로는 사람이 그대로 옮겨 적는 것이라 고정폭이라야 글자를 잃지 않는다.
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = textAlign,
        modifier = modifier,
    )
}
