package cli

import (
	"context"
	"encoding/json"
	"fmt"
	"strings"
	"sync"
	"time"

	"github.com/maximinhan/Kyuchestration/internal/mcpserver"
	"github.com/maximinhan/Kyuchestration/internal/workdir"
)

// 이 파일은 run_in_repo 다 — 메인이 레포에 일을 시키는 자리(orchestration-tools-design.md 5.3).
//
// 조립과 실행은 조율 계층이 한다(workdir.RunDelegation). 이 파일이 정하는 것은 셋이다 —
// 어느 대화로 걸 것인가(5.5), 같은 레포에 동시에 걸리지 않게 하는 것(5.7), 그리고 결과를
// 모델이 속지 않을 모양으로 옮기는 것(5.4.2).

// runInRepoToolName 은 모델이 이 도구를 부르는 이름이다.
const runInRepoToolName = "run_in_repo"

const runInRepoToolDescription = `그 레포 안에서 claude 세션 하나를 띄워 시킨 일을 하게 한다.

그 세션은 레포 디렉토리에서 뜨므로 그 레포의 CLAUDE.md · .mcp.json · .claude/settings.json 의 훅 ·
.claude/agents/ 를 전부 로드한 채로 일한다. 이 도구가 있는 이유가 그것이다 — 메인 세션이 파일을
직접 고치면 그 설정들이 붙지 않는다.

prompt 는 그 레포에서 할 일을 그대로 적는다. 위임된 세션은 이 대화를 볼 수 없으므로, 계획이 정한
인터페이스나 앞서 확정한 결정은 요약하지 말고 프롬프트 안에 그대로 옮겨 적는다.

같은 레포에 두 번 부르면 앞 위임의 대화를 이어간다. 이어가지 않고 새로 시작하려면
newConversation 을 참으로 준다.

답의 permissionDenials 가 비어 있지 않으면 그 위임은 완결되지 않은 것이다 — 권한에 막힌 실행도
정상 종료로 끝나므로, 그 배열이 비어 있는지를 반드시 확인하고 비어 있지 않으면 사용자에게 알린다.`

// delegationTimeLimit 은 위임 하나를 기다리는 상한이다.
//
// 숫자를 하나 정해야 하는 자리다(설계 문서 9 절 3 번). 상한이 없으면 메인이 영원히 기다리고,
// 짧으면 진짜 일이 잘린다. 실측한 사소한 일이 15~60 초였고(3.6·3.7) 진짜 일은 몇 분이므로,
// 그보다 넉넉한 자리에 둔다. 잘린 위임은 원출력 기록과 함께 "얼마 만에 끊었는지" 로 답한다.
const delegationTimeLimit = 10 * time.Minute

// runInRepoArguments 는 모델이 이 도구에 넘기는 인자다.
//
// bypass 스위치를 두지 않는다. 모델이 부르는 도구에 그것을 두면 "위임이 메인보다 넓은 권한을
// 갖지 않는다"(설계 원칙 13)가 도구 설명 한 줄로 무너진다. 그 사실은 메인 세션의 환경에서 온다.
type runInRepoArguments struct {
	Repo   string `json:"repo"`
	Prompt string `json:"prompt"`

	// NewConversation 이 참이면 이어가지 않고 새 대화로 시작한다. 기본 거짓이다.
	NewConversation bool `json:"newConversation"`
}

