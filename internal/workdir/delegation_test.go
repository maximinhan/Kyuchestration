package workdir

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"testing"
	"time"
)

// fakeClaudeOnPath 는 PATH 앞에 가짜 claude 를 놓는다.
//
// 조립과 결과 읽기를 진짜 claude 없이 확인하기 위한 것이다. 진짜 claude 로 무엇이 되는지는
// 통합 시험이 따로 본다 — 여기서 묻는 것은 "무엇을 어떻게 실행하고 그 답을 어떻게 읽는가" 다.
func fakeClaudeOnPath(t *testing.T, script string) {
	t.Helper()

	binDirectoryPath := t.TempDir()
	fakeClaudePath := filepath.Join(binDirectoryPath, "claude")
	if err := os.WriteFile(fakeClaudePath, []byte("#!/bin/sh\n"+script), 0o755); err != nil {
		t.Fatalf("가짜 claude 생성 실패: %v", err)
	}

	t.Setenv("PATH", binDirectoryPath+string(os.PathListSeparator)+os.Getenv("PATH"))
}

// delegationFixture 는 위임 하나를 걸 수 있는 최소한의 워크디렉토리다.
func delegationFixture(t *testing.T) (workDirPath string, repo Repo) {
	t.Helper()

	workDirPath = t.TempDir()
	repoPath := filepath.Join(workDirPath, "proj-a")
	if err := os.MkdirAll(repoPath, 0o755); err != nil {
		t.Fatalf("테스트용 레포 디렉토리 생성 실패: %v", err)
	}
	return workDirPath, Repo{Name: "proj-a", AbsolutePath: repoPath}
}

func TestDelegationRunsClaudeInTheRepoWithTheModeThatAllowsMCPToolsAndEdits(t *testing.T) {
	// acceptEdits 는 MCP 도구 호출을 막는다(설계 문서 3.3). 그것을 고르면 사용자 요구의 핵심인
	// "해당 레포의 MCP 설정을 다 활용한 채로" 가 성립하지 않는다 — 서버는 붙는데 부를 수가 없다.
	fakeClaudeOnPath(t, `
printf '%s\n' "$@" > "$KYU_TEST_ARGV_PATH"
pwd > "$KYU_TEST_CWD_PATH"
cat > "$KYU_TEST_STDIN_PATH"
echo '{"subtype":"success","is_error":false,"result":"고쳤습니다","session_id":"11111111-2222-4333-8444-555555555555","permission_denials":[],"total_cost_usd":0.01,"duration_ms":1234,"num_turns":3}'
`)

	workDirPath, repo := delegationFixture(t)
	argvPath := filepath.Join(t.TempDir(), "argv")
	cwdPath := filepath.Join(t.TempDir(), "cwd")
	stdinPath := filepath.Join(t.TempDir(), "stdin")
	t.Setenv("KYU_TEST_ARGV_PATH", argvPath)
	t.Setenv("KYU_TEST_CWD_PATH", cwdPath)
	t.Setenv("KYU_TEST_STDIN_PATH", stdinPath)

	outcome, err := RunDelegation(t.Context(), workDirPath, DelegationRequest{
		Repo:           repo,
		Prompt:         "README 에 한 줄 더해줘",
		ConversationID: "11111111-2222-4333-8444-555555555555",
	})
	if err != nil {
		t.Fatalf("RunDelegation() 실패: %v", err)
	}

	argv := readLinesForTest(t, argvPath)
	if !slices.Contains(argv, "--permission-mode") || !slices.Contains(argv, "auto") {
		t.Errorf("argv = %q, --permission-mode auto 를 기대", argv)
	}
	if slices.Contains(argv, "--dangerously-skip-permissions") {
		t.Errorf("argv = %q, bypass 는 메인이 켰을 때만 전파하기를 기대(설계 원칙 13)", argv)
	}
	if !slices.Contains(argv, "-p") || !slices.Contains(argv, "--output-format") || !slices.Contains(argv, "json") {
		t.Errorf("argv = %q, 기계가 읽는 답을 요구하기를 기대", argv)
	}
	// 위임은 그 레포 안의 일이다. 다른 레포를 봐야 하는 일이라면 그것은 별도의 위임이다(설계 문서 5.4).
	if slices.Contains(argv, "--add-dir") {
		t.Errorf("argv = %q, 위임에는 --add-dir 을 붙이지 않기를 기대", argv)
	}

	// 이 한 줄이 그 레포의 .mcp.json·CLAUDE.md·훅·에이전트를 살린다(설계 문서 3.1).
	assertSamePathForTest(t, strings.TrimSpace(readFileForTest(t, cwdPath)), repo.AbsolutePath)

	// 프롬프트는 stdin 으로 간다. argv 로 넘기면 - 로 시작하는 프롬프트가 플래그로 읽히고,
	// 긴 프롬프트가 인자 길이 상한에 걸린다.
	if strings.TrimSpace(readFileForTest(t, stdinPath)) != "README 에 한 줄 더해줘" {
		t.Errorf("stdin = %q, 프롬프트를 기대", readFileForTest(t, stdinPath))
	}

	if outcome.Result != "고쳤습니다" || outcome.NumTurns != 3 || outcome.CostUSD != 0.01 {
		t.Errorf("결과 = %+v, claude 의 답 문서를 그대로 읽기를 기대", outcome)
	}
}

