package cli

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"testing"
	"time"

	"github.com/maximinhan/Kyuchestration/internal/mcpserver"
)

// fakeClaudeOnPathForTest 는 PATH 앞에 가짜 claude 를 놓는다.
//
// 도구의 답과 갈래를 진짜 claude 없이 확인하기 위한 것이다. 진짜 claude 로 무엇이 되는지는
// 통합 시험이 따로 본다 — 여기서 묻는 것은 "결과를 모델에게 어떻게 옮기는가" 다.
func fakeClaudeOnPathForTest(t *testing.T, script string) {
	t.Helper()

	binDirectoryPath := t.TempDir()
	if err := os.WriteFile(filepath.Join(binDirectoryPath, "claude"), []byte("#!/bin/sh\n"+script), 0o755); err != nil {
		t.Fatalf("가짜 claude 생성 실패: %v", err)
	}
	t.Setenv("PATH", binDirectoryPath+string(os.PathListSeparator)+os.Getenv("PATH"))
}

// echoBackTheConversationIDForTest 는 받은 --session-id · --resume 값을 쉘 변수로 꺼내는 조각이다.
//
// 진짜 claude 는 자기가 받은 대화 ID 를 답 문서의 session_id 로 돌려준다(설계 문서 3.5). 가짜가
// 고정값을 답하면 "대화가 바뀌었나" 를 묻는 시험이 통과할 수 없는 것을 요구하게 된다.
const echoBackTheConversationIDForTest = `
conversation_id=""
take_next=""
for argument in "$@"; do
  if [ -n "$take_next" ]; then conversation_id="$argument"; take_next=""; fi
  if [ "$argument" = "--session-id" ] || [ "$argument" = "--resume" ]; then take_next="yes"; fi
done
`

// claudeAnsweringForTest 는 답 본문 하나를 내고 끝나는 가짜 claude 스크립트를 만든다.
func claudeAnsweringForTest(result string) string {
	return fmt.Sprintf(`
printf '%%s\n' "$@" >> "${KYU_TEST_ARGV_PATH:-/dev/null}"
cat > /dev/null
%s
echo "{\"subtype\":\"success\",\"is_error\":false,\"result\":\"%s\",\"session_id\":\"$conversation_id\",\"permission_denials\":[],\"total_cost_usd\":0.02,\"duration_ms\":1500,\"num_turns\":2}"
`, echoBackTheConversationIDForTest, result)
}

// runInRepoAnswerForTest 는 run_in_repo 가 모델에게 답하는 문서를 테스트가 직접 다시 적어둔 모양이다.
type runInRepoAnswerForTest struct {
	Repo              string   `json:"repo"`
	Result            string   `json:"result"`
	Incomplete        *string  `json:"incomplete"`
	PermissionDenials []string `json:"permissionDenials"`
	ConversationID    string   `json:"conversationId"`
	Resumed           bool     `json:"resumed"`
	CostUSD           float64  `json:"costUsd"`
	DurationMS        int64    `json:"durationMs"`
	NumTurns          int      `json:"numTurns"`
	ExitCode          int      `json:"exitCode"`
	LogPath           string   `json:"logPath"`
}

func delegateForTest(t *testing.T, tool mcpserver.Tool, arguments string) (runInRepoAnswerForTest, mcpserver.ToolResult) {
	t.Helper()

	result := callToolForTest(t, tool, arguments)

	var answer runInRepoAnswerForTest
	if err := json.Unmarshal([]byte(result.Text), &answer); err != nil {
		t.Fatalf("run_in_repo 의 답을 JSON 으로 읽지 못했습니다: %v\n--- 답 ---\n%s", err, result.Text)
	}
	return answer, result
}

// workDirWithOneRepoForTest 는 위임 하나를 걸 수 있는 워크디렉토리를 만든다.
func workDirWithOneRepoForTest(t *testing.T) string {
	t.Helper()

	workDirPath := makeWorkDir(t)
	makeCleanRepo(t, workDirPath, "alpha-commons")
	return workDirPath
}

