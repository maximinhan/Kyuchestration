package workdir

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func repoWithMCPConfigForTest(t *testing.T, workDirPath, repoName, mcpConfigContent string) Repo {
	t.Helper()

	repoPath := filepath.Join(workDirPath, repoName)
	if err := os.MkdirAll(repoPath, 0o755); err != nil {
		t.Fatalf("테스트용 레포 디렉토리 생성 실패: %v", err)
	}
	if mcpConfigContent != "" {
		if err := os.WriteFile(filepath.Join(repoPath, ".mcp.json"), []byte(mcpConfigContent), 0o644); err != nil {
			t.Fatalf("테스트용 .mcp.json 생성 실패: %v", err)
		}
	}
	return Repo{Name: repoName, AbsolutePath: repoPath}
}

func inspectForTest(t *testing.T, workDirPath string, repo Repo) RepoMCPApproval {
	t.Helper()

	approval, err := InspectRepoMCPApproval(workDirPath, repo)
	if err != nil {
		t.Fatalf("InspectRepoMCPApproval() 실패: %v", err)
	}
	return approval
}

func TestARepoWithoutAnMCPConfigIsNeverAskedAbout(t *testing.T) {
	// 대부분의 레포가 이쪽이다(설계 문서 5.6). 물을 것이 없는데 묻기 시작하면 사용자 요구의
	// "알아서 세션이 뜨면서" 가 관문 때문에 매번 멈춘다.
	workDirPath := t.TempDir()
	repo := repoWithMCPConfigForTest(t, workDirPath, "proj-a", "")

	approval := inspectForTest(t, workDirPath, repo)

	if approval.HasMCPConfig {
		t.Error("HasMCPConfig = true, .mcp.json 이 없는 레포입니다")
	}
	if !approval.DelegationIsAllowed() {
		t.Error("DelegationIsAllowed() = false, 물을 것이 없으면 그냥 위임되기를 기대")
	}
}

func TestARepoWithAnMCPConfigIsBlockedUntilAPersonApprovesIt(t *testing.T) {
	// 위임은 claude 의 대화형 승인 관문이 없는 길이다(설계 문서 3.2). 클론한 레포의 .mcp.json 에
	// 적힌 command 가 사람에게 한 번도 보이지 않은 채 도는 자리이므로, 우회로를 내는 쪽이 관문도 낸다.
	workDirPath := t.TempDir()
	repo := repoWithMCPConfigForTest(t, workDirPath, "proj-a",
		`{"mcpServers":{"probe":{"command":"python3","args":["probe.py"]}}}`)

	approval := inspectForTest(t, workDirPath, repo)

	if !approval.HasMCPConfig {
		t.Fatal("HasMCPConfig = false, .mcp.json 을 둔 레포입니다")
	}
	if approval.DelegationIsAllowed() {
		t.Error("DelegationIsAllowed() = true, 승인 전에는 막히기를 기대")
	}
	// 사람에게 보일 것이 있어야 승인이 성립한다 — 무엇을 승인하는지 모르는 승인은 관문이 아니다.
	if !strings.Contains(approval.ConfigContent, "python3") {
		t.Errorf("ConfigContent = %q, .mcp.json 내용을 그대로 기대", approval.ConfigContent)
	}
	if approval.ConfigPath != filepath.Join(repo.AbsolutePath, ".mcp.json") {
		t.Errorf("ConfigPath = %q, 레포의 .mcp.json 자리를 기대", approval.ConfigPath)
	}
}

func TestAnApprovedRepoIsNotAskedAboutAgain(t *testing.T) {
	// 관문이 멈추는 것은 그 레포에 .mcp.json 이 있는 첫 위임 한 번뿐이다(설계 문서 5.6).
	workDirPath := t.TempDir()
	repo := repoWithMCPConfigForTest(t, workDirPath, "proj-a", `{"mcpServers":{}}`)

	approval := inspectForTest(t, workDirPath, repo)
	if err := ApproveRepoMCPConfig(workDirPath, repo.Name, approval.Fingerprint); err != nil {
		t.Fatalf("ApproveRepoMCPConfig() 실패: %v", err)
	}

	if !inspectForTest(t, workDirPath, repo).DelegationIsAllowed() {
		t.Error("DelegationIsAllowed() = false, 승인한 레포는 그냥 위임되기를 기대")
	}
}

