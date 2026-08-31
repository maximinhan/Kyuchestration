package com.kyuchestration.desktop.terminal

import java.nio.file.Path

/**
 * "이 세션은 무엇을 띄우는가" 에 대한 엔진의 답.
 *
 * **조립 지식은 엔진에 하나만 둔다**(설계 문서 원칙 11). 메인 세션이 어느 레포를 `--add-dir` 로
 * 달고 있는지, `--bypass-permissions` 가 `claude` 의 어느 플래그로 옮겨지는지, `--repo-claude-md`
 * 가 어느 환경변수가 되는지는 `kyu start` 가 이미 아는 것이다. 그것을 코틀린으로 한 번 더 옮기면
 * 두 구현이 조용히 갈라지고, 갈라진 것을 발견하는 자리는 "메인 세션이 레포 지침을 안 읽는다" 라
 * 그때 어느 쪽이 틀렸는지 알아내는 데 오래 걸린다.
 *
 * @param command PTY 에서 실행할 argv. 순서가 곧 계약이다(session_command_json.go).
 * @param workingDirectory 그 프로세스의 작업 디렉토리. 레포 세션에서 이 한 줄이 그 레포의
 *   `.mcp.json`·`CLAUDE.md`·에이전트를 살린다.
 * @param environmentToAdd 앱이 자기 바탕 환경에 **더할** 것. 전체 환경이 아니다 — 엔진은 앱이
 *   자식에게 물려줄 바탕 환경([com.kyuchestration.desktop.platform.childProcessEnvironment])을
 *   모른다. [SessionEntryPlan] 과 담는 것이 닮았어도 뜻이 다른 자리가 여기라, 두 타입을 하나로
 *   합치지 않는다 — 합치면 "더할 환경" 과 "물려줄 환경 전부" 를 같은 필드가 가리키게 된다.
 * @param resumedConversationId 이 명령이 이어가는 대화의 ID. 새 대화면 null.
 *
 *   **앱이 이어가기 실패를 가려내는 근거가 이것 하나다**(설계 문서 5.5.4). 적혀 있던 대화의
 *   전사가 사라졌으면 `claude` 는 즉시 종료 코드 1 로 끝나는데, 앱이 보는 것은 곧바로 닫힌
 *   PTY 뿐이라 사용자가 `/exit` 한 것과 구분되지 않는다.
 *
 *   명령 안에도 같은 값이 있지만 argv 를 되읽지 않는다. 그러면 앱이 `--resume` 뒤의 자리를
 *   아는 셈이 되고, 그 앎은 엔진에만 있어야 한다(설계 원칙 11).
 */
data class SessionCommandAnswer(
    val command: List<String>,
    val workingDirectory: Path,
    val environmentToAdd: Map<String, String>,
    val resumedConversationId: String?,
)
