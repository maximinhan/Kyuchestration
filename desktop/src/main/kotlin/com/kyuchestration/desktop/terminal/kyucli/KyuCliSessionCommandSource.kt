package com.kyuchestration.desktop.terminal.kyucli

import com.kyuchestration.desktop.kyu.KyuCommandFailure
import com.kyuchestration.desktop.kyu.KyuCommandRunner
import com.kyuchestration.desktop.terminal.SessionCommandAnswer
import com.kyuchestration.desktop.terminal.SessionCommandSource
import com.kyuchestration.desktop.terminal.SessionConversationChoice
import com.kyuchestration.desktop.terminal.SessionMode
import com.kyuchestration.desktop.terminal.SessionTarget
import com.kyuchestration.desktop.terminal.TerminalSessionFailure
import java.nio.file.Path

/**
 * kyu CLI 에게 이 세션이 무엇을 띄울지 묻는다.
 *
 * 앱은 세션 명령을 스스로 조립하지 않는다. 그 지식은 엔진 안에 있고, 이 표면이 그것을
 * 프로세스 경계 너머로 건네준다(설계 문서 5.2).
 *
 * @param sessionMode 무엇으로 부릴 세션을 물을지. 부르는 자리마다 정하지 않고 여기서 한 번
 *   정하는 이유는, 이 값이 물음이 아니라 **묻는 쪽이 어떤 화면인가**여서다 — 챗 화면은 늘
 *   챗 모드를 묻고 터미널 화면은 늘 터미널 모드를 묻는다. 기본값이 터미널인 것은 3 단계가
 *   화면을 갈아 끼우기 전까지 앱이 여는 것이 터미널이기 때문이다.
 */
class KyuCliSessionCommandSource(
    private val kyuCommandRunner: KyuCommandRunner,
    private val sessionMode: SessionMode = SessionMode.Terminal,
) : SessionCommandSource {

    override fun sessionCommandFor(
        workDirPath: Path,
        target: SessionTarget,
        conversationChoice: SessionConversationChoice,
    ): SessionCommandAnswer {
        val arguments = sessionCommandArguments(target, conversationChoice, sessionMode)

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
                targetLabel = target.label,
                exitCode = result.exitCode,
                arguments = arguments,
                standardError = result.standardError.trim(),
            )
        }

        return parseKyuSessionCommandOutput(result.standardOutput)
    }
}

/**
 * `kyu session-command [repo] --json`.
 *
 * 메인 세션은 레포 이름 자리를 비운다 — 이 명령은 인자 없는 실행을 메인 세션으로 읽는다
 * (session_command.go 의 parseSessionCommandArgs). when 으로 가르면 세션 종류가 하나 늘 때
 * 이 파일이 컴파일되지 않는다.
 */
private fun sessionCommandArguments(
    target: SessionTarget,
    conversationChoice: SessionConversationChoice,
    sessionMode: SessionMode,
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

    // 챗 모드에는 옵션이 붙는다. 붙는 것은 이 한 낱말뿐이고, 그것이 어떤 플래그로 옮겨지는지는
    // 엔진만 안다(claude_command.go 의 chatModeFlags) — 앱이 그 목록을 알기 시작하면 조립 지식이
    // 두 곳에 놓인다(설계 원칙 11).
    val modeOption = when (sessionMode) {
        SessionMode.Terminal -> emptyList()
        SessionMode.Chat -> listOf(CHAT_MODE_OPTION)
    }

    return listOf("session-command") + repoArgument + conversationOption + modeOption + "--json"
}

/** 파이프로 부릴 스트림 모드로 답하라는 옵션(session_command.go 의 chatModeOptionName). */
private const val CHAT_MODE_OPTION = "--chat"

/**
 * 적혀 있는 대화를 버리고 새 대화로 답하라는 옵션(session_command.go 의 forgetConversationOptionName).
 *
 * 화면의 낱말("새 대화로 시작")과 다른 이름인 것을 짚어둔다. 앱이 사용자에게 말하는 것은 바라는
 * 것 쪽이고, 엔진에게 말하는 것은 되돌릴 수 없는 일 쪽이다 — 그 옮김이 이 어댑터의 몫이다.
 */
private const val FORGET_CONVERSATION_OPTION = "--forget-conversation"