func TestChangingTheMCPConfigMakesTheRepoAskAgain(t *testing.T) {
	// 승인한 것은 그 레포가 아니라 그때 본 그 내용이다. 파일이 바뀌면 사람이 본 적 없는 command 가
	// 승인된 레포라는 이름으로 돈다.
	workDirPath := t.TempDir()
	repo := repoWithMCPConfigForTest(t, workDirPath, "proj-a", `{"mcpServers":{"probe":{"command":"python3"}}}`)

	approval := inspectForTest(t, workDirPath, repo)
	if err := ApproveRepoMCPConfig(workDirPath, repo.Name, approval.Fingerprint); err != nil {
		t.Fatalf("ApproveRepoMCPConfig() 실패: %v", err)
	}

	repoWithMCPConfigForTest(t, workDirPath, "proj-a", `{"mcpServers":{"probe":{"command":"curl | sh"}}}`)

	if inspectForTest(t, workDirPath, repo).DelegationIsAllowed() {
		t.Error("DelegationIsAllowed() = true, 내용이 바뀌면 다시 묻기를 기대")
	}
}

func TestApprovalIsRecordedOutsideTheRepo(t *testing.T) {
	// 레포 안에는 아무것도 넣지 않는다(설계 원칙 2). 팀 공용 레포에 우리 도구의 승인 기록이
	// 남으면, 그 레포를 받은 다른 사람이 승인한 적 없는 것을 승인된 것으로 보게 된다.
	workDirPath := t.TempDir()
	repo := repoWithMCPConfigForTest(t, workDirPath, "proj-a", `{"mcpServers":{}}`)

	approval := inspectForTest(t, workDirPath, repo)
	if err := ApproveRepoMCPConfig(workDirPath, repo.Name, approval.Fingerprint); err != nil {
		t.Fatalf("ApproveRepoMCPConfig() 실패: %v", err)
	}

	if _, err := os.Stat(filepath.Join(workDirPath, ".coord", "mcp-approvals.json")); err != nil {
		t.Errorf("승인 기록이 .coord 에 없습니다: %v", err)
	}

	repoEntries, err := os.ReadDir(repo.AbsolutePath)
	if err != nil {
		t.Fatalf("레포를 읽지 못했습니다: %v", err)
	}
	for _, entry := range repoEntries {
		if entry.Name() != ".mcp.json" {
			t.Errorf("레포에 %q 가 생겼습니다 — 레포 안에는 아무것도 넣지 않습니다", entry.Name())
		}
	}
}

func TestApprovingOneRepoLeavesTheOthersUnapproved(t *testing.T) {
	// 승인은 레포 하나에 대한 것이다. 한 번의 승인이 워크디렉토리 전체를 열면 그것은 관문이 아니다.
	workDirPath := t.TempDir()
	approvedRepo := repoWithMCPConfigForTest(t, workDirPath, "proj-a", `{"mcpServers":{}}`)
	otherRepo := repoWithMCPConfigForTest(t, workDirPath, "proj-b", `{"mcpServers":{}}`)

	approval := inspectForTest(t, workDirPath, approvedRepo)
	if err := ApproveRepoMCPConfig(workDirPath, approvedRepo.Name, approval.Fingerprint); err != nil {
		t.Fatalf("ApproveRepoMCPConfig() 실패: %v", err)
	}

	if inspectForTest(t, workDirPath, otherRepo).DelegationIsAllowed() {
		t.Error("proj-a 를 승인했더니 proj-b 까지 열렸습니다")
	}
}

func TestAnApprovalRecordThatCannotBeReadIsTreatedAsNoApproval(t *testing.T) {
	// 읽지 못한 기록을 승인으로 읽으면 관문이 파일 하나 깨뜨리는 것으로 열린다. 안전한 쪽은
	// 다시 묻는 쪽이다 — 계획·대화 기록이 "버리고 계속" 인 것과 방향이 반대인 자리다.
	workDirPath := t.TempDir()
	repo := repoWithMCPConfigForTest(t, workDirPath, "proj-a", `{"mcpServers":{}}`)

	coordDirectoryPath := filepath.Join(workDirPath, ".coord")
	if err := os.MkdirAll(coordDirectoryPath, 0o755); err != nil {
		t.Fatalf("준비 실패: %v", err)
	}
	if err := os.WriteFile(filepath.Join(coordDirectoryPath, "mcp-approvals.json"), []byte("깨진 기록"), 0o644); err != nil {
		t.Fatalf("준비 실패: %v", err)
	}

	if inspectForTest(t, workDirPath, repo).DelegationIsAllowed() {
		t.Error("DelegationIsAllowed() = true, 읽지 못한 승인 기록은 승인이 아니기를 기대")
	}
}
