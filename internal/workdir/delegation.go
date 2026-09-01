package workdir

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os/exec"
	"strings"
	"time"
)

// 이 파일은 위임 하나를 실행하는 자리다 — 메인 세션이 레포에 시킨 일을 그 레포 cwd 에서 도는
// 헤드리스 claude 로 옮긴다(orchestration-tools-design.md 5.4).
//
// state.go 가 git 을 부르는 것과 같은 선의 코드다. 조율 계층이 바깥 명령을 실제로 실행하는
// 자리이고, 표시 계층은 그 결과를 옮기기만 한다.
//
// 위임은 세션이 아니다. PTY 가 없고 사람이 붙지 않는다 — 그래서 승인 프롬프트에 답할 상대가
// 없고, 권한을 미리 정해야 한다(설계 문서 2 절).

// delegatedCommandName 은 위임이 실행하는 명령이다.
//
// 앱이 세션에서 띄우는 명령 이름(internal/cli/claude_command.go 의 sessionCommandName)과 글자가
// 같지만 다른 사실이다. 그쪽은 "앱이 PTY 에서 무엇을 띄우는가" 이고 이쪽은 "엔진이 무엇을
// 자식으로 실행하는가" 다. 한 상수로 묶으면 조율 계층이 표시 계층을 의존하게 된다.
const delegatedCommandName = "claude"

// printFlagName 은 사람용 화면 없이 답 하나를 내고 끝내는 플래그다.
const printFlagName = "-p"

// autoPermissionMode 는 위임의 기본 권한이다.
//
// acceptEdits 가 답이 아니라는 것이 실측의 요점이다(설계 문서 3.3). 이름만 보면 위임에 알맞아
// 보이는데 MCP 도구 호출을 막는다 — 그것을 고르면 사용자 요구의 핵심인 "해당 레포의 MCP 설정을
// 다 활용한 채로" 가 성립하지 않는다. 서버는 붙어 있는데 부를 수가 없다.
//
// --allowedTools 로 도구 이름을 나열하는 길도 되지만, 그러려면 그 레포의 MCP 서버가 무슨 도구를
// 갖고 있는지 미리 알아야 하고 그것은 서버를 띄워봐야 아는 것이다 — 위임마다 서버를 두 번 띄운다.
const autoPermissionMode = "auto"

// delegationOutputDrainLimit 은 위임을 죽인 뒤 그 출력을 마저 받는 데 주는 시간이다.
//
// 이 값이 필요한 이유는 손자 프로세스다. 상한에 걸려 claude 를 죽여도 claude 가 띄운 프로세스는
// 살아서 우리가 준 파이프를 붙들 수 있고, 그러면 Wait 이 그것이 끝날 때까지 돌아오지 않는다 —
// 시간 상한을 두고도 그만큼 더 기다리게 된다.
//
// 2 초인 것은 이 시간이 "이미 쓰인 출력을 마저 읽는" 데만 쓰이기 때문이다. 정상적으로 끝난
// 위임은 이 자리에 오지 않는다.
const delegationOutputDrainLimit = 2 * time.Second

// DelegationRequest 는 위임 한 번에 필요한 것 전부다.
type DelegationRequest struct {
	// Repo 는 이 위임이 도는 레포다. 그 절대경로가 곧 cwd 이고, 그 한 줄이 레포의 설정 넷을 살린다.
	Repo Repo

	// Prompt 는 메인이 쓴, 그 레포에서 할 일이다.
	Prompt string

	// ConversationID 는 이 위임이 쓸 대화다.
	ConversationID string

	// ResumeConversation 이 참이면 --resume 으로, 거짓이면 --session-id 로 넘긴다.
	//
	// 한 번 쓴 ID 는 --session-id 로 다시 쓸 수 없으므로(설계 문서 3.5) 이 갈래를 부르는 쪽이
	// 반드시 정해 주어야 한다.
	ResumeConversation bool

	// BypassPermissions 는 메인 세션이 bypass 로 열렸는지다. 위임이 스스로 켜는 값이 아니다
	// (설계 원칙 13).
	BypassPermissions bool
}

