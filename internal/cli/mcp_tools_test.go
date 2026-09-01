package cli

import (
	"encoding/json"
	"testing"

	"github.com/maximinhan/Kyuchestration/internal/mcpserver"
)

// listReposAnswerForTest 는 list_repos 가 모델에게 답하는 문서를 테스트가 직접 다시 적어둔 모양이다.
//
// 생산 코드의 직렬화 타입을 재사용하지 않는 이유는 다른 기계용 표면과 같다 — 읽는 쪽(모델)은 이
// 모양을 보고 run_in_repo 의 인자를 정하므로, 테스트도 따로 적어두어야 이름이 바뀌는 순간
// 여기에서 먼저 깨진다.
type listReposAnswerForTest struct {
	WorkDir struct {
		Name         string `json:"name"`
		AbsolutePath string `json:"absolutePath"`
	} `json:"workDir"`
	Repos []struct {
		Name         string `json:"name"`
		AbsolutePath string `json:"absolutePath"`
		State        string `json:"state"`
		Task         *struct {
			ID        string   `json:"id"`
			Status    string   `json:"status"`
			BlockedBy []string `json:"blockedBy"`
		} `json:"task"`
	} `json:"repos"`
}

func callToolForTest(t *testing.T, tool mcpserver.Tool, arguments string) mcpserver.ToolResult {
	t.Helper()

	result, err := tool.Call(json.RawMessage(arguments))
	if err != nil {
		t.Fatalf("%s 호출 실패: %v", tool.Name, err)
	}
	return result
}

func decodeListReposAnswerForTest(t *testing.T, result mcpserver.ToolResult) listReposAnswerForTest {
	t.Helper()

	if result.IsError {
		t.Fatalf("list_repos 가 거절했습니다: %s", result.Text)
	}

	var answer listReposAnswerForTest
	if err := json.Unmarshal([]byte(result.Text), &answer); err != nil {
		t.Fatalf("list_repos 의 답을 JSON 으로 읽지 못했습니다: %v\n--- 답 ---\n%s", err, result.Text)
	}
	return answer
}

func TestListReposAnswersEveryRepoWithTheNameRunInRepoTakes(t *testing.T) {
	// 모델은 이 답을 보고서야 어떤 레포 이름이 유효한지 안다. 도구 스키마의 열거형으로 고정할 수
	// 없는 이유는 워크디렉토리에 레포가 도중에 클론될 수 있어서다(설계 문서 5.3).
	workDirPath := makeWorkDir(t)
	makeCleanRepo(t, workDirPath, "zeta-service")
	makeCleanRepo(t, workDirPath, "alpha-commons")

	answer := decodeListReposAnswerForTest(t, callToolForTest(t, newListReposTool(workDirPath), `{}`))

	if answer.WorkDir.AbsolutePath != workDirPath {
		t.Errorf("workDir.absolutePath = %q, want %q", answer.WorkDir.AbsolutePath, workDirPath)
	}
	if len(answer.Repos) != 2 {
		t.Fatalf("repos = %d 개, 클론해 둔 둘을 기대: %+v", len(answer.Repos), answer.Repos)
	}
	// 이름순은 스캔이 정한다. 순서가 실행마다 흔들리면 모델이 같은 물음에 다른 답을 본다.
	if answer.Repos[0].Name != "alpha-commons" || answer.Repos[1].Name != "zeta-service" {
		t.Errorf("repos 이름 = %q %q, 이름순을 기대", answer.Repos[0].Name, answer.Repos[1].Name)
	}
	if answer.Repos[0].State == "" {
		t.Error("state 가 비어 있습니다 — 관찰하지 못한 것을 그럴듯한 값으로 메우지 않되 답은 있어야 합니다")
	}
	if answer.Repos[0].AbsolutePath == "" {
		t.Error("absolutePath 가 비어 있습니다 — 이름은 레포를 특정하지 못합니다")
	}
}

func TestListReposAnswersAnEmptyArrayWhenNothingIsCloned(t *testing.T) {
	// 없음을 null 과 [] 두 모양으로 다루게 하지 않는다 — 기계용 표면의 공통 규칙이다.
	workDirPath := makeWorkDir(t)

	result := callToolForTest(t, newListReposTool(workDirPath), `{}`)

	if result.IsError {
		t.Fatalf("빈 워크디렉토리에서 거절했습니다: %s", result.Text)
	}
	answer := decodeListReposAnswerForTest(t, result)
	if answer.Repos == nil {
		t.Errorf("repos = null, 빈 배열을 기대: %s", result.Text)
	}
}

func TestListReposCarriesThePlanTaskSoTheModelCanSeeWhatIsBlocked(t *testing.T) {
	// 메인 세션은 .coord/plan.md 를 자기 Read 도구로 이미 읽는다(설계 문서 5.3.1). 그래도 이
	// 도구가 작업을 함께 답하는 이유는, 위임을 걸기 전에 "지금 이 레포가 막혀 있는가" 를
	// 목록 한 번으로 알 수 있어야 해서다.
	workDirPath := makeWorkDir(t)
	makeCleanRepo(t, workDirPath, "alpha-commons")
	writePlanFileForTest(t, workDirPath, `---
tasks:
  - id: publish
    repo: alpha-commons
    title: 발행 로직 구현
    needs: []
    status: doing
---
`)

	answer := decodeListReposAnswerForTest(t, callToolForTest(t, newListReposTool(workDirPath), `{}`))

	if len(answer.Repos) != 1 {
		t.Fatalf("repos = %+v, 하나를 기대", answer.Repos)
	}
	if answer.Repos[0].Task == nil {
		t.Fatal("task = null, 계획이 가리키는 작업을 기대")
	}
	if answer.Repos[0].Task.ID != "publish" || answer.Repos[0].Task.Status != "doing" {
		t.Errorf("task = %+v, 계획의 publish/doing 을 기대", answer.Repos[0].Task)
	}
	if answer.Repos[0].Task.BlockedBy == nil {
		t.Error("blockedBy = null, 막힌 것이 없으면 빈 배열을 기대")
	}
}

func TestListReposRefusesADirectoryItCannotRead(t *testing.T) {
	// 답하지 못한 것을 빈 목록으로 답하면 모델은 "이 워크디렉토리에 레포가 없다" 로 읽고
	// 사용자에게 그렇게 전한다.
	result := callToolForTest(t, newListReposTool(makeWorkDir(t)+"/없는-디렉토리"), `{}`)

	if !result.IsError {
		t.Errorf("없는 워크디렉토리에서 성공했습니다: %s", result.Text)
	}
}
