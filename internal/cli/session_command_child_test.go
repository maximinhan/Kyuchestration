package cli

import (
	"bytes"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"testing"

	"github.com/maximinhan/Kyuchestration/internal/workdir"
)

// childSessionCommandDocumentForTest 는 하위 세션 답 문서를 테스트가 다시 적어둔 모양이다.
//
// 앞 판의 네 필드에 둘이 더해진다(판은 1 그대로 — 필드를 더하는 변경). 앱(4 단계)이 이 모양을
// 자기 코드에 적어 파싱하므로, 이름이 바뀌는 순간 여기서 먼저 깨져야 한다.
type childSessionCommandDocumentForTest struct {
	SchemaVersion         int               `json:"schemaVersion"`
	Command               []string          `json:"command"`
	Cwd                   string            `json:"cwd"`
	Env                   map[string]string `json:"env"`
	ResumedConversationID *string           `json:"resumedConversationId"`

	ChildSession *struct {
		ID             string `json:"id"`
		Cwd            string `json:"cwd"`
		Title          string `json:"title"`
		ConversationID string `json:"conversationId"`
	} `json:"childSession"`

	ApprovalRequired *struct {
		Cwd              string `json:"cwd"`
		Fingerprint      string `json:"fingerprint"`
		ExecutionConfigs []struct {
			RelativePath string `json:"relativePath"`
			AbsolutePath string `json:"absolutePath"`
			Content      string `json:"content"`
		} `json:"executionConfigs"`
	} `json:"approvalRequired"`
}

const childSocketPathForTest = "/run/user/1000/kyu/child-1.sock"

func runChildSessionCommandForTest(t *testing.T, args ...string) childSessionCommandDocumentForTest {
	t.Helper()

	var out, errOut bytes.Buffer
	if err := AnswerSessionCommand(&out, &errOut, append(args, machineJSONOptionName)); err != nil {
		t.Fatalf("AnswerSessionCommand(%q) 실패: %v", args, err)
	}

	var document childSessionCommandDocumentForTest
	decodeMachineJSONForTest(t, out.String(), &document)
	if document.SchemaVersion != 1 {
		t.Errorf("schemaVersion = %d, 필드를 더하는 변경이라 1 그대로이기를 기대", document.SchemaVersion)
	}
	return document
}

// childArgsForTest 는 앱이 하위 하나를 띄울 때 붙일 기본 인자다.
func childArgsForTest(childID string, extraArgs ...string) []string {
	return append([]string{chatModeOptionName, childSessionOptionName, childID, appSocketOptionName, childSocketPathForTest}, extraArgs...)
}

// splitAppendSystemPromptForTest 는 규약 문구 한 쌍을 떼어내 그 문구와 나머지를 돌려준다.
func splitAppendSystemPromptForTest(t *testing.T, command []string) (string, []string) {
	t.Helper()

	flagIndex := slices.Index(command, "--append-system-prompt")
	if flagIndex < 0 || flagIndex+1 >= len(command) {
		t.Fatalf("명령 = %q, --append-system-prompt 와 문구를 기대", command)
	}
	return command[flagIndex+1], slices.Concat(command[:flagIndex], command[flagIndex+2:])
}

