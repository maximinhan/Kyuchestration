package com.kyuchestration.desktop.terminal.kyucli

import com.kyuchestration.desktop.kyu.KyuCommandFailure
import com.kyuchestration.desktop.kyu.KyuCommandRunner
import com.kyuchestration.desktop.terminal.ChildSessionCommandAnswer
import com.kyuchestration.desktop.terminal.ChildSessionCommandRequest
import com.kyuchestration.desktop.terminal.ChildSessionCommandSource
import com.kyuchestration.desktop.terminal.TerminalSessionFailure
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
        val output = try {
            runSessionCommand(kyuCommandRunner, arguments, workDirPath, targetLabel = UNNAMED_CHILD_SESSION_LABEL)
        } catch (refusal: TerminalSessionFailure.SessionCommandRefused) {
            throw engineTooOldOrNull(refusal) ?: refusal
        }
        return parseKyuChildSessionCommandOutput(output.standardOutput, output.engineWarnings)
    }

    /**
     * 거절한 kyu 가 [MINIMUM_ENGINE_VERSION] 보다 낮은 릴리스면 그 사실로 옮긴다. 가를 수 없으면 null.
     *
     * **거절된 뒤에만 묻는다.** 답한 엔진은 이미 이 옵션들을 아는 엔진이라 물을 까닭이 없고, spawn 마다 프로세스를
     * 하나 더 띄울 까닭도 없다.
     *
     * **릴리스 판만 견준다.** 저장소에서 빌드한 kyu 는 `v0.13.1-0.<시각>-<커밋>` 꼴의 유사 판을 답하는데(version.go),
     * v0.13.0 뒤의 커밋은 PR #70 앞이든 뒤든 모두 이 꼴이라 수로는 가를 수 없다. 그때는 엔진의 거절을 그대로 둔다.
     */
    private fun engineTooOldOrNull(refusal: TerminalSessionFailure.SessionCommandRefused): TerminalSessionFailure.EngineTooOld? {
        val versionResult = try {
            kyuCommandRunner.run(listOf(VERSION_COMMAND))
        } catch (couldNotAsk: KyuCommandFailure) {
            return null
        }
        if (versionResult.exitCode != 0) {
            return null
        }
        val released = RELEASED_ENGINE_VERSION_PATTERN.find(versionResult.standardOutput)?.groupValues ?: return null
        val actual = released.drop(1).map(String::toInt)
        val minimum = MINIMUM_ENGINE_VERSION.split('.').map(String::toInt)
        val olderThanMinimum = actual.zip(minimum).firstOrNull { (a, m) -> a != m }?.let { (a, m) -> a < m } ?: false
        if (!olderThanMinimum) {
            return null
        }
        return TerminalSessionFailure.EngineTooOld(
            actualVersion = actual.joinToString("."),
            minimumVersion = MINIMUM_ENGINE_VERSION,
            refusal = refusal,
        )
    }
}

/**
 * 이 앱이 하위 세션에 요구하는 엔진의 최소 릴리스.
 *
 * `--child` 의 id 생략(PR #70)이 처음 실리는 릴리스다. v0.13.0 이 그 앞의 마지막 릴리스이고, 1.0 전에는 마이너만
 * 올리므로 다음 릴리스가 0.14.0 이다.
 */
private const val MINIMUM_ENGINE_VERSION = "0.14.0"

private const val VERSION_COMMAND = "version"

/** `kyu v0.13.0 (3edd9cf05752)` 꼴의 릴리스 판. `-` 이 붙은 유사 판(`v0.13.1-0.2026…`)은 맞지 않는다. */
private val RELEASED_ENGINE_VERSION_PATTERN = Regex("""^kyu v(\d+)\.(\d+)\.(\d+)(?:\s|$)""")

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
