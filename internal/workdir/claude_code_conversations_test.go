package workdir

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// claudeCodeHomeForTest 는 Claude Code 의 설정 자리를 임시 디렉토리로 돌린다.
//
// 이 머신의 ~/.claude 를 읽지 않게 하는 것이 요점이다 — 시험이 사용자의 실제 대화를 보면 결과가
// 머신마다 달라지고, 시험이 무언가를 잘못 쓰면 그 자리가 사용자의 전사다.
func claudeCodeHomeForTest(t *testing.T) string {
	t.Helper()

	claudeConfigDirectory := t.TempDir()
	t.Setenv("CLAUDE_CONFIG_DIR", claudeConfigDirectory)
	return claudeConfigDirectory
}

// resolvedDirectoryForTest 는 새 디렉토리를 만들고 링크를 편 절대경로를 돌려준다.
//
// Claude Code 는 링크를 편 cwd 를 적는다(실측). 맥의 임시 디렉토리는 /var → /private/var 링크 아래라,
// 편 값으로 전사를 적어야 실제와 같은 모양이 된다.
func resolvedDirectoryForTest(t *testing.T) string {
	t.Helper()

	resolvedPath, err := filepath.EvalSymlinks(t.TempDir())
	if err != nil {
		t.Fatalf("임시 디렉토리의 링크를 펴지 못했습니다: %v", err)
	}
	return resolvedPath
}

// projectDirectoryNameForTest 는 실측한 인코딩을 시험이 따로 적어둔 것이다 — 영숫자가 아닌
// UTF-16 단위 하나가 - 하나다. 생산 코드의 함수로 조립하면 규칙이 바뀌어도 시험이 따라가 버린다.
func projectDirectoryNameForTest(absoluteCwd string) string {
	var directoryName strings.Builder
	for _, character := range absoluteCwd {
		switch {
		case character >= 'a' && character <= 'z', character >= 'A' && character <= 'Z', character >= '0' && character <= '9':
			directoryName.WriteRune(character)
		case character > 0xFFFF:
			directoryName.WriteString("--")
		default:
			directoryName.WriteString("-")
		}
	}
	return directoryName.String()
}

// writeTranscriptForTest 는 Claude Code 가 남기는 전사 하나를 흉내 낸다. 마지막 활동 시각은 파일의 mtime 이다.
func writeTranscriptForTest(t *testing.T, claudeConfigDirectory, projectDirectoryName, conversationID string, lastActiveAt time.Time, lines ...string) {
	t.Helper()

	projectDirectory := filepath.Join(claudeConfigDirectory, "projects", projectDirectoryName)
	if err := os.MkdirAll(projectDirectory, 0o755); err != nil {
		t.Fatalf("전사 디렉토리 생성 실패: %v", err)
	}
	transcriptPath := filepath.Join(projectDirectory, conversationID+".jsonl")
	if err := os.WriteFile(transcriptPath, []byte(strings.Join(lines, "\n")+"\n"), 0o600); err != nil {
		t.Fatalf("전사 생성 실패: %v", err)
	}
	if err := os.Chtimes(transcriptPath, lastActiveAt, lastActiveAt); err != nil {
		t.Fatalf("전사 시각 설정 실패: %v", err)
	}
}

// userLineForTest 는 사람이 쓴 글 한 줄이다. 실제 전사에서 이 판단에 쓰이는 자리만 남겼다.
func userLineForTest(conversationID, cwd, timestamp, contentJSON string) string {
	return `{"parentUuid":null,"isSidechain":false,"type":"user","message":{"role":"user","content":` + contentJSON +
		`},"uuid":"u-1","timestamp":"` + timestamp + `","cwd":"` + cwd + `","sessionId":"` + conversationID + `"}`
}

func assistantLineForTest(conversationID, cwd, timestamp string) string {
	return `{"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"ok"}]},"timestamp":"` +
		timestamp + `","cwd":"` + cwd + `","sessionId":"` + conversationID + `"}`
}

const (
	firstConversationIDForTest  = "654ce605-b030-40e8-8448-2e9003ad32b1"
	secondConversationIDForTest = "8f488465-713d-40f0-92a7-bfa5a028b227"
	thirdConversationIDForTest  = "92a16701-82f5-4830-8588-a789e74e1fad"
)

func listClaudeCodeConversationsForTest(t *testing.T, workDirPath, sessionCwd string) ClaudeCodeConversationListing {
	t.Helper()

	listing, err := ListClaudeCodeConversations(workDirPath, sessionCwd)
	if err != nil {
		t.Fatalf("ListClaudeCodeConversations() 실패: %v", err)
	}
	return listing
}