// runInRepoAnswer 는 run_in_repo 가 모델에게 답하는 문서다.
type runInRepoAnswer struct {
	Repo   string `json:"repo"`
	Result string `json:"result"`

	// Incomplete 는 이 위임이 시킨 일을 끝내지 못한 이유다. 끝냈으면 null 이다.
	//
	// 종료 코드로는 알 수 없는 사실이라 문장으로 적는다(설계 문서 5.4.2). 권한에 막힌 실행은
	// 종료 코드 0 · subtype success 로 끝나므로, 이 필드가 없으면 모델은 아무것도 하지 않은
	// 위임을 성공으로 읽고 다음 걸음으로 간다.
	Incomplete *string `json:"incomplete"`

	// PermissionDenials 는 권한에 막혀 부르지 못한 도구 이름들이다. 없으면 빈 배열이다.
	PermissionDenials []string `json:"permissionDenials"`

	ConversationID string `json:"conversationId"`

	// Resumed 는 앞 위임의 대화를 이어갔는지다.
	Resumed bool `json:"resumed"`

	CostUSD    float64 `json:"costUsd"`
	DurationMS int64   `json:"durationMs"`
	NumTurns   int     `json:"numTurns"`

	ExitCode int    `json:"exitCode"`
	Stderr   string `json:"stderr,omitempty"`

	// LogPath 는 claude 의 답 원문이 통째로 남은 자리다(설계 문서 5.4.3).
	LogPath string `json:"logPath"`
}

// delegationDispatcher 는 이 워크디렉토리의 위임을 거는 자리다.
//
// 상태를 갖는 이유가 둘이다. 같은 레포에 두 위임이 동시에 걸리지 않게 지켜야 하고(설계 문서 5.7),
// 대화 기록 파일을 고치는 걸음이 서로 겹치지 않아야 한다 — 두 위임이 같은 파일을 읽고 각자
// 자기 라벨을 더해 적으면 나중 것이 이기고 진 쪽의 기록이 사라진다.
type delegationDispatcher struct {
	workDirPath string

	// bypassPermissions 는 이 서버를 띄운 메인 세션이 bypass 로 열렸는지다.
	//
	// 서버가 열릴 때 한 번 읽는다. 우리를 띄운 프로세스의 환경은 도중에 바뀌지 않고,
	// 도구를 부를 때마다 다시 읽으면 그 사실이 매번 달라질 수 있는 것처럼 보인다.
	bypassPermissions bool

	// mutex 는 "이 레포를 잡는다" 와 "대화를 배정한다" 를 함께 감싼다. 실행 자체는 감싸지 않는다 —
	// 서로 다른 레포에 대한 위임은 동시에 돌아야 한다(설계 문서 5.7).
	mutex sync.Mutex

	// runningRepos 는 지금 위임이 돌고 있는 레포다.
	runningRepos map[string]bool
}

// newRunInRepoTool 은 이 워크디렉토리에 위임을 거는 run_in_repo 도구 하나를 만든다.
func newRunInRepoTool(workDirPath string, bypassPermissions bool) mcpserver.Tool {
	dispatcher := &delegationDispatcher{
		workDirPath:       workDirPath,
		bypassPermissions: bypassPermissions,
		runningRepos:      map[string]bool{},
	}

	return mcpserver.Tool{
		Name:        runInRepoToolName,
		Description: runInRepoToolDescription,
		InputSchema: map[string]any{
			"type": "object",
			"properties": map[string]any{
				"repo": map[string]any{
					"type":        "string",
					"description": "레포 디렉토리 이름. list_repos 가 답한 것 중 하나여야 한다",
				},
				"prompt": map[string]any{
					"type":        "string",
					"description": "그 레포에서 할 일. 필요한 맥락을 요약하지 말고 그대로 옮겨 적는다",
				},
				"newConversation": map[string]any{
					"type":        "boolean",
					"description": "참이면 앞 위임의 대화를 이어가지 않고 새로 시작한다",
				},
			},
			"required":             []any{"repo", "prompt"},
			"additionalProperties": false,
		},
		Call: dispatcher.delegate,
	}
}

