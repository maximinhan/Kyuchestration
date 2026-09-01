package cli

import (
	"bytes"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/maximinhan/Kyuchestration/internal/workdir"
)

// writeRepoConfigForTest 는 레포 안에 실행 유발 설정 파일 하나를 둔다.
func writeRepoConfigForTest(t *testing.T, workDirPath, repoName, relativePath, content string) {
	t.Helper()

	filePath := filepath.Join(workDirPath, repoName, filepath.FromSlash(relativePath))
	if err := os.MkdirAll(filepath.Dir(filePath), 0o755); err != nil {
		t.Fatalf("테스트용 디렉토리 생성 실패: %v", err)
	}
	if err := os.WriteFile(filePath, []byte(content), 0o644); err != nil {
		t.Fatalf("테스트용 %s 생성 실패: %v", relativePath, err)
	}
}

func approveForTest(t *testing.T, workDirPath, repoName string) {
	t.Helper()

	repo := workdir.Repo{Name: repoName, AbsolutePath: filepath.Join(workDirPath, repoName)}
	approval, err := workdir.InspectRepoDelegationApproval(workDirPath, repo)
	if err != nil {
		t.Fatalf("InspectRepoDelegationApproval() 실패: %v", err)
	}
	if err := workdir.ApproveRepoDelegation(workDirPath, repo.Name, approval.Fingerprint); err != nil {
		t.Fatalf("ApproveRepoDelegation() 실패: %v", err)
	}
}

func TestApproveRefusesToTakeAnAnswerThatDidNotComeFromATerminal(t *testing.T) {
	// 파이프로 흘려보낸 답은 사람을 한 번도 지나지 않는다.
	//
	// 이 시험이 보증하는 범위를 정확히 적어둔다 — 파이프까지다. pty 를 붙인 프로그램
	// (script · unbuffer)은 여기서 사람과 구분되지 않고, 구분하는 길이 이 계층에 없다
	// (mcp_approve.go 머리말). 그것까지 막는 것은 메인 세션 자신의 도구 권한 확인의 몫이다.
	workDirPath := workDirWithOneRepoForTest(t)
	writeRepoConfigForTest(t, workDirPath, "alpha-commons", ".mcp.json", `{"mcpServers":{}}`)
	t.Chdir(workDirPath)

	var out bytes.Buffer
	err := approveRepoDelegation(strings.NewReader("y\n"), &out, []string{"alpha-commons"})

	if err == nil {
		t.Fatalf("파이프로 흘려보낸 y 가 승인으로 통과했습니다: %s", out.String())
	}
	if !strings.Contains(err.Error(), "터미널") {
		t.Errorf("거절 = %q, 터미널이 필요하다는 것을 알리기를 기대", err.Error())
	}
	if _, statErr := os.Stat(filepath.Join(workDirPath, ".coord", "delegation-approvals.json")); statErr == nil {
		t.Error("터미널 없이 승인 기록이 만들어졌습니다")
	}
}

func TestApproveStopsForProjectSettingsEvenWithoutAnMCPConfig(t *testing.T) {
	// .claude/settings.json 의 훅은 .mcp.json 없이도 위임 시작만으로 돈다(설계 문서 5.6 의 실측 표).
	// 승인 명령이 그것을 "승인할 것 없음" 으로 답하면 관문이 그 파일에 대해서만 열려 있게 된다.
	workDirPath := workDirWithOneRepoForTest(t)
	writeRepoConfigForTest(t, workDirPath, "alpha-commons", ".claude/settings.json",
		`{"hooks":{"SessionStart":[{"hooks":[{"type":"command","command":"echo 돈다"}]}]}}`)
	t.Chdir(workDirPath)

	var out bytes.Buffer
	err := approveRepoDelegation(strings.NewReader("y\n"), &out, []string{"alpha-commons"})

	if err == nil {
		t.Fatalf("settings.json 만 있는 레포가 물음 없이 지나갔습니다: %s", out.String())
	}
	if !strings.Contains(err.Error(), "터미널") {
		t.Errorf("거절 = %q, 사람에게 물어야 하는 자리이기를 기대", err.Error())
	}
}

func TestApproveSaysThereIsNothingToApproveForARepoWithoutExecutionConfigs(t *testing.T) {
	// 물을 것이 없는 것은 실패가 아니다. 종료 코드 1 로 끝내면 스크립트가 그것을 실패로 읽는다.
	workDirPath := workDirWithOneRepoForTest(t)
	t.Chdir(workDirPath)

	var out bytes.Buffer
	if err := approveRepoDelegation(strings.NewReader(""), &out, []string{"alpha-commons"}); err != nil {
		t.Fatalf("승인할 것이 없는 레포에서 실패했습니다: %v", err)
	}
	if !strings.Contains(out.String(), "승인할 것이 없습니다") {
		t.Errorf("출력 = %q, 물을 것이 없다는 것을 알리기를 기대", out.String())
	}
}

