package cli

import (
	"bytes"
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

func TestSessionCommandForTheMainSessionAnswersAddDirForEveryRepo(t *testing.T) {
	// 앱이 이 답을 그대로 실행한다. 레포를 하나라도 빠뜨리면 메인 세션은 그 레포를 읽지 못한 채
	// 계획을 세우고, 사용자는 그 사실을 세션 안에서야 알게 된다.
	workDirPath := makeWorkDir(t)
	makeCleanRepo(t, workDirPath, "zeta-service")
	makeCleanRepo(t, workDirPath, "alpha-commons")
	t.Chdir(workDirPath)

	run := runSessionCommandForTest(t)

	flagName, _, rest := splitConversationFlagsForTest(t, run.document.Command)
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
	// "기계가 실행할 것" 이기 때문이다(설계 문서 5.2). 사람이 세션을 띄우는 자리는 kyu start 다.
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
	if !strings.Contains(err.Error(), "kyu start") {
		t.Errorf("에러 = %q, 사람이 세션을 띄우는 자리를 함께 알리기를 기대", err.Error())
	}
}

func TestSessionCommandRejectsRepoClaudeMdForARepoSession(t *testing.T) {
	// 레포 세션은 자기 디렉토리의 CLAUDE.md 를 이미 읽는다. 조용히 무시하면 앱은 무언가 켜졌다고
	// 믿고, 그 믿음은 세션이 지침을 어길 때까지 드러나지 않는다 — kyu start 와 같은 규칙이다.
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