// DelegationOutcome 은 위임 한 번의 결과다.
//
// 성공과 실패를 한 타입에 담는다. 이 자리에서 에러로 올려야 하는 것은 "실행조차 못 했다" 뿐이고,
// claude 가 답한 것은 그 내용이 무엇이든 결과다 — 종료 코드 1 도, 권한 거절도, 시간 초과도
// 메인에게 전해야 하는 사실이다(설계 문서 5.4.2).
type DelegationOutcome struct {
	// Result 는 위임의 답 본문이다.
	Result string

	// PermissionDeniedTools 는 권한에 막혀 부르지 못한 도구 이름들이다.
	//
	// **이것이 이 타입에서 가장 중요한 필드다.** 권한에 막힌 실행도 종료 코드 0 · subtype success
	// 로 끝나므로(설계 문서 3.3), 이 배열 말고는 아무것도 하지 못한 위임을 알아볼 길이 없다.
	PermissionDeniedTools []string

	// ConversationID 는 claude 가 답한 이 위임의 대화 ID 다.
	ConversationID string

	CostUSD    float64
	DurationMS int64
	NumTurns   int

	// ExitCode 는 claude 프로세스의 종료 코드다.
	ExitCode int

	// Stderr 는 claude 가 남긴 이유다. --resume 실패가 이 길로 온다(설계 문서 3.5).
	Stderr string

	// TimedOut 은 상한에 걸려 우리가 끊었는지다.
	TimedOut bool

	// Elapsed 는 위임이 실제로 걸린 벽시계 시간이다. 끊었을 때 "얼마 만에" 를 답하는 근거다.
	Elapsed time.Duration

	// LogPath 는 원출력을 남긴 파일이다(설계 문서 5.4.3).
	LogPath string
}

// conversationGoneMarker 는 claude 가 없는 대화를 이어가려 할 때 stderr 에 남기는 말이다(설계 문서 3.5).
const conversationGoneMarker = "No conversation found with session ID"

// ConversationIsGone 은 이어가려던 대화의 전사가 사라졌는지다.
//
// 폴백이 앱보다 쉬운 자리다(설계 문서 5.5). 앱은 PTY 안의 실패를 가려내야 하지만, 위임은 엔진이
// 프로세스를 직접 실행하므로 종료 코드와 stderr 를 그대로 본다.
func (outcome DelegationOutcome) ConversationIsGone() bool {
	return outcome.ExitCode != 0 && strings.Contains(outcome.Stderr, conversationGoneMarker)
}

// RunDelegation 은 위임 하나를 그 레포에서 실행하고, 원출력을 남긴 뒤 결과를 돌려준다.
//
// 시간 상한은 ctx 가 정한다. 상수로 안에 두지 않는 이유는 그 숫자가 "메인이 얼마나 기다릴
// 것인가" 라는 표시 계층의 판단이어서다 — 조율 계층은 끊으라면 끊고 얼마 만에 끊었는지를 답한다.
func RunDelegation(ctx context.Context, workDirPath string, request DelegationRequest) (DelegationOutcome, error) {
	commandArguments := delegationArguments(request)

	command := exec.CommandContext(ctx, delegatedCommandName, commandArguments...)

	// 이 한 줄이 위임의 존재 이유다. 레포 cwd 라서 그 레포의 CLAUDE.md · .mcp.json ·
	// .claude/settings.json(훅) · .claude/agents/ 가 전부 로드된다(설계 문서 3.1).
	command.Dir = request.Repo.AbsolutePath

	// 프롬프트를 인자가 아니라 stdin 으로 넘긴다. 인자로 넘기면 - 로 시작하는 프롬프트가 claude 의
	// 플래그로 읽히고, 긴 프롬프트는 인자 길이 상한에 걸린다 — 계획의 확정 인터페이스를 그대로
	// 옮겨 적으라는 것이 이 프롬프트의 성격이라(설계 문서 9 절 6 번) 길어지는 쪽이 정상이다.
	command.Stdin = strings.NewReader(request.Prompt)

	var stdout, stderr bytes.Buffer
	command.Stdout = &stdout
	command.Stderr = &stderr

	// 죽인 뒤 출력을 기다리는 시간에 상한을 둔다. ctx 가 끝나면 exec 은 claude 를 죽이지만,
	// claude 가 띄운 손자 프로세스는 그대로 살아서 우리가 준 파이프를 붙들고 있을 수 있다 —
	// 그러면 Wait 이 그 손자가 끝날 때까지 돌아오지 않아, 시간 상한을 두고도 그만큼 더 기다린다.
	command.WaitDelay = delegationOutputDrainLimit

	// 바탕 환경을 그대로 물려준다. 부모 세션의 CLAUDE_CODE_* 를 걷어내는 길도 있지만, 무엇이
	// 세션 표식이고 무엇이 사용자 설정인지를 이 도구가 판정하게 되고 그 판정은 claude 가 변수를
	// 하나 더할 때마다 낡는다. 2026-09-01 실측에서 부모 환경을 그대로 물려준 위임이 정상으로 돌았다.
	startedAt := time.Now()
	runErr := command.Run()
	elapsed := time.Since(startedAt)

	outcome := DelegationOutcome{
		Stderr:   stderr.String(),
		Elapsed:  elapsed,
		TimedOut: errors.Is(ctx.Err(), context.DeadlineExceeded),
	}

	// 프로세스가 끝까지 가지 못한 것만 에러로 올린다. claude 를 찾지 못한 것과 claude 가 거절한
	// 것은 다른 사실이고, 앞엣것에는 메인에게 전할 위임 결과가 아예 없다.
	//
	// 그 가름을 runErr 의 종류가 아니라 ProcessState 의 유무로 한다. Run 이 돌려주는 에러에는
	// claude 의 거절(ExitError)만 오는 것이 아니라 출력 대기 상한 만료(ErrWaitDelay)도 오는데,
	// 뒤엣것은 claude 가 답을 다 내고 0 으로 끝난 뒤 손자가 파이프를 붙들고 있을 때 온다 —
	// 그것을 실행 실패로 읽으면 이미 받아둔 답을 통째로 버린다. ProcessState 가 있다는 것은
	// 프로세스가 돌고 거둬졌다는 뜻이고, 그러면 종료 코드도 받아둔 출력도 전할 값이 있다.
	if command.ProcessState == nil {
		return DelegationOutcome{}, fmt.Errorf("위임 실행 실패 (%s): %w", request.Repo.Name, runErr)
	}
	outcome.ExitCode = command.ProcessState.ExitCode()

	// 종료 코드가 0 이어도 답 문서를 읽어야 한다. 권한에 막힌 위임이 그 자리이고, 그것은
	// permission_denials 배열에만 남아 있다(설계 문서 3.3).
	answer, answerIsJSON := parseDelegationAnswer(stdout.Bytes())
	if answerIsJSON {
		outcome.Result = answer.Result
		outcome.ConversationID = answer.SessionID
		outcome.CostUSD = answer.TotalCostUSD
		outcome.DurationMS = answer.DurationMS
		outcome.NumTurns = answer.NumTurns
		outcome.PermissionDeniedTools = deniedToolNames(answer.PermissionDenials)
	}

	logPath, err := recordDelegationRun(workDirPath, request, outcome, commandArguments, stdout.Bytes(), answerIsJSON, startedAt)
	if err != nil {
		return DelegationOutcome{}, err
	}
	outcome.LogPath = logPath

	return outcome, nil
}

