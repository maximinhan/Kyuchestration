package cli

import (
	"bytes"
	"encoding/json"
	"fmt"

	"github.com/maximinhan/Kyuchestration/internal/mcpserver"
	"github.com/maximinhan/Kyuchestration/internal/workdir"
)

// 이 파일은 메인 세션의 모델이 보는 도구 표면이다 — 설계 문서 5.3 의 둘.
//
// list_json.go 와 같은 자리에 둔다. 관찰은 inspectWorkDir 이 한 벌로 하고, 이 파일은 그 결과를
// 모델이 읽는 문서로 옮기기만 한다 — 사람용 표와 --json 문서가 그렇게 갈리는 것과 같다
// (설계 문서 5.1 의 "셋 다 같은 조율 계층을 부른다").
//
// 답을 텍스트로 싣는다. MCP 의 도구 결과는 텍스트 덩어리이고, 그 안에 JSON 을 넣으면 모델이
// 필드 이름으로 값을 집을 수 있다 — 표로 그려 넘기면 모델이 그것을 다시 파싱해야 한다.

// listReposToolName 은 모델이 이 도구를 부르는 이름이다.
const listReposToolName = "list_repos"

const listReposToolDescription = `이 워크디렉토리에 클론되어 있는 레포 목록과 각 레포의 상태를 답한다.

run_in_repo 의 repo 인자로 넣을 수 있는 이름이 여기에 있는 것뿐이다. 레포는 도중에 클론될 수
있으므로, 위임하기 전에 이 도구로 확인한다.

state 는 git 을 관찰한 결과다 — DIRTY(커밋되지 않은 변경) · AHEAD(푸시가 남음) · IDLE(넘길 것 없음).
task 는 .coord/plan.md 에서 이 레포를 가리키는 작업이다. 없으면 null 이다.
recentDelegation 은 이 레포에서 마지막으로 끝난 위임이다 — 위임한 적이 없으면 null 이고,
지금 도는 중인 위임은 여기 나오지 않는다.`

// listReposAnswer 는 list_repos 가 모델에게 답하는 문서다.
//
// listJSONDocument 를 재사용하지 않는다. 그것은 앱과 맺은 계약이고 이것은 모델과 맺는 계약이라,
// 한쪽에 필드를 더하는 일이 다른 쪽의 계약을 건드려서는 안 된다. 실제로 두 문서가 담는 것도
// 다르다 — 앱은 카드를 그리려고 doneTaskCount 를 받고, 모델은 위임을 걸려고 이름과 상태를 받는다.
type listReposAnswer struct {
	WorkDir listReposWorkDir `json:"workDir"`
	Repos   []listReposRepo  `json:"repos"`
}

type listReposWorkDir struct {
	Name         string `json:"name"`
	AbsolutePath string `json:"absolutePath"`
}

type listReposRepo struct {
	Name         string `json:"name"`
	AbsolutePath string `json:"absolutePath"`
	State        string `json:"state"`

	// Task 는 계획이 이 레포에 대해 가리키는 작업이다. 가리키는 것이 없으면 null 이다.
	Task *listReposTask `json:"task"`

	// RecentDelegation 은 이 레포에서 가장 최근에 끝난 위임이다. 위임한 적이 없으면 null 이다.
	//
	// 도는 중인 위임은 여기 없다(설계 문서 6.2). 그것을 알리려면 시작할 때 어딘가에 적어야 하고,
	// 그 순간 그것은 관찰된 상태가 아니라 선언된 상태가 된다 — 그 프로세스가 SIGKILL 로 죽으면
	// 끝 줄이 안 적히고, 그때부터 그 기록은 거짓말을 한다.
	RecentDelegation *listReposDelegation `json:"recentDelegation"`
}

type listReposTask struct {
	ID        string   `json:"id"`
	Status    string   `json:"status"`
	BlockedBy []string `json:"blockedBy"`
}

type listReposDelegation struct {
	FinishedAt string `json:"finishedAt"`
	ExitCode   int    `json:"exitCode"`
	TimedOut   bool   `json:"timedOut"`

	// HadPermissionDenials 는 그 위임이 권한에 막힌 도구를 만났는지다. 참이면 그 위임은
	// 완결되지 않은 것이고, 종료 코드 0 은 그 사실을 말하지 않는다(설계 문서 3.3).
	HadPermissionDenials bool `json:"hadPermissionDenials"`

	// LogPath 는 claude 의 답 원문이 통째로 남은 자리다.
	LogPath string `json:"logPath"`
}