func (dispatcher *delegationDispatcher) delegate(rawArguments json.RawMessage) (mcpserver.ToolResult, error) {
	var arguments runInRepoArguments
	if err := json.Unmarshal(rawArguments, &arguments); err != nil {
		return refuseDelegation(fmt.Sprintf("run_in_repo 의 인자를 읽지 못했습니다: %v", err)), nil
	}

	// 빈 프롬프트로 claude 를 띄우면 아무것도 시키지 않은 위임이 비용만 쓰고 돌아온다.
	if strings.TrimSpace(arguments.Prompt) == "" {
		return refuseDelegation("run_in_repo 에는 그 레포에서 할 일(prompt)이 필요합니다"), nil
	}

	repos, err := workdir.ScanRepos(dispatcher.workDirPath)
	if err != nil {
		return refuseDelegation(err.Error()), nil
	}

	// 없는 이름을 그대로 실행하면 claude 가 없는 디렉토리에서 뜨려다 실패하고, 모델은 그 실패에서
	// "레포 이름을 잘못 골랐다" 를 읽어내지 못한다.
	repo, err := repoNamed(repos, arguments.Repo)
	if err != nil {
		return refuseDelegation(err.Error()), nil
	}

	// 관문이 먼저다. 대화를 배정하기 전이어야, 승인을 기다리는 물음이 쓰이지 않을 대화 기록을
	// 남기지 않는다 — 없는 레포를 대화 배정 전에 거절하는 것과 같은 자세다.
	approval, err := workdir.InspectRepoMCPApproval(dispatcher.workDirPath, repo)
	if err != nil {
		return refuseDelegation(err.Error()), nil
	}
	if !approval.DelegationIsAllowed() {
		return refuseDelegation(mcpApprovalNeededMessage(dispatcher.workDirPath, repo.Name, approval)), nil
	}

	assignment, err := dispatcher.claimRepoAndConversation(repo.Name, arguments.NewConversation)
	if err != nil {
		return refuseDelegation(err.Error()), nil
	}
	defer dispatcher.releaseRepo(repo.Name)

	return dispatcher.runAndAnswer(repo, arguments.Prompt, assignment)
}

// claimRepoAndConversation 은 이 레포를 잡고 이번 위임이 쓸 대화를 정한다.
//
// 둘을 한 잠금 안에서 하는 이유는 대화 기록 파일이다. 잡기만 먼저 하고 배정을 밖에서 하면
// 서로 다른 레포의 두 위임이 같은 파일을 읽고 각자 자기 라벨을 더해 적어, 나중 것이 이기고
// 진 쪽의 대화가 사라진다.
func (dispatcher *delegationDispatcher) claimRepoAndConversation(
	repoName string,
	startNewConversation bool,
) (workdir.ConversationAssignment, error) {
	dispatcher.mutex.Lock()
	defer dispatcher.mutex.Unlock()

	// 두 프로세스가 같은 디렉토리의 파일을 동시에 고치면 서로의 수정을 덮는다. 그리고 대화 ID 가
	// 하나뿐이라 뒤에 오는 쪽은 어차피 already in use 로 거절된다 — 알아들을 수 있는 말로 먼저
	// 거절한다(설계 문서 5.7).
	if dispatcher.runningRepos[repoName] {
		return workdir.ConversationAssignment{}, fmt.Errorf(
			"%s 에는 이미 위임이 돌고 있습니다 — 그 위임이 끝난 뒤에 다시 거세요", repoName)
	}

	assignment, err := dispatcher.assignDelegationConversation(repoName, startNewConversation)
	if err != nil {
		return workdir.ConversationAssignment{}, err
	}

	dispatcher.runningRepos[repoName] = true
	return assignment, nil
}

func (dispatcher *delegationDispatcher) assignDelegationConversation(
	repoName string,
	startNewConversation bool,
) (workdir.ConversationAssignment, error) {
	if startNewConversation {
		return workdir.StartNewConversation(dispatcher.workDirPath, repoName, workdir.DelegationConversation)
	}
	return workdir.AssignConversation(dispatcher.workDirPath, repoName, workdir.DelegationConversation)
}

func (dispatcher *delegationDispatcher) releaseRepo(repoName string) {
	dispatcher.mutex.Lock()
	defer dispatcher.mutex.Unlock()
	delete(dispatcher.runningRepos, repoName)
}