func TestClaudeCodeProjectDirectoryNameFollowsWhatClaudeCodeActuallyWrote(t *testing.T) {
	// claude 2.1.316 을 각 디렉토리에서 돌려 생긴 이름 그대로다(2026-11-01 실측). 한국어는 바이트가 아니라
	// 글자 하나에 - 하나, 이모지는 UTF-16 대리쌍이라 - 둘이다.
	measured := map[string]string{
		"/home/yawn/Workspace/Kyuchestration": "-home-yawn-Workspace-Kyuchestration",
		"/tmp/s/enc test/a.b_c-d":             "-tmp-s-enc-test-a-b-c-d",
		"/tmp/s/enc-한글폴더":                     "-tmp-s-enc-----",
		"/tmp/s/enc~tilde+plus@at":            "-tmp-s-enc-tilde-plus-at",
		"/tmp/s/encemoji😀x":                   "-tmp-s-encemoji--x",
	}
	for absoluteCwd, want := range measured {
		if got := claudeCodeProjectDirectoryName(absoluteCwd); got != want {
			t.Errorf("claudeCodeProjectDirectoryName(%q) = %q, want %q", absoluteCwd, got, want)
		}
	}
}

func TestListClaudeCodeConversationsAnswersWhatATerminalConversationLeft(t *testing.T) {
	claudeConfigDirectory := claudeCodeHomeForTest(t)
	sessionCwd := resolvedDirectoryForTest(t)
	lastActiveAt := time.Date(2026, 11, 1, 9, 0, 56, 0, time.UTC)

	writeTranscriptForTest(t, claudeConfigDirectory, projectDirectoryNameForTest(sessionCwd), firstConversationIDForTest, lastActiveAt,
		`{"type":"mode","mode":"normal","sessionId":"`+firstConversationIDForTest+`"}`,
		`{"type":"attachment","timestamp":"2026-11-01T09:00:49.714Z","cwd":"`+sessionCwd+`","sessionId":"`+firstConversationIDForTest+`"}`,
		userLineForTest(firstConversationIDForTest, sessionCwd, "2026-11-01T09:00:51.046Z", `"답은 ok 한 단어"`),
		assistantLineForTest(firstConversationIDForTest, sessionCwd, "2026-11-01T09:00:55.996Z"),
	)

	listing := listClaudeCodeConversationsForTest(t, t.TempDir(), sessionCwd)

	if len(listing.Conversations) != 1 {
		t.Fatalf("대화 = %+v, 하나를 기대", listing.Conversations)
	}
	conversation := listing.Conversations[0]
	if conversation.ID != firstConversationIDForTest {
		t.Errorf("id = %q, want %q", conversation.ID, firstConversationIDForTest)
	}
	if conversation.Title != "답은 ok 한 단어" {
		t.Errorf("title = %q, 첫 사용자 글을 기대", conversation.Title)
	}
	// 첫 메시지 줄의 시각이다 — 그 앞의 attachment 줄이 더 이르지만 그것은 대화가 아니다.
	if want := time.Date(2026, 11, 1, 9, 0, 51, 46_000_000, time.UTC); !conversation.StartedAt.Equal(want) {
		t.Errorf("startedAt = %v, want %v", conversation.StartedAt, want)
	}
	if !conversation.LastActiveAt.Equal(lastActiveAt) {
		t.Errorf("lastActiveAt = %v, 파일 mtime %v 를 기대", conversation.LastActiveAt, lastActiveAt)
	}
	if conversation.RecordedFor != "" {
		t.Errorf("recordedFor = %q, 기록에 없으므로 비어 있기를 기대", conversation.RecordedFor)
	}
	if len(listing.Warnings) != 0 {
		t.Errorf("경고 = %q, 없기를 기대", listing.Warnings)
	}
}

func TestListClaudeCodeConversationsTitlesWithTheFirstTextAPersonWrote(t *testing.T) {
	// 시스템이 주입한 <…> 덩어리는 사람이 쓴 글이 아니다. 그것을 제목으로 쓰면 목록의 모든 줄이
	// "<local-command-caveat>…" 로 보인다.
	claudeConfigDirectory := claudeCodeHomeForTest(t)
	sessionCwd := resolvedDirectoryForTest(t)
	longText := strings.Repeat("가", 70) + "\n" + strings.Repeat("나", 30)

	writeTranscriptForTest(t, claudeConfigDirectory, projectDirectoryNameForTest(sessionCwd), firstConversationIDForTest, time.Now(),
		userLineForTest(firstConversationIDForTest, sessionCwd, "2026-11-01T09:00:00Z",
			`"<local-command-caveat>Caveat: generated by local commands.</local-command-caveat>"`),
		userLineForTest(firstConversationIDForTest, sessionCwd, "2026-11-01T09:00:01Z",
			`"<command-name>/clear</command-name>\n            <command-message>clear</command-message>"`),
		userLineForTest(firstConversationIDForTest, sessionCwd, "2026-11-01T09:00:02Z",
			`[{"type":"tool_result","tool_use_id":"t-1","content":"결과"},{"type":"text","text":"<system-reminder>\n주입\n</system-reminder>"},{"type":"text","text":"`+
				strings.ReplaceAll(longText, "\n", `\n`)+`"}]`),
	)

	listing := listClaudeCodeConversationsForTest(t, t.TempDir(), sessionCwd)

	if len(listing.Conversations) != 1 {
		t.Fatalf("대화 = %+v, 하나를 기대", listing.Conversations)
	}
	want := strings.Repeat("가", 70) + " " + strings.Repeat("나", 9)
	if got := listing.Conversations[0].Title; got != want {
		t.Errorf("title = %q, 줄바꿈을 공백으로 편 앞 80 자 %q 를 기대", got, want)
	}
}

