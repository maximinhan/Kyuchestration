package com.kyuchestration.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kyuchestration.desktop.claudeauth.ClaudeAuthenticationFailure
import com.kyuchestration.desktop.claudeauth.ClaudeAuthenticationState

/**
 * claude 가 로그인되지 않은 채로 앱을 띄웠을 때 엔진 설치 화면 다음에 뜨는 화면.
 *
 * **고를 것이 둘이고 하나가 앞이다.** 주 버튼은 브라우저 로그인이고, 보조는 토큰 붙여넣기다.
 * 브라우저가 앞인 이유는 그 길만 사용자가 아무것도 미리 들고 있지 않아도 되기 때문이다 —
 * 토큰은 **이미 로그인된 다른 머신**에서 `claude setup-token` 으로 받아 와야 얻어진다
 * (chat-ui-design.md 7.3 다).
 *
 * **기다리는 것 말고 할 수 있는 일이 없는 구간이 있다.** 로그인이 되돌아오는 자리가 이 머신이
 * 아니라 앤트로픽의 주소라(7.3 가), 앱이 열어 둘 콜백도 물어볼 자리도 없다. 그래서 주소를 보인
 * 뒤에는 코드를 받는 칸 하나가 반드시 있어야 한다 — 그 칸이 이 흐름의 유일한 되돌아오는 길이다.
 *
 * 화면이 스스로 드는 상태는 하나다: "지금 토큰 칸을 펼쳐 두었는가". 그것은 사용자가 방금 무엇을
 * 눌렀는가의 문제이지 인증이 어디까지 갔는가의 문제가 아니라, 홀더가 알 일이 아니다
 * (Main.kt 의 conversationPaneContent 와 같은 선이다).
 */
@Composable
internal fun ClaudeAuthenticationScreen(
    versionLabel: String,
    diagnosticLogPathLabel: String,
    claudeAuthenticationState: ClaudeAuthenticationState,
    onCheckCredentialsRequested: () -> Unit,
    onBrowserLoginRequested: () -> Unit,
    onBrowserLoginCodeSubmitted: (String) -> Unit,
    onBrowserLoginCancelled: () -> Unit,
    onTokenSubmitted: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp).verticalScroll(rememberScrollState()),
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

        Text("Claude 에 로그인합니다", style = MaterialTheme.typography.headlineSmall)
        Text(
            // 상태마다 갈라 놓지 않는다. 이 문단이 말하는 것은 지금 무슨 일이 일어나는지가 아니라
            // 앱이 왜 이것을 묻는지라, 어느 상태에서도 그대로 참이다.
            text = "이 앱의 세션은 이 머신의 claude 를 띄웁니다. 그 claude 가 로그인되지 않으면 " +
                "세션은 한 줄을 답하고 곧바로 끝납니다 — 그래서 대화를 열기 전에 여기서 한 번 " +
                "확인합니다. 앱에는 따로 계정이 없습니다.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = EXPLANATION_MAX_WIDTH),
        )

        WhatToDoAboutCredentials(
            claudeAuthenticationState = claudeAuthenticationState,
            onCheckCredentialsRequested = onCheckCredentialsRequested,
            onBrowserLoginRequested = onBrowserLoginRequested,
            onBrowserLoginCodeSubmitted = onBrowserLoginCodeSubmitted,
            onBrowserLoginCancelled = onBrowserLoginCancelled,
            onTokenSubmitted = onTokenSubmitted,
        )

        Spacer(Modifier.height(8.dp))
        HorizontalDivider(modifier = Modifier.widthIn(max = EXPLANATION_MAX_WIDTH))
        DiagnosticLogPathNotice(diagnosticLogPathLabel, textAlign = TextAlign.Center)
    }
}

