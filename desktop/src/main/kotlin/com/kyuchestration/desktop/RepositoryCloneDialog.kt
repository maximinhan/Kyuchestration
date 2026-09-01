package com.kyuchestration.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.kyuchestration.desktop.repoclone.CloneStatus
import com.kyuchestration.desktop.repoclone.CloneStep
import com.kyuchestration.desktop.repoclone.OwnerKind
import com.kyuchestration.desktop.repoclone.RemoteRepository
import com.kyuchestration.desktop.repoclone.RepositoryCloneResult
import com.kyuchestration.desktop.repoclone.RepositoryCloneState
import com.kyuchestration.desktop.repoclone.RepositoryCloneStateHolder
import com.kyuchestration.desktop.repoclone.TokenStorage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 앱 안에서 레포를 골라 받아 오는 대화상자.
 *
 * 상태를 위에서 받지 않고 여기서 구독한다. 다른 상태들은 화면 여러 곳이 나눠 보므로 조립하는
 * 자리에서 한 번 구독해 내려보내지만, 클론 상태를 보는 곳은 이 대화상자 하나뿐이다 — 위에서
 * 구독하면 검색 칸에 한 글자 칠 때마다 창 전체가(터미널까지) 다시 그려진다.
 *
 * 걸음마다 화면을 따로 만들지 않고 한 대화상자 안에서 본문만 바꾼다. 사용자가 하는 일은
 * "레포를 받아 온다" 하나이고, 그 사이의 물음들은 그 일의 부분이다.
 */
@Composable
fun RepositoryCloneDialog(
    repositoryCloneStateHolder: RepositoryCloneStateHolder,
    diagnosticLogPathLabel: String,
) {
    val cloneState by repositoryCloneStateHolder.state.collectAsState()
    if (cloneState is RepositoryCloneState.Closed) {
        return
    }

    // 폼에 적어 넣은 것은 화면이 잠깐 들고 있는 것이지 앱의 상태가 아니다. 특히 토큰은 상태
    // 홀더로 올려서는 안 된다 — 올리면 대화상자를 떠난 뒤에도 남는다.
    var newProfileName by remember { mutableStateOf("") }
    var newProfileToken by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = repositoryCloneStateHolder::close,
        modifier = Modifier.width(760.dp),
        title = { Text(cloneState.title) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 460.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (val state = cloneState) {
                    // 닫혀 있으면 위에서 이미 돌아갔다. when 이 남김없이 덮는지를 컴파일러가
                    // 봐 주려면 이 갈래도 적어 두어야 한다.
                    is RepositoryCloneState.Closed -> Unit

                    is RepositoryCloneState.StepRunning -> StepRunningBody(state.step)

                    is RepositoryCloneState.StepFailed -> StepFailedBody(state, diagnosticLogPathLabel)

                    is RepositoryCloneState.ChoosingTokenProfile -> TokenProfileChoiceBody(
                        state = state,
                        onTokenProfileChosen = repositoryCloneStateHolder::chooseTokenProfile,
                        onTokenProfileRemovalRequested = repositoryCloneStateHolder::startRemovingTokenProfile,
                    )

                    is RepositoryCloneState.ConfirmingTokenProfileRemoval ->
                        TokenProfileRemovalConfirmationBody(state)

                    is RepositoryCloneState.RegisteringTokenProfile -> TokenProfileRegistrationBody(
                        state = state,
                        newProfileName = newProfileName,
                        newProfileToken = newProfileToken,
                        onNewProfileNameChanged = { newProfileName = it },
                        onNewProfileTokenChanged = { newProfileToken = it },
                        onStopRequested = repositoryCloneStateHolder::stopRegisteringTokenProfile,
                    )

                    is RepositoryCloneState.TokenProfileRegistered -> Text(
                        text = "${state.profile.profileName} 프로필을 저장했습니다 — GitHub 이 확인해준 계정은 " +
                            "${state.profile.gitHubLogin} 입니다. 이 계정이 아니라면 닫고 다른 토큰으로 등록하세요.",
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    is RepositoryCloneState.ChoosingOwner -> OwnerChoiceBody(
                        state = state,
                        onOwnerChosen = repositoryCloneStateHolder::chooseOwner,
                    )

                    is RepositoryCloneState.ChoosingRepositories -> RepositoryChoiceBody(
                        state = state,
                        onSearchTextChanged = repositoryCloneStateHolder::changeRepositorySearchText,
                        onSelectionToggled = repositoryCloneStateHolder::toggleRepositorySelection,
                    )

                    is RepositoryCloneState.CloneFinished -> CloneResultBody(state)
                }
            }
        },
        confirmButton = {
            CloneDialogConfirmButton(
                cloneState = cloneState,
                onRegisterTokenProfileRequested = repositoryCloneStateHolder::startRegisteringTokenProfile,
                onRegisterSubmitted = {
                    repositoryCloneStateHolder.registerTokenProfile(newProfileName, newProfileToken)
                    // 건네자마자 화면에서 지운다. 거절당하면 다시 붙여넣게 되지만, 앱이 토큰을
                    // 들고 있는 시간을 그 대가로 줄인다.
                    newProfileToken = ""
                },
                onContinueRequested = repositoryCloneStateHolder::continueWithRegisteredTokenProfile,
                onCloneRequested = repositoryCloneStateHolder::cloneSelectedRepositories,
                onRetryRequested = repositoryCloneStateHolder::retryFailedStep,
                onCloseRequested = repositoryCloneStateHolder::close,
                onRemovalConfirmed = repositoryCloneStateHolder::removeChosenTokenProfile,
            )
        },
        dismissButton = {
            when {
                cloneState is RepositoryCloneState.CloneFinished -> Unit

                // 지우기를 확인하는 자리에서 "닫기" 는 대화상자를 통째로 닫는 말로 읽힌다.
                // 사용자가 무르려던 것은 지우기 하나이지 레포를 받는 일 전체가 아니다.
                cloneState is RepositoryCloneState.ConfirmingTokenProfileRemoval ->
                    TextButton(onClick = repositoryCloneStateHolder::stopRemovingTokenProfile) {
                        Text("지우지 않기")
                    }

                else -> TextButton(onClick = repositoryCloneStateHolder::close) { Text("닫기") }
            }
        },
    )
}

