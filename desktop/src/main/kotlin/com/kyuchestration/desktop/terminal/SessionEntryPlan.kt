package com.kyuchestration.desktop.terminal

import java.nio.file.Path

/**
 * 세션 하나에 들어가기 위해 실행할 것 전부.
 *
 * PTY 를 여는 코드와 갈라 둔다. 여기서 정해지는 것(어느 명령을 어떤 인자로, 어느 디렉토리에서,
 * 어떤 환경으로 부르는가)은 프로세스를 띄우지 않고도 전부 시험할 수 있는 판단이고, 실제로 틀리기
 * 쉬운 자리도 거기다.
 */
data class SessionEntryPlan(
    /** 붙기 전에 먼저 실행할 명령. 세션이 이미 떠 있으면 kyu 가 안내만 하고 성공으로 끝낸다. */
    val startCommand: List<String>,
    /** PTY 안에서 실행할 명령. 사용자가 빠져나올 때까지 살아 있는다. */
    val attachCommand: List<String>,
    val workingDirectory: Path,
    val environment: Map<String, String>,
)

/**
 * 진입 계획을 세운다.
 *
 * start 를 조건 없이 앞에 둔다. 대시보드가 아는 상태는 최대 3 초 묵은 것이라 "RUNNING 이니
 * 건너뛴다" 는 판단은 그 사이에 죽은 세션에서 틀리고, 그때 사용자가 보는 것은 터미널에 찍힌
 * "세션이 없습니다" 뿐이다. kyu start 는 이미 떠 있는 세션을 실패로 보지 않고 안내만 하고
 * 끝내도록 만들어져 있으므로(start.go 의 createSession), 두 번 불러도 상태가 달라지지 않는다.
 *
 * @param parentEnvironment 이 앱이 물려받은 환경. 자식에게 그대로 물려주되 두 가지만 손본다.
 */
internal fun planSessionEntry(
    kyuExecutablePath: Path,
    workDirPath: Path,
    target: SessionTarget,
    parentEnvironment: Map<String, String>,
): SessionEntryPlan {
    val kyu = kyuExecutablePath.toString()

    val startArguments = when (target) {
        // kyu start 는 인자 없는 실행을 메인 세션으로 읽는다(start.go 의 parseStartArgs).
        is SessionTarget.Main -> emptyList()
        is SessionTarget.Repo -> listOf(target.repoName)
    }

    return SessionEntryPlan(
        startCommand = listOf(kyu, "start") + startArguments,
        // attach 는 메인 세션도 이름으로 가리킨다 — 인자를 빠뜨린 것을 메인 진입으로 해석하지
        // 않는 것이 그쪽의 판단이다(attach.go 의 parseAttachArgs).
        attachCommand = listOf(kyu, "attach", target.label),
        // 두 명령 모두 경로 인자가 없다. 실행된 디렉토리가 곧 워크디렉토리다.
        workingDirectory = workDirPath,
        environment = sessionEnvironment(parentEnvironment),
    )
}

private fun sessionEnvironment(parentEnvironment: Map<String, String>): Map<String, String> {
    val environment = parentEnvironment.toMutableMap()

    // 이 앱을 tmux 안에서 띄웠으면 TMUX 가 딸려 온다. 그 값은 이 GUI 프로세스의 사실이지
    // 앱 안에서 새로 연 PTY 의 사실이 아니다. 그대로 넘기면 kyu attach 가 중첩으로 보고
    // "이 터미널은 이미 세션 안입니다" 로 거절한다(tmux.go 의 insideTmuxEnvName).
    environment.remove(INSIDE_TMUX_ENV_NAME)

    // JediTerm 이 흉내 내는 것이 xterm 계열이다. 물려받은 TERM(데스크톱 런처에서 띄우면 아예
    // 없고, 셸에서 띄우면 그 셸의 값)을 그대로 두면 세션 안의 프로그램이 다른 화면을 그린다.
    environment[TERMINAL_TYPE_ENV_NAME] = TERMINAL_TYPE

    return environment
}

private const val INSIDE_TMUX_ENV_NAME = "TMUX"
private const val TERMINAL_TYPE_ENV_NAME = "TERM"
private const val TERMINAL_TYPE = "xterm-256color"
