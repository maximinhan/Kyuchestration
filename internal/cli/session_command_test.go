package cli

import (
	"bytes"
	"encoding/json"
	"os"
	"path/filepath"
	"regexp"
	"slices"
	"strings"
	"testing"
)

// sessionCommandJSONDocumentForTest 는 kyu session-command --json 이 내보내는 문서를 테스트가
// 직접 다시 적어둔 모양이다.
//
// 생산 코드의 직렬화 타입을 재사용하지 않는 이유는 list_json_test.go 와 같다 — 읽는 쪽(앱)은
// 자기 코드에 이 모양을 적어두고 그것에 맞춰 파싱하므로, 테스트도 똑같이 적어두어야
// 이름이나 모양이 바뀌는 순간 여기에서 먼저 깨진다.
type sessionCommandJSONDocumentForTest struct {
	SchemaVersion int               `json:"schemaVersion"`
	Command       []string          `json:"command"`
	Cwd           string            `json:"cwd"`
	Env           map[string]string `json:"env"`

	// ResumedConversationID 는 이어가기로 연 대화의 ID 다. 새 대화면 null 이므로 포인터로 받는다 —
	// 빈 문자열로 받으면 "없음" 과 "빈 ID" 를 테스트가 가르지 못한다.
	ResumedConversationID *string `json:"resumedConversationId"`
}

// sessionCommandRunForTest 는 한 번의 실행 결과다. 되읽은 문서와 두 스트림의 원문을 함께 들고 있다.
type sessionCommandRunForTest struct {
	document sessionCommandJSONDocumentForTest
	stdout   string
	stderr   string
}

func runSessionCommandForTest(t *testing.T, args ...string) sessionCommandRunForTest {
	t.Helper()

	var out, errOut bytes.Buffer
	if err := AnswerSessionCommand(&out, &errOut, append(args, machineJSONOptionName)); err != nil {
		t.Fatalf("AnswerSessionCommand() 실패: %v", err)
	}

	var document sessionCommandJSONDocumentForTest
	decodeMachineJSONForTest(t, out.String(), &document)

	if document.SchemaVersion != 1 {
		t.Errorf("schemaVersion = %d, want 1", document.SchemaVersion)
	}
	return sessionCommandRunForTest{document: document, stdout: out.String(), stderr: errOut.String()}
}