func TestAChildSessionRegistersTheChildRoleServerAndNoSpawnTools(t *testing.T) {
	// 깊이 제한은 도구 가용성으로 강제한다(6.3). 하위의 kyu 서버는 --child 로 뜨고 워크디렉토리를
	// 받지 않는다 — 그 역할의 도구 목록에 spawn_session 이 없다. 서버 상한은 상위와 같다(6.7 — ask_parent
	// 는 서버 상한까지 기다린다).
	workDirPath := makeWorkDir(t)
	makeCleanRepo(t, workDirPath, "proj-a")
	t.Chdir(workDirPath)

	document := runChildSessionCommandForTest(t, childArgsForTest("child-1", childCwdOptionName, "proj-a")...)

	registration, commandWithoutRegistration := mcpServerRegistrationForTest(t, document.Command)
	server, registered := registration.McpServers["kyu"]
	if !registered {
		t.Fatalf("등록한 서버 = %v, 하위 역할의 kyu 서버를 기대", registration.McpServers)
	}
	wantArgs := []string{"mcp", "serve", "--child", "--app-socket", childSocketPathForTest}
	if !slices.Equal(server.Args, wantArgs) {
		t.Errorf("kyu args = %q, want %q", server.Args, wantArgs)
	}
	if server.Timeout == nil || *server.Timeout != 3_600_000 {
		t.Errorf("kyu timeout = %v, 3600000 을 기대", server.Timeout)
	}
	if strings.Contains(strings.Join(document.Command, " "), "spawn_session") {
		t.Errorf("하위의 명령에 spawn_session 이 보입니다: %q", document.Command)
	}

	// 권한 상속(6.10) — 상위가 승인 브리지면 하위도 승인 브리지다. auto 는 쓰지 않는다.
	assertAskServerRegistration(t, registration, childSocketPathForTest)
	commandWithoutPermissionFlag := splitPermissionPromptToolForTest(t, commandWithoutRegistration)
	if slices.Contains(document.Command, "--permission-mode") {
		t.Errorf("명령 = %q, 하위에 --permission-mode 가 없기를 기대 — 사람 앞에 있으므로 묻는다", document.Command)
	}

	conventionText, commandWithoutConvention := splitAppendSystemPromptForTest(t, commandWithoutPermissionFlag)
	if !strings.Contains(conventionText, "[상위 세션]") || !strings.Contains(conventionText, "child-1") {
		t.Errorf("하위의 규약 문구 = %q, '[상위 세션]' 접두와 자기 id 를 기대", conventionText)
	}

	commandWithoutChatFlags := splitChatModeFlagsForTest(t, commandWithoutConvention)
	flagName, _, rest := splitConversationFlagsForTest(t, commandWithoutChatFlags)
	if flagName != "--session-id" {
		t.Errorf("대화 플래그 = %s, 하위는 언제나 새 대화(--session-id)이기를 기대(6.15)", flagName)
	}
	// 하위는 자기 cwd 하나에서 일한다 — 메인처럼 레포를 --add-dir 로 붙이지 않는다.
	assertRestOfCommand(t, rest, nil)

	if document.Cwd != filepath.Join(workDirPath, "proj-a") {
		t.Errorf("cwd = %q, 하위의 자리 proj-a 를 기대", document.Cwd)
	}
	if document.ApprovalRequired != nil {
		t.Errorf("approvalRequired = %+v, 걸리는 설정이 없으면 null 이기를 기대", document.ApprovalRequired)
	}
}

func TestAChildOfABypassParentIsBypassToo(t *testing.T) {
	// 하위는 상위의 권한 모드를 넘지도, 밑돌지도 않는다(6.10). bypass 표식(KYU_BYPASS_PERMISSIONS)은
	// 싣지 않는다 — 그것을 읽는 run_in_repo 가 하위 서버에는 없다.
	workDirPath := makeWorkDir(t)
	t.Chdir(workDirPath)

	document := runChildSessionCommandForTest(t, childArgsForTest("child-1", bypassPermissionsOptionName)...)

	if !slices.Contains(document.Command, "--dangerously-skip-permissions") {
		t.Errorf("명령 = %q, bypass 상위의 하위도 bypass 이기를 기대", document.Command)
	}
	registration, _ := mcpServerRegistrationForTest(t, document.Command)
	if _, registered := registration.McpServers["kyu-ask"]; registered {
		t.Errorf("등록한 서버 = %v, bypass 하위에는 승인 서버가 없기를 기대", registration.McpServers)
	}
	if len(document.Env) != 0 {
		t.Errorf("env = %v, 하위에는 bypass 표식이 없기를 기대", document.Env)
	}
}

