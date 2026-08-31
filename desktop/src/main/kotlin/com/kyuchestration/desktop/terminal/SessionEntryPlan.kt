package com.kyuchestration.desktop.terminal

import java.nio.file.Path

/**
 * 세션 하나를 앱의 PTY 에서 띄우기 위해 넘길 것 전부.
 *
 * PTY 를 여는 코드와 갈라 둔다. 여기서 정해지는 것(어느 명령을 어느 디렉토리에서 어떤 환경으로
 * 부르는가)은 프로세스를 띄우지 않고도 전부 시험할 수 있는 판단이고, 실제로 틀리기 쉬운 자리도
 * 거기다.
 *
 * @param environment 자식에게 물려줄 환경 **전부**. 엔진이 답한 "더할 것"([SessionCommandAnswer])
 *   과 뜻이 다른 자리라 두 타입을 합치지 않는다.
 */
data class SessionEntryPlan(
    val command: List<String>,
    val workingDirectory: Path,
    val environment: Map<String, String>,
)

/**
 * 엔진의 답을 PTY 에 그대로 넘길 수 있는 모양으로 옮긴다.
 *
 * **명령과 자리는 손대지 않는다.** 그것은 엔진의 답이고, 여기서 한 글자라도 고치면 조립 지식이
 * 두 곳에 있게 된다(설계 원칙 11). 이 함수가 하는 일은 환경을 합치는 것 하나다.
 *
 * @param baseEnvironment 자식에게 물려줄 바탕 환경(ChildProcessEnvironment 가 정한다).
 *   엔진의 답에 실행 파일 경로가 아니라 `claude` 라는 이름이 들어 있으므로(계약이 그렇다),
 *   그것을 찾는 PATH 가 여기서 정해진다.
 */
internal fun planSessionEntry(
    sessionCommandAnswer: SessionCommandAnswer,
    baseEnvironment: Map<String, String>,
    workDirPath: Path,
    target: SessionTarget,
): SessionEntryPlan = SessionEntryPlan(
    command = sessionCommandAnswer.command,
    workingDirectory = sessionCommandAnswer.workingDirectory,
    environment = sessionEnvironment(
        environmentToAdd = sessionCommandAnswer.environmentToAdd,
        baseEnvironment = baseEnvironment,
        workDirPath = workDirPath,
        target = target,
    ),
)

private fun sessionEnvironment(
    environmentToAdd: Map<String, String>,
    baseEnvironment: Map<String, String>,
    workDirPath: Path,
    target: SessionTarget,
): Map<String, String> {
    val environment = baseEnvironment.toMutableMap()

    // 이 앱을 tmux 안에서 띄웠으면 TMUX 가, 감독 세션 안에서 띄웠으면 KYU_SESSION 이 딸려 온다.
    // 그 값은 이 GUI 프로세스의 사실이지 앱 안에서 새로 연 PTY 의 사실이 아니다. 예전에는
    // kyu attach 가 중첩으로 보고 거절하기 때문에 덜어냈고, 이제는 claude 가 그 값을 보고 다르게
    // 굴 수 있기 때문에 덜어낸다(claude --help 에 --tmux 가 있다).
    // 앱 안의 PTY 는 어느 세션 안도 아니라는 사실은 어느 쪽이든 참이다.
    environment.remove(INSIDE_TMUX_ENV_NAME)
    environment.remove(INSIDE_SUPERVISED_SESSION_ENV_NAME)

    // JediTerm 이 흉내 내는 것이 xterm 계열이다. 물려받은 TERM(데스크톱 런처에서 띄우면 아예
    // 없고, 셸에서 띄우면 그 셸의 값)을 그대로 두면 세션 안의 프로그램이 다른 화면을 그린다.
    // 이제 claude 가 이 값을 직접 읽으므로 전보다 중요해졌다 — 사이에 tmux 가 없다.
    environment[TERMINAL_TYPE_ENV_NAME] = TERMINAL_TYPE

    // 앱이 죽어도 SIGHUP 을 무시하고 살아남은 자식은 kyu list 에도 앱에도 보이지 않는다.
    // 그때 그 프로세스를 찾을 수 있는 유일한 자리가 ps 이고, 이 표식이 거기서 눈에 띈다
    // (`ps eww` 가 환경을 함께 그린다). 설계 문서 8 절 1 번의 (가)다.
    environment[APP_SESSION_MARKER_ENV_NAME] = "${workDirPath.fileName ?: workDirPath}-${target.label}"

    // 엔진의 답이 맨 위다. 앱이 다시 덮으면 계약이 "더하라" 고 답한 것이 조용히 무시된다.
    environment.putAll(environmentToAdd)

    return environment
}

private const val INSIDE_TMUX_ENV_NAME = "TMUX"

/** 감독이 자기 세션 안의 프로세스에 심는 표식(session/supervise.go 의 insideSupervisorSessionEnvName). */
private const val INSIDE_SUPERVISED_SESSION_ENV_NAME = "KYU_SESSION"

/** 앱이 보유한 세션임을 자식 환경에 남기는 표식. `<워크디렉토리 이름>-<라벨>` 이 값이다. */
internal const val APP_SESSION_MARKER_ENV_NAME = "KYU_APP_SESSION"

private const val TERMINAL_TYPE_ENV_NAME = "TERM"
private const val TERMINAL_TYPE = "xterm-256color"