// conversationIDPattern 은 claude 가 --session-id 에 요구하는 모양이다(설계 문서 5.5.1).
var conversationIDPattern = regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$`)

// splitConversationFlagsForTest 는 답한 명령의 앞부분에서 대화 플래그를 떼어내고 나머지를 돌려준다.
//
// 대화 ID 는 실행할 때마다 새로 만들어지므로 값을 미리 적어둘 수 없다. 값 대신 자리와 모양을
// 확인하고, 나머지 조립은 그대로 비교한다 — 앱이 이 명령을 그대로 exec 하므로 순서가 계약이다.
func splitConversationFlagsForTest(t *testing.T, command []string) (flagName, conversationID string, rest []string) {
	t.Helper()

	if len(command) < 3 {
		t.Fatalf("명령 = %q, claude 와 대화 플래그 한 쌍을 기대", command)
	}
	if command[0] != "claude" {
		t.Errorf("명령의 첫 자리 = %q, want claude", command[0])
	}
	if command[1] != "--session-id" && command[1] != "--resume" {
		t.Fatalf("명령의 둘째 자리 = %q, --session-id 또는 --resume 을 기대", command[1])
	}
	if !conversationIDPattern.MatchString(command[2]) {
		t.Errorf("대화 ID = %q, claude 가 받는 UUID 모양을 기대", command[2])
	}
	return command[1], command[2], command[3:]
}

func assertRestOfCommand(t *testing.T, rest, want []string) {
	t.Helper()

	if !slices.Equal(rest, want) {
		t.Errorf("대화 플래그 뒤의 명령 = %q, want %q", rest, want)
	}
}

// orchestrationServerRegistrationForTest 는 답한 명령에서 --mcp-config 한 쌍을 떼어내 되읽고,
// 나머지를 돌려준다.
//
// 등록에 실리는 kyu 절대경로는 실행마다 다르므로(테스트에서는 테스트 바이너리다) 값을 미리
// 적어둘 수 없다. 자리와 모양을 확인하고 나머지 조립은 그대로 비교한다.
func orchestrationServerRegistrationForTest(t *testing.T, command []string) (registration mcpConfigForTest, rest []string) {
	t.Helper()

	flagIndex := slices.Index(command, "--mcp-config")
	if flagIndex < 0 {
		t.Fatalf("명령 = %q, 오케스트레이션 서버를 등록하는 --mcp-config 를 기대", command)
	}
	if flagIndex+1 >= len(command) {
		t.Fatalf("명령 = %q, --mcp-config 뒤에 설정 문자열을 기대", command)
	}

	if err := json.Unmarshal([]byte(command[flagIndex+1]), &registration); err != nil {
		t.Fatalf("--mcp-config 의 값을 JSON 으로 읽지 못했습니다: %v\n--- 값 ---\n%s", err, command[flagIndex+1])
	}
	return registration, slices.Concat(command[:flagIndex], command[flagIndex+2:])
}

// mcpConfigForTest 는 claude 가 --mcp-config 로 받는 문서다.
//
// 생산 코드의 타입을 재사용하지 않는다. 이 모양은 claude 와 맺는 계약이고, 도구 안에서 이름이
// 바뀌는 것이 계약을 바꾸는 일이 되어서는 안 된다.
type mcpConfigForTest struct {
	McpServers map[string]struct {
		Type    string   `json:"type"`
		Command string   `json:"command"`
		Args    []string `json:"args"`
	} `json:"mcpServers"`
}

func TestTheMainSessionCommandRegistersTheOrchestrationServerAsAConfigString(t *testing.T) {
	// 워크디렉토리 .mcp.json 에 적는 길은 대화형 승인 관문을 탄다(설계 문서 3.2 · 5.2).
	// 문자열로 넘기면 그 관문이 없고, 워크디렉토리에 낡을 파일도 생기지 않는다.
	workDirPath := makeWorkDir(t)
	makeCleanRepo(t, workDirPath, "alpha-commons")
	t.Chdir(workDirPath)

	run := runSessionCommandForTest(t)

	registration, rest := orchestrationServerRegistrationForTest(t, run.document.Command)
	server, registered := registration.McpServers["kyu"]
	if !registered {
		t.Fatalf("등록한 서버 = %v, kyu 하나를 기대", registration.McpServers)
	}
	if server.Type != "stdio" {
		t.Errorf("서버 type = %q, want stdio", server.Type)
	}
	// 답하는 kyu 가 자기 경로를 적는다 — 앱이 KYU_BINARY_PATH 로 어느 엔진을 겨누든
	// 그 엔진이 자기를 등록해야 등록이 낡지 않는다(설계 문서 5.2).
	if !filepath.IsAbs(server.Command) {
		t.Errorf("서버 command = %q, kyu 의 절대경로를 기대", server.Command)
	}
	if !slices.Equal(server.Args, []string{"mcp", "serve", workDirPath}) {
		t.Errorf("서버 args = %q, want [mcp serve %s]", server.Args, workDirPath)
	}

	// 등록을 떼어낸 나머지는 전과 같아야 한다.
	_, _, restAfterConversation := splitConversationFlagsForTest(t, rest)
	assertRestOfCommand(t, restAfterConversation, []string{"--add-dir", filepath.Join(workDirPath, "alpha-commons")})
}

func TestARepoSessionIsNotGivenTheOrchestrationServer(t *testing.T) {
	// 레포 세션이 run_in_repo 를 가지면 레포 세션이 다른 레포에 위임하게 된다. 위임의 시작점은
	// 메인 하나다(설계 문서 5.2.1).
	workDirPath := makeWorkDir(t)
	makeCleanRepo(t, workDirPath, "alpha-commons")
	t.Chdir(workDirPath)

	run := runSessionCommandForTest(t, "alpha-commons")

	if slices.Contains(run.document.Command, "--mcp-config") {
		t.Errorf("명령 = %q, 레포 세션에는 오케스트레이션 서버를 붙이지 않기를 기대", run.document.Command)
	}
}

func TestTheMainSessionCarriesTheBypassMarkerInItsEnvironmentSoDelegationInheritsIt(t *testing.T) {
	// 위임은 메인보다 넓은 권한을 갖지 않는다(설계 원칙 13). 메인이 bypass 로 열렸다는 사실이
	// 위임까지 닿는 통로가 이 환경변수다 — stdio MCP 서버는 자기를 띄운 claude 의 환경을
	// 물려받는다(설계 문서 5.4.1).
	workDirPath := makeWorkDir(t)
	t.Chdir(workDirPath)

	run := runSessionCommandForTest(t, bypassPermissionsOptionName)

	if run.document.Env["KYU_BYPASS_PERMISSIONS"] != "1" {
		t.Errorf("env = %v, 위임까지 닿을 bypass 표식을 기대", run.document.Env)
	}
}

func TestTheMainSessionWithoutBypassCarriesNoBypassMarker(t *testing.T) {
	// 표식이 늘 붙어 있으면 그것은 사실을 나르지 않는다 — 위임은 늘 bypass 로 돌게 된다.
	workDirPath := makeWorkDir(t)
	t.Chdir(workDirPath)

	run := runSessionCommandForTest(t)

	if _, marked := run.document.Env["KYU_BYPASS_PERMISSIONS"]; marked {
		t.Errorf("env = %v, bypass 없이 연 메인에는 표식이 없기를 기대", run.document.Env)
	}
}

func TestARepoSessionDoesNotCarryTheBypassMarker(t *testing.T) {
	// 레포 세션에는 오케스트레이션 서버가 없으므로 이 표식을 읽을 상대가 없다. 그래도 실어 보내면
	// 그 세션이 무언가를 더 할 수 있다고 읽힌다.
	workDirPath := makeWorkDir(t)
	makeCleanRepo(t, workDirPath, "alpha-commons")
	t.Chdir(workDirPath)

	run := runSessionCommandForTest(t, "alpha-commons", bypassPermissionsOptionName)

	if _, marked := run.document.Env["KYU_BYPASS_PERMISSIONS"]; marked {
		t.Errorf("env = %v, 레포 세션에는 bypass 표식이 없기를 기대", run.document.Env)
	}
	if !slices.Contains(run.document.Command, "--dangerously-skip-permissions") {
		t.Errorf("명령 = %q, 레포 세션의 bypass 는 여전히 플래그로 가기를 기대", run.document.Command)
	}
}

func TestSessionCommandForTheMainSessionAnswersAddDirForEveryRepo(t *testing.T) {
	// 앱이 이 답을 그대로 실행한다. 레포를 하나라도 빠뜨리면 메인 세션은 그 레포를 읽지 못한 채
	// 계획을 세우고, 사용자는 그 사실을 세션 안에서야 알게 된다.
	workDirPath := makeWorkDir(t)
	makeCleanRepo(t, workDirPath, "zeta-service")
	makeCleanRepo(t, workDirPath, "alpha-commons")
	t.Chdir(workDirPath)

	run := runSessionCommandForTest(t)

	_, commandWithoutRegistration := orchestrationServerRegistrationForTest(t, run.document.Command)
	flagName, _, rest := splitConversationFlagsForTest(t, commandWithoutRegistration)
	if flagName != "--session-id" {
		t.Errorf("대화 플래그 = %q, 첫 물음에는 --session-id 를 기대", flagName)
	}
	assertRestOfCommand(t, rest, []string{
		"--add-dir", filepath.Join(workDirPath, "alpha-commons"),
		"--add-dir", filepath.Join(workDirPath, "zeta-service"),
	})

	if run.document.Cwd != workDirPath {
		t.Errorf("cwd = %q, want %q", run.document.Cwd, workDirPath)
	}
	if len(run.document.Env) != 0 {
		t.Errorf("env = %v, 더할 것이 없으면 비어 있기를 기대", run.document.Env)
	}
}

func TestSessionCommandForARepoSessionAnswersThatRepoDirectoryAsCwd(t *testing.T) {
	// 레포 세션이 그 레포의 MCP·CLAUDE.md·에이전트를 그대로 쓰는 근거는 cwd 하나뿐이다
	// (설계 문서 1.3). 이 값이 어긋나면 이 도구의 존재 이유가 앱에서 성립하지 않는다.
	workDirPath := makeWorkDir(t)
	makeCleanRepo(t, workDirPath, "alpha-commons")
	makeCleanRepo(t, workDirPath, "beta-gateway")
	t.Chdir(workDirPath)

	run := runSessionCommandForTest(t, "beta-gateway")

	_, _, rest := splitConversationFlagsForTest(t, run.document.Command)
	assertRestOfCommand(t, rest, nil)

	if run.document.Cwd != filepath.Join(workDirPath, "beta-gateway") {
		t.Errorf("cwd = %q, want %q", run.document.Cwd, filepath.Join(workDirPath, "beta-gateway"))
	}
}

func TestSessionCommandCarriesBypassPermissionsInTheCommand(t *testing.T) {
	// 이 옵션은 claude 의 플래그로 옮겨지는 것이라 명령에 실려야 한다 — 위험 수준이 다른 모드를
	// 앱이 코틀린에서 다시 옮기게 두지 않는 것이 이 표면의 목적이다(설계 문서 5.2.1 가).
	workDirPath := makeWorkDir(t)
	makeCleanRepo(t, workDirPath, "alpha-commons")
	t.Chdir(workDirPath)

	run := runSessionCommandForTest(t, "alpha-commons", bypassPermissionsOptionName)

	_, _, rest := splitConversationFlagsForTest(t, run.document.Command)
	assertRestOfCommand(t, rest, []string{"--dangerously-skip-permissions"})
}

func TestSessionCommandCarriesRepoClaudeMdInTheEnvironmentNotTheCommand(t *testing.T) {
	// 이 표면은 환경을 환경으로 답한다(설계 문서 5.2). 명령을 env 로 감싸는 것은
	// SessionBackend.Create 에 환경 인자가 없어서 생긴 CLI 쪽 제약이고, 프로세스를 직접 띄우는
	// 앱은 그 아래 있지 않다 — 같은 사실을 두 표현으로 답하지 않는다.
	workDirPath := makeWorkDir(t)
	makeCleanRepo(t, workDirPath, "alpha-commons")
	t.Chdir(workDirPath)

	run := runSessionCommandForTest(t, repoClaudeMdOptionName)

	if run.document.Env["CLAUDE_CODE_ADDITIONAL_DIRECTORIES_CLAUDE_MD"] != "1" {
		t.Errorf("env = %v, 추가 디렉토리의 지침을 읽히는 환경변수를 기대", run.document.Env)
	}
	if run.document.Command[0] != "claude" {
		t.Errorf("명령 = %q, env 로 감싸지 않기를 기대", run.document.Command)
	}
}

func TestSessionCommandAnswersAnEmptyEnvironmentAsAnObject(t *testing.T) {
	// 비어 있음을 null 과 {} 두 모양으로 다루게 하지 않는다 — 기계용 표면의 공통 규칙이다.
	// 앱은 이 값을 자기 바탕 환경에 얹는데, null 이 오면 그 한 줄에서 멈춘다.
	workDirPath := makeWorkDir(t)
	t.Chdir(workDirPath)

	run := runSessionCommandForTest(t)

	if !strings.Contains(run.stdout, `"env": {}`) {
		t.Errorf("stdout = %q, 빈 환경이 {} 로 나가기를 기대", run.stdout)
	}
}

func TestSessionCommandAnswersTheSameConversationOnTheSecondCallAndResumesIt(t *testing.T) {
	// 설계 문서 7절 1 단계의 완료 확인 항목이다 — "두 번 불러도 같은 UUID 를 답한다".
	// 두 번째 물음이 새 ID 를 만들면 앱을 껐다 켠 사용자가 이어갈 대화를 매번 잃는다.
	workDirPath := makeWorkDir(t)
	makeCleanRepo(t, workDirPath, "alpha-commons")
	t.Chdir(workDirPath)

	firstFlag, firstID, _ := splitConversationFlagsForTest(t, runSessionCommandForTest(t, "alpha-commons").document.Command)
	secondFlag, secondID, _ := splitConversationFlagsForTest(t, runSessionCommandForTest(t, "alpha-commons").document.Command)

	if firstFlag != "--session-id" {
		t.Errorf("첫 물음의 플래그 = %q, want --session-id", firstFlag)
	}
	if secondFlag != "--resume" {
		t.Errorf("둘째 물음의 플래그 = %q, want --resume", secondFlag)
	}
	if secondID != firstID {
		t.Errorf("둘째 물음의 대화 ID = %q, 첫 물음의 %q 와 같기를 기대", secondID, firstID)
	}
}

func TestSessionCommandGivesEachLabelItsOwnConversation(t *testing.T) {
	// 메인 세션과 레포 세션은 다른 자리에서 다른 일을 한다. 대화를 나눠 갖게 하면
	// 한쪽의 맥락이 다른 쪽에 섞여 들어간다.
	workDirPath := makeWorkDir(t)
	makeCleanRepo(t, workDirPath, "alpha-commons")
	t.Chdir(workDirPath)

	_, mainConversationID, _ := splitConversationFlagsForTest(t, runSessionCommandForTest(t).document.Command)
	_, repoConversationID, _ := splitConversationFlagsForTest(t, runSessionCommandForTest(t, "alpha-commons").document.Command)

	if mainConversationID == repoConversationID {
		t.Errorf("두 라벨의 대화 ID 가 %q 로 같습니다", mainConversationID)
	}

	// 사람이 열어볼 수 있는 자리에 남는지도 함께 본다(설계 문서 5.5.2).
	record, err := os.ReadFile(filepath.Join(workDirPath, ".coord", "conversations.json"))
	if err != nil {
		t.Fatalf("대화 기록 파일을 읽지 못했습니다: %v", err)
	}
	for _, wantLabel := range []string{"main", "alpha-commons"} {
		if !strings.Contains(string(record), wantLabel) {
			t.Errorf("기록 = %s, 라벨 %q 를 포함하기를 기대", record, wantLabel)
		}
	}
}

func TestSessionCommandOnlyAnswersAsAMachineDocument(t *testing.T) {
	// 이 명령에는 사람용 흐름이 없다 — 답이 "사람이 읽고 무엇을 할 것" 이 아니라
	// "기계가 실행할 것" 이기 때문이다(설계 문서 5.2).
	workDirPath := makeWorkDir(t)
	t.Chdir(workDirPath)

	var out, errOut bytes.Buffer
	err := AnswerSessionCommand(&out, &errOut, nil)

	if err == nil {
		t.Fatal("AnswerSessionCommand() 가 --json 없이 성공했습니다")
	}
	if out.Len() != 0 {
		t.Errorf("stdout = %q, 거절했을 때는 문서를 내지 않기를 기대", out.String())
	}
	if !strings.Contains(err.Error(), machineJSONOptionName) {
		t.Errorf("에러 = %q, 어떤 옵션이 필요한지 함께 알리기를 기대", err.Error())
	}
}

func TestSessionCommandRejectsRepoClaudeMdForARepoSession(t *testing.T) {
	// 레포 세션은 자기 디렉토리의 CLAUDE.md 를 이미 읽는다. 조용히 무시하면 앱은 무언가 켜졌다고
	// 믿고, 그 믿음은 세션이 지침을 어길 때까지 드러나지 않는다.
	workDirPath := makeWorkDir(t)
	makeCleanRepo(t, workDirPath, "alpha-commons")
	t.Chdir(workDirPath)

	var out, errOut bytes.Buffer
	err := AnswerSessionCommand(&out, &errOut,
		[]string{"alpha-commons", repoClaudeMdOptionName, machineJSONOptionName})

	if err == nil {
		t.Fatal("AnswerSessionCommand() 가 레포 세션의 --repo-claude-md 로 성공했습니다")
	}
	if out.Len() != 0 {
		t.Errorf("stdout = %q, 거절했을 때는 문서를 내지 않기를 기대", out.String())
	}
}

func TestSessionCommandOnUnknownRepoListsTheReposItFoundAndRecordsNothing(t *testing.T) {
	// 답할 수 없는 물음에 대화를 배정하면, 오타 하나가 다시는 쓰이지 않을 기록을 남긴다.
	// 사용자가 그것을 지울 이유를 알아낼 방법은 없다.
	workDirPath := makeWorkDir(t)
	makeCleanRepo(t, workDirPath, "alpha-commons")
	t.Chdir(workDirPath)

	var out, errOut bytes.Buffer
	err := AnswerSessionCommand(&out, &errOut, []string{"gamma-worker", machineJSONOptionName})

	if err == nil {
		t.Fatal("AnswerSessionCommand() 가 없는 레포로 성공했습니다")
	}
	if !strings.Contains(err.Error(), "alpha-commons") {
		t.Errorf("에러 = %q, 실제 레포 이름을 함께 알리기를 기대", err.Error())
	}
	if _, statErr := os.Stat(filepath.Join(workDirPath, ".coord", "conversations.json")); statErr == nil {
		t.Error("없는 레포에 대화 기록이 만들어졌습니다")
	}
}

func TestSessionCommandRejectsAnOptionItDoesNotKnow(t *testing.T) {
	// 모르는 옵션을 레포 이름으로 흘려보내면 "없는 레포입니다: --typo" 라는 엉뚱한 안내가 나온다.
	workDirPath := makeWorkDir(t)
	t.Chdir(workDirPath)

	var out, errOut bytes.Buffer
	if err := AnswerSessionCommand(&out, &errOut, []string{"--bypass-permission", machineJSONOptionName}); err == nil {
		t.Fatal("AnswerSessionCommand() 가 모르는 옵션으로 성공했습니다")
	}
}

func TestSessionCommandTellsAboutADiscardedRecordOnStderr(t *testing.T) {
	// 기록이 깨져도 세션은 열려야 한다(설계 문서 6.1 의 규율). 그 말은 stdout 이 아니라
	// stderr 로 가야 앱의 파싱이 깨지지 않는다 — 기계용 표면의 공통 규칙이다.
	workDirPath := makeWorkDir(t)
	t.Chdir(workDirPath)

	coordDirectoryPath := filepath.Join(workDirPath, ".coord")
	if err := os.MkdirAll(coordDirectoryPath, 0o755); err != nil {
		t.Fatalf("준비 실패: %v", err)
	}
	if err := os.WriteFile(filepath.Join(coordDirectoryPath, "conversations.json"), []byte("깨진 기록"), 0o644); err != nil {
		t.Fatalf("준비 실패: %v", err)
	}

	run := runSessionCommandForTest(t)

	if flagName, _, _ := splitConversationFlagsForTest(t, run.document.Command); flagName != "--session-id" {
		t.Errorf("대화 플래그 = %q, 버린 기록 뒤에는 새 대화를 기대", flagName)
	}
	if !strings.Contains(run.stderr, "conversations.json") {
		t.Errorf("stderr = %q, 버린 기록의 경로를 알리기를 기대", run.stderr)
	}
}

func TestSessionCommandForgetsTheRecordedConversationWhenAsked(t *testing.T) {
	// 화면의 "새 대화로 시작" 이 이 옵션으로 온다. 앱이 기록 파일을 직접 지우지 않는 것이
	// 요점이다 — 기록의 자리와 모양은 엔진의 것이고, 두 곳이 그것을 알면 갈라진다(설계 원칙 11).
	workDirPath := makeWorkDir(t)
	makeCleanRepo(t, workDirPath, "alpha-commons")
	t.Chdir(workDirPath)

	_, recordedID, _ := splitConversationFlagsForTest(t, runSessionCommandForTest(t, "alpha-commons").document.Command)

	freshRun := runSessionCommandForTest(t, "alpha-commons", forgetConversationOptionName)

	freshFlag, freshID, _ := splitConversationFlagsForTest(t, freshRun.document.Command)
	if freshFlag != "--session-id" {
		t.Errorf("대화 플래그 = %q, 버린 기록 뒤에는 새 대화를 기대", freshFlag)
	}
	if freshID == recordedID {
		t.Errorf("대화 ID = %q, 적혀 있던 것과 다르기를 기대", freshID)
	}

	// 기록이 교체되어야 다음에 카드를 누른 사용자가 방금 버린 대화로 되돌아가지 않는다.
	nextFlag, nextID, _ := splitConversationFlagsForTest(t, runSessionCommandForTest(t, "alpha-commons").document.Command)
	if nextFlag != "--resume" || nextID != freshID {
		t.Errorf("다음 물음 = %q %q, 새로 시작한 %q 를 이어가기를 기대", nextFlag, nextID, freshID)
	}
}

func TestSessionCommandForgetsTheMainSessionConversationToo(t *testing.T) {
	// 메인 세션도 대화를 하나 갖는다. 레포 세션에만 새로 시작할 길이 있으면, 조율용 세션의
	// 대화가 길어졌을 때 사용자가 빠져나올 자리가 없다.
	workDirPath := makeWorkDir(t)
	t.Chdir(workDirPath)

	_, recordedID, _ := splitConversationFlagsForTest(t, runSessionCommandForTest(t).document.Command)

	freshFlag, freshID, _ := splitConversationFlagsForTest(t,
		runSessionCommandForTest(t, forgetConversationOptionName).document.Command)

	if freshFlag != "--session-id" || freshID == recordedID {
		t.Errorf("대화 플래그 = %q, ID = %q, 적혀 있던 %q 를 버린 새 대화를 기대", freshFlag, freshID, recordedID)
	}
}

func TestSessionCommandTellsWhichConversationItResumed(t *testing.T) {
	// 앱이 "이어가려던 대화를 열지 못했다" 를 가려내는 근거가 이 필드다(설계 문서 5.5.4·8 절 2 번).
	// 이것이 없으면 앱은 곧바로 닫힌 터미널을 보고 사용자가 /exit 한 것과 구분하지 못한다.
	workDirPath := makeWorkDir(t)
	makeCleanRepo(t, workDirPath, "alpha-commons")
	t.Chdir(workDirPath)

	firstRun := runSessionCommandForTest(t, "alpha-commons")
	if firstRun.document.ResumedConversationID != nil {
		t.Errorf("첫 물음의 resumedConversationId = %q, 새 대화이므로 null 을 기대", *firstRun.document.ResumedConversationID)
	}
	if !strings.Contains(firstRun.stdout, `"resumedConversationId": null`) {
		t.Errorf("stdout = %q, 이어간 대화가 없음이 null 로 나가기를 기대", firstRun.stdout)
	}

	secondRun := runSessionCommandForTest(t, "alpha-commons")

	_, resumedID, _ := splitConversationFlagsForTest(t, secondRun.document.Command)
	if secondRun.document.ResumedConversationID == nil {
		t.Fatal("둘째 물음의 resumedConversationId = null, 이어간 대화의 ID 를 기대")
	}
	// 명령에 실린 것과 같은 값이어야 한다. 다르면 앱은 자기가 띄우지도 않은 대화를 두고
	// "이어가지 못했다" 를 판단하게 된다.
	if *secondRun.document.ResumedConversationID != resumedID {
		t.Errorf("resumedConversationId = %q, 명령에 실린 %q 와 같기를 기대",
			*secondRun.document.ResumedConversationID, resumedID)
	}
}

func TestSessionCommandResumesNothingWhenItWasToldToForgetTheRecord(t *testing.T) {
	// 새로 시작한 세션이 곧바로 끝나는 것은 이어가기 실패가 아니다. 여기서 ID 를 실어 보내면
	// 앱이 없는 실패를 화면에 띄운다.
	workDirPath := makeWorkDir(t)
	t.Chdir(workDirPath)
	runSessionCommandForTest(t)

	run := runSessionCommandForTest(t, forgetConversationOptionName)

	if run.document.ResumedConversationID != nil {
		t.Errorf("resumedConversationId = %q, 새로 시작한 대화이므로 null 을 기대", *run.document.ResumedConversationID)
	}
}