func TestAChildSessionIsRecordedUnderTheMainLabelWithTheConversationItWasGiven(t *testing.T) {
	// 기록이 상위 라벨 아래 쌓인다(7 절 3 단계 완료 확인). 답 문서의 childSession 이 같은 사실을 앱에게
	// 준다 — 앱이 argv 에서 --session-id 를 되읽게 하지 않는다(원칙 11).
	workDirPath := makeWorkDir(t)
	makeCleanRepo(t, workDirPath, "proj-a")
	t.Chdir(workDirPath)

	first := runChildSessionCommandForTest(t, childArgsForTest("child-1", childCwdOptionName, "proj-a", childTitleOptionName, "포트 수정")...)
	second := runChildSessionCommandForTest(t, childArgsForTest("child-2", childCwdOptionName, "scratch-x", createDirectoryOptionName)...)

	if first.ChildSession == nil || second.ChildSession == nil {
		t.Fatalf("childSession = %+v / %+v, 하위 답에는 childSession 이 있기를 기대", first.ChildSession, second.ChildSession)
	}
	if first.ChildSession.ID != "child-1" || first.ChildSession.Cwd != "proj-a" || first.ChildSession.Title != "포트 수정" {
		t.Errorf("childSession = %+v, child-1 · proj-a · 포트 수정 을 기대", first.ChildSession)
	}
	// title 을 주지 않으면 cwd 의 마지막 이름이다(6.2).
	if second.ChildSession.Title != "scratch-x" {
		t.Errorf("title = %q, cwd 의 마지막 이름 scratch-x 를 기대", second.ChildSession.Title)
	}
	if info, err := os.Stat(filepath.Join(workDirPath, "scratch-x")); err != nil || !info.IsDir() {
		t.Errorf("--create-directory 의 scratch-x 가 폴더로 생기기를 기대 (err=%v)", err)
	}

	_, sessionID, _ := splitConversationFlagsForTest(t, splitChatModeFlagsForTest(t,
		withoutFlagPairForTest(withoutFlagPairForTest(withoutFlagPairForTest(first.Command,
			"--mcp-config"), "--permission-prompt-tool"), "--append-system-prompt")))
	if first.ChildSession.ConversationID != sessionID {
		t.Errorf("childSession.conversationId = %q, 명령의 --session-id %q 와 같기를 기대", first.ChildSession.ConversationID, sessionID)
	}

	recorded := readChildSessionsForTest(t, workDirPath)
	if recorded["main"]["child-1"].ConversationID != sessionID || recorded["main"]["child-1"].Cwd != "proj-a" {
		t.Errorf("childSessions[main][child-1] = %+v, 배정한 대화와 proj-a 를 기대", recorded["main"]["child-1"])
	}
	if recorded["main"]["child-2"].Cwd != "scratch-x" {
		t.Errorf("childSessions[main][child-2] = %+v, scratch-x 를 기대", recorded["main"]["child-2"])
	}
}

func TestAChildAskedWithoutAnIdIsNamedByTheEngine(t *testing.T) {
	// --child 뒤에 값이 없으면 엔진이 기록을 보고 다음 id 를 짓는다. 앱이 기록 파일을 읽어 번호를 세면
	// 파일 형식과 판 규약을 두 곳이 알게 된다(원칙 11). 지은 id 는 답 문서·규약 문구·기록에 같이 실린다.
	workDirPath := makeWorkDir(t)
	makeCleanRepo(t, workDirPath, "proj-a")
	t.Chdir(workDirPath)

	named := runChildSessionCommandForTest(t, childArgsForTest("child-3", childCwdOptionName, "proj-a")...)
	unnamed := runChildSessionCommandForTest(t, chatModeOptionName, appSocketOptionName, childSocketPathForTest,
		childSessionOptionName, childCwdOptionName, "proj-a")
	unnamedAtTheEnd := runChildSessionCommandForTest(t, chatModeOptionName, appSocketOptionName, childSocketPathForTest,
		childCwdOptionName, "proj-a", childSessionOptionName)

	if named.ChildSession == nil || named.ChildSession.ID != "child-3" {
		t.Fatalf("childSession = %+v, 명시한 child-3 을 그대로 기대", named.ChildSession)
	}
	if unnamed.ChildSession == nil || unnamed.ChildSession.ID != "child-4" {
		t.Fatalf("childSession = %+v, 엔진이 지은 child-4 를 기대", unnamed.ChildSession)
	}
	if unnamedAtTheEnd.ChildSession == nil || unnamedAtTheEnd.ChildSession.ID != "child-5" {
		t.Fatalf("childSession = %+v, 엔진이 지은 child-5 를 기대", unnamedAtTheEnd.ChildSession)
	}

	conventionText, _ := splitAppendSystemPromptForTest(t, unnamed.Command)
	if !strings.Contains(conventionText, "하위 세션 child-4 ") {
		t.Errorf("규약 문구 = %q, 지은 id child-4 를 담기를 기대", conventionText)
	}
	recorded := readChildSessionsForTest(t, workDirPath)
	if recorded["main"]["child-4"].ConversationID != unnamed.ChildSession.ConversationID {
		t.Errorf("childSessions[main][child-4] = %+v, 답한 대화가 지은 id 아래 적히기를 기대", recorded["main"]["child-4"])
	}
}

