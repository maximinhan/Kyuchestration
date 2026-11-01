package cli

import (
	"bytes"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// conversationsListJSONDocumentForTest 는 kyu conversations list --json 의 문서를 시험이 다시 적어둔 모양이다.
//
// 앱(다음 PR)이 이 모양을 자기 코드에 적어 파싱한다. 생산 타입을 재사용하면 이름이 바뀌어도 여기서 깨지지 않는다.
type conversationsListJSONDocumentForTest struct {
	SchemaVersion int    `json:"schemaVersion"`
	Cwd           string `json:"cwd"`
	Conversations []struct {
		ID           string  `json:"id"`
		Title        string  `json:"title"`
		StartedAt    *string `json:"startedAt"`
		LastActiveAt string  `json:"lastActiveAt"`
		RecordedFor  *string `json:"recordedFor"`
	} `json:"conversations"`
}

const (
	terminalConversationIDForTest = "654ce605-b030-40e8-8448-2e9003ad32b1"
	otherConversationIDForTest    = "8f488465-713d-40f0-92a7-bfa5a028b227"
)

// isolateClaudeCodeHomeForTest 는 Claude Code 의 설정 자리를 임시 디렉토리로 돌린다 — 사용자의 전사를 읽지 않는다.
func isolateClaudeCodeHomeForTest(t *testing.T) string {
	t.Helper()

	claudeConfigDirectory := t.TempDir()
	t.Setenv("CLAUDE_CONFIG_DIR", claudeConfigDirectory)
	return claudeConfigDirectory
}

// writeTerminalConversationForTest 는 사용자가 sessionCwd 에서 터미널 claude 로 나눈 대화 하나를 흉내 낸다.
//
// 디렉토리 이름은 실측한 규칙(영숫자가 아닌 것은 -)을 시험이 따로 적은 것이고, 안의 cwd 는 claude 처럼
// 링크를 편 경로다.
func writeTerminalConversationForTest(t *testing.T, claudeConfigDirectory, sessionCwd, conversationID, text string, lastActiveAt time.Time) {
	t.Helper()

	resolvedCwd, err := filepath.EvalSymlinks(sessionCwd)
	if err != nil {
		t.Fatalf("세션 자리의 링크를 펴지 못했습니다: %v", err)
	}
	directoryName := strings.Map(func(character rune) rune {
		if (character >= 'a' && character <= 'z') || (character >= 'A' && character <= 'Z') || (character >= '0' && character <= '9') {
			return character
		}
		return '-'
	}, resolvedCwd)

	projectDirectory := filepath.Join(claudeConfigDirectory, "projects", directoryName)
	if err := os.MkdirAll(projectDirectory, 0o755); err != nil {
		t.Fatalf("전사 디렉토리 생성 실패: %v", err)
	}
	transcriptPath := filepath.Join(projectDirectory, conversationID+".jsonl")
	transcript := `{"type":"user","message":{"role":"user","content":"` + text + `"},"timestamp":"2026-11-01T09:00:51.046Z","cwd":"` +
		resolvedCwd + `","sessionId":"` + conversationID + `"}` + "\n"
	if err := os.WriteFile(transcriptPath, []byte(transcript), 0o600); err != nil {
		t.Fatalf("전사 생성 실패: %v", err)
	}
	if err := os.Chtimes(transcriptPath, lastActiveAt, lastActiveAt); err != nil {
		t.Fatalf("전사 시각 설정 실패: %v", err)
	}
}

func runConversationsListForTest(t *testing.T, args ...string) (conversationsListJSONDocumentForTest, string) {
	t.Helper()

	var out, errOut bytes.Buffer
	if err := ManageClaudeCodeConversations(&out, &errOut, append(append([]string{"list"}, args...), machineJSONOptionName)); err != nil {
		t.Fatalf("ManageClaudeCodeConversations() 실패: %v", err)
	}
	var document conversationsListJSONDocumentForTest
	decodeMachineJSONForTest(t, out.String(), &document)
	if document.SchemaVersion != 1 {
		t.Errorf("schemaVersion = %d, want 1", document.SchemaVersion)
	}
	return document, out.String()
}

func TestConversationsListAnswersTheTerminalConversationsOfTheMainSessionCwd(t *testing.T) {
	claudeConfigDirectory := isolateClaudeCodeHomeForTest(t)
	workDirPath := makeWorkDir(t)
	lastActiveAt := time.Date(2026, 11, 1, 9, 0, 56, 0, time.UTC)
	writeTerminalConversationForTest(t, claudeConfigDirectory, workDirPath, terminalConversationIDForTest, "터미널에서 한 대화", lastActiveAt)

	document, stdout := runConversationsListForTest(t, workDirPath)

	if document.Cwd != workDirPath {
		t.Errorf("cwd = %q, 메인 세션이 뜰 %q 를 기대", document.Cwd, workDirPath)
	}
	if len(document.Conversations) != 1 {
		t.Fatalf("대화 = %+v, 하나를 기대", document.Conversations)
	}
	conversation := document.Conversations[0]
	if conversation.ID != terminalConversationIDForTest || conversation.Title != "터미널에서 한 대화" {
		t.Errorf("대화 = %+v", conversation)
	}
	if conversation.StartedAt == nil || *conversation.StartedAt != "2026-11-01T09:00:51Z" {
		t.Errorf("startedAt = %v, RFC 3339 UTC 2026-11-01T09:00:51Z 를 기대", conversation.StartedAt)
	}
	if conversation.LastActiveAt != "2026-11-01T09:00:56Z" {
		t.Errorf("lastActiveAt = %q, 파일 mtime 의 RFC 3339 UTC 를 기대", conversation.LastActiveAt)
	}
	if conversation.RecordedFor != nil || !strings.Contains(stdout, `"recordedFor": null`) {
		t.Errorf("recordedFor = %v, 기록에 없으므로 null 을 기대", conversation.RecordedFor)
	}
}

func TestConversationsListTellsWhichLabelTheAppRecordedTheConversationFor(t *testing.T) {
	claudeConfigDirectory := isolateClaudeCodeHomeForTest(t)
	workDirPath := makeWorkDir(t)
	writeTerminalConversationForTest(t, claudeConfigDirectory, workDirPath, terminalConversationIDForTest, "앱이 연 대화", time.Now())
	writeFileForConversationsTest(t, filepath.Join(workDirPath, ".coord", "conversations.json"),
		`{"schemaVersion":1,"conversations":{"main":"`+terminalConversationIDForTest+`"}}`)

	document, _ := runConversationsListForTest(t, workDirPath)

	if len(document.Conversations) != 1 || document.Conversations[0].RecordedFor == nil || *document.Conversations[0].RecordedFor != "main" {
		t.Errorf("대화 = %+v, recordedFor main 을 기대", document.Conversations)
	}
}

func TestConversationsListForARepoLooksAtTheCwdTheRepoSessionOpensIn(t *testing.T) {
	// 목록의 자리와 session-command 가 그 레포에 주는 cwd 가 갈라지면, 고른 대화를 다른 자리에서 --resume 한다.
	claudeConfigDirectory := isolateClaudeCodeHomeForTest(t)
	workDirPath := makeWorkDir(t)
	makeCleanRepo(t, workDirPath, "proj-a")
	repoPath := filepath.Join(workDirPath, "proj-a")
	writeTerminalConversationForTest(t, claudeConfigDirectory, repoPath, terminalConversationIDForTest, "레포 대화", time.Now())
	writeTerminalConversationForTest(t, claudeConfigDirectory, workDirPath, otherConversationIDForTest, "메인 대화", time.Now())

	document, _ := runConversationsListForTest(t, workDirPath, "proj-a")

	t.Chdir(workDirPath)
	sessionCommandCwd := runSessionCommandForTest(t, "proj-a").document.Cwd
	if document.Cwd != sessionCommandCwd {
		t.Errorf("cwd = %q, session-command 가 그 레포에 주는 %q 와 같기를 기대", document.Cwd, sessionCommandCwd)
	}
	if len(document.Conversations) != 1 || document.Conversations[0].ID != terminalConversationIDForTest {
		t.Errorf("대화 = %+v, 레포 자리의 대화 하나를 기대", document.Conversations)
	}
}

func TestConversationsListAnswersAnEmptyArrayWhenClaudeCodeNeverRanThere(t *testing.T) {
	isolateClaudeCodeHomeForTest(t)
	workDirPath := makeWorkDir(t)

	_, stdout := runConversationsListForTest(t, workDirPath)

	if !strings.Contains(stdout, `"conversations": []`) {
		t.Errorf("stdout = %q, 빈 목록은 null 이 아니라 [] 를 기대", stdout)
	}
}

func TestConversationsListRefusesARepoThatIsNotThere(t *testing.T) {
	isolateClaudeCodeHomeForTest(t)
	workDirPath := makeWorkDir(t)
	makeCleanRepo(t, workDirPath, "proj-a")

	var out, errOut bytes.Buffer
	err := ManageClaudeCodeConversations(&out, &errOut, []string{"list", workDirPath, "proj-typo", machineJSONOptionName})
	if err == nil || !strings.Contains(err.Error(), "proj-a") {
		t.Errorf("err = %v, 있는 레포 이름을 함께 알리는 거절을 기대", err)
	}
	if out.Len() != 0 {
		t.Errorf("stdout = %q, 실패는 문서를 내지 않기를 기대", out.String())
	}
}

func TestConversationsListNeedsTheWorkDirPath(t *testing.T) {
	// 경로를 생략하면 현재 디렉토리로 짐작하지 않는다. 앱은 늘 고른 폴더를 주고, 짐작한 자리의 대화를
	// 그 폴더의 것으로 보이면 고른 대화가 다른 자리에서 이어진다.
	var out, errOut bytes.Buffer
	if err := ManageClaudeCodeConversations(&out, &errOut, []string{"list", machineJSONOptionName}); err == nil {
		t.Error("ManageClaudeCodeConversations(list) = nil, 경로가 없으면 사용법 오류를 기대")
	}
}

func TestConversationsListShowsAPersonATable(t *testing.T) {
	claudeConfigDirectory := isolateClaudeCodeHomeForTest(t)
	workDirPath := makeWorkDir(t)
	writeTerminalConversationForTest(t, claudeConfigDirectory, workDirPath, terminalConversationIDForTest, "사람이 보는 목록", time.Now())

	var out, errOut bytes.Buffer
	if err := ManageClaudeCodeConversations(&out, &errOut, []string{"list", workDirPath}); err != nil {
		t.Fatalf("ManageClaudeCodeConversations() 실패: %v", err)
	}
	if !strings.Contains(out.String(), terminalConversationIDForTest) || !strings.Contains(out.String(), "사람이 보는 목록") {
		t.Errorf("stdout = %q, id 와 제목이 든 표를 기대", out.String())
	}
}

func writeFileForConversationsTest(t *testing.T, filePath, content string) {
	t.Helper()

	if err := os.MkdirAll(filepath.Dir(filePath), 0o755); err != nil {
		t.Fatalf("디렉토리 생성 실패: %v", err)
	}
	if err := os.WriteFile(filePath, []byte(content), 0o644); err != nil {
		t.Fatalf("파일 생성 실패: %v", err)
	}
}

func TestSessionCommandAdoptsATerminalConversationAndResumesIt(t *testing.T) {
	claudeConfigDirectory := isolateClaudeCodeHomeForTest(t)
	workDirPath := makeWorkDir(t)
	t.Chdir(workDirPath)
	runSessionCommandForTest(t)
	writeTerminalConversationForTest(t, claudeConfigDirectory, workDirPath, terminalConversationIDForTest, "터미널에서 한 대화", time.Now())

	run := runSessionCommandForTest(t, chatModeOptionName, adoptConversationOptionName, terminalConversationIDForTest)

	flagName, conversationID, _ := splitConversationFlagsForTest(t, splitChatModeFlagsForTest(t, withoutMcpConfigForTest(t, run.document.Command)))
	if flagName != "--resume" || conversationID != terminalConversationIDForTest {
		t.Errorf("대화 플래그 = %q %q, 채택한 대화의 --resume 을 기대", flagName, conversationID)
	}
	if run.document.ResumedConversationID == nil || *run.document.ResumedConversationID != terminalConversationIDForTest {
		t.Errorf("resumedConversationId = %v, 채택한 %s 를 기대", run.document.ResumedConversationID, terminalConversationIDForTest)
	}

	// 다음 물음은 옵션 없이도 채택한 대화를 이어간다 — 기록에 적혔기 때문이다.
	nextFlag, nextID, _ := splitConversationFlagsForTest(t, withoutMcpConfigForTest(t, runSessionCommandForTest(t).document.Command))
	if nextFlag != "--resume" || nextID != terminalConversationIDForTest {
		t.Errorf("다음 물음 = %q %q, 채택한 대화를 이어가기를 기대", nextFlag, nextID)
	}
}

func TestSessionCommandAdoptsForARepoSessionFromThatRepoCwd(t *testing.T) {
	claudeConfigDirectory := isolateClaudeCodeHomeForTest(t)
	workDirPath := makeWorkDir(t)
	makeCleanRepo(t, workDirPath, "proj-a")
	t.Chdir(workDirPath)
	writeTerminalConversationForTest(t, claudeConfigDirectory, filepath.Join(workDirPath, "proj-a"), terminalConversationIDForTest, "레포 대화", time.Now())

	run := runSessionCommandForTest(t, "proj-a", adoptConversationOptionName, terminalConversationIDForTest)

	if run.document.ResumedConversationID == nil || *run.document.ResumedConversationID != terminalConversationIDForTest {
		t.Errorf("resumedConversationId = %v, 채택한 %s 를 기대", run.document.ResumedConversationID, terminalConversationIDForTest)
	}
}

func TestSessionCommandRefusesToAdoptWhatCannotBeAdopted(t *testing.T) {
	claudeConfigDirectory := isolateClaudeCodeHomeForTest(t)
	workDirPath := makeWorkDir(t)
	t.Chdir(workDirPath)
	writeTerminalConversationForTest(t, claudeConfigDirectory, workDirPath, terminalConversationIDForTest, "터미널에서 한 대화", time.Now())

	refusals := map[string][]string{
		// 버리라는 것과 이것으로 바꾸라는 것은 함께 성립하지 않는다.
		"forget 과 함께": {adoptConversationOptionName, terminalConversationIDForTest, forgetConversationOptionName},
		// 하위 세션은 늘 새 대화다(session-tree-design.md 6.15).
		"하위 세션": {chatModeOptionName, appSocketOptionName, "/run/user/1000/kyu/a.sock", childSessionOptionName,
			adoptConversationOptionName, terminalConversationIDForTest},
		"uuid 가 아닌 값": {adoptConversationOptionName, "not-a-conversation"},
		"값 없음":        {adoptConversationOptionName},
		// 이 자리에 전사가 없는 id 를 적으면 다음 --resume 이 종료 코드 1 로 끝난다.
		"전사가 없는 id": {adoptConversationOptionName, otherConversationIDForTest},
	}
	for name, args := range refusals {
		var out, errOut bytes.Buffer
		if err := AnswerSessionCommand(&out, &errOut, append(args, machineJSONOptionName)); err == nil {
			t.Errorf("%s: AnswerSessionCommand(%q) = nil, 거절을 기대", name, args)
		}
		if out.Len() != 0 {
			t.Errorf("%s: stdout = %q, 거절은 문서를 내지 않기를 기대", name, out.String())
		}
	}

	if _, err := os.Stat(filepath.Join(workDirPath, ".coord", "conversations.json")); !os.IsNotExist(err) {
		t.Errorf("기록 파일 = %v, 거절한 물음은 아무것도 적지 않기를 기대", err)
	}
}

// withoutMcpConfigForTest 는 메인 세션 명령에서 --mcp-config 한 쌍을 뗀다. 값은 실행마다 달라 비교하지 않는다.
func withoutMcpConfigForTest(t *testing.T, command []string) []string {
	t.Helper()

	_, rest := mcpServerRegistrationForTest(t, command)
	return rest
}

func TestConversationsTableKeepsItsColumnsWhenTheTitleHasATab(t *testing.T) {
	claudeConfigDirectory := isolateClaudeCodeHomeForTest(t)
	workDirPath := makeWorkDir(t)
	writeTerminalConversationForTest(t, claudeConfigDirectory, workDirPath, terminalConversationIDForTest, `앞\t뒤`, time.Now())

	var out, errOut bytes.Buffer
	if err := ManageClaudeCodeConversations(&out, &errOut, []string{"list", workDirPath}); err != nil {
		t.Fatalf("ManageClaudeCodeConversations() 실패: %v", err)
	}
	if strings.Contains(out.String(), "\t") || !strings.Contains(out.String(), "-  앞 뒤") {
		t.Errorf("stdout = %q, 탭을 공백으로 접고 열 간격이 그대로인 표를 기대", out.String())
	}
}