func TestListClaudeCodeConversationsPrefersTheTitleClaudeCodeGaveIt(t *testing.T) {
	// 실제 전사(claude 2.1.316)는 제목을 ai-title 줄로 남긴다. summary 줄은 그보다 앞선 판의 모양이다.
	claudeConfigDirectory := claudeCodeHomeForTest(t)
	sessionCwd := resolvedDirectoryForTest(t)
	projectDirectoryName := projectDirectoryNameForTest(sessionCwd)

	writeTranscriptForTest(t, claudeConfigDirectory, projectDirectoryName, firstConversationIDForTest, time.Now(),
		`{"type":"summary","summary":"요약 제목","leafUuid":"u-1"}`,
		userLineForTest(firstConversationIDForTest, sessionCwd, "2026-11-01T09:00:00Z", `"첫 글"`),
		`{"type":"ai-title","aiTitle":"처음 지은 제목","sessionId":"`+firstConversationIDForTest+`"}`,
		`{"type":"ai-title","aiTitle":"다시 지은 제목","sessionId":"`+firstConversationIDForTest+`"}`,
	)
	writeTranscriptForTest(t, claudeConfigDirectory, projectDirectoryName, secondConversationIDForTest, time.Now(),
		`{"type":"summary","summary":"요약 제목","leafUuid":"u-1"}`,
		userLineForTest(secondConversationIDForTest, sessionCwd, "2026-11-01T09:00:00Z", `"첫 글"`),
	)

	titles := map[string]string{}
	for _, conversation := range listClaudeCodeConversationsForTest(t, t.TempDir(), sessionCwd).Conversations {
		titles[conversation.ID] = conversation.Title
	}
	if titles[firstConversationIDForTest] != "다시 지은 제목" {
		t.Errorf("ai-title 이 있는 대화의 title = %q, 마지막 ai-title 을 기대", titles[firstConversationIDForTest])
	}
	if titles[secondConversationIDForTest] != "요약 제목" {
		t.Errorf("summary 만 있는 대화의 title = %q, summary 를 기대", titles[secondConversationIDForTest])
	}
}

func TestListClaudeCodeConversationsLeavesOutAConversationFromAnotherCwd(t *testing.T) {
	// 인코딩은 영숫자가 아닌 것을 모두 - 로 접으므로 "/a/b-c" 와 "/a/b/c" 가 같은 디렉토리에 산다.
	// 디렉토리 이름만 믿으면 다른 자리의 대화를 이 세션이 이어가게 된다.
	claudeConfigDirectory := claudeCodeHomeForTest(t)
	sessionCwd := resolvedDirectoryForTest(t)
	collidingCwd := sessionCwd + "/x"
	sessionCwdWithSuffix := sessionCwd + "-x"
	if err := os.Mkdir(sessionCwdWithSuffix, 0o755); err != nil {
		t.Fatalf("디렉토리 생성 실패: %v", err)
	}

	writeTranscriptForTest(t, claudeConfigDirectory, projectDirectoryNameForTest(sessionCwdWithSuffix), firstConversationIDForTest, time.Now(),
		userLineForTest(firstConversationIDForTest, collidingCwd, "2026-11-01T09:00:00Z", `"다른 자리"`))
	writeTranscriptForTest(t, claudeConfigDirectory, projectDirectoryNameForTest(sessionCwdWithSuffix), secondConversationIDForTest, time.Now(),
		userLineForTest(secondConversationIDForTest, sessionCwdWithSuffix, "2026-11-01T09:00:00Z", `"이 자리"`))

	listing := listClaudeCodeConversationsForTest(t, t.TempDir(), sessionCwdWithSuffix)

	if len(listing.Conversations) != 1 || listing.Conversations[0].ID != secondConversationIDForTest {
		t.Errorf("대화 = %+v, 안에 적힌 cwd 가 같은 %s 하나만 기대", listing.Conversations, secondConversationIDForTest)
	}
}