@Composable
private fun WhatToDoAboutCredentials(
    claudeAuthenticationState: ClaudeAuthenticationState,
    onCheckCredentialsRequested: () -> Unit,
    onBrowserLoginRequested: () -> Unit,
    onBrowserLoginCodeSubmitted: (String) -> Unit,
    onBrowserLoginCancelled: () -> Unit,
    onTokenSubmitted: (String) -> Unit,
) {
    when (claudeAuthenticationState) {
        ClaudeAuthenticationState.CheckingCredentials ->
            WaitingRow("claude 에게 로그인돼 있는지 묻는 중입니다")

        is ClaudeAuthenticationState.ClaudeCliMissing ->
            ClaudeCliInstallationGuidance(claudeAuthenticationState.failure, onCheckCredentialsRequested)

        is ClaudeAuthenticationState.CredentialsMissing ->
            LoginChoices(claudeAuthenticationState, onBrowserLoginRequested, onTokenSubmitted)

        ClaudeAuthenticationState.BrowserLoginStarting ->
            WaitingRow("claude 가 브라우저를 여는 중입니다")

        is ClaudeAuthenticationState.BrowserLoginWaitingForCode ->
            BrowserLoginCodeForm(
                authorizationUrl = claudeAuthenticationState.authorizationUrl,
                onBrowserLoginCodeSubmitted = onBrowserLoginCodeSubmitted,
                onBrowserLoginCancelled = onBrowserLoginCancelled,
            )

        ClaudeAuthenticationState.BrowserLoginFinishing ->
            WaitingRow("받아 온 코드로 로그인을 끝내는 중입니다")

        ClaudeAuthenticationState.PastedTokenVerifying ->
            // 무엇을 기다리는지 말한다. 이 걸음은 실제로 claude 를 한 번 띄워 답을 받아 보는
            // 일이라 몇 초가 걸리고, 그 사이에 화면이 아무 말도 하지 않으면 멈춘 것으로 보인다.
            WaitingRow("이 토큰으로 claude 가 답하는지 한 번 물어보는 중입니다")

        // 로그인돼 있으면 이 화면 자체가 뜨지 않는다.
        ClaudeAuthenticationState.CredentialsReady -> Unit
    }
}

/**
 * claude 가 이 머신에 없다.
 *
 * 엔진(kyu)과 달리 앱이 대신 받아 놓지 않는다. claude 는 이 도구의 것이 아니라 설치 방법도
 * 판올림도 앤트로픽이 정하고, 그것을 앱이 흉내 내기 시작하면 판이 바뀔 때마다 앱이 먼저 깨진다.
 * 그래서 넣는 길만 적고, 넣은 뒤에 누를 자리를 남긴다.
 */
@Composable
private fun ClaudeCliInstallationGuidance(
    failure: ClaudeAuthenticationFailure,
    onCheckCredentialsRequested: () -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        FailureReason(failure)

        Text(
            text = "claude 를 이 머신에 넣은 뒤 [다시 확인] 을 누르세요. 앱을 다시 띄우지 않아도 됩니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = EXPLANATION_MAX_WIDTH),
        )
        SelectableMonospaceText(CLAUDE_INSTALLATION_COMMAND)
        SelectableMonospaceText(CLAUDE_INSTALLATION_PAGE)

        Button(onClick = onCheckCredentialsRequested) { Text("다시 확인") }
    }
}

/**
 * 고를 자리 — 브라우저가 앞이고 토큰이 보조다.
 *
 * 토큰 칸을 처음부터 펼쳐 두지 않는다. 대부분의 사람에게는 브라우저 한 번이면 끝나는 일이고,
 * 붙여넣을 칸이 나란히 있으면 "무엇을 붙여넣어야 하지" 라는 물음이 먼저 생긴다.
 */
@Composable
private fun LoginChoices(
    state: ClaudeAuthenticationState.CredentialsMissing,
    onBrowserLoginRequested: () -> Unit,
    onTokenSubmitted: (String) -> Unit,
) {
    var tokenFormOpen by remember { mutableStateOf(false) }

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.widthIn(max = EXPLANATION_MAX_WIDTH),
    ) {
        state.lastFailure?.let { FailureReason(it) }

        Button(onClick = onBrowserLoginRequested) { Text("브라우저로 로그인") }
        Text(
            text = "누르면 claude 가 브라우저를 엽니다. 로그인한 뒤 그 화면이 주는 코드를 여기로 " +
                "가져오면 끝납니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        if (!tokenFormOpen) {
            TextButton(onClick = { tokenFormOpen = true }) { Text("토큰으로 대신 로그인") }
        } else {
            PastedTokenForm(
                tokenWouldBeStoredInPlaintext = state.tokenWouldBeStoredInPlaintext,
                onTokenSubmitted = onTokenSubmitted,
                onCancelled = { tokenFormOpen = false },
            )
        }
    }
}

/**
 * 브라우저가 열리지 않는 자리를 위한 폴백.
 *
 * 이 토큰은 웹 화면에서 발급받는 것이 아니라 **이미 로그인된 머신**의 claude 가 만들어 주는
 * 것이다. 그 사실을 이 자리에 적지 않으면 사용자는 발급 페이지를 찾아 헤맨다.
 */