// runAndAnswer 는 위임을 실행하고, 이어갈 수 없게 된 대화는 한 번 다시 연다.
func (dispatcher *delegationDispatcher) runAndAnswer(
	repo workdir.Repo,
	prompt string,
	assignment workdir.ConversationAssignment,
) (mcpserver.ToolResult, error) {
	outcome, err := dispatcher.runDelegation(repo, prompt, assignment)
	if err != nil {
		return refuseDelegation(err.Error()), nil
	}

	// 폴백이 앱보다 쉬운 자리다(설계 문서 5.5). 앱은 PTY 안의 실패를 가려내야 하지만, 위임은
	// 엔진이 프로세스를 직접 실행하므로 종료 코드와 stderr 를 그대로 본다.
	if outcome.ConversationIsGone() {
		freshAssignment, assignErr := dispatcher.startNewDelegationConversation(repo.Name)
		if assignErr != nil {
			return refuseDelegation(assignErr.Error()), nil
		}

		assignment = freshAssignment
		outcome, err = dispatcher.runDelegation(repo, prompt, assignment)
		if err != nil {
			return refuseDelegation(err.Error()), nil
		}
	}

	return renderToolAnswerWithFailure(newRunInRepoAnswer(repo.Name, assignment, outcome))
}

// startNewDelegationConversation 은 이어갈 수 없게 된 기록을 버리고 새 대화를 배정한다.
//
// 잡아둔 레포를 놓지 않은 채로 기록만 고친다. 놓았다가 다시 잡으면 그 틈에 다른 위임이 같은
// 레포를 가져갈 수 있고, 그러면 이 위임이 방금 만든 대화를 그쪽이 쓰게 된다.
func (dispatcher *delegationDispatcher) startNewDelegationConversation(repoName string) (workdir.ConversationAssignment, error) {
	dispatcher.mutex.Lock()
	defer dispatcher.mutex.Unlock()

	return workdir.StartNewConversation(dispatcher.workDirPath, repoName, workdir.DelegationConversation)
}

func (dispatcher *delegationDispatcher) runDelegation(
	repo workdir.Repo,
	prompt string,
	assignment workdir.ConversationAssignment,
) (workdir.DelegationOutcome, error) {
	timeLimited, stopWaiting := context.WithTimeout(context.Background(), delegationTimeLimit)
	defer stopWaiting()

	return workdir.RunDelegation(timeLimited, dispatcher.workDirPath, workdir.DelegationRequest{
		Repo:               repo,
		Prompt:             prompt,
		ConversationID:     assignment.ConversationID,
		ResumeConversation: !assignment.IsNewConversation,
		BypassPermissions:  dispatcher.bypassPermissions,
	})
}

// newRunInRepoAnswer 는 위임 결과를 모델이 속지 않을 모양으로 옮긴다.
func newRunInRepoAnswer(
	repoName string,
	assignment workdir.ConversationAssignment,
	outcome workdir.DelegationOutcome,
) (runInRepoAnswer, bool) {
	conversationID := outcome.ConversationID
	if conversationID == "" {
		// claude 가 답하지 못하고 끝난 경우다. 우리가 정해준 ID 로 답해야 다음 위임이 무엇을
		// 이어가려 하는지 모델이 안다.
		conversationID = assignment.ConversationID
	}

	incompleteReason := delegationIncompleteReason(outcome)

	answer := runInRepoAnswer{
		Repo:              repoName,
		Result:            outcome.Result,
		PermissionDenials: alwaysAnArray(outcome.PermissionDeniedTools),
		ConversationID:    conversationID,
		Resumed:           !assignment.IsNewConversation,
		CostUSD:           outcome.CostUSD,
		DurationMS:        outcome.DurationMS,
		NumTurns:          outcome.NumTurns,
		ExitCode:          outcome.ExitCode,
		Stderr:            outcome.Stderr,
		LogPath:           outcome.LogPath,
	}
	if incompleteReason != "" {
		answer.Incomplete = &incompleteReason
	}
	return answer, incompleteReason != ""
}