func TestListClaudeCodeConversationsSkipsATranscriptWhoseLinesNameAnotherConversation(t *testing.T) {
	// 파일 이름과 안의 sessionId 가 다르면 --resume <파일 이름> 이 무엇을 여는지 알 수 없다. 조용히 빼면
	// 사용자는 터미널에서 본 대화가 왜 목록에 없는지 모르므로 경고로 알린다.
	claudeConfigDirectory := claudeCodeHomeForTest(t)
	sessionCwd := resolvedDirectoryForTest(t)

	writeTranscriptForTest(t, claudeConfigDirectory, projectDirectoryNameForTest(sessionCwd), firstConversationIDForTest, time.Now(),
		userLineForTest(secondConversationIDForTest, sessionCwd, "2026-11-01T09:00:00Z", `"엇갈린 전사"`))

	listing := listClaudeCodeConversationsForTest(t, t.TempDir(), sessionCwd)

	if len(listing.Conversations) != 0 {
		t.Errorf("대화 = %+v, 엇갈린 전사는 빠지기를 기대", listing.Conversations)
	}
	if len(listing.Warnings) != 1 || !strings.Contains(listing.Warnings[0], firstConversationIDForTest+".jsonl") {
		t.Errorf("경고 = %q, 그 전사 파일을 가리키는 경고 하나를 기대", listing.Warnings)
	}
}

func TestListClaudeCodeConversationsLeavesOutATranscriptWithNothingAPersonSaid(t *testing.T) {
	claudeConfigDirectory := claudeCodeHomeForTest(t)
	sessionCwd := resolvedDirectoryForTest(t)

	writeTranscriptForTest(t, claudeConfigDirectory, projectDirectoryNameForTest(sessionCwd), firstConversationIDForTest, time.Now(),
		`{"type":"mode","mode":"normal","sessionId":"`+firstConversationIDForTest+`"}`,
		`{"type":"file-history-snapshot","messageId":"m-1"}`)

	if listing := listClaudeCodeConversationsForTest(t, t.TempDir(), sessionCwd); len(listing.Conversations) != 0 {
		t.Errorf("대화 = %+v, 사용자 줄이 없는 전사는 빠지기를 기대", listing.Conversations)
	}
}

func TestListClaudeCodeConversationsReadsPastBrokenLines(t *testing.T) {
	// claude 가 쓰는 중인 전사의 마지막 줄은 잘려 있을 수 있다. 줄 하나 때문에 대화를 잃지 않는다.
	claudeConfigDirectory := claudeCodeHomeForTest(t)
	sessionCwd := resolvedDirectoryForTest(t)

	writeTranscriptForTest(t, claudeConfigDirectory, projectDirectoryNameForTest(sessionCwd), firstConversationIDForTest, time.Now(),
		`{"type":"user", 깨진 줄`,
		`{"type":"brand-new-kind","sessionId":"`+firstConversationIDForTest+`"}`,
		userLineForTest(firstConversationIDForTest, sessionCwd, "2026-11-01T09:00:00Z", `"살아남은 글"`),
		`{"type":"assistant","mess`)

	listing := listClaudeCodeConversationsForTest(t, t.TempDir(), sessionCwd)

	if len(listing.Conversations) != 1 || listing.Conversations[0].Title != "살아남은 글" {
		t.Errorf("대화 = %+v, 깨진 줄을 건너뛴 대화 하나를 기대", listing.Conversations)
	}
}

func TestListClaudeCodeConversationsPutsTheMostRecentlyActiveFirst(t *testing.T) {
	claudeConfigDirectory := claudeCodeHomeForTest(t)
	sessionCwd := resolvedDirectoryForTest(t)
	projectDirectoryName := projectDirectoryNameForTest(sessionCwd)
	baseTime := time.Date(2026, 11, 1, 9, 0, 0, 0, time.UTC)

	for conversationID, lastActiveAt := range map[string]time.Time{
		firstConversationIDForTest:  baseTime,
		secondConversationIDForTest: baseTime.Add(2 * time.Hour),
		thirdConversationIDForTest:  baseTime.Add(time.Hour),
	} {
		writeTranscriptForTest(t, claudeConfigDirectory, projectDirectoryName, conversationID, lastActiveAt,
			userLineForTest(conversationID, sessionCwd, "2026-11-01T08:00:00Z", `"글"`))
	}

	var order []string
	for _, conversation := range listClaudeCodeConversationsForTest(t, t.TempDir(), sessionCwd).Conversations {
		order = append(order, conversation.ID)
	}
	want := []string{secondConversationIDForTest, thirdConversationIDForTest, firstConversationIDForTest}
	if strings.Join(order, ",") != strings.Join(want, ",") {
		t.Errorf("순서 = %q, 마지막 활동이 늦은 것부터 %q 를 기대", order, want)
	}
}