func TestAnExplicitChildIdAlreadyRecordedIsStillRefused(t *testing.T) {
	// 엔진이 짓는 길이 생겨도 명시한 id 는 지금처럼 받고, 겹치면 거절한다 — 조용히 다른 이름을 주면
	// 앱이 믿는 id 와 기록의 id 가 갈라진다.
	workDirPath := makeWorkDir(t)
	makeCleanRepo(t, workDirPath, "proj-a")
	t.Chdir(workDirPath)

	runChildSessionCommandForTest(t, childArgsForTest("child-1", childCwdOptionName, "proj-a")...)
	err := AnswerSessionCommand(&bytes.Buffer{}, &bytes.Buffer{},
		append(childArgsForTest("child-1", childCwdOptionName, "proj-a"), machineJSONOptionName))
	if err == nil || !strings.Contains(err.Error(), "child-1") {
		t.Errorf("오류 = %v, 이미 적힌 child-1 거절을 기대", err)
	}
}

func TestAChildSessionTellsAboutADiscardedRecordOnStderr(t *testing.T) {
	// 메인·레포 세션과 같은 규율이다(TestSessionCommandTellsAboutADiscardedRecordOnStderr). 기록이 깨져도
	// 하위는 뜨고, 앞선 기록을 버렸다는 말은 stdout 이 아니라 stderr 로 간다 — stdout 은 앱이 파싱하는
	// 문서 하나의 자리다. 이 말을 버리면 상위 아래 적혀 있던 하위들의 기록이 소리 없이 사라진다.
	workDirPath := makeWorkDir(t)
	makeCleanRepo(t, workDirPath, "proj-a")
	t.Chdir(workDirPath)

	coordDirectoryPath := filepath.Join(workDirPath, ".coord")
	if err := os.MkdirAll(coordDirectoryPath, 0o755); err != nil {
		t.Fatalf("준비 실패: %v", err)
	}
	if err := os.WriteFile(filepath.Join(coordDirectoryPath, "conversations.json"), []byte("깨진 기록"), 0o644); err != nil {
		t.Fatalf("준비 실패: %v", err)
	}

	var out, errOut bytes.Buffer
	if err := AnswerSessionCommand(&out, &errOut,
		append(childArgsForTest("", childCwdOptionName, "proj-a"), machineJSONOptionName)); err != nil {
		t.Fatalf("AnswerSessionCommand() 실패: %v", err)
	}

	var document childSessionCommandDocumentForTest
	decodeMachineJSONForTest(t, out.String(), &document)
	if document.ChildSession == nil || document.ChildSession.ID != "child-1" {
		t.Errorf("childSession = %+v, 버린 기록 뒤에도 하위가 child-1 로 뜨기를 기대", document.ChildSession)
	}
	if !strings.Contains(errOut.String(), "conversations.json") {
		t.Errorf("stderr = %q, 버린 기록의 경로를 알리기를 기대", errOut.String())
	}
}

func TestAChildCwdRefusalIsAnErrorAndRecordsNothing(t *testing.T) {
	// cwd 규칙에 걸린 요청은 답할 것이 없는 물음이다 — 종료 코드 1 과 stderr 가 그 구분을 한다.
	// 앱은 그 문구를 spawn 의 {"error"} 로 상위에게 옮긴다.
	workDirPath := makeWorkDir(t)
	t.Chdir(workDirPath)

	err := AnswerSessionCommand(&bytes.Buffer{}, &bytes.Buffer{},
		append(childArgsForTest("child-1", childCwdOptionName, ".coord"), machineJSONOptionName))
	if err == nil || !strings.Contains(err.Error(), ".coord") {
		t.Errorf("오류 = %v, .coord 거절을 기대", err)
	}
	if _, statErr := os.Stat(filepath.Join(workDirPath, ".coord", "conversations.json")); !errors.Is(statErr, os.ErrNotExist) {
		t.Errorf("거절한 요청이 대화 기록을 남겼습니다 (err=%v)", statErr)
	}
}