// newListReposTool 은 이 워크디렉토리를 훑어 답하는 list_repos 도구 하나를 만든다.
//
// 워크디렉토리를 도구 인자로 받지 않고 서버가 열릴 때 정한다. 모델이 정하게 두면 메인 세션이
// 자기 워크디렉토리 밖을 훑을 수 있게 되고, 그것은 이 서버가 메인 세션에만 붙는다는 전제
// (설계 문서 5.2.1)를 도구 인자 하나로 무르는 일이다.
func newListReposTool(workDirPath string) mcpserver.Tool {
	return mcpserver.Tool{
		Name:        listReposToolName,
		Description: listReposToolDescription,
		InputSchema: map[string]any{
			"type":                 "object",
			"properties":           map[string]any{},
			"additionalProperties": false,
		},
		Call: func(json.RawMessage) (mcpserver.ToolResult, error) {
			return listReposToolAnswer(workDirPath)
		},
	}
}

// listReposToolAnswer 는 워크디렉토리를 관찰해 모델이 읽을 문서로 옮긴다.
//
// 관찰에 실패한 것을 빈 목록으로 답하지 않는다. 모델은 그것을 "이 워크디렉토리에 레포가 없다"
// 로 읽고 사용자에게 그렇게 전한다 — 관찰하지 못한 것을 그럴듯한 값으로 메우지 않는 것이
// 조율 계층의 자세이고(workdir.InferRepoState), 표시 계층도 같아야 한다.
func listReposToolAnswer(workDirPath string) (mcpserver.ToolResult, error) {
	listing, err := inspectWorkDir(workDirPath)
	if err != nil {
		return mcpserver.ToolResult{Text: err.Error(), IsError: true}, nil
	}

	repos := make([]listReposRepo, 0, len(listing.repos))
	for _, repo := range listing.repos {
		// 위임 기록을 읽지 못한 것은 목록을 막지 않는다. 잃는 것은 "그때 무엇이 있었나" 한 줄이고,
		// 그것 때문에 모델이 레포 목록 자체를 못 받으면 위임을 걸 길이 사라진다.
		recentDelegation, err := workdir.MostRecentDelegation(listing.workDirAbsolutePath, repo.name)
		if err != nil {
			return mcpserver.ToolResult{Text: err.Error(), IsError: true}, nil
		}

		repos = append(repos, listReposRepo{
			Name:             repo.name,
			AbsolutePath:     repo.absolutePath,
			State:            string(repo.state),
			Task:             newListReposTask(repo.planSummary),
			RecentDelegation: newListReposDelegation(recentDelegation),
		})
	}

	return renderToolAnswer(listReposAnswer{
		WorkDir: listReposWorkDir{Name: listing.workDirName, AbsolutePath: listing.workDirAbsolutePath},
		Repos:   repos,
	})
}

// newListReposDelegation 은 관찰한 최근 위임을 문서의 한 항목으로 옮긴다. 없으면 nil 이다.
//
// 빈 객체로 채우지 않는다. 그러면 모델이 "위임한 적 있음" 으로 읽고, 위임한 적 없는 레포에
// 이어가기를 기대하게 된다.
func newListReposDelegation(recent *workdir.RecentDelegation) *listReposDelegation {
	if recent == nil {
		return nil
	}
	return &listReposDelegation{
		FinishedAt:           recent.FinishedAt,
		ExitCode:             recent.ExitCode,
		TimedOut:             recent.TimedOut,
		HadPermissionDenials: recent.HadPermissionDenials,
		LogPath:              recent.LogPath,
	}
}

func newListReposTask(summary repoPlanSummary) *listReposTask {
	if !summary.hasTaskToShow {
		return nil
	}
	return &listReposTask{
		ID:        summary.taskToShow.ID,
		Status:    string(summary.taskToShow.Status),
		BlockedBy: alwaysAnArray(summary.unfinishedNeeds),
	}
}

// renderToolAnswer 는 답 문서를 도구 결과의 본문으로 옮긴다.
//
// --json 표면과 같은 조립을 쓴다(json_output.go). 들여쓰기와 이스케이프 규칙이 표면마다 다르면
// 같은 사실이 어디서 나왔느냐에 따라 다른 모양으로 보인다.
func renderToolAnswer(document any) (mcpserver.ToolResult, error) {
	var rendered bytes.Buffer
	if err := writeJSONDocument(&rendered, document); err != nil {
		return mcpserver.ToolResult{}, fmt.Errorf("도구의 답 조립 실패: %w", err)
	}
	return mcpserver.ToolResult{Text: rendered.String()}, nil
}