// delegationIncompleteReason 은 이 위임이 시킨 일을 끝내지 못한 이유다. 끝냈으면 빈 문자열이다.
//
// 세 갈래를 한 자리에서 본다(설계 문서 5.4.2 의 표). 권한 거절이 먼저인 이유는 그것만 종료 코드로
// 드러나지 않아서다 — 나머지 둘은 종료 코드나 시간으로도 보인다.
func delegationIncompleteReason(outcome workdir.DelegationOutcome) string {
	if len(outcome.PermissionDeniedTools) > 0 {
		return fmt.Sprintf(
			"이 위임은 완결되지 않았습니다 — 권한에 막혀 부르지 못한 도구가 있습니다: %s. "+
				"무엇을 허용할지 사용자에게 물어보세요 (원출력: %s)",
			strings.Join(outcome.PermissionDeniedTools, ", "), outcome.LogPath)
	}

	if outcome.TimedOut {
		return fmt.Sprintf(
			"이 위임은 %s 만에 시간 상한에 걸려 끊겼습니다 — 한 것과 하지 않은 것이 갈리지 않았습니다 (원출력: %s)",
			outcome.Elapsed.Round(time.Second), outcome.LogPath)
	}

	if outcome.ExitCode != 0 {
		return fmt.Sprintf("이 위임은 종료 코드 %d 로 끝났습니다: %s (원출력: %s)",
			outcome.ExitCode, strings.TrimSpace(outcome.Stderr), outcome.LogPath)
	}

	return ""
}

// renderToolAnswerWithFailure 는 답 문서를 도구 결과로 옮기면서 완결 여부를 함께 싣는다.
func renderToolAnswerWithFailure(answer runInRepoAnswer, incomplete bool) (mcpserver.ToolResult, error) {
	result, err := renderToolAnswer(answer)
	if err != nil {
		return mcpserver.ToolResult{}, err
	}

	result.IsError = incomplete
	return result, nil
}

// mcpApprovalNeededMessage 는 관문에 걸린 위임을 모델이 사용자에게 전할 수 있는 말로 옮긴다.
//
// 모델이 스스로 통과할 수 있는 길을 적지 않는다. 여기 적힌 명령은 터미널에서 사람이 직접 쳐야
// 하는 것이고(kyu mcp approve 가 터미널을 요구한다), 그것이 이 관문의 요점이다 — 위임에는
// 승인 프롬프트에 답할 사람이 없으므로(설계 문서 2 절) 사람이 있는 자리로 물음을 옮긴다.
func mcpApprovalNeededMessage(workDirPath, repoName string, approval workdir.RepoMCPApproval) string {
	return fmt.Sprintf(`%s 에 위임하려면 그 레포의 .mcp.json 을 사람이 먼저 확인해야 합니다.

이 파일에 적힌 command 는 위임이 시작되는 순간 그대로 실행됩니다. 대화형 claude 에는 그 전에 사람이
한 번 보는 관문이 있지만 헤드리스 실행에는 없어서, 이 도구가 그 자리를 대신합니다.

파일: %s
내용:
%s
사용자에게 이렇게 전하세요 — 워크디렉토리(%s)에서 터미널을 열고 다음을 실행한 뒤 알려 달라고:

  kyu mcp approve %s

승인은 지금 이 내용에 대한 것입니다. 파일이 바뀌면 다시 묻습니다.`,
		repoName, approval.ConfigPath, approval.ConfigContent, workDirPath, repoName)
}

// refuseDelegation 은 위임을 걸기도 전에 끝난 물음을 모델이 읽을 거절로 옮긴다.
//
// 프로토콜 오류로 올리지 않는다. 없는 레포 이름이나 아직 승인되지 않은 관문은 모델이 이유를 읽고
// 고쳐 부르거나 사용자에게 물어야 하는 자리다.
func refuseDelegation(reason string) mcpserver.ToolResult {
	return mcpserver.ToolResult{Text: reason, IsError: true}
}