func TestTheAppTellsWhichCwdsLiveChildrenHold(t *testing.T) {
	// 누가 살아 있는지는 앱만 안다(원칙 16). 앱이 넘긴 목록과 겹치면 거절한다(6.8).
	workDirPath := makeWorkDir(t)
	makeCleanRepo(t, workDirPath, "proj-a")
	t.Chdir(workDirPath)

	err := AnswerSessionCommand(&bytes.Buffer{}, &bytes.Buffer{}, append(childArgsForTest("child-2",
		childCwdOptionName, "proj-a", liveChildCwdOptionName, "scratch-x", liveChildCwdOptionName, "proj-a"), machineJSONOptionName))
	if err == nil || !strings.Contains(err.Error(), "살아 있는 하위") {
		t.Errorf("오류 = %v, 살아 있는 하위와 같은 cwd 거절을 기대", err)
	}
}

func TestAnUnapprovedCwdAnswersWhatToShowThePersonInsteadOfACommand(t *testing.T) {
	// 관문(6.9). 하위 세션은 상위가 띄우므로 사람이 그 폴더를 고른 적이 없다. 앱이 사람에게 그 파일들을
	// 보여주고 묻도록, 명령 대신 보여줄 것을 답한다. 명령은 빈 배열이다 — 이 필드를 모르는 앱이 그것을
	// 실행하려 하면 그 자리에서 실패하고, 사람이 본 적 없는 설정으로 세션이 뜨는 일은 없다.
	workDirPath := makeWorkDir(t)
	makeCleanRepo(t, workDirPath, "proj-a")
	writeFileInRepo(t, filepath.Join(workDirPath, "proj-a"), ".mcp.json", `{"mcpServers":{"probe":{"command":"python3"}}}`)
	t.Chdir(workDirPath)

	document := runChildSessionCommandForTest(t, childArgsForTest("child-1", childCwdOptionName, "proj-a")...)

	if document.ApprovalRequired == nil {
		t.Fatalf("approvalRequired = null, 걸리는 .mcp.json 이 있으니 물을 것을 기대 (문서 = %+v)", document)
	}
	if len(document.Command) != 0 || document.Command == nil {
		t.Errorf("command = %q, 승인 전에는 빈 배열을 기대", document.Command)
	}
	if document.ChildSession != nil {
		t.Errorf("childSession = %+v, 승인 전에는 대화를 배정하지 않기를 기대", document.ChildSession)
	}
	approval := document.ApprovalRequired
	if approval.Cwd != "proj-a" || !strings.HasPrefix(approval.Fingerprint, "sha256:") {
		t.Errorf("approvalRequired = %+v, proj-a 와 지문을 기대", approval)
	}
	if len(approval.ExecutionConfigs) != 1 || approval.ExecutionConfigs[0].RelativePath != ".mcp.json" ||
		!strings.Contains(approval.ExecutionConfigs[0].Content, "python3") {
		t.Errorf("executionConfigs = %+v, .mcp.json 의 내용을 그대로 기대", approval.ExecutionConfigs)
	}
	if _, err := os.Stat(filepath.Join(workDirPath, ".coord", "conversations.json")); !errors.Is(err, os.ErrNotExist) {
		t.Errorf("승인을 기다리는 물음이 대화 기록을 남겼습니다 (err=%v)", err)
	}
}

