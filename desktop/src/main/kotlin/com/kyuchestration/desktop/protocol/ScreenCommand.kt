package com.kyuchestration.desktop.protocol

import com.kyuchestration.desktop.terminal.PersonOpenedSessionTarget
import com.kyuchestration.desktop.terminal.SessionConversationChoice
import com.kyuchestration.desktop.terminal.SessionTarget
import com.kyuchestration.desktop.terminal.chat.PermissionCardChoice
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 화면이 코어에게 시키는 일 하나 — `ScreenFacingCore` 의 명령 하나가 틀 위에서 갖는 모양이다(core-ui-split-design.md 6.2 (나)).
 *
 * 이름은 목적어까지 적는다(6.4). 앱 소켓의 `kind`(`spawn` · `send` · `end` …)와 같은 낱말이 두 규약에서 다른 뜻이 되지 않게.
 */
@Serializable
sealed interface ScreenCommand {

    @Serializable
    @SerialName("install_engine")
    data object InstallEngine : ScreenCommand

    @Serializable
    @SerialName("look_for_engine_again")
    data object LookForEngineAgain : ScreenCommand

    @Serializable
    @SerialName("check_claude_credentials")
    data object CheckClaudeCredentials : ScreenCommand

    @Serializable
    @SerialName("start_claude_browser_login")
    data object StartClaudeBrowserLogin : ScreenCommand

    @Serializable
    @SerialName("submit_claude_browser_login_code")
    data class SubmitClaudeBrowserLoginCode(val code: String) : ScreenCommand

    @Serializable
    @SerialName("cancel_claude_browser_login")
    data object CancelClaudeBrowserLogin : ScreenCommand

    @Serializable
    @SerialName("use_claude_token")
    data class UseClaudeToken(val token: String) : ScreenCommand

    @Serializable
    @SerialName("open_workdir")
    data class OpenWorkDir(val hostPath: String) : ScreenCommand

    @Serializable
    @SerialName("initialize_opened_workdir")
    data object InitializeOpenedWorkDir : ScreenCommand

    @Serializable
    @SerialName("refresh_workdir")
    data object RefreshWorkDir : ScreenCommand

    @Serializable
    @SerialName("close_workdir")
    data object CloseWorkDir : ScreenCommand

    @Serializable
    @SerialName("enter_session")
    data class EnterSession(
        val target: PersonOpenedSessionTarget,
        val conversationChoice: SessionConversationChoice,
    ) : ScreenCommand

    @Serializable
    @SerialName("dismiss_session_entry_failure")
    data class DismissSessionEntryFailure(val target: PersonOpenedSessionTarget) : ScreenCommand

    @Serializable
    @SerialName("send_user_message")
    data class SendUserMessage(val target: SessionTarget, val text: String) : ScreenCommand

    @Serializable
    @SerialName("interrupt_turn")
    data class InterruptTurn(val target: SessionTarget) : ScreenCommand

    @Serializable
    @SerialName("answer_permission")
    data class AnswerPermission(
        val target: SessionTarget,
        val toolUseId: String,
        val choice: PermissionCardChoice,
    ) : ScreenCommand

    @Serializable
    @SerialName("end_session")
    data class EndSession(val target: SessionTarget) : ScreenCommand

    @Serializable
    @SerialName("answer_gate")
    data class AnswerGate(val gateQuestionId: String, val approved: Boolean) : ScreenCommand

    @Serializable
    @SerialName("open_repository_clone")
    data object OpenRepositoryClone : ScreenCommand

    @Serializable
    @SerialName("close_repository_clone")
    data object CloseRepositoryClone : ScreenCommand

    @Serializable
    @SerialName("choose_token_profile")
    data class ChooseTokenProfile(val profileName: String) : ScreenCommand

    @Serializable
    @SerialName("start_removing_token_profile")
    data class StartRemovingTokenProfile(val profileName: String) : ScreenCommand

    @Serializable
    @SerialName("stop_removing_token_profile")
    data object StopRemovingTokenProfile : ScreenCommand

    @Serializable
    @SerialName("remove_chosen_token_profile")
    data object RemoveChosenTokenProfile : ScreenCommand

    @Serializable
    @SerialName("start_registering_token_profile")
    data object StartRegisteringTokenProfile : ScreenCommand

    @Serializable
    @SerialName("stop_registering_token_profile")
    data object StopRegisteringTokenProfile : ScreenCommand

    @Serializable
    @SerialName("register_token_profile")
    data class RegisterTokenProfile(val profileName: String, val token: String) : ScreenCommand

    @Serializable
    @SerialName("continue_with_registered_token_profile")
    data object ContinueWithRegisteredTokenProfile : ScreenCommand

    @Serializable
    @SerialName("choose_repository_owner")
    data class ChooseRepositoryOwner(val ownerLogin: String) : ScreenCommand

    @Serializable
    @SerialName("change_repository_search_text")
    data class ChangeRepositorySearchText(val searchText: String) : ScreenCommand

    @Serializable
    @SerialName("toggle_repository_selection")
    data class ToggleRepositorySelection(val repositoryFullName: String) : ScreenCommand

    @Serializable
    @SerialName("clone_selected_repositories")
    data object CloneSelectedRepositories : ScreenCommand

    @Serializable
    @SerialName("retry_failed_clone_step")
    data object RetryFailedCloneStep : ScreenCommand
}