// delegationArguments 는 위임이 실행할 인자를 조립한다(설계 문서 5.4).
//
// --add-dir 을 붙이지 않는다. 위임은 그 레포 안의 일이고, 다른 레포를 봐야 하는 일이라면 그것은
// 메인이 할 일이거나 다른 레포에 대한 별도 위임이다 — "프로세스는 격리" 가 위임에도 그대로 걸린다.
func delegationArguments(request DelegationRequest) []string {
	arguments := []string{printFlagName, "--output-format", "json"}

	// 두 권한 모드를 함께 넘기지 않는다. 넘기면 claude 가 무엇을 고를지를 이 도구가 정하지 못한다.
	if request.BypassPermissions {
		arguments = append(arguments, "--dangerously-skip-permissions")
	} else {
		arguments = append(arguments, "--permission-mode", autoPermissionMode)
	}

	if request.ResumeConversation {
		return append(arguments, "--resume", request.ConversationID)
	}
	return append(arguments, "--session-id", request.ConversationID)
}

// delegationAnswer 는 claude 의 --output-format json 에서 이 도구가 읽는 키들이다.
//
// 최상위 키가 23 개인데(설계 문서 3.4) 여기 적힌 것만 읽는다. 나머지는 원출력 기록에 그대로
// 남으므로(5.4.3) 나중에 필요해지면 그 파일에서 꺼낼 수 있다.
type delegationAnswer struct {
	Result       string  `json:"result"`
	SessionID    string  `json:"session_id"`
	TotalCostUSD float64 `json:"total_cost_usd"`
	DurationMS   int64   `json:"duration_ms"`
	NumTurns     int     `json:"num_turns"`

	PermissionDenials []delegationPermissionDenial `json:"permission_denials"`
}

// delegationPermissionDenial 은 거절된 도구 호출 하나다. 이 도구가 읽는 것은 이름뿐이고,
// 나머지(tool_use_id · tool_input)는 원출력 기록에 그대로 남는다.
type delegationPermissionDenial struct {
	ToolName string `json:"tool_name"`
}

// parseDelegationAnswer 는 stdout 을 claude 의 답 문서로 읽는다. 읽지 못하면 그 사실을 함께 답한다.
//
// 읽지 못한 것을 에러로 올리지 않는다. 종료 코드가 0 이 아니면 stdout 이 JSON 이 아닌 것이
// 정상이고, 그때 알아야 하는 것은 stderr 와 종료 코드다.
func parseDelegationAnswer(stdout []byte) (delegationAnswer, bool) {
	var answer delegationAnswer
	if err := json.Unmarshal(stdout, &answer); err != nil {
		return delegationAnswer{}, false
	}
	return answer, true
}

func deniedToolNames(denials []delegationPermissionDenial) []string {
	if len(denials) == 0 {
		return nil
	}

	toolNames := make([]string, 0, len(denials))
	for _, denial := range denials {
		toolNames = append(toolNames, denial.ToolName)
	}
	return toolNames
}