func TestDelegationCarriesBypassOnlyWhenTheMainSessionHadIt(t *testing.T) {
	// 위임은 메인의 손이지 별개의 주체가 아니다(설계 원칙 13).
	fakeClaudeOnPath(t, `
printf '%s\n' "$@" > "$KYU_TEST_ARGV_PATH"
cat > /dev/null
echo '{"subtype":"success","result":"","session_id":"11111111-2222-4333-8444-555555555555"}'
`)

	workDirPath, repo := delegationFixture(t)
	argvPath := filepath.Join(t.TempDir(), "argv")
	t.Setenv("KYU_TEST_ARGV_PATH", argvPath)

	if _, err := RunDelegation(t.Context(), workDirPath, DelegationRequest{
		Repo:              repo,
		Prompt:            "고쳐줘",
		ConversationID:    "11111111-2222-4333-8444-555555555555",
		BypassPermissions: true,
	}); err != nil {
		t.Fatalf("RunDelegation() 실패: %v", err)
	}

	argv := readLinesForTest(t, argvPath)
	if !slices.Contains(argv, "--dangerously-skip-permissions") {
		t.Errorf("argv = %q, 메인이 켠 bypass 가 위임까지 닿기를 기대", argv)
	}
	// 두 모드를 함께 넘기면 claude 가 무엇을 고를지 이 도구가 정하지 못한다.
	if slices.Contains(argv, "--permission-mode") {
		t.Errorf("argv = %q, bypass 와 permission-mode 를 함께 넘기지 않기를 기대", argv)
	}
}

func TestDelegationStartsANewConversationOrResumesTheRecordedOne(t *testing.T) {
	// 한 번 쓴 대화 ID 는 --session-id 로 다시 쓸 수 없다(설계 문서 3.5). 그래서 첫 회는
	// --session-id, 이후는 --resume 으로 반드시 갈린다.
	for _, testCase := range []struct {
		name             string
		resume           bool
		wantFlag         string
		unwantedFlagName string
	}{
		{name: "첫 위임", resume: false, wantFlag: "--session-id", unwantedFlagName: "--resume"},
		{name: "이어가는 위임", resume: true, wantFlag: "--resume", unwantedFlagName: "--session-id"},
	} {
		t.Run(testCase.name, func(t *testing.T) {
			fakeClaudeOnPath(t, `
printf '%s\n' "$@" > "$KYU_TEST_ARGV_PATH"
cat > /dev/null
echo '{"subtype":"success","result":"","session_id":"11111111-2222-4333-8444-555555555555"}'
`)

			workDirPath, repo := delegationFixture(t)
			argvPath := filepath.Join(t.TempDir(), "argv")
			t.Setenv("KYU_TEST_ARGV_PATH", argvPath)

			if _, err := RunDelegation(t.Context(), workDirPath, DelegationRequest{
				Repo:               repo,
				Prompt:             "고쳐줘",
				ConversationID:     "11111111-2222-4333-8444-555555555555",
				ResumeConversation: testCase.resume,
			}); err != nil {
				t.Fatalf("RunDelegation() 실패: %v", err)
			}

			argv := readLinesForTest(t, argvPath)
			flagIndex := slices.Index(argv, testCase.wantFlag)
			if flagIndex < 0 || argv[flagIndex+1] != "11111111-2222-4333-8444-555555555555" {
				t.Errorf("argv = %q, %s 뒤에 대화 ID 를 기대", argv, testCase.wantFlag)
			}
			if slices.Contains(argv, testCase.unwantedFlagName) {
				t.Errorf("argv = %q, %s 는 붙지 않기를 기대", argv, testCase.unwantedFlagName)
			}
		})
	}
}

