package com.kyuchestration.desktop.terminal.kyucli

import com.kyuchestration.desktop.kyu.KyuCommandRunner
import com.kyuchestration.desktop.terminal.ChildSessionCommandAnswer
import com.kyuchestration.desktop.terminal.ChildSessionCommandRequest
import com.kyuchestration.desktop.terminal.ChildSessionCommandSource
import java.nio.file.Path

/**
 * kyu CLI 에게 하위 세션 하나가 무엇을 띄울지 묻는다(`kyu session-command --chat --child`).
 *
 * 하위가 무엇으로 뜨는지 — 하위 역할의 kyu 서버, 규약 문구, 대화 ID, cwd 규칙, 관문 — 는 엔진이 조립한다
 * (원칙 11). 앱이 넘기는 것은 상위가 고른 것과 앱만 아는 사실(누가 살아 있는가 · 사람이 무엇을 승인했는가)뿐이다.
 */
class KyuCliChildSessionCommandSource(
    private val kyuCommandRunner: KyuCommandRunner,
) : ChildSessionCommandSource {

    override fun childSessionCommandFor(
        workDirPath: Path,
        request: ChildSessionCommandRequest,
        appSocketPath: Path,
    ): ChildSessionCommandAnswer {
        val arguments = childSessionCommandArguments(request, appSocketPath)
        // 거절된 하위에게는 아직 이름이 없다 — 이름은 엔진이 기록에 적을 때 짓는다.
        val output = runSessionCommand(kyuCommandRunner, arguments, workDirPath, targetLabel = UNNAMED_CHILD_SESSION_LABEL)
        return parseKyuChildSessionCommandOutput(output.standardOutput, output.engineWarnings)
    }
}

/**
 * `kyu session-command --chat --child [--cwd …] [--create-directory] [--title …] [--live-child-cwd …]…
 * [--approve-fingerprint …] --app-socket <p> --json`.
 *
 * **`--child` 에 id 를 붙이지 않는다.** 엔진이 기록을 보고 `child-<n>` 을 지어 답의 `childSession.id` 로 돌려준다
 * (conversations.go 의 nextChildSessionID). 앱이 따로 지으면 이름을 짓는 길이 둘이 되어 어긋날 수 있다 — 앱의 수는
 * 앱을 켤 때마다 비지만 기록은 남는다.
 *
 * 상위가 생략한 것은 옵션도 생략한다. 생략의 풀이(루트 · cwd 의 마지막 이름)는 엔진의 몫이라, 앱이 빈 값을
 * 붙이면 그 풀이를 앱이 대신 정한 셈이 된다.
 *
 * `--bypass-permissions` 는 붙이지 않는다. 하위는 상위의 권한 모드를 따르는데(6.10), 이 앱은 메인을 bypass
 * 로 여는 길이 없다 — 그 길이 생기는 날 상위의 모드를 여기로 함께 넘긴다.
 */
private fun childSessionCommandArguments(request: ChildSessionCommandRequest, appSocketPath: Path): List<String> =
    buildList {
        add("session-command")
        add(CHAT_MODE_OPTION)
        add(CHILD_SESSION_OPTION)
        request.requestedCwd?.let { addAll(listOf(CHILD_CWD_OPTION, it)) }
        if (request.createDirectory) {
            add(CREATE_DIRECTORY_OPTION)
        }
        request.title?.let { addAll(listOf(CHILD_TITLE_OPTION, it)) }
        request.liveChildRelativeCwds.forEach { addAll(listOf(LIVE_CHILD_CWD_OPTION, it)) }
        request.approvedFingerprint?.let { addAll(listOf(APPROVE_FINGERPRINT_OPTION, it)) }
        addAll(listOf(APP_SOCKET_OPTION, appSocketPath.toString()))
        add("--json")
    }

// 하위 세션 옵션들(session_command_child.go 의 같은 이름 상수들).
private const val CHILD_SESSION_OPTION = "--child"
private const val CHILD_CWD_OPTION = "--cwd"
private const val CREATE_DIRECTORY_OPTION = "--create-directory"
private const val CHILD_TITLE_OPTION = "--title"
private const val LIVE_CHILD_CWD_OPTION = "--live-child-cwd"
private const val APPROVE_FINGERPRINT_OPTION = "--approve-fingerprint"

private const val UNNAMED_CHILD_SESSION_LABEL = "새 하위"