func TestRunInRepoRefusesARepoTheWorkDirDoesNotHave(t *testing.T) {
	// 없는 이름을 그대로 실행하면 claude 가 없는 디렉토리에서 뜨려다 실패하고, 모델은 그 실패에서
	// "레포 이름을 잘못 골랐다" 를 읽어내지 못한다.
	workDirPath := workDirWithOneRepoForTest(t)

	result := callToolForTest(t, newRunInRepoTool(workDirPath, false), `{"repo":"gamma-worker","prompt":"고쳐줘"}`)

	if !result.IsError {
		t.Fatalf("없는 레포로 성공했습니다: %s", result.Text)
	}
	if !strings.Contains(result.Text, "alpha-commons") {
		t.Errorf("거절 = %q, 실제 레포 이름을 함께 알리기를 기대", result.Text)
	}
}

func TestRunInRepoRefusesAnEmptyPrompt(t *testing.T) {
	// 빈 프롬프트로 claude 를 띄우면 아무것도 시키지 않은 위임이 비용만 쓰고 돌아온다.
	workDirPath := workDirWithOneRepoForTest(t)

	result := callToolForTest(t, newRunInRepoTool(workDirPath, false), `{"repo":"alpha-commons","prompt":"   "}`)

	if !result.IsError {
		t.Fatalf("빈 프롬프트로 성공했습니다: %s", result.Text)
	}
}

func TestRunInRepoCarriesTheAnswerTheCostAndWhereTheRawOutputWent(t *testing.T) {
	fakeClaudeOnPathForTest(t, claudeAnsweringForTest("README 에 한 줄 더했습니다"))
	workDirPath := workDirWithOneRepoForTest(t)

	answer, result := delegateForTest(t, newRunInRepoTool(workDirPath, false),
		`{"repo":"alpha-commons","prompt":"README 에 한 줄 더해줘"}`)

	if result.IsError {
		t.Fatalf("정상 위임이 거절로 왔습니다: %s", result.Text)
	}
	if answer.Repo != "alpha-commons" || answer.Result != "README 에 한 줄 더했습니다" {
		t.Errorf("답 = %+v, 위임의 답 본문을 기대", answer)
	}
	if answer.Incomplete != nil {
		t.Errorf("incomplete = %q, 완결된 위임에는 null 을 기대", *answer.Incomplete)
	}
	if answer.CostUSD != 0.02 || answer.NumTurns != 2 || answer.DurationMS != 1500 {
		t.Errorf("답 = %+v, 비용·턴·시간을 함께 답하기를 기대", answer)
	}
	if answer.Resumed {
		t.Error("resumed = true, 첫 위임은 새 대화이기를 기대")
	}
	// 원출력을 어디서 열어볼 수 있는지가 사후 추적의 출발점이다(설계 문서 5.4.3·6.3).
	if _, err := os.Stat(answer.LogPath); err != nil {
		t.Errorf("logPath = %q, 실제로 열 수 있는 파일을 기대: %v", answer.LogPath, err)
	}
}

func TestRunInRepoDoesNotReportAPermissionBlockedDelegationAsSuccess(t *testing.T) {
	// **이 시험이 이 파일의 요점이다.** 막힌 위임도 종료 코드 0 · subtype success 로 끝난다
	// (설계 문서 3.3). 엔진이 이것을 삼키면 메인은 성공으로 믿고 다음 걸음으로 간다(5.4.2).
	fakeClaudeOnPathForTest(t, `
cat > /dev/null
echo '{"subtype":"success","is_error":false,"result":"권한이 필요합니다","session_id":"11111111-2222-4333-8444-555555555555","permission_denials":[{"tool_name":"mcp__proj__deploy"},{"tool_name":"Edit"}]}'
`)
	workDirPath := workDirWithOneRepoForTest(t)

	answer, result := delegateForTest(t, newRunInRepoTool(workDirPath, false),
		`{"repo":"alpha-commons","prompt":"고쳐줘"}`)

	if !result.IsError {
		t.Error("isError = false, 아무것도 하지 못한 위임을 성공으로 보고했습니다")
	}
	if !slices.Equal(answer.PermissionDenials, []string{"mcp__proj__deploy", "Edit"}) {
		t.Errorf("permissionDenials = %q, 거절된 도구 이름을 그대로 기대", answer.PermissionDenials)
	}
	if answer.Incomplete == nil {
		t.Fatal("incomplete = null, 완결되지 않았다는 것을 문장으로도 적기를 기대(설계 문서 5.4.2)")
	}
	if answer.ExitCode != 0 {
		t.Errorf("exitCode = %d, 막힌 실행도 0 으로 끝나는 것이 이 시험의 전제다", answer.ExitCode)
	}
}