func TestApproveIgnoresRepoFilesThatDoNotRunOnTheirOwn(t *testing.T) {
	// 2026-09-01 실측: .claude/agents/ 와 .claude/commands/ 는 모델이 부를 때만 돌고,
	// CLAUDE.md 는 로드되지만 실행이 아니다(설계 문서 5.6). 이것들까지 물으면 거의 모든 레포가
	// 관문에 걸려, 사용자 요구의 "알아서 세션이 뜨면서" 가 매번 멈춘다.
	workDirPath := workDirWithOneRepoForTest(t)
	writeRepoConfigForTest(t, workDirPath, "alpha-commons", "CLAUDE.md", "이 레포의 지침")
	writeRepoConfigForTest(t, workDirPath, "alpha-commons", ".claude/agents/helper.md", "---\nname: helper\n---\n")
	writeRepoConfigForTest(t, workDirPath, "alpha-commons", ".claude/commands/build.md", "!`make`")
	t.Chdir(workDirPath)

	var out bytes.Buffer
	if err := approveRepoDelegation(strings.NewReader(""), &out, []string{"alpha-commons"}); err != nil {
		t.Fatalf("스스로 돌지 않는 파일들 때문에 실패했습니다: %v", err)
	}
	if !strings.Contains(out.String(), "승인할 것이 없습니다") {
		t.Errorf("출력 = %q, 물을 것이 없다는 것을 알리기를 기대", out.String())
	}
}

func TestApproveSaysSoWhenTheContentIsAlreadyApproved(t *testing.T) {
	// 이미 승인된 것을 다시 물으면 사용자는 자기 승인이 저장되지 않았다고 읽는다.
	workDirPath := workDirWithOneRepoForTest(t)
	writeRepoConfigForTest(t, workDirPath, "alpha-commons", ".mcp.json", `{"mcpServers":{}}`)
	t.Chdir(workDirPath)

	approveForTest(t, workDirPath, "alpha-commons")

	var out bytes.Buffer
	if err := approveRepoDelegation(strings.NewReader(""), &out, []string{"alpha-commons"}); err != nil {
		t.Fatalf("이미 승인된 레포에서 실패했습니다: %v", err)
	}
	if !strings.Contains(out.String(), "이미 승인") {
		t.Errorf("출력 = %q, 이미 승인되어 있음을 알리기를 기대", out.String())
	}
}

func TestApproveAsksAgainWhenAnotherExecutionConfigAppears(t *testing.T) {
	// 승인한 것은 그때 본 파일들의 집합이다. .mcp.json 만 보고 승인한 뒤 settings.json 이 생기면
	// 사람이 본 적 없는 훅이 승인된 레포라는 이름으로 돈다.
	workDirPath := workDirWithOneRepoForTest(t)
	writeRepoConfigForTest(t, workDirPath, "alpha-commons", ".mcp.json", `{"mcpServers":{}}`)
	t.Chdir(workDirPath)

	approveForTest(t, workDirPath, "alpha-commons")

	writeRepoConfigForTest(t, workDirPath, "alpha-commons", ".claude/settings.json", `{"apiKeyHelper":"새로 생겼다"}`)

	var out bytes.Buffer
	err := approveRepoDelegation(strings.NewReader(""), &out, []string{"alpha-commons"})

	if err == nil {
		t.Fatalf("파일이 하나 늘었는데 이미 승인으로 지나갔습니다: %s", out.String())
	}
	if !strings.Contains(err.Error(), "터미널") {
		t.Errorf("거절 = %q, 다시 물어야 하는 자리이기를 기대", err.Error())
	}
}

func TestTheApprovalScreenShowsEveryBlockedFileWithoutCutting(t *testing.T) {
	// 무엇을 승인하는지 모르는 승인은 관문이 아니다. 걸린 파일 중 하나가 이름만 보이거나 내용이
	// 잘려 보이면, 사람은 자기가 본 적 없는 command 를 승인하게 된다.
	longCommand := strings.Repeat("가", 10_000)
	executionConfigs := []workdir.RepoExecutionConfig{
		{RelativePath: ".mcp.json", AbsolutePath: "/w/proj-a/.mcp.json", Content: `{"mcpServers":{"probe":{"command":"python3"}}}`},
		{RelativePath: ".claude/settings.json", AbsolutePath: "/w/proj-a/.claude/settings.json", Content: `{"apiKeyHelper":"` + longCommand + `"}`},
		{RelativePath: ".claude/settings.local.json", AbsolutePath: "/w/proj-a/.claude/settings.local.json", Content: `{"hooks":{}}`},
	}

	screen := executionConfigsReviewText("proj-a", executionConfigs)

	for _, config := range executionConfigs {
		if !strings.Contains(screen, config.AbsolutePath) {
			t.Errorf("화면에 %s 의 자리가 없습니다", config.RelativePath)
		}
		if !strings.Contains(screen, config.Content) {
			t.Errorf("화면에 %s 의 내용이 온전히 들어 있지 않습니다", config.RelativePath)
		}
	}
}

func TestApproveRefusesARepoTheWorkDirDoesNotHave(t *testing.T) {
	workDirPath := workDirWithOneRepoForTest(t)
	t.Chdir(workDirPath)

	var out bytes.Buffer
	err := approveRepoDelegation(strings.NewReader(""), &out, []string{"gamma-worker"})

	if err == nil {
		t.Fatal("없는 레포를 승인하려는 시도가 성공했습니다")
	}
	if !strings.Contains(err.Error(), "alpha-commons") {
		t.Errorf("거절 = %q, 실제 레포 이름을 함께 알리기를 기대", err.Error())
	}
}
