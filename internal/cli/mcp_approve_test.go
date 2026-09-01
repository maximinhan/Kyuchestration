package cli

import (
	"bytes"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/maximinhan/Kyuchestration/internal/workdir"
)

func writeRepoMCPConfigForTest(t *testing.T, workDirPath, repoName, mcpConfigContent string) {
	t.Helper()

	if err := os.WriteFile(filepath.Join(workDirPath, repoName, ".mcp.json"), []byte(mcpConfigContent), 0o644); err != nil {
		t.Fatalf("테스트용 .mcp.json 생성 실패: %v", err)
	}
}

func TestRunInRepoStopsAtTheGateWhenTheRepoHasAnUnapprovedMCPConfig(t *testing.T) {
	// 위임은 claude 의 대화형 승인 관문이 없는 길이다(설계 문서 3.2·5.6). 클론한 레포의
	// .mcp.json 에 적힌 command 가 사람에게 한 번도 보이지 않은 채 도는 자리를 막는다.
	fakeClaudeOnPathForTest(t, claudeAnsweringForTest("했습니다"))
	workDirPath := workDirWithOneRepoForTest(t)
	writeRepoMCPConfigForTest(t, workDirPath, "alpha-commons",
		`{"mcpServers":{"probe":{"command":"python3","args":["probe.py"]}}}`)

	result := callToolForTest(t, newRunInRepoTool(workDirPath, false), `{"repo":"alpha-commons","prompt":"고쳐줘"}`)

	if !result.IsError {
		t.Fatalf("승인 없이 위임이 통과했습니다: %s", result.Text)
	}
	// 무엇을 승인하는지 보이지 않으면 그것은 관문이 아니다.
	if !strings.Contains(result.Text, "python3") {
		t.Errorf("거절 = %q, .mcp.json 내용을 함께 보이기를 기대", result.Text)
	}
	// 모델은 이 거절을 사용자에게 전할 수 있어야 한다 — 무엇을 실행해 달라고 할지가 그 안에 있다.
	if !strings.Contains(result.Text, "kyu mcp approve alpha-commons") {
		t.Errorf("거절 = %q, 사람이 실행할 명령을 함께 알리기를 기대", result.Text)
	}

	// 걸기도 전에 끝난 물음이 쓰이지 않을 대화 기록을 남기면 안 된다.
	if _, err := os.Stat(filepath.Join(workDirPath, ".coord", "conversations.json")); err == nil {
		t.Error("관문에 막힌 위임이 대화를 배정했습니다")
	}
	if _, err := os.Stat(filepath.Join(workDirPath, ".coord", "runs")); err == nil {
		t.Error("관문에 막힌 위임이 claude 를 실행했습니다")
	}
}

func TestRunInRepoGoesThroughOnceTheMCPConfigIsApproved(t *testing.T) {
	// 관문이 멈추는 것은 그 레포에 .mcp.json 이 있는 첫 위임 한 번뿐이다(설계 문서 5.6).
	fakeClaudeOnPathForTest(t, claudeAnsweringForTest("했습니다"))
	workDirPath := workDirWithOneRepoForTest(t)
	writeRepoMCPConfigForTest(t, workDirPath, "alpha-commons", `{"mcpServers":{}}`)

	repo := workdir.Repo{Name: "alpha-commons", AbsolutePath: filepath.Join(workDirPath, "alpha-commons")}
	approval, err := workdir.InspectRepoMCPApproval(workDirPath, repo)
	if err != nil {
		t.Fatalf("InspectRepoMCPApproval() 실패: %v", err)
	}
	if err := workdir.ApproveRepoMCPConfig(workDirPath, repo.Name, approval.Fingerprint); err != nil {
		t.Fatalf("ApproveRepoMCPConfig() 실패: %v", err)
	}

	if _, result := delegateForTest(t, newRunInRepoTool(workDirPath, false), `{"repo":"alpha-commons","prompt":"고쳐줘"}`); result.IsError {
		t.Errorf("승인한 레포의 위임이 거절됐습니다: %s", result.Text)
	}
}

func TestRunInRepoNeverStopsForARepoWithoutAnMCPConfig(t *testing.T) {
	// 대부분의 레포가 이쪽이다. 여기서 묻기 시작하면 "알아서 세션이 뜨면서" 가 매번 멈춘다.
	fakeClaudeOnPathForTest(t, claudeAnsweringForTest("했습니다"))
	workDirPath := workDirWithOneRepoForTest(t)

	if _, result := delegateForTest(t, newRunInRepoTool(workDirPath, false), `{"repo":"alpha-commons","prompt":"고쳐줘"}`); result.IsError {
		t.Errorf(".mcp.json 이 없는 레포의 위임이 거절됐습니다: %s", result.Text)
	}
}