func TestRunInRepoResumesItsOwnConversationOnTheSecondDelegation(t *testing.T) {
	// 같은 레포에 두 번 위임하면 두 번째가 첫 번째를 기억한다(설계 문서 5.5).
	argvPath := filepath.Join(t.TempDir(), "argv")
	t.Setenv("KYU_TEST_ARGV_PATH", argvPath)
	fakeClaudeOnPathForTest(t, claudeAnsweringForTest("했습니다"))

	workDirPath := workDirWithOneRepoForTest(t)
	tool := newRunInRepoTool(workDirPath, false)

	first, _ := delegateForTest(t, tool, `{"repo":"alpha-commons","prompt":"하나"}`)
	second, _ := delegateForTest(t, tool, `{"repo":"alpha-commons","prompt":"둘"}`)

	if first.Resumed {
		t.Error("첫 위임의 resumed = true, 새 대화이기를 기대")
	}
	if !second.Resumed {
		t.Error("둘째 위임의 resumed = false, 첫 위임을 이어가기를 기대")
	}
	if second.ConversationID != first.ConversationID {
		t.Errorf("둘째 대화 ID = %q, 첫째의 %q 와 같기를 기대", second.ConversationID, first.ConversationID)
	}

	// 위임 대화는 앱 세션 대화와 다른 지도에 산다(설계 문서 5.5.1).
	record := readFileForTest(t, filepath.Join(workDirPath, ".coord", "conversations.json"))
	if !strings.Contains(record, "delegations") {
		t.Errorf("대화 기록 = %s, delegations 키 공간을 기대", record)
	}
}

func TestRunInRepoStartsANewConversationWhenTheModelAsksForOne(t *testing.T) {
	fakeClaudeOnPathForTest(t, claudeAnsweringForTest("했습니다"))
	workDirPath := workDirWithOneRepoForTest(t)
	tool := newRunInRepoTool(workDirPath, false)

	first, _ := delegateForTest(t, tool, `{"repo":"alpha-commons","prompt":"하나"}`)
	fresh, _ := delegateForTest(t, tool, `{"repo":"alpha-commons","prompt":"둘","newConversation":true}`)

	if fresh.Resumed {
		t.Error("resumed = true, 새 대화를 요구했습니다")
	}
	if fresh.ConversationID == first.ConversationID {
		t.Errorf("대화 ID = %q, 앞 대화와 다르기를 기대", fresh.ConversationID)
	}
}

func TestRunInRepoOpensANewConversationWhenTheRecordedOneIsGone(t *testing.T) {
	// 기록해 둔 대화의 전사가 사라졌을 때 빠져나오는 길이다(설계 문서 5.5). 위임은 엔진의 자식
	// 프로세스라 종료 코드와 stderr 를 그대로 보고, 그 자리에서 한 번 다시 연다.
	fakeClaudeOnPathForTest(t, `
cat > /dev/null
`+echoBackTheConversationIDForTest+`
for argument in "$@"; do
  if [ "$argument" = "--resume" ]; then
    echo "No conversation found with session ID: $conversation_id" >&2
    exit 1
  fi
done
echo "{\"subtype\":\"success\",\"result\":\"새 대화로 했습니다\",\"session_id\":\"$conversation_id\",\"permission_denials\":[]}"
`)
	workDirPath := workDirWithOneRepoForTest(t)
	tool := newRunInRepoTool(workDirPath, false)

	first, _ := delegateForTest(t, tool, `{"repo":"alpha-commons","prompt":"하나"}`)
	second, result := delegateForTest(t, tool, `{"repo":"alpha-commons","prompt":"둘"}`)

	if result.IsError {
		t.Fatalf("이어가기 실패 뒤 새 대화를 열지 못했습니다: %s", result.Text)
	}
	if second.Result != "새 대화로 했습니다" {
		t.Errorf("결과 = %q, 다시 연 대화의 답을 기대", second.Result)
	}
	if second.Resumed {
		t.Error("resumed = true, 이어가기에 실패해 새로 연 대화이기를 기대")
	}
	if second.ConversationID == first.ConversationID {
		t.Error("이어갈 수 없게 된 대화를 그대로 다시 적었습니다")
	}

	// 기록이 교체되어야 다음 위임이 방금 버린 대화로 되돌아가지 않는다.
	third, _ := delegateForTest(t, tool, `{"repo":"alpha-commons","prompt":"셋"}`)
	if third.ConversationID == first.ConversationID {
		t.Errorf("셋째 위임의 대화 = %q, 버린 대화로 되돌아갔습니다", third.ConversationID)
	}
}