func TestTheApprovedFingerprintIsRecordedAndTheChildIsAssembled(t *testing.T) {
	// 사람이 대화 상자에서 승인하면 앱이 그 지문을 들고 다시 묻는다. 엔진이 지문을 적고 명령을 답한다.
	// 승인 기록은 kyu mcp approve 와 같은 파일 같은 지문이다(6.9) — 다음 하위는 묻지 않는다.
	workDirPath := makeWorkDir(t)
	makeCleanRepo(t, workDirPath, "proj-a")
	writeFileInRepo(t, filepath.Join(workDirPath, "proj-a"), ".mcp.json", `{"mcpServers":{}}`)
	t.Chdir(workDirPath)

	asked := runChildSessionCommandForTest(t, childArgsForTest("child-1", childCwdOptionName, "proj-a")...)
	approved := runChildSessionCommandForTest(t, childArgsForTest("child-1", childCwdOptionName, "proj-a",
		approveFingerprintOptionName, asked.ApprovalRequired.Fingerprint)...)

	if approved.ApprovalRequired != nil || len(approved.Command) == 0 || approved.ChildSession == nil {
		t.Fatalf("승인한 지문으로 다시 물은 답 = %+v, 명령과 childSession 을 기대", approved)
	}

	inspection, err := workdir.InspectDelegationApproval(workDirPath, "proj-a")
	if err != nil || !inspection.DelegationIsAllowed() {
		t.Errorf("InspectDelegationApproval(proj-a) = %+v, %v — 승인이 적히기를 기대", inspection, err)
	}

	next := runChildSessionCommandForTest(t, childArgsForTest("child-2", childCwdOptionName, "proj-a")...)
	if next.ApprovalRequired != nil {
		t.Errorf("이미 승인한 자리를 다시 묻습니다: %+v", next.ApprovalRequired)
	}
}

func TestAFingerprintThatNoLongerMatchesIsAskedAgain(t *testing.T) {
	// 사람이 본 뒤 답하기까지 사이에 파일이 바뀌면, 사람이 본 적 없는 것이 승인된다. 받은 지문이 지금
	// 내용과 다르면 적지 않고 지금 내용을 다시 보여 달라고 답한다.
	workDirPath := makeWorkDir(t)
	makeCleanRepo(t, workDirPath, "proj-a")
	writeFileInRepo(t, filepath.Join(workDirPath, "proj-a"), ".mcp.json", `{"mcpServers":{}}`)
	t.Chdir(workDirPath)

	asked := runChildSessionCommandForTest(t, childArgsForTest("child-1", childCwdOptionName, "proj-a")...)
	writeFileInRepo(t, filepath.Join(workDirPath, "proj-a"), ".mcp.json", `{"mcpServers":{"late":{"command":"sh"}}}`)

	again := runChildSessionCommandForTest(t, childArgsForTest("child-1", childCwdOptionName, "proj-a",
		approveFingerprintOptionName, asked.ApprovalRequired.Fingerprint)...)

	if again.ApprovalRequired == nil || again.ApprovalRequired.Fingerprint == asked.ApprovalRequired.Fingerprint {
		t.Fatalf("approvalRequired = %+v, 바뀐 내용의 새 지문으로 다시 묻기를 기대", again.ApprovalRequired)
	}
	if !strings.Contains(again.ApprovalRequired.ExecutionConfigs[0].Content, "late") {
		t.Errorf("다시 보일 내용 = %q, 바뀐 내용을 기대", again.ApprovalRequired.ExecutionConfigs[0].Content)
	}
	if inspection, _ := workdir.InspectDelegationApproval(workDirPath, "proj-a"); inspection.DelegationIsAllowed() {
		t.Error("낡은 지문이 바뀐 내용을 승인했습니다")
	}
}

func TestChildSessionOptionsAreCheckedTogether(t *testing.T) {
	// 조용히 무시된 옵션은 앱이 무언가 켜졌다고 믿게 만든다 — 어긋난 조합은 그 자리에서 거절한다.
	workDirPath := makeWorkDir(t)
	makeCleanRepo(t, workDirPath, "proj-a")
	t.Chdir(workDirPath)

	cases := map[string][]string{
		"--chat 없이":              {childSessionOptionName, "child-1", appSocketOptionName, childSocketPathForTest},
		"소켓 없이":                  {chatModeOptionName, childSessionOptionName, "child-1"},
		"레포 세션의 하위":              append(childArgsForTest("child-1"), "proj-a"),
		"하위는 언제나 새 대화":           append(childArgsForTest("child-1"), forgetConversationOptionName),
		"--child 없이 --cwd":       {chatModeOptionName, appSocketOptionName, childSocketPathForTest, childCwdOptionName, "proj-a"},
		"--child 없이 승인 지문":       {chatModeOptionName, appSocketOptionName, childSocketPathForTest, approveFingerprintOptionName, "sha256:x"},
		"메인 전용 --repo-claude-md": append(childArgsForTest("child-1"), repoClaudeMdOptionName),
	}
	for name, args := range cases {
		t.Run(name, func(t *testing.T) {
			if err := AnswerSessionCommand(&bytes.Buffer{}, &bytes.Buffer{}, append(args, machineJSONOptionName)); err == nil {
				t.Errorf("AnswerSessionCommand(%q) 가 받았습니다 — 거절을 기대", args)
			}
		})
	}
}