func TestListClaudeCodeConversationsTellsWhichLabelRecordedTheConversation(t *testing.T) {
	// 앱이 자기가 적은 대화와 바깥 대화를 가르는 근거다. 위임 대화는 사람이 붙는 대화가 아니라 목록에 없다 —
	// 그것을 세션이 채택하면 하나의 UUID 를 둘이 써서 뒤에 오는 쪽이 already in use 로 거절된다.
	claudeConfigDirectory := claudeCodeHomeForTest(t)
	sessionCwd := resolvedDirectoryForTest(t)
	projectDirectoryName := projectDirectoryNameForTest(sessionCwd)
	workDirPath := t.TempDir()
	writeConversationsFileForTest(t, workDirPath, `{"schemaVersion":1,"conversations":{"proj-a":"`+firstConversationIDForTest+
		`"},"delegations":{"proj-a":"`+secondConversationIDForTest+`"}}`)

	for _, conversationID := range []string{firstConversationIDForTest, secondConversationIDForTest, thirdConversationIDForTest} {
		writeTranscriptForTest(t, claudeConfigDirectory, projectDirectoryName, conversationID, time.Now(),
			userLineForTest(conversationID, sessionCwd, "2026-11-01T08:00:00Z", `"글"`))
	}

	recordedFor := map[string]string{}
	for _, conversation := range listClaudeCodeConversationsForTest(t, workDirPath, sessionCwd).Conversations {
		recordedFor[conversation.ID] = conversation.RecordedFor
	}
	want := map[string]string{firstConversationIDForTest: "proj-a", thirdConversationIDForTest: ""}
	if len(recordedFor) != len(want) || recordedFor[firstConversationIDForTest] != "proj-a" || recordedFor[thirdConversationIDForTest] != "" {
		t.Errorf("recordedFor = %v, want %v (위임 대화는 빠진다)", recordedFor, want)
	}
}

func TestListClaudeCodeConversationsAnswersNothingWhenClaudeCodeNeverRanThere(t *testing.T) {
	claudeCodeHomeForTest(t)

	listing := listClaudeCodeConversationsForTest(t, t.TempDir(), resolvedDirectoryForTest(t))

	if listing.Conversations == nil || len(listing.Conversations) != 0 {
		t.Errorf("대화 = %#v, 빈 목록(nil 아님)을 기대", listing.Conversations)
	}
}

func TestListClaudeCodeConversationsLooksUnderHomeWithoutClaudeConfigDir(t *testing.T) {
	homeDirectory := t.TempDir()
	t.Setenv("CLAUDE_CONFIG_DIR", "")
	t.Setenv("HOME", homeDirectory)
	sessionCwd := resolvedDirectoryForTest(t)

	writeTranscriptForTest(t, filepath.Join(homeDirectory, ".claude"), projectDirectoryNameForTest(sessionCwd), firstConversationIDForTest, time.Now(),
		userLineForTest(firstConversationIDForTest, sessionCwd, "2026-11-01T08:00:00Z", `"글"`))

	if listing := listClaudeCodeConversationsForTest(t, t.TempDir(), sessionCwd); len(listing.Conversations) != 1 {
		t.Errorf("대화 = %+v, ~/.claude 아래의 대화 하나를 기대", listing.Conversations)
	}
}

func TestListClaudeCodeConversationsFollowsALinkedCwdTheWayClaudeCodeDoes(t *testing.T) {
	// claude 는 링크를 편 경로로 전사 자리를 정하고 cwd 를 적는다(실측). 맥의 /tmp 가 이미 링크다.
	claudeConfigDirectory := claudeCodeHomeForTest(t)
	realCwd := resolvedDirectoryForTest(t)
	linkedCwd := filepath.Join(t.TempDir(), "linked")
	if err := os.Symlink(realCwd, linkedCwd); err != nil {
		t.Fatalf("링크 생성 실패: %v", err)
	}

	writeTranscriptForTest(t, claudeConfigDirectory, projectDirectoryNameForTest(realCwd), firstConversationIDForTest, time.Now(),
		userLineForTest(firstConversationIDForTest, realCwd, "2026-11-01T08:00:00Z", `"글"`))

	if listing := listClaudeCodeConversationsForTest(t, t.TempDir(), linkedCwd); len(listing.Conversations) != 1 {
		t.Errorf("대화 = %+v, 링크를 편 자리의 대화 하나를 기대", listing.Conversations)
	}
}

