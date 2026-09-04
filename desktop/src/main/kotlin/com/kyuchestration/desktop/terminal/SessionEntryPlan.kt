package com.kyuchestration.desktop.terminal

import java.nio.file.Path

/**
 * 세션 하나를 띄우기 위해 넘길 것 전부.
 *
 * 프로세스를 띄우는 코드와 갈라 둔다. 여기서 정해지는 것(어느 명령을 어느 디렉토리에서 어떤
 * 환경으로 부르는가)은 프로세스를 띄우지 않고도 전부 시험할 수 있는 판단이고, 실제로 틀리기
 * 쉬운 자리도 거기다.
 *
 * **PTY 전용이 아니다.** 챗 모드는 같은 계획을 파이프 셋에 문다
 * ([com.kyuchestration.desktop.terminal.chat.ChatSessionOpener]) — 엔진이 답한 argv·cwd·env 를
 * 실행한다는 사실은 화면 모델이 바뀌어도 그대로이고, 무엇에 물리느냐만 다르다
 * (chat-ui-design.md 5.3.1).
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
 * 엔진의 답을 그대로 실행할 수 있는 모양으로 옮긴다.
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

    // 사용자가 자기 tmux 안에서 이 앱을 띄웠으면 TMUX 가 딸려 온다. 그 값은 GUI 프로세스가
    // 어디서 떴는지에 대한 사실이지 앱 안에서 새로 연 PTY 의 사실이 아니고, 그대로 넘기면
    // claude 가 자기가 tmux 안에 있다고 믿는다(claude --help 에 --tmux 가 있다).
    environment.remove(INSIDE_TMUX_ENV_NAME)

    // JediTerm 이 흉내 내는 것이 xterm 계열이다. 물려받은 TERM(데스크톱 런처에서 띄우면 아예
    // 없고, 셸에서 띄우면 그 셸의 값)을 그대로 두면 세션 안의 프로그램이 다른 화면을 그린다.
    // claude 가 이 값을 직접 읽는다 — 사이에 아무것도 없다.
    environment[TERMINAL_TYPE_ENV_NAME] = TERMINAL_TYPE

    // 앱이 죽어도 SIGHUP 을 무시하고 살아남은 자식은 앱 어디에도 보이지 않는다. 그때 그
    // 프로세스를 찾을 수 있는 유일한 자리가 ps 이고, 이 표식이 거기서 눈에 띈다
    // (`ps eww` 가 환경을 함께 그린다). 설계 문서 8 절 1 번의 (가)다.
    environment[APP_SESSION_MARKER_ENV_NAME] = "${workDirPath.fileName ?: workDirPath}-${target.label}"

    // 엔진의 답이 맨 위다. 앱이 다시 덮으면 계약이 "더하라" 고 답한 것이 조용히 무시된다.
    environment.putAll(environmentToAdd)

    return environment
}

private const val INSIDE_TMUX_ENV_NAME = "TMUX"

/** 앱이 보유한 세션임을 자식 환경에 남기는 표식. `<워크디렉토리 이름>-<라벨>` 이 값이다. */
internal const val APP_SESSION_MARKER_ENV_NAME = "KYU_APP_SESSION"

private const val TERMINAL_TYPE_ENV_NAME = "TERM"
private const val TERMINAL_TYPE = "xterm-256color"