func TestRunInRepoRefusesASecondDelegationToTheSameRepoWhileOneIsRunning(t *testing.T) {
	// 두 프로세스가 같은 디렉토리의 파일을 동시에 고치면 서로의 수정을 덮는다. 그리고 대화 ID 가
	// 하나뿐이라 뒤에 오는 쪽은 어차피 already in use 로 거절된다 — 알아들을 수 있는 말로 먼저
	// 거절하는 것이 설계 문서 5.7 이다.
	handshakeDirectoryPath := t.TempDir()
	startedPath := filepath.Join(handshakeDirectoryPath, "started")
	releasePath := filepath.Join(handshakeDirectoryPath, "release")
	t.Setenv("KYU_TEST_STARTED_PATH", startedPath)
	t.Setenv("KYU_TEST_RELEASE_PATH", releasePath)

	fakeClaudeOnPathForTest(t, `
cat > /dev/null
`+echoBackTheConversationIDForTest+`
: > "$KYU_TEST_STARTED_PATH"
while [ ! -f "$KYU_TEST_RELEASE_PATH" ]; do sleep 0.01; done
echo "{\"subtype\":\"success\",\"result\":\"했습니다\",\"session_id\":\"$conversation_id\",\"permission_denials\":[]}"
`)

	workDirPath := workDirWithOneRepoForTest(t)
	tool := newRunInRepoTool(workDirPath, false)

	firstFinished := make(chan mcpserver.ToolResult, 1)
	go func() {
		result, err := tool.Call(json.RawMessage(`{"repo":"alpha-commons","prompt":"하나"}`))
		if err != nil {
			panic(err)
		}
		firstFinished <- result
	}()

	waitUntilFileExistsForTest(t, startedPath)

	secondResult := callToolForTest(t, tool, `{"repo":"alpha-commons","prompt":"둘"}`)

	if err := os.WriteFile(releasePath, nil, 0o644); err != nil {
		t.Fatalf("가짜 claude 를 놓아주지 못했습니다: %v", err)
	}
	if first := <-firstFinished; first.IsError {
		t.Fatalf("첫 위임이 거절로 끝났습니다: %s", first.Text)
	}

	if !secondResult.IsError {
		t.Errorf("같은 레포에 대한 동시 위임이 통과했습니다: %s", secondResult.Text)
	}
	if !strings.Contains(secondResult.Text, "alpha-commons") {
		t.Errorf("거절 = %q, 어느 레포가 돌고 있는지를 알리기를 기대", secondResult.Text)
	}
}

func TestRunInRepoLetsTheSameRepoBeDelegatedToAgainOnceItIsFree(t *testing.T) {
	// 도는 중인 레포를 막는 것과 영영 막는 것은 다르다. 끝난 뒤에도 막히면 위임을 한 번 건 레포는
	// 그 세션 내내 다시 쓸 수 없게 된다.
	fakeClaudeOnPathForTest(t, claudeAnsweringForTest("했습니다"))
	workDirPath := workDirWithOneRepoForTest(t)
	tool := newRunInRepoTool(workDirPath, false)

	delegateForTest(t, tool, `{"repo":"alpha-commons","prompt":"하나"}`)
	if _, result := delegateForTest(t, tool, `{"repo":"alpha-commons","prompt":"둘"}`); result.IsError {
		t.Errorf("끝난 위임 뒤의 위임이 거절됐습니다: %s", result.Text)
	}
}