func TestListClaudeCodeConversationsFindsALongCwdWhoseDirectoryNameCarriesAHash(t *testing.T) {
	// 200 자를 넘는 이름은 앞 200 자 뒤에 해시 꼬리가 붙는다(실측: "-903mh7"). 꼬리를 다시 만들지 않고
	// 앞자리로 찾는다 — 안의 cwd 로 다시 확인하므로 꼬리가 무엇이든 맞는 대화만 남는다.
	claudeConfigDirectory := claudeCodeHomeForTest(t)
	sessionCwd := filepath.Join(resolvedDirectoryForTest(t), strings.Repeat("a", 210))
	if err := os.Mkdir(sessionCwd, 0o755); err != nil {
		t.Fatalf("긴 디렉토리 생성 실패: %v", err)
	}
	directoryName := projectDirectoryNameForTest(sessionCwd)[:200] + "-903mh7"

	writeTranscriptForTest(t, claudeConfigDirectory, directoryName, firstConversationIDForTest, time.Now(),
		userLineForTest(firstConversationIDForTest, sessionCwd, "2026-11-01T08:00:00Z", `"긴 자리"`))

	if listing := listClaudeCodeConversationsForTest(t, t.TempDir(), sessionCwd); len(listing.Conversations) != 1 {
		t.Errorf("대화 = %+v, 해시 꼬리 디렉토리의 대화 하나를 기대", listing.Conversations)
	}
}

func TestListClaudeCodeConversationsLooksOnlyAtConversationFiles(t *testing.T) {
	// <uuid>/ 하위 디렉토리(하위 에이전트 전사 등)와 uuid 꼴이 아닌 파일은 --resume 이 받는 대화가 아니다.
	claudeConfigDirectory := claudeCodeHomeForTest(t)
	sessionCwd := resolvedDirectoryForTest(t)
	projectDirectoryName := projectDirectoryNameForTest(sessionCwd)

	writeTranscriptForTest(t, claudeConfigDirectory, projectDirectoryName, "notes", time.Now(),
		userLineForTest("notes", sessionCwd, "2026-11-01T08:00:00Z", `"글"`))
	writeTranscriptForTest(t, claudeConfigDirectory, filepath.Join(projectDirectoryName, firstConversationIDForTest, "subagents"), secondConversationIDForTest, time.Now(),
		userLineForTest(secondConversationIDForTest, sessionCwd, "2026-11-01T08:00:00Z", `"하위 에이전트"`))

	if listing := listClaudeCodeConversationsForTest(t, t.TempDir(), sessionCwd); len(listing.Conversations) != 0 {
		t.Errorf("대화 = %+v, 대화 파일이 아닌 것은 없기를 기대", listing.Conversations)
	}
}

func TestAdoptClaudeCodeConversationRecordsItForTheLabelSoTheNextCallResumesIt(t *testing.T) {
	claudeConfigDirectory := claudeCodeHomeForTest(t)
	sessionCwd := resolvedDirectoryForTest(t)
	workDirPath := t.TempDir()
	writeConversationsFileForTest(t, workDirPath, `{"schemaVersion":1,"conversations":{"main":"`+thirdConversationIDForTest+`","proj-a":"`+secondConversationIDForTest+`"}}`)
	writeTranscriptForTest(t, claudeConfigDirectory, projectDirectoryNameForTest(sessionCwd), firstConversationIDForTest, time.Now(),
		userLineForTest(firstConversationIDForTest, sessionCwd, "2026-11-01T08:00:00Z", `"터미널에서 한 대화"`))

	assignment, err := AdoptClaudeCodeConversation(workDirPath, "main", sessionCwd, firstConversationIDForTest)
	if err != nil {
		t.Fatalf("AdoptClaudeCodeConversation() 실패: %v", err)
	}

	if assignment.ConversationID != firstConversationIDForTest || assignment.IsNewConversation {
		t.Errorf("배정 = %+v, 채택한 대화를 이어가기로 기대", assignment)
	}
	record := readConversationsFileForTest(t, workDirPath)
	if record.Conversations["main"] != firstConversationIDForTest || record.Conversations["proj-a"] != secondConversationIDForTest {
		t.Errorf("기록 = %v, main 만 채택한 대화로 바뀌기를 기대", record.Conversations)
	}

	next, err := AssignConversation(workDirPath, "main", SessionConversation)
	if err != nil {
		t.Fatalf("AssignConversation() 실패: %v", err)
	}
	if next.ConversationID != firstConversationIDForTest || next.IsNewConversation {
		t.Errorf("다음 배정 = %+v, 채택한 대화를 이어가기를 기대", next)
	}
}

func TestAdoptClaudeCodeConversationRefusesAConversationThatIsNotThere(t *testing.T) {
	// 없는 id 를 적으면 다음 실행의 --resume 이 종료 코드 1 로 끝난다(설계 문서 5.5.4). 그 실패를
	// 적는 자리에서 막는다 — 앞서 적혀 있던 대화도 그대로 남아야 한다.
	claudeCodeHomeForTest(t)
	sessionCwd := resolvedDirectoryForTest(t)
	workDirPath := t.TempDir()
	writeConversationsFileForTest(t, workDirPath, `{"schemaVersion":1,"conversations":{"main":"`+thirdConversationIDForTest+`"}}`)

	if _, err := AdoptClaudeCodeConversation(workDirPath, "main", sessionCwd, firstConversationIDForTest); err == nil {
		t.Fatal("AdoptClaudeCodeConversation() = nil, 전사가 없는 대화는 거절을 기대")
	}
	if record := readConversationsFileForTest(t, workDirPath); record.Conversations["main"] != thirdConversationIDForTest {
		t.Errorf("기록 = %v, 거절한 채택이 기록을 건드리지 않기를 기대", record.Conversations)
	}
}