func TestApproveRefusesToTakeAnAnswerThatDidNotComeFromATerminal(t *testing.T) {
	// 파이프로 흘려보낸 답은 사람을 한 번도 지나지 않는다.
	//
	// 이 시험이 보증하는 범위를 정확히 적어둔다 — 파이프까지다. pty 를 붙인 프로그램
	// (script · unbuffer)은 여기서 사람과 구분되지 않고, 구분하는 길이 이 계층에 없다
	// (mcp_approve.go 머리말). 그것까지 막는 것은 메인 세션 자신의 도구 권한 확인의 몫이다.
	workDirPath := workDirWithOneRepoForTest(t)
	writeRepoMCPConfigForTest(t, workDirPath, "alpha-commons", `{"mcpServers":{}}`)
	t.Chdir(workDirPath)

	var out bytes.Buffer
	err := approveRepoMCPConfig(strings.NewReader("y\n"), &out, []string{"alpha-commons"})

	if err == nil {
		t.Fatalf("파이프로 흘려보낸 y 가 승인으로 통과했습니다: %s", out.String())
	}
	if !strings.Contains(err.Error(), "터미널") {
		t.Errorf("거절 = %q, 터미널이 필요하다는 것을 알리기를 기대", err.Error())
	}
	if _, statErr := os.Stat(filepath.Join(workDirPath, ".coord", "mcp-approvals.json")); statErr == nil {
		t.Error("터미널 없이 승인 기록이 만들어졌습니다")
	}
}

func TestApproveSaysThereIsNothingToApproveForARepoWithoutAnMCPConfig(t *testing.T) {
	// 물을 것이 없는 것은 실패가 아니다. 종료 코드 1 로 끝내면 스크립트가 그것을 실패로 읽는다.
	workDirPath := workDirWithOneRepoForTest(t)
	t.Chdir(workDirPath)

	var out bytes.Buffer
	if err := approveRepoMCPConfig(strings.NewReader(""), &out, []string{"alpha-commons"}); err != nil {
		t.Fatalf("승인할 것이 없는 레포에서 실패했습니다: %v", err)
	}
	if !strings.Contains(out.String(), "승인할 것이 없습니다") {
		t.Errorf("출력 = %q, 물을 것이 없다는 것을 알리기를 기대", out.String())
	}
}

func TestApproveSaysSoWhenTheContentIsAlreadyApproved(t *testing.T) {
	// 이미 승인된 것을 다시 물으면 사용자는 자기 승인이 저장되지 않았다고 읽는다.
	workDirPath := workDirWithOneRepoForTest(t)
	writeRepoMCPConfigForTest(t, workDirPath, "alpha-commons", `{"mcpServers":{}}`)
	t.Chdir(workDirPath)

	repo := workdir.Repo{Name: "alpha-commons", AbsolutePath: filepath.Join(workDirPath, "alpha-commons")}
	approval, err := workdir.InspectRepoMCPApproval(workDirPath, repo)
	if err != nil {
		t.Fatalf("InspectRepoMCPApproval() 실패: %v", err)
	}
	if err := workdir.ApproveRepoMCPConfig(workDirPath, repo.Name, approval.Fingerprint); err != nil {
		t.Fatalf("ApproveRepoMCPConfig() 실패: %v", err)
	}

	var out bytes.Buffer
	if err := approveRepoMCPConfig(strings.NewReader(""), &out, []string{"alpha-commons"}); err != nil {
		t.Fatalf("이미 승인된 레포에서 실패했습니다: %v", err)
	}
	if !strings.Contains(out.String(), "이미 승인") {
		t.Errorf("출력 = %q, 이미 승인되어 있음을 알리기를 기대", out.String())
	}
}

func TestApproveRefusesARepoTheWorkDirDoesNotHave(t *testing.T) {
	workDirPath := workDirWithOneRepoForTest(t)
	t.Chdir(workDirPath)

	var out bytes.Buffer
	err := approveRepoMCPConfig(strings.NewReader(""), &out, []string{"gamma-worker"})

	if err == nil {
		t.Fatal("없는 레포를 승인하려는 시도가 성공했습니다")
	}
	if !strings.Contains(err.Error(), "alpha-commons") {
		t.Errorf("거절 = %q, 실제 레포 이름을 함께 알리기를 기대", err.Error())
	}
}