func TestDelegationBlockedByPermissionsIsNotReportedAsSuccess(t *testing.T) {
	// 이것이 이 파일에서 가장 중요한 시험이다. 권한에 막힌 실행도 종료 코드 0, subtype success 로
	// 끝난다(설계 문서 3.3). 종료 코드만 보면 아무것도 하지 않은 위임이 성공으로 보고된다.
	fakeClaudeOnPath(t, `
cat > /dev/null
echo '{"subtype":"success","is_error":false,"result":"권한이 필요합니다","session_id":"11111111-2222-4333-8444-555555555555","permission_denials":[{"tool_name":"mcp__probe__repo_fingerprint","tool_use_id":"toolu_1","tool_input":{}},{"tool_name":"Edit","tool_use_id":"toolu_2","tool_input":{}}]}'
`)

	workDirPath, repo := delegationFixture(t)

	outcome, err := RunDelegation(t.Context(), workDirPath, DelegationRequest{
		Repo:           repo,
		Prompt:         "고쳐줘",
		ConversationID: "11111111-2222-4333-8444-555555555555",
	})
	if err != nil {
		t.Fatalf("RunDelegation() 실패: %v", err)
	}

	if outcome.ExitCode != 0 {
		t.Errorf("exitCode = %d, 막힌 실행도 0 으로 끝나는 것이 이 시험의 전제다", outcome.ExitCode)
	}
	if !slices.Equal(outcome.PermissionDeniedTools, []string{"mcp__probe__repo_fingerprint", "Edit"}) {
		t.Errorf("거절된 도구 = %q, 두 이름을 그대로 기대", outcome.PermissionDeniedTools)
	}
}

func TestDelegationCarriesTheExitCodeAndStderrWhenClaudeRefuses(t *testing.T) {
	// --resume 실패가 이 길로 온다(설계 문서 3.5·5.4.2). 위임이 엔진의 자식 프로세스라서
	// 앱과 달리 종료 코드와 stderr 를 그대로 볼 수 있다.
	fakeClaudeOnPath(t, `
cat > /dev/null
echo 'No conversation found with session ID: 887b1e33-0000-4000-8000-000000000000' >&2
exit 1
`)

	workDirPath, repo := delegationFixture(t)

	outcome, err := RunDelegation(t.Context(), workDirPath, DelegationRequest{
		Repo:               repo,
		Prompt:             "고쳐줘",
		ConversationID:     "887b1e33-0000-4000-8000-000000000000",
		ResumeConversation: true,
	})
	if err != nil {
		t.Fatalf("RunDelegation() 은 claude 의 거절을 에러가 아니라 결과로 답해야 합니다: %v", err)
	}

	if outcome.ExitCode != 1 {
		t.Errorf("exitCode = %d, want 1", outcome.ExitCode)
	}
	if !strings.Contains(outcome.Stderr, "No conversation found") {
		t.Errorf("stderr = %q, claude 가 남긴 이유를 그대로 기대", outcome.Stderr)
	}
	if !outcome.ConversationIsGone() {
		t.Error("ConversationIsGone() = false, 전사가 사라진 대화를 알아보기를 기대")
	}
}

func TestDelegationThatRanOutOfTimeSaysSo(t *testing.T) {
	// 상한이 없으면 메인이 영원히 기다린다(설계 문서 9 절 3 번).
	fakeClaudeOnPath(t, "sleep 30\n")

	workDirPath, repo := delegationFixture(t)

	timeLimited, cancel := context.WithTimeout(t.Context(), 200*time.Millisecond)
	defer cancel()

	outcome, err := RunDelegation(timeLimited, workDirPath, DelegationRequest{
		Repo:           repo,
		Prompt:         "고쳐줘",
		ConversationID: "11111111-2222-4333-8444-555555555555",
	})
	if err != nil {
		t.Fatalf("RunDelegation() 은 시간 초과를 에러가 아니라 결과로 답해야 합니다: %v", err)
	}

	if !outcome.TimedOut {
		t.Error("timedOut = false, 상한에 걸린 위임을 알아보기를 기대")
	}
	if outcome.Elapsed <= 0 {
		t.Error("elapsed = 0, 얼마 만에 끊었는지를 함께 답하기를 기대(설계 문서 5.4.2)")
	}

	// 죽인 뒤 손자 프로세스가 파이프를 붙들면 상한을 두고도 그만큼 더 기다리게 된다.
	// 가짜 claude 의 sleep 30 이 그 손자다 — 30 초를 다 기다렸다면 상한이 듣지 않은 것이다.
	if outcome.Elapsed >= 10*time.Second {
		t.Errorf("elapsed = %v, 상한에 걸린 뒤 곧바로 돌아오기를 기대", outcome.Elapsed)
	}
}