func TestAdoptClaudeCodeConversationRefusesADelegationConversation(t *testing.T) {
	claudeConfigDirectory := claudeCodeHomeForTest(t)
	sessionCwd := resolvedDirectoryForTest(t)
	workDirPath := t.TempDir()
	writeConversationsFileForTest(t, workDirPath, `{"schemaVersion":1,"conversations":{},"delegations":{"proj-a":"`+firstConversationIDForTest+`"}}`)
	writeTranscriptForTest(t, claudeConfigDirectory, projectDirectoryNameForTest(sessionCwd), firstConversationIDForTest, time.Now(),
		userLineForTest(firstConversationIDForTest, sessionCwd, "2026-11-01T08:00:00Z", `"위임"`))

	_, err := AdoptClaudeCodeConversation(workDirPath, "proj-a", sessionCwd, firstConversationIDForTest)
	if err == nil || !strings.Contains(err.Error(), "위임") {
		t.Errorf("AdoptClaudeCodeConversation() = %v, 위임 대화라는 거절을 기대", err)
	}
}

func TestListClaudeCodeConversationsStopsReadingATranscriptOnceItsCwdIsSomewhereElse(t *testing.T) {
	// 이름이 겹친 다른 자리의 전사는 cwd 가 정해지는 줄에서 읽기를 멈춘다 — 수십 MB 를 끝까지 디코드할 까닭이
	// 없다. 멈췄다는 사실은 그 뒤의 엇갈린 줄이 경고를 내지 않는 것으로 드러난다.
	claudeConfigDirectory := claudeCodeHomeForTest(t)
	sessionCwd := resolvedDirectoryForTest(t)
	sessionCwdWithSuffix := sessionCwd + "-x"
	if err := os.Mkdir(sessionCwdWithSuffix, 0o755); err != nil {
		t.Fatalf("디렉토리 생성 실패: %v", err)
	}

	writeTranscriptForTest(t, claudeConfigDirectory, projectDirectoryNameForTest(sessionCwdWithSuffix), firstConversationIDForTest, time.Now(),
		userLineForTest(firstConversationIDForTest, sessionCwd+"/x", "2026-11-01T09:00:00Z", `"다른 자리"`),
		userLineForTest(secondConversationIDForTest, sessionCwd+"/x", "2026-11-01T09:00:01Z", `"엇갈린 줄"`))

	listing := listClaudeCodeConversationsForTest(t, t.TempDir(), sessionCwdWithSuffix)

	if len(listing.Conversations) != 0 || len(listing.Warnings) != 0 {
		t.Errorf("목록 = %+v, 다른 자리의 전사는 경고 없이 빠지기를 기대", listing)
	}
}

func TestListClaudeCodeConversationsLeavesOutATranscriptWhereNoPersonWroteAnything(t *testing.T) {
	// /status 만 치고 나간 세션은 사용자 줄이 있어도 사람이 쓴 글이 없다. 제목이 빈 줄로 목록에 남으면
	// 사람이 고를 근거가 없다.
	claudeConfigDirectory := claudeCodeHomeForTest(t)
	sessionCwd := resolvedDirectoryForTest(t)

	writeTranscriptForTest(t, claudeConfigDirectory, projectDirectoryNameForTest(sessionCwd), firstConversationIDForTest, time.Now(),
		userLineForTest(firstConversationIDForTest, sessionCwd, "2026-11-01T09:00:00Z",
			`"<command-name>/status</command-name>\n<command-message>status</command-message>"`),
		userLineForTest(firstConversationIDForTest, sessionCwd, "2026-11-01T09:00:01Z",
			`[{"type":"tool_result","tool_use_id":"t-1","content":"결과"}]`))

	if listing := listClaudeCodeConversationsForTest(t, t.TempDir(), sessionCwd); len(listing.Conversations) != 0 {
		t.Errorf("대화 = %+v, 사람이 쓴 글이 없는 전사는 빠지기를 기대", listing.Conversations)
	}
}