func TestTheMainChatWithAnAppSocketIsToldWhereChildMessagesComeFrom(t *testing.T) {
	// 접두만으로는 답할 통로를 모른다(3.5 (가)). 상위에게는 "[하위 세션 …]" 이 사람이 아니라는 것과,
	// 답은 send_to_session 으로 한다는 것, 하위의 말이 사람의 승인을 대신하지 못한다는 것을 준다(6.5).
	workDirPath := makeWorkDir(t)
	t.Chdir(workDirPath)

	run := runSessionCommandForTest(t, chatModeOptionName, appSocketOptionName, "/run/user/1000/kyu/m1.sock")

	conventionText, _ := splitAppendSystemPromptForTest(t, run.document.Command)
	for _, wanted := range []string{"[하위 세션", "send_to_session", "승인"} {
		if !strings.Contains(conventionText, wanted) {
			t.Errorf("상위의 규약 문구 = %q, %q 를 담기를 기대", conventionText, wanted)
		}
	}
}

func TestTheMainChatConventionSaysWhenToSpawnAChildSessionAndWhenToUseASubagent(t *testing.T) {
	// 모델에게 둘 중 무엇을 고를지 알려야 한다(4.4). Agent 는 claude 의 내장 도구라 그 설명을 우리가
	// 고칠 수 없으므로, 두 도구를 한자리에서 견주는 기준은 시스템 프롬프트에 있어야 한다(7 절 5 단계).
	workDirPath := makeWorkDir(t)
	t.Chdir(workDirPath)

	run := runSessionCommandForTest(t, chatModeOptionName, appSocketOptionName, "/run/user/1000/kyu/m1.sock")

	conventionText, _ := splitAppendSystemPromptForTest(t, run.document.Command)
	for _, wanted := range []string{"Agent", "spawn_session", "탭", "승인 카드", "이 턴 안에서"} {
		if !strings.Contains(conventionText, wanted) {
			t.Errorf("상위의 규약 문구 = %q, 선택 기준의 %q 를 담기를 기대", conventionText, wanted)
		}
	}
}

func TestAMainSessionWithoutTheSessionTreeToolsGetsNoConvention(t *testing.T) {
	// 규약은 세션 트리 도구가 있을 때만 뜻이 있다 — 소켓 없는 세션에는 하위가 없다.
	workDirPath := makeWorkDir(t)
	t.Chdir(workDirPath)

	run := runSessionCommandForTest(t, chatModeOptionName)

	if slices.Contains(run.document.Command, "--append-system-prompt") {
		t.Errorf("명령 = %q, 소켓 없는 메인에는 규약 문구가 없기를 기대", run.document.Command)
	}
}

// withoutFlagPairForTest 는 플래그 한 쌍을 떼어낸다. 없으면 그대로 돌려준다.
func withoutFlagPairForTest(command []string, flagName string) []string {
	flagIndex := slices.Index(command, flagName)
	if flagIndex < 0 || flagIndex+1 >= len(command) {
		return command
	}
	return slices.Concat(command[:flagIndex], command[flagIndex+2:])
}

type childSessionRecordForTest struct {
	ConversationID string `json:"conversationId"`
	Cwd            string `json:"cwd"`
}

func readChildSessionsForTest(t *testing.T, workDirPath string) map[string]map[string]childSessionRecordForTest {
	t.Helper()

	fileContent, err := os.ReadFile(filepath.Join(workDirPath, ".coord", "conversations.json"))
	if err != nil {
		t.Fatalf("대화 기록을 읽지 못했습니다: %v", err)
	}
	var record struct {
		ChildSessions map[string]map[string]childSessionRecordForTest `json:"childSessions"`
	}
	if err := json.Unmarshal(fileContent, &record); err != nil {
		t.Fatalf("대화 기록을 JSON 으로 읽지 못했습니다: %v", err)
	}
	return record.ChildSessions
}