@Composable
private fun PastedTokenForm(
    tokenWouldBeStoredInPlaintext: Boolean,
    onTokenSubmitted: (String) -> Unit,
    onCancelled: () -> Unit,
) {
    var token by remember { mutableStateOf("") }

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = "이미 로그인된 다른 머신의 터미널에서 claude setup-token 을 실행하면 장기 토큰이 " +
                "나옵니다. 그것을 여기 붙여넣으세요 (Claude 구독이 있어야 합니다).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text("claude 자격 증명 토큰") },
            singleLine = true,
            // 화면에 그대로 띄우지 않는다 — 이 화면은 화면 공유 중에도 열린다. 붙여넣기는 그대로 된다.
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )

        // 저장하기 전에 알린다. 받은 뒤에 알리면 사용자가 할 수 있는 일은 이미 저장된 것을
        // 지우는 것뿐이다(kyu auth add 가 같은 자세다).
        if (tokenWouldBeStoredInPlaintext) {
            Text(
                text = "이 머신에는 키체인도 secret-service 도 없어 토큰이 설정 파일에 평문으로 " +
                    "저장됩니다 (권한 0600). 남기고 싶지 않다면 브라우저 로그인을 쓰세요.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        }

        Text(
            text = "토큰은 이 머신에만 저장되고 앤트로픽 말고 어디에도 보내지 않습니다. 저장은 kyu 가 합니다. " +
                "넣으면 실제로 통하는지 claude 에게 한 번 물어본 뒤 저장합니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { onTokenSubmitted(token.trim()) },
                enabled = token.isNotBlank(),
            ) {
                Text("이 토큰으로 로그인")
            }
            TextButton(onClick = onCancelled) { Text("취소") }
        }
    }
}

/**
 * 주소를 보이고 코드를 받는 자리.
 *
 * 주소를 화면에 그대로 적어 두는 것이 이 화면의 요점이다. `claude` 가 브라우저를 스스로 열지만
 * 그것이 된다는 보장은 없고(원격 셸·WSL·기본 브라우저가 없는 자리), 열리지 않았을 때 사용자가
 * 할 수 있는 일이 여기 없으면 흐름이 통째로 막힌다.
 */
@Composable
private fun BrowserLoginCodeForm(
    authorizationUrl: String,
    onBrowserLoginCodeSubmitted: (String) -> Unit,
    onBrowserLoginCancelled: () -> Unit,
) {
    var code by remember { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.widthIn(max = EXPLANATION_MAX_WIDTH),
    ) {
        Text(
            text = "브라우저가 열리지 않았으면 이 주소를 직접 여세요.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        SelectableMonospaceText(authorizationUrl)
        TextButton(onClick = { clipboard.setText(AnnotatedString(authorizationUrl)) }) { Text("주소 복사") }

        Text(
            text = "로그인을 마치면 그 화면이 코드를 줍니다. 그것을 여기 붙여넣으세요.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        OutlinedTextField(
            value = code,
            onValueChange = { code = it },
            label = { Text("브라우저가 준 코드") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { onBrowserLoginCodeSubmitted(code.trim()) },
                enabled = code.isNotBlank(),
            ) {
                Text("로그인 끝내기")
            }
            TextButton(onClick = onBrowserLoginCancelled) { Text("취소") }
        }
    }
}

/** 무슨 일이 있었는가와 지금 무엇을 할 수 있는가. 뒤엣줄이 실패마다 다른 것이 요점이다. */
@Composable
private fun FailureReason(failure: ClaudeAuthenticationFailure) {
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

@Composable
private fun WaitingRow(label: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp))
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * 손으로 옮겨 적을 것을 고를 수 있게 둔다.
 *
 * 주소와 설치 명령이 여기 온다. 둘 다 사용자가 다른 창으로 옮겨야 하는 글자라, 고를 수 없으면
 * 보고 따라 치는 수밖에 없다 — 주소는 그렇게 옮길 수 있는 길이가 아니다.
 */
@Composable
private fun SelectableMonospaceText(text: String) {
    SelectionContainer {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = EXPLANATION_MAX_WIDTH),
        )
    }
}

/** 앤트로픽이 안내하는 설치 한 줄. 이 도구가 대신 넣지 않으므로 그대로 옮겨 적는다. */
private const val CLAUDE_INSTALLATION_COMMAND = "curl -fsSL https://claude.ai/install.sh | bash"

private const val CLAUDE_INSTALLATION_PAGE = "https://code.claude.com/docs/en/setup"

/**
 * 설명 글이 창 폭을 다 쓰지 않게 묶는다. 넓은 창에서 한 줄이 끝까지 늘어나면 눈이 다음 줄
 * 첫머리를 찾지 못한다(설치 화면과 같은 값이다).
 */
private val EXPLANATION_MAX_WIDTH = 560.dp