func TestListClaudeCodeConversationsFoldsTabsInTheTitleToo(t *testing.T) {
	// 제목은 사람용 표의 마지막 칸이다. 탭이 남으면 tabwriter 가 그것을 열 경계로 읽어 표가 깨진다.
	claudeConfigDirectory := claudeCodeHomeForTest(t)
	sessionCwd := resolvedDirectoryForTest(t)

	writeTranscriptForTest(t, claudeConfigDirectory, projectDirectoryNameForTest(sessionCwd), firstConversationIDForTest, time.Now(),
		userLineForTest(firstConversationIDForTest, sessionCwd, "2026-11-01T09:00:00Z", `"앞\t뒤"`))

	listing := listClaudeCodeConversationsForTest(t, t.TempDir(), sessionCwd)
	if len(listing.Conversations) != 1 || listing.Conversations[0].Title != "앞 뒤" {
		t.Errorf("대화 = %+v, 탭을 공백으로 접은 제목을 기대", listing.Conversations)
	}
}

func TestCwdComparisonIgnoresCaseOnlyWhereTheFileSystemDoes(t *testing.T) {
	// 맥(APFS 기본)과 윈도우는 글자 케이스가 달라도 같은 자리다. 터미널이 적은 cwd 와 앱이 준 cwd 의 케이스가
	// 다르면 바이트 비교로는 빈 목록이 된다. 리눅스는 케이스가 다르면 다른 자리라 그대로 바이트 비교다.
	if !cwdsNameTheSamePlace("/Users/Me/Work", "/users/me/work", true) {
		t.Error("케이스 무시 파일 시스템에서 케이스만 다른 두 cwd 를 같은 자리로 기대")
	}
	if cwdsNameTheSamePlace("/home/me/Work", "/home/me/work", false) {
		t.Error("케이스를 가르는 파일 시스템에서 케이스가 다른 두 cwd 를 다른 자리로 기대")
	}
	if cwdsNameTheSamePlace("/Users/Me/Work", "/Users/Me/Other", true) {
		t.Error("이름이 다른 두 cwd 는 어디서든 다른 자리여야 한다")
	}
}

func TestListClaudeCodeConversationsMatchesACwdWrittenInAnotherCaseOnACaseInsensitiveFileSystem(t *testing.T) {
	claudeConfigDirectory := claudeCodeHomeForTest(t)
	sessionCwd := filepath.Join(resolvedDirectoryForTest(t), "Work", strings.Repeat("a", 210))
	if err := os.MkdirAll(sessionCwd, 0o755); err != nil {
		t.Fatalf("디렉토리 생성 실패: %v", err)
	}
	cwdInAnotherCase := strings.ToUpper(sessionCwd)
	previous := fileSystemIgnoresCase
	fileSystemIgnoresCase = true
	t.Cleanup(func() { fileSystemIgnoresCase = previous })

	// 긴 이름은 앞자리 찾기라 그 단계도 케이스를 무시해야 한다 — 짧은 이름은 같은 파일 시스템의 Stat 이 이미 그렇다.
	writeTranscriptForTest(t, claudeConfigDirectory, projectDirectoryNameForTest(cwdInAnotherCase)[:200]+"-abc123", firstConversationIDForTest, time.Now(),
		userLineForTest(firstConversationIDForTest, cwdInAnotherCase, "2026-11-01T08:00:00Z", `"다른 케이스"`))

	if listing := listClaudeCodeConversationsForTest(t, t.TempDir(), sessionCwd); len(listing.Conversations) != 1 {
		t.Errorf("대화 = %+v, 케이스만 다른 자리의 대화 하나를 기대", listing.Conversations)
	}
}

func TestAdoptClaudeCodeConversationReadsOnlyTheChosenTranscript(t *testing.T) {
	// 채택은 id 하나를 확인하는 일이다. 같은 자리의 다른 전사를 다 읽으면 그 전사들의 경고까지 채택의 답에 섞인다.
	claudeConfigDirectory := claudeCodeHomeForTest(t)
	sessionCwd := resolvedDirectoryForTest(t)
	projectDirectoryName := projectDirectoryNameForTest(sessionCwd)
	workDirPath := t.TempDir()
	writeTranscriptForTest(t, claudeConfigDirectory, projectDirectoryName, firstConversationIDForTest, time.Now(),
		userLineForTest(firstConversationIDForTest, sessionCwd, "2026-11-01T08:00:00Z", `"고른 대화"`))
	writeTranscriptForTest(t, claudeConfigDirectory, projectDirectoryName, secondConversationIDForTest, time.Now(),
		userLineForTest(thirdConversationIDForTest, sessionCwd, "2026-11-01T08:00:00Z", `"엇갈린 전사"`))

	assignment, err := AdoptClaudeCodeConversation(workDirPath, "main", sessionCwd, firstConversationIDForTest)
	if err != nil {
		t.Fatalf("AdoptClaudeCodeConversation() 실패: %v", err)
	}
	if len(assignment.Warnings) != 0 {
		t.Errorf("경고 = %q, 고르지 않은 전사의 경고는 없기를 기대", assignment.Warnings)
	}
}