@Composable
private fun CloneDialogConfirmButton(
    cloneState: RepositoryCloneState,
    onRegisterTokenProfileRequested: () -> Unit,
    onRegisterSubmitted: () -> Unit,
    onContinueRequested: () -> Unit,
    onCloneRequested: () -> Unit,
    onRetryRequested: () -> Unit,
    onCloseRequested: () -> Unit,
    onRemovalConfirmed: () -> Unit,
) {
    when (cloneState) {
        is RepositoryCloneState.ChoosingTokenProfile ->
            Button(onClick = onRegisterTokenProfileRequested) { Text("새 토큰 등록") }

        is RepositoryCloneState.ConfirmingTokenProfileRemoval -> Button(
            onClick = onRemovalConfirmed,
            // 되돌릴 수 없는 일이라 붉게 칠한다. 옆의 "지우지 않기" 와 같은 색으로 두면 손이
            // 어느 쪽을 누르는지 보지 않고 지나간다.
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
        ) {
            Text("${cloneState.profileName} 지우기")
        }

        is RepositoryCloneState.RegisteringTokenProfile ->
            Button(onClick = onRegisterSubmitted) { Text("등록") }

        is RepositoryCloneState.TokenProfileRegistered ->
            Button(onClick = onContinueRequested) { Text("계속") }

        is RepositoryCloneState.ChoosingRepositories -> Button(
            onClick = onCloneRequested,
            // 고른 것이 없으면 시킬 일이 없다. 눌리게 두면 사용자가 하지도 않은 일에 대한
            // 오류 문구를 보게 된다.
            enabled = cloneState.selectedRepositoryFullNames.isNotEmpty(),
        ) {
            Text("${cloneState.selectedRepositoryFullNames.size}개 받아 오기")
        }

        is RepositoryCloneState.StepFailed -> Button(onClick = onRetryRequested) { Text("다시 시도") }

        is RepositoryCloneState.CloneFinished -> Button(onClick = onCloseRequested) { Text("닫기") }

        is RepositoryCloneState.StepRunning,
        is RepositoryCloneState.ChoosingOwner,
        is RepositoryCloneState.Closed,
        -> Unit
    }
}

