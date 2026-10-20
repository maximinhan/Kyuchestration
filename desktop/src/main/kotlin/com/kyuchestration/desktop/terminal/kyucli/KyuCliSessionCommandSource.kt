package com.kyuchestration.desktop.terminal.kyucli

import com.kyuchestration.desktop.kyu.KyuCommandFailure
import com.kyuchestration.desktop.kyu.KyuCommandRunner
import com.kyuchestration.desktop.terminal.PersonOpenedSessionTarget
import com.kyuchestration.desktop.terminal.SessionCommandAnswer
import com.kyuchestration.desktop.terminal.SessionCommandSource
import com.kyuchestration.desktop.terminal.SessionConversationChoice
import com.kyuchestration.desktop.terminal.SessionTarget
import com.kyuchestration.desktop.terminal.TerminalSessionFailure
import java.nio.file.Path

/**
 * kyu CLI 에게 이 세션이 무엇을 띄울지 묻는다.
 *
 * 앱은 세션 명령을 스스로 조립하지 않는다. 그 지식은 엔진 안에 있고, 이 표면이 그것을
 * 프로세스 경계 너머로 건네준다(설계 문서 5.2).
 *
 * **늘 챗 모드로 묻는다.** 어느 모드를 물을지 고르는 자리가 있었지만, 앱이 여는 화면이 챗
 * 하나가 되면서(chat-ui-design.md 9 절 7 단계) 고를 것이 남지 않았다. 엔진의 터미널 모드는
 * 그대로 있다 — 사람이 자기 터미널에서 `kyu session-command` 를 치는 길은 앱과 무관하다.
 */
class KyuCliSessionCommandSource(
    private val kyuCommandRunner: KyuCommandRunner,
) : SessionCommandSource {

    override fun sessionCommandFor(
        workDirPath: Path,
        target: PersonOpenedSessionTarget,
        conversationChoice: SessionConversationChoice,
        appSocketPath: Path?,
    ): SessionCommandAnswer {
        val arguments = sessionCommandArguments(target, conversationChoice, appSocketPath)

        val standardOutput = runSessionCommand(kyuCommandRunner, arguments, workDirPath, targetLabel = target.label)

        return parseKyuSessionCommandOutput(standardOutput)
    }
}

/**
 * `kyu session-command` 를 워크디렉토리에서 부르고, 답한 stdout 을 돌려준다.
 *
 * 사람이 연 세션과 하위 세션이 함께 쓴다. 둘 다 "부르지 못했다 · 거절했다" 를 세션에 들어가려던 쪽에게 할
 * 말로 옮기는 일이 같다 — 다른 것은 묻는 옵션과 답을 읽는 모양뿐이다.
 *
 * @param targetLabel 거절 문구가 어느 세션의 것인지 말할 이름.
 */
internal fun runSessionCommand(
    kyuCommandRunner: KyuCommandRunner,
    arguments: List<String>,
    workDirPath: Path,
    targetLabel: String,
): String {
    val result = try {
        // 자리를 인자가 아니라 작업 디렉토리로 준다. 이 명령은 경로 인자를 받지 않고
        // 실행된 디렉토리를 워크디렉토리로 읽는다(session_command.go 의 currentWorkDir).
        kyuCommandRunner.run(arguments, workingDirectory = workDirPath)
    } catch (failure: KyuCommandFailure) {
        // 실행기는 "부르지 못했다" 는 사실만 던진다. 그 사실을 세션에 들어가려던 사람에게 할
        // 말로 옮기는 것이 이 어댑터의 몫이다.
        throw when (failure) {
            is KyuCommandFailure.ExecutableNotFound -> TerminalSessionFailure.KyuExecutableNotFound()
            is KyuCommandFailure.FailedToStart ->
                TerminalSessionFailure.KyuFailedToStart(failure.processStartFailure)
        }
    }

    if (result.exitCode != 0) {
        throw TerminalSessionFailure.SessionCommandRefused(
            targetLabel = targetLabel,
            exitCode = result.exitCode,
            arguments = arguments,
            standardError = result.standardError.trim(),
        )
    }

    return result.standardOutput
}

/**
 * `kyu session-command [repo] --json`.
 *
 * 메인 세션은 레포 이름 자리를 비운다 — 이 명령은 인자 없는 실행을 메인 세션으로 읽는다
 * (session_command.go 의 parseSessionCommandArgs). when 으로 가르면 세션 종류가 하나 늘 때
 * 이 파일이 컴파일되지 않는다.
 */
private fun sessionCommandArguments(
    target: PersonOpenedSessionTarget,
    conversationChoice: SessionConversationChoice,
    appSocketPath: Path?,
): List<String> {
    val repoArgument = when (target) {
        is SessionTarget.Main -> emptyList()
        is SessionTarget.Repo -> listOf(target.repoName)
    }

    // 이어가기에는 옵션이 붙지 않는다. 그것이 이 표면의 기본이고, 앱이 매번 "이어가라" 고
    // 말해야 한다면 그 말을 빠뜨린 자리에서 대화가 조용히 새로 열린다.
    val conversationOption = when (conversationChoice) {
        SessionConversationChoice.ContinueRecordedConversation -> emptyList()
        SessionConversationChoice.StartNewConversation -> listOf(FORGET_CONVERSATION_OPTION)
    }

    // 앱 소켓은 앱이 정하고 엔진이 옮긴다(chat-ui-design.md 5.2). 엔진은 이 경로를 --mcp-config 의
    // 서버 인자들로 옮기는데(session-tree-design.md 6.4), 어느 서버에 실을지를 앱이 알기 시작하면
    // 지식이 두 곳에 놓인다 — 앱이 아는 것은 "우리가 이 자리에서 듣고 있다" 하나다(원칙 11).
    val appSocketOption = appSocketPath
        ?.let { listOf(APP_SOCKET_OPTION, it.toString()) }
        .orEmpty()

    // 챗 모드는 이 한 낱말로 청한다. 그것이 어떤 플래그로 옮겨지는지는 엔진만 안다
    // (claude_command.go 의 chatModeFlags) — 앱이 그 목록을 알기 시작하면 조립 지식이 두 곳에
    // 놓인다(설계 원칙 11).
    return listOf("session-command") + repoArgument + conversationOption + CHAT_MODE_OPTION +
        appSocketOption + "--json"
}

/** 앱이 이 세션을 위해 연 소켓의 자리를 넘기는 옵션(app_socket.go 의 appSocketOptionName). */
internal const val APP_SOCKET_OPTION = "--app-socket"

/** 파이프로 부릴 스트림 모드로 답하라는 옵션(session_command.go 의 chatModeOptionName). */
internal const val CHAT_MODE_OPTION = "--chat"

/**
 * 적혀 있는 대화를 버리고 새 대화로 답하라는 옵션(session_command.go 의 forgetConversationOptionName).
 *
 * 화면의 낱말("새 대화로 시작")과 다른 이름인 것을 짚어둔다. 앱이 사용자에게 말하는 것은 바라는
 * 것 쪽이고, 엔진에게 말하는 것은 되돌릴 수 없는 일 쪽이다 — 그 옮김이 이 어댑터의 몫이다.
 */
private const val FORGET_CONVERSATION_OPTION = "--forget-conversation"