func TestDelegationWritesTheRawAnswerWhereAPersonCanOpenIt(t *testing.T) {
	// 결과가 도구의 답 안에서 요약되어 사라지면 나중에 "그때 무엇이 거절됐나" 를 알 길이 없다
	// (설계 문서 5.4.3).
	fakeClaudeOnPath(t, `
cat > /dev/null
echo '{"subtype":"success","result":"고쳤습니다","session_id":"11111111-2222-4333-8444-555555555555","permission_denials":[],"ttft_ms":42}'
`)

	workDirPath, repo := delegationFixture(t)

	outcome, err := RunDelegation(t.Context(), workDirPath, DelegationRequest{
		Repo:           repo,
		Prompt:         "README 에 한 줄 더해줘",
		ConversationID: "11111111-2222-4333-8444-555555555555",
	})
	if err != nil {
		t.Fatalf("RunDelegation() 실패: %v", err)
	}

	if !strings.HasPrefix(outcome.LogPath, filepath.Join(workDirPath, ".coord", "runs")) {
		t.Errorf("logPath = %q, .coord/runs 아래를 기대 — 레포 안에는 넣지 않는다(설계 원칙 2)", outcome.LogPath)
	}

	var run struct {
		SchemaVersion  int             `json:"schemaVersion"`
		Repo           string          `json:"repo"`
		ConversationID string          `json:"conversationId"`
		ExitCode       int             `json:"exitCode"`
		Prompt         string          `json:"prompt"`
		Command        []string        `json:"command"`
		Output         json.RawMessage `json:"output"`
	}
	if err := json.Unmarshal([]byte(readFileForTest(t, outcome.LogPath)), &run); err != nil {
		t.Fatalf("남긴 기록을 JSON 으로 읽지 못했습니다: %v", err)
	}

	if run.SchemaVersion != 1 || run.Repo != "proj-a" || run.Prompt != "README 에 한 줄 더해줘" {
		t.Errorf("기록 = %+v, 무엇을 어느 레포에 시켰는지를 기대", run)
	}
	if len(run.Command) == 0 {
		t.Error("기록에 command 가 없습니다 — 어떤 권한으로 돌았는지를 나중에 알 수 없습니다")
	}
	// 원문을 요약하지 않고 통째로 담는다. 우리가 읽지 않는 키까지 남아 있어야 사후 추적이 된다.
	if !strings.Contains(string(run.Output), "ttft_ms") {
		t.Errorf("output = %s, claude 의 답 원문을 그대로 담기를 기대", run.Output)
	}
}

func TestDelegationKeepsWhatClaudePrintedEvenWhenItWasNotJSON(t *testing.T) {
	// 읽지 못한 답을 버리면, 왜 실패했는지를 알아낼 유일한 재료가 사라진다.
	fakeClaudeOnPath(t, `
cat > /dev/null
echo '이건 JSON 이 아니다'
exit 2
`)

	workDirPath, repo := delegationFixture(t)

	outcome, err := RunDelegation(t.Context(), workDirPath, DelegationRequest{
		Repo:           repo,
		Prompt:         "고쳐줘",
		ConversationID: "11111111-2222-4333-8444-555555555555",
	})
	if err != nil {
		t.Fatalf("RunDelegation() 실패: %v", err)
	}

	if outcome.ExitCode != 2 {
		t.Errorf("exitCode = %d, want 2", outcome.ExitCode)
	}
	if !strings.Contains(readFileForTest(t, outcome.LogPath), "이건 JSON 이 아니다") {
		t.Errorf("기록 = %s, 읽지 못한 답도 그대로 남기기를 기대", readFileForTest(t, outcome.LogPath))
	}
}