@Composable
private fun StepRunningBody(step: CloneStep) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp))
        Text(step.runningLabel, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun StepFailedBody(state: RepositoryCloneState.StepFailed, diagnosticLogPathLabel: String) {
    Text(state.failure.message.orEmpty(), style = MaterialTheme.typography.bodyMedium)
    Text(
        // kyu 가 stderr 에 적은 사람 말 그대로다. 앱이 다시 지어낸 문구보다 정확하다.
        text = state.failure.guidance,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.heightIn(max = LIST_MAX_HEIGHT).verticalScroll(rememberScrollState()),
    )
    DiagnosticLogPathNotice(diagnosticLogPathLabel)
}

@Composable
private fun TokenProfileChoiceBody(
    state: RepositoryCloneState.ChoosingTokenProfile,
    onTokenProfileChosen: (String) -> Unit,
    onTokenProfileRemovalRequested: (String) -> Unit,
) {
    if (state.profiles.isEmpty()) {
        Text(
            text = "등록된 토큰 프로필이 없습니다. 새 토큰을 등록하면 이 목록에 남고, 다음부터는 고르기만 하면 됩니다.",
            style = MaterialTheme.typography.bodyMedium,
        )
        return
    }

    // 지우지 못한 자리에서 목록으로 돌아왔다. 목록 위에 얹어야 사용자가 방금 누른 것과 이어 읽는다.
    state.removalRejectionReason?.let { reason ->
        Text(
            text = reason,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.error,
        )
    }

    Text("어느 토큰으로 GitHub 에 붙을까요?", style = MaterialTheme.typography.bodyMedium)
    LazyColumn(
        modifier = Modifier.heightIn(max = LIST_MAX_HEIGHT),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(state.profiles, key = { it.name }) { profile ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // 줄 전체를 누르는 것은 고르는 일이다. 지우기는 그 안의 버튼 하나로만
                    // 일어나야, 이 목록에서 누르는 손이 실수로 지우는 자리를 만들지 않는다.
                    .clickable { onTokenProfileChosen(profile.name) }
                    .padding(vertical = 4.dp, horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(profile.name, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.weight(1f))
                Text(
                    text = profile.storage.label,
                    style = MaterialTheme.typography.labelMedium,
                    // 평문 파일에 놓인 토큰은 눈에 띄어야 한다. 사용자가 그 사실을 알고 쓰는 것과
                    // 모른 채 쓰는 것은 다르다.
                    color = if (profile.storage == TokenStorage.ConfigFile) {
                        PLAINTEXT_STORAGE_COLOR
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { onTokenProfileRemovalRequested(profile.name) }) {
                    Text("제거", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun TokenProfileRemovalConfirmationBody(state: RepositoryCloneState.ConfirmingTokenProfileRemoval) {
    // 이름을 문구 안에서 그대로 말한다. "정말 지울까요?" 만으로는 사용자가 무엇을 지우는지
    // 그 자리에서 확인할 수 없고, 목록은 이 화면에 더 이상 떠 있지 않다.
    Text(
        text = "${state.profileName} 프로필과 그 토큰을 이 머신에서 지웁니다.",
        style = MaterialTheme.typography.bodyMedium,
    )
    Text(
        text = "되돌릴 수 없습니다 — 지워진 토큰은 앱도 kyu 도 되살리지 못하고, 다시 쓰려면 " +
            "GitHub 에서 새로 발급받아 등록해야 합니다. 이미 받아 둔 레포는 그대로 남습니다.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun TokenProfileRegistrationBody(
    state: RepositoryCloneState.RegisteringTokenProfile,
    newProfileName: String,
    newProfileToken: String,
    onNewProfileNameChanged: (String) -> Unit,
    onNewProfileTokenChanged: (String) -> Unit,
    onStopRequested: () -> Unit,
) {
    Text(
        text = "GitHub personal access token 을 등록합니다. 발급: https://github.com/settings/tokens\n" +
            "필요한 권한: 클래식 토큰이면 repo, fine-grained 이면 Contents 읽기 " +
            "(조직 레포까지 보려면 그 조직을 토큰의 대상에 포함해야 합니다)",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
        value = newProfileName,
        onValueChange = onNewProfileNameChanged,
        label = { Text("프로필 이름") },
        placeholder = { Text("개인") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = newProfileToken,
        onValueChange = onNewProfileTokenChanged,
        label = { Text("토큰") },
        singleLine = true,
        // 화면에 그대로 띄우지 않는다 — 이 창은 화면 공유 중에도 열린다. 붙여넣기는 그대로 된다.
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        text = "토큰은 이 머신에만 저장되고 GitHub 말고 어디에도 보내지 않습니다. 저장은 kyu 가 합니다.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    state.rejectionReason?.let { reason ->
        Text(
            text = reason,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.error,
        )
    }

    if (state.profilesToReturnTo.isNotEmpty()) {
        TextButton(onClick = onStopRequested) { Text("등록하지 않고 목록으로") }
    }
}

@Composable
private fun OwnerChoiceBody(state: RepositoryCloneState.ChoosingOwner, onOwnerChosen: (String) -> Unit) {
    if (state.owners.isEmpty()) {
        Text(
            text = "이 토큰으로 볼 수 있는 계정이 없습니다. 토큰의 권한을 확인하거나 다른 프로필로 다시 시작하세요.",
            style = MaterialTheme.typography.bodyMedium,
        )
        return
    }

    Text("누구의 레포를 볼까요?", style = MaterialTheme.typography.bodyMedium)
    LazyColumn(
        modifier = Modifier.heightIn(max = LIST_MAX_HEIGHT),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(state.owners, key = { it.login }) { owner ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOwnerChosen(owner.login) }
                    .padding(vertical = 10.dp, horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(owner.login, style = MaterialTheme.typography.titleSmall, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.width(10.dp))
                Text(
                    text = owner.kind.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RepositoryChoiceBody(
    state: RepositoryCloneState.ChoosingRepositories,
    onSearchTextChanged: (String) -> Unit,
    onSelectionToggled: (String) -> Unit,
) {
    OutlinedTextField(
        value = state.searchText,
        onValueChange = onSearchTextChanged,
        label = { Text("${state.ownerLogin} 의 레포 ${state.repositories.size}개 — 이름으로 찾기") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    if (state.matchedRepositories.isEmpty()) {
        Text(
            text = if (state.repositories.isEmpty()) {
                "${state.ownerLogin} 에 받아 올 레포가 없습니다."
            } else {
                "검색어에 걸린 레포가 없습니다."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    LazyColumn(modifier = Modifier.heightIn(max = LIST_MAX_HEIGHT)) {
        items(state.matchedRepositories, key = { it.fullName }) { repository ->
            RemoteRepositoryRow(
                repository = repository,
                alreadyCloned = state.isAlreadyCloned(repository),
                selected = repository.fullName in state.selectedRepositoryFullNames,
                onSelectionToggled = { onSelectionToggled(repository.fullName) },
            )
        }
    }
}

@Composable
private fun RemoteRepositoryRow(
    repository: RemoteRepository,
    alreadyCloned: Boolean,
    selected: Boolean,
    onSelectionToggled: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !alreadyCloned, onClick = onSelectionToggled)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = selected, onCheckedChange = { onSelectionToggled() }, enabled = !alreadyCloned)
        Text(
            text = repository.name,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            color = if (alreadyCloned) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
        if (repository.isPrivate) {
            Spacer(Modifier.width(8.dp))
            Text("private", style = MaterialTheme.typography.labelSmall, color = PRIVATE_REPOSITORY_COLOR)
        }
        if (alreadyCloned) {
            Spacer(Modifier.width(8.dp))
            // 목록에서 지우지 않는다. 지우면 "GitHub 에 없다" 와 "이미 받아 뒀다" 가 한 얼굴이 된다.
            Text(
                text = "이미 있음",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.weight(1f))
        Text(
            text = repository.lastUpdatedAt?.asLocalDate() ?: "-",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CloneResultBody(state: RepositoryCloneState.CloneFinished) {
    LazyColumn(
        modifier = Modifier.heightIn(max = LIST_MAX_HEIGHT),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(state.outcome.results, key = { it.repositoryFullName }) { result ->
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = result.status.rawValue,
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                        color = result.status.resultColor,
                        modifier = Modifier
                            .background(result.status.resultColor.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = result.repositoryFullName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                if (result.message.isNotBlank()) {
                    Text(
                        text = result.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
        }

        if (state.outcome.mainSessionRestartNeeded) {
            item {
                // 세션이 실행할 명령은 세션을 만드는 순간 정해진다. 조용히 넘어가면 사용자는 방금
                // 받은 레포가 메인 세션에서 왜 안 보이는지 알 수 없다.
                Text(
                    text = "새로 받은 레포는 이미 떠 있는 메인 세션에 붙지 않습니다 — 세션의 --add-dir 목록은 " +
                        "만들 때 정해집니다. 반영하려면 kyu kill main 뒤에 메인 세션을 다시 띄우세요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MAIN_SESSION_NOTICE_COLOR,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

private val RepositoryCloneState.title: String
    get() = when (this) {
        is RepositoryCloneState.Closed -> ""
        is RepositoryCloneState.StepRunning -> "레포 클론"
        is RepositoryCloneState.StepFailed -> "이 걸음을 마치지 못했습니다"
        is RepositoryCloneState.ChoosingTokenProfile -> "1 / 3 · 토큰 프로필"
        is RepositoryCloneState.ConfirmingTokenProfileRemoval -> "1 / 3 · 토큰 프로필 지우기"
        is RepositoryCloneState.RegisteringTokenProfile -> "1 / 3 · 새 토큰 등록"
        is RepositoryCloneState.TokenProfileRegistered -> "1 / 3 · 등록한 계정 확인"
        is RepositoryCloneState.ChoosingOwner -> "2 / 3 · 계정"
        is RepositoryCloneState.ChoosingRepositories -> "3 / 3 · 받아 올 레포"
        is RepositoryCloneState.CloneFinished -> "클론 결과"
    }

private val CloneStep.runningLabel: String
    get() = when (this) {
        is CloneStep.LoadTokenProfiles -> "등록된 토큰 프로필을 묻는 중입니다"
        is CloneStep.RegisterTokenProfile -> "$profileName 프로필로 토큰을 확인하고 저장하는 중입니다"
        is CloneStep.RemoveTokenProfile -> "$profileName 프로필과 그 토큰을 지우는 중입니다"
        is CloneStep.LoadOwners -> "이 토큰으로 볼 수 있는 계정을 묻는 중입니다"
        is CloneStep.LoadRepositories -> "$ownerLogin 의 레포 목록을 받아 오는 중입니다"
        is CloneStep.CloneRepositories -> "레포 ${repositoryFullNames.size}개를 받아 오는 중입니다"
    }

private val TokenStorage.label: String
    get() = when (this) {
        TokenStorage.Keychain -> "macOS 키체인"
        TokenStorage.SecretService -> "secret-service"
        // 평문이라는 사실이 전달되지 않으면 "파일" 은 안심하고 읽힌다.
        TokenStorage.ConfigFile -> "설정 파일 (평문)"
        is TokenStorage.Unrecognized -> rawValue
    }

private val OwnerKind.label: String
    get() = when (this) {
        OwnerKind.Personal -> "개인"
        OwnerKind.Organization -> "조직"
        is OwnerKind.Unrecognized -> rawValue
    }

private val CloneStatus.resultColor: Color
    get() = when (this) {
        CloneStatus.Cloned -> Color(0xFF2E7D32)
        // 건너뛴 것은 실패가 아니다 — 같은 이름이 이미 있었을 뿐이라 붉게 칠하지 않는다.
        CloneStatus.Skipped -> Color(0xFF6B6B6B)
        CloneStatus.Failed -> Color(0xFFB3261E)
        is CloneStatus.Unrecognized -> Color(0xFF6B6B6B)
    }

/**
 * 사람용 목록과 같은 형식으로 날짜만 찍는다(clone.go 의 updatedDateLayout).
 *
 * 계약은 UTC 로 주지만 여기서는 이 머신의 시간대로 옮긴다 — 사용자가 "어제" 를 알아보는 자리다.
 */
private fun Instant.asLocalDate(): String = LOCAL_DATE_FORMAT.format(this)

private val LOCAL_DATE_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault())

/**
 * 목록이 차지할 수 있는 높이의 위끝.
 *
 * 위끝이 있어야 하는 이유는 보기 좋음이 아니라 측정이다. 스크롤되는 것에 높이의 위끝이 없으면
 * Compose 는 "무한한 높이 안의 스크롤" 을 재지 못하고 그 자리에서 예외로 죽는다.
 */
private val LIST_MAX_HEIGHT = 300.dp

private val PRIVATE_REPOSITORY_COLOR = Color(0xFFB26A00)
private val PLAINTEXT_STORAGE_COLOR = Color(0xFFB26A00)
private val MAIN_SESSION_NOTICE_COLOR = Color(0xFF1565C0)