func TestRunInRepoPassesBypassOnlyWhenTheMainSessionHadIt(t *testing.T) {
	// 위임은 메인보다 넓은 권한을 갖지 않는다(설계 원칙 13). 그 스위치는 도구 인자에 없다 —
	// 모델이 부르는 도구에 두면 원칙 13 이 도구 설명 한 줄로 무너진다.
	for _, testCase := range []struct {
		name       string
		bypass     bool
		wantedFlag string
	}{
		{name: "bypass 없이 연 메인", bypass: false, wantedFlag: "--permission-mode"},
		{name: "bypass 로 연 메인", bypass: true, wantedFlag: "--dangerously-skip-permissions"},
	} {
		t.Run(testCase.name, func(t *testing.T) {
			argvPath := filepath.Join(t.TempDir(), "argv")
			t.Setenv("KYU_TEST_ARGV_PATH", argvPath)
			fakeClaudeOnPathForTest(t, claudeAnsweringForTest("했습니다"))

			workDirPath := workDirWithOneRepoForTest(t)

			delegateForTest(t, newRunInRepoTool(workDirPath, testCase.bypass), `{"repo":"alpha-commons","prompt":"고쳐줘"}`)

			argv := readFileForTest(t, argvPath)
			if !strings.Contains(argv, testCase.wantedFlag) {
				t.Errorf("argv = %q, %s 를 기대", argv, testCase.wantedFlag)
			}
		})
	}
}

func TestListReposCarriesTheMostRecentDelegationSoTheModelSeesWhatAlreadyRan(t *testing.T) {
	// 설계 문서 5.3 의 list_repos 답에 recentDelegation 이 들어 있다. 엔진은 별도 상태 파일이
	// 아니라 .coord/runs/ 를 읽어서 답한다 — 그것이 곧 관찰이다(6.1).
	fakeClaudeOnPathForTest(t, claudeAnsweringForTest("했습니다"))
	workDirPath := workDirWithOneRepoForTest(t)

	delegateForTest(t, newRunInRepoTool(workDirPath, false), `{"repo":"alpha-commons","prompt":"고쳐줘"}`)

	var answer struct {
		Repos []struct {
			Name             string `json:"name"`
			RecentDelegation *struct {
				FinishedAt           string `json:"finishedAt"`
				ExitCode             int    `json:"exitCode"`
				HadPermissionDenials bool   `json:"hadPermissionDenials"`
				LogPath              string `json:"logPath"`
			} `json:"recentDelegation"`
		} `json:"repos"`
	}
	result := callToolForTest(t, newListReposTool(workDirPath), `{}`)
	if err := json.Unmarshal([]byte(result.Text), &answer); err != nil {
		t.Fatalf("list_repos 의 답을 JSON 으로 읽지 못했습니다: %v\n--- 답 ---\n%s", err, result.Text)
	}

	if len(answer.Repos) != 1 {
		t.Fatalf("repos = %+v, 하나를 기대", answer.Repos)
	}
	if answer.Repos[0].RecentDelegation == nil {
		t.Fatalf("recentDelegation = null, 방금 돌린 위임을 기대: %s", result.Text)
	}
	if answer.Repos[0].RecentDelegation.FinishedAt == "" || answer.Repos[0].RecentDelegation.LogPath == "" {
		t.Errorf("recentDelegation = %+v, 언제 끝났고 원출력이 어디 있는지를 기대", answer.Repos[0].RecentDelegation)
	}
}

func TestListReposAnswersNullForARepoNeverDelegatedTo(t *testing.T) {
	// 위임한 적 없는 레포가 대부분이다. 빈 객체로 채우면 모델이 "위임한 적 있음" 으로 읽는다.
	workDirPath := workDirWithOneRepoForTest(t)

	var answer struct {
		Repos []struct {
			RecentDelegation *struct{} `json:"recentDelegation"`
		} `json:"repos"`
	}
	result := callToolForTest(t, newListReposTool(workDirPath), `{}`)
	if err := json.Unmarshal([]byte(result.Text), &answer); err != nil {
		t.Fatalf("list_repos 의 답을 JSON 으로 읽지 못했습니다: %v", err)
	}

	if len(answer.Repos) != 1 || answer.Repos[0].RecentDelegation != nil {
		t.Errorf("recentDelegation = %+v, null 을 기대", answer.Repos)
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

func waitUntilFileExistsForTest(t *testing.T, path string) {
	t.Helper()

	deadline := time.Now().Add(10 * time.Second)
	for time.Now().Before(deadline) {
		if _, err := os.Stat(path); err == nil {
			return
		}
		time.Sleep(5 * time.Millisecond)
	}
	t.Fatalf("%s 가 생기지 않았습니다", path)
}