func TestMostRecentDelegationAnswersTheNewestRunOfThatRepo(t *testing.T) {
	// 카드가 최근 위임 줄을 보이는 근거다(설계 문서 6.1). 별도의 상태 파일을 두지 않고
	// 원출력 기록을 읽는다 — 그것이 곧 관찰이다.
	fakeClaudeOnPath(t, `
cat > /dev/null
echo "{\"subtype\":\"success\",\"result\":\"$KYU_TEST_ANSWER\",\"session_id\":\"11111111-2222-4333-8444-555555555555\",\"permission_denials\":[]}"
`)

	workDirPath, repo := delegationFixture(t)
	otherRepoPath := filepath.Join(workDirPath, "proj")
	if err := os.MkdirAll(otherRepoPath, 0o755); err != nil {
		t.Fatalf("테스트용 레포 디렉토리 생성 실패: %v", err)
	}

	// 이름이 접두사로 겹치는 레포를 함께 둔다. 파일 이름만으로 고르면 proj 의 기록이
	// proj-a 의 것으로 잘못 잡힌다.
	t.Setenv("KYU_TEST_ANSWER", "다른 레포의 답")
	if _, err := RunDelegation(t.Context(), workDirPath, DelegationRequest{
		Repo:           Repo{Name: "proj", AbsolutePath: otherRepoPath},
		Prompt:         "고쳐줘",
		ConversationID: "22222222-2222-4333-8444-555555555555",
	}); err != nil {
		t.Fatalf("RunDelegation() 실패: %v", err)
	}

	t.Setenv("KYU_TEST_ANSWER", "이 레포의 답")
	newest, err := RunDelegation(t.Context(), workDirPath, DelegationRequest{
		Repo:           repo,
		Prompt:         "고쳐줘",
		ConversationID: "11111111-2222-4333-8444-555555555555",
	})
	if err != nil {
		t.Fatalf("RunDelegation() 실패: %v", err)
	}

	recent, err := MostRecentDelegation(workDirPath, "proj-a")
	if err != nil {
		t.Fatalf("MostRecentDelegation() 실패: %v", err)
	}
	if recent == nil {
		t.Fatal("최근 위임 = null, 방금 돌린 위임을 기대")
	}
	if recent.LogPath != newest.LogPath {
		t.Errorf("logPath = %q, want %q", recent.LogPath, newest.LogPath)
	}
	if recent.HadPermissionDenials {
		t.Error("hadPermissionDenials = true, 거절이 없던 위임을 기대")
	}
	if recent.FinishedAt == "" {
		t.Error("finishedAt 이 비어 있습니다 — 언제 끝난 위임인지를 카드가 보여야 합니다")
	}
}

func TestMostRecentDelegationAnswersNothingWhenTheRepoWasNeverDelegatedTo(t *testing.T) {
	// 위임한 적 없는 레포가 대부분이다. 없음이 실패가 되면 목록이 통째로 멈춘다.
	workDirPath, _ := delegationFixture(t)

	recent, err := MostRecentDelegation(workDirPath, "proj-a")
	if err != nil {
		t.Fatalf("MostRecentDelegation() 실패: %v", err)
	}
	if recent != nil {
		t.Errorf("최근 위임 = %+v, 없음을 기대", recent)
	}
}

func readFileForTest(t *testing.T, path string) string {
	t.Helper()

	content, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("파일을 읽지 못했습니다 (%s): %v", path, err)
	}
	return string(content)
}

func readLinesForTest(t *testing.T, path string) []string {
	t.Helper()

	return strings.Split(strings.TrimSuffix(readFileForTest(t, path), "\n"), "\n")
}

// assertSamePathForTest 는 심볼릭 링크를 편 뒤 경로를 비교한다.
//
// 임시 디렉토리 경로는 링크를 거쳐 올 수 있어(macOS 의 /var) 문자열로 비교하면 엉뚱하게 깨진다.
func assertSamePathForTest(t *testing.T, actualPath, wantedPath string) {
	t.Helper()

	actualRealPath, err := filepath.EvalSymlinks(actualPath)
	if err != nil {
		t.Fatalf("경로를 확인하지 못했습니다 (%s): %v", actualPath, err)
	}
	wantedRealPath, err := filepath.EvalSymlinks(wantedPath)
	if err != nil {
		t.Fatalf("경로를 확인하지 못했습니다 (%s): %v", wantedPath, err)
	}
	if actualRealPath != wantedRealPath {
		t.Errorf("경로 = %q, want %q", actualRealPath, wantedRealPath)
	}
}
