package workdir

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// conversationLineForTest 는 전사의 user · assistant 한 줄이다. extraFields 는 `"isMeta":true,` 처럼 앞에 끼울 자리다.
//
// 실측(claude 2.1.316)한 줄의 모양을 따른다 — assistant 줄은 블록 하나씩, 같은 message.id 로 여러 줄이다.
func conversationLineForTest(lineType, conversationID, cwd, timestamp, contentJSON, extraFields string) string {
	return `{"parentUuid":null,"isSidechain":false,` + extraFields + `"type":"` + lineType + `","message":{"role":"` + lineType +
		`","content":` + contentJSON + `},"uuid":"u-1","timestamp":"` + timestamp + `","cwd":"` + cwd + `","sessionId":"` + conversationID + `"}`
}

func readConversationHistoryForTest(t *testing.T, sessionCwd, conversationID string, lastEntryLimit int) ClaudeCodeConversationHistory {
	t.Helper()

	history, warnings, err := ReadClaudeCodeConversationHistory(sessionCwd, conversationID, lastEntryLimit)
	if err != nil {
		t.Fatalf("ReadClaudeCodeConversationHistory() 실패: %v", err)
	}
	if len(warnings) != 0 {
		t.Errorf("경고 = %q, 없기를 기대", warnings)
	}
	return history
}

// writeHistoryTranscriptForTest 는 sessionCwd 자리에 첫 대화의 전사를 적는다.
func writeHistoryTranscriptForTest(t *testing.T, sessionCwd string, lines ...string) {
	t.Helper()

	writeTranscriptForTest(t, claudeCodeHomeForTest(t), projectDirectoryNameForTest(sessionCwd), firstConversationIDForTest, time.Now(), lines...)
}

func TestConversationHistoryAnswersWhatAPersonWroteWhetherContentIsAStringOrBlocks(t *testing.T) {
	sessionCwd := resolvedDirectoryForTest(t)
	writeHistoryTranscriptForTest(t, sessionCwd,
		conversationLineForTest("user", firstConversationIDForTest, sessionCwd, "2026-11-01T09:06:18.123Z", `"문자열로 쓴 글"`, ""),
		conversationLineForTest("user", firstConversationIDForTest, sessionCwd, "2026-11-01T09:07:00Z", `[{"type":"text","text":"블록으로 쓴 글"}]`, ""))

	history := readConversationHistoryForTest(t, sessionCwd, firstConversationIDForTest, 200)

	want := []ClaudeCodeConversationEntry{
		{Kind: ConversationEntryKindUser, Text: "문자열로 쓴 글", At: time.Date(2026, 11, 1, 9, 6, 18, 123_000_000, time.UTC)},
		{Kind: ConversationEntryKindUser, Text: "블록으로 쓴 글", At: time.Date(2026, 11, 1, 9, 7, 0, 0, time.UTC)},
	}
	assertConversationEntriesForTest(t, history.Entries, want)
	if history.ConversationID != firstConversationIDForTest || history.TotalEntries != 2 {
		t.Errorf("history = %+v, 전체 2 개를 기대", history)
	}
}

func TestConversationHistoryJoinsAssistantTextAndLeavesOutThinking(t *testing.T) {
	sessionCwd := resolvedDirectoryForTest(t)
	writeHistoryTranscriptForTest(t, sessionCwd,
		conversationLineForTest("user", firstConversationIDForTest, sessionCwd, "2026-11-01T09:00:00Z", `"질문"`, ""),
		// 실측에서 생각만 담긴 줄은 따로 온다 — 그 줄은 항목이 되지 않는다.
		conversationLineForTest("assistant", firstConversationIDForTest, sessionCwd, "2026-11-01T09:00:01Z", `[{"type":"thinking","thinking":"속생각","signature":"s"}]`, ""),
		conversationLineForTest("assistant", firstConversationIDForTest, sessionCwd, "2026-11-01T09:00:02Z",
			`[{"type":"thinking","thinking":"속생각"},{"type":"text","text":"앞 문단"},{"type":"text","text":"뒤 문단"}]`, ""))

	history := readConversationHistoryForTest(t, sessionCwd, firstConversationIDForTest, 200)

	assertConversationEntriesForTest(t, history.Entries, []ClaudeCodeConversationEntry{
		{Kind: ConversationEntryKindUser, Text: "질문", At: time.Date(2026, 11, 1, 9, 0, 0, 0, time.UTC)},
		{Kind: ConversationEntryKindAssistant, Text: "앞 문단\n\n뒤 문단", At: time.Date(2026, 11, 1, 9, 0, 2, 0, time.UTC)},
	})
}

func TestConversationHistorySummarizesEachToolUseInOneLineAPersonRecognizes(t *testing.T) {
	sessionCwd := resolvedDirectoryForTest(t)
	longCommand := strings.Repeat("가", 130)
	writeHistoryTranscriptForTest(t, sessionCwd,
		conversationLineForTest("assistant", firstConversationIDForTest, sessionCwd, "2026-11-01T09:00:00Z",
			`[{"type":"tool_use","id":"t1","name":"Bash","input":{"command":"ls -la\ncat go.mod","description":"목록 보기"}},`+
				`{"type":"tool_use","id":"t2","name":"Read","input":{"file_path":"/a/b.go"}}]`, ""),
		conversationLineForTest("assistant", firstConversationIDForTest, sessionCwd, "2026-11-01T09:00:01Z",
			`[{"type":"tool_use","id":"t3","name":"Grep","input":{"pattern":"func .*Test"}}]`, ""),
		conversationLineForTest("assistant", firstConversationIDForTest, sessionCwd, "2026-11-01T09:00:02Z",
			`[{"type":"tool_use","id":"t4","name":"Agent","input":{"description":"하위 작업","prompt":"긴 지시"}}]`, ""),
		conversationLineForTest("assistant", firstConversationIDForTest, sessionCwd, "2026-11-01T09:00:03Z",
			`[{"type":"tool_use","id":"t5","name":"ListAgents","input":{}}]`, ""),
		conversationLineForTest("assistant", firstConversationIDForTest, sessionCwd, "2026-11-01T09:00:04Z",
			`[{"type":"tool_use","id":"t6","name":"Bash","input":{"command":"`+longCommand+`"}}]`, ""))

	history := readConversationHistoryForTest(t, sessionCwd, firstConversationIDForTest, 200)

	at := func(second int) time.Time { return time.Date(2026, 11, 1, 9, 0, second, 0, time.UTC) }
	assertConversationEntriesForTest(t, history.Entries, []ClaudeCodeConversationEntry{
		// command 가 description 보다 앞선다. 여러 줄 명령은 한 줄로 편다.
		{Kind: ConversationEntryKindToolUse, ToolName: "Bash", ToolSummary: "ls -la cat go.mod", At: at(0)},
		{Kind: ConversationEntryKindToolUse, ToolName: "Read", ToolSummary: "/a/b.go", At: at(0)},
		{Kind: ConversationEntryKindToolUse, ToolName: "Grep", ToolSummary: "func .*Test", At: at(1)},
		{Kind: ConversationEntryKindToolUse, ToolName: "Agent", ToolSummary: "하위 작업", At: at(2)},
		{Kind: ConversationEntryKindToolUse, ToolName: "ListAgents", ToolSummary: "", At: at(3)},
		{Kind: ConversationEntryKindToolUse, ToolName: "Bash", ToolSummary: strings.Repeat("가", 120), At: at(4)},
	})
}

func TestConversationHistoryLeavesOutToolResults(t *testing.T) {
	sessionCwd := resolvedDirectoryForTest(t)
	writeHistoryTranscriptForTest(t, sessionCwd,
		conversationLineForTest("user", firstConversationIDForTest, sessionCwd, "2026-11-01T09:00:00Z",
			`[{"type":"tool_result","tool_use_id":"t1","content":"total 0","is_error":false}]`, `"toolUseResult":{"stdout":"total 0"},`),
		conversationLineForTest("user", firstConversationIDForTest, sessionCwd, "2026-11-01T09:00:01Z", `"사람의 글"`, ""))

	history := readConversationHistoryForTest(t, sessionCwd, firstConversationIDForTest, 200)

	assertConversationEntriesForTest(t, history.Entries, []ClaudeCodeConversationEntry{
		{Kind: ConversationEntryKindUser, Text: "사람의 글", At: time.Date(2026, 11, 1, 9, 0, 1, 0, time.UTC)},
	})
}

func TestConversationHistoryFoldsConsecutiveSidechainLinesIntoOneOmittedEntry(t *testing.T) {
	// 앞선 판의 claude 는 하위 에이전트의 줄을 본 전사에 섞어 적었다. 그 줄은 이 대화가 아니라서 빼되, 빠졌다는
	// 사실은 한 줄로 남긴다 — 그렇지 않으면 앞뒤 글이 끊김 없이 이어진 것처럼 보인다.
	sessionCwd := resolvedDirectoryForTest(t)
	sidechain := `"isSidechain":true,`
	writeHistoryTranscriptForTest(t, sessionCwd,
		conversationLineForTest("user", firstConversationIDForTest, sessionCwd, "2026-11-01T09:00:00Z", `"위임해"`, ""),
		strings.Replace(conversationLineForTest("user", firstConversationIDForTest, sessionCwd, "2026-11-01T09:00:01Z", `"하위 지시"`, ""), `"isSidechain":false,`, sidechain, 1),
		conversationLineForTest("user", firstConversationIDForTest, sessionCwd, "2026-11-01T09:00:02Z",
			`[{"type":"tool_result","tool_use_id":"t1","content":"ok"}]`, ""),
		strings.Replace(conversationLineForTest("assistant", firstConversationIDForTest, sessionCwd, "2026-11-01T09:00:03Z", `[{"type":"text","text":"하위 답"}]`, ""), `"isSidechain":false,`, sidechain, 1),
		conversationLineForTest("assistant", firstConversationIDForTest, sessionCwd, "2026-11-01T09:00:04Z", `[{"type":"text","text":"끝났습니다"}]`, ""),
		strings.Replace(conversationLineForTest("assistant", firstConversationIDForTest, sessionCwd, "2026-11-01T09:00:05Z", `[{"type":"text","text":"하위 꼬리"}]`, ""), `"isSidechain":false,`, sidechain, 1))

	history := readConversationHistoryForTest(t, sessionCwd, firstConversationIDForTest, 200)

	assertConversationEntriesForTest(t, history.Entries, []ClaudeCodeConversationEntry{
		{Kind: ConversationEntryKindUser, Text: "위임해", At: time.Date(2026, 11, 1, 9, 0, 0, 0, time.UTC)},
		{Kind: ConversationEntryKindOmitted, OmittedCount: 2},
		{Kind: ConversationEntryKindAssistant, Text: "끝났습니다", At: time.Date(2026, 11, 1, 9, 0, 4, 0, time.UTC)},
		{Kind: ConversationEntryKindOmitted, OmittedCount: 1},
	})
}

func TestConversationHistoryLeavesOutWhatClaudeCodeWroteOnThePersonsBehalf(t *testing.T) {
	// 실측(claude 2.1.316): 주입 태그 덩어리 · 작업 알림 · isMeta(다른 세션의 메시지 · 스킬 본문 · 사용량 안내) ·
	// isCompactSummary(압축 요약) 는 user 줄이지만 사람이 쓴 글이 아니다.
	sessionCwd := resolvedDirectoryForTest(t)
	writeHistoryTranscriptForTest(t, sessionCwd,
		conversationLineForTest("user", firstConversationIDForTest, sessionCwd, "2026-11-01T09:00:00Z", `"<system-reminder>\n주입\n</system-reminder>\n진짜 질문"`, ""),
		conversationLineForTest("user", firstConversationIDForTest, sessionCwd, "2026-11-01T09:00:01Z",
			`"<task-notification>\n<result>끝</result>\n</task-notification>"`, `"origin":{"kind":"task-notification"},`),
		conversationLineForTest("user", firstConversationIDForTest, sessionCwd, "2026-11-01T09:00:02Z",
			`"Another Claude session sent a message:\n보고"`, `"isMeta":true,"origin":{"kind":"peer"},`),
		conversationLineForTest("user", firstConversationIDForTest, sessionCwd, "2026-11-01T09:00:03Z",
			`"This session is being continued from a previous conversation"`, `"isVisibleInTranscriptOnly":true,"isCompactSummary":true,`))

	history := readConversationHistoryForTest(t, sessionCwd, firstConversationIDForTest, 200)

	assertConversationEntriesForTest(t, history.Entries, []ClaudeCodeConversationEntry{
		{Kind: ConversationEntryKindUser, Text: "진짜 질문", At: time.Date(2026, 11, 1, 9, 0, 0, 0, time.UTC)},
	})
}

func TestConversationHistoryAnswersOnlyTheLastEntriesAndCountsTheOnesBefore(t *testing.T) {
	sessionCwd := resolvedDirectoryForTest(t)
	var lines []string
	for index, text := range []string{"하나", "둘", "셋", "넷", "다섯"} {
		lines = append(lines, conversationLineForTest("user", firstConversationIDForTest, sessionCwd,
			time.Date(2026, 11, 1, 9, 0, index, 0, time.UTC).Format(time.RFC3339), `"`+text+`"`, ""))
	}
	writeHistoryTranscriptForTest(t, sessionCwd, lines...)

	history := readConversationHistoryForTest(t, sessionCwd, firstConversationIDForTest, 2)

	assertConversationEntriesForTest(t, history.Entries, []ClaudeCodeConversationEntry{
		{Kind: ConversationEntryKindUser, Text: "넷", At: time.Date(2026, 11, 1, 9, 0, 3, 0, time.UTC)},
		{Kind: ConversationEntryKindUser, Text: "다섯", At: time.Date(2026, 11, 1, 9, 0, 4, 0, time.UTC)},
	})
	if history.TotalEntries != 5 {
		t.Errorf("전체 = %d, 5 를 기대 — 답하지 않은 앞의 3 개도 센다", history.TotalEntries)
	}
}

func TestConversationHistoryReadsPastBrokenLinesAndUnknownTimestamps(t *testing.T) {
	// claude 가 쓰는 중인 전사의 마지막 줄은 잘려 있을 수 있다.
	sessionCwd := resolvedDirectoryForTest(t)
	writeHistoryTranscriptForTest(t, sessionCwd,
		`{"type":"user","message":{"content":"잘린`,
		conversationLineForTest("user", firstConversationIDForTest, sessionCwd, "시각 아님", `"앞"`, ""),
		`not json at all`,
		conversationLineForTest("assistant", firstConversationIDForTest, sessionCwd, "2026-11-01T09:00:00Z", `[{"type":"text","text":"뒤"}]`, ""),
		`{"type":"assistant","message":{"content":[{"type":"text","text":"잘`)

	history := readConversationHistoryForTest(t, sessionCwd, firstConversationIDForTest, 200)

	assertConversationEntriesForTest(t, history.Entries, []ClaudeCodeConversationEntry{
		{Kind: ConversationEntryKindUser, Text: "앞"},
		{Kind: ConversationEntryKindAssistant, Text: "뒤", At: time.Date(2026, 11, 1, 9, 0, 0, 0, time.UTC)},
	})
}

func TestConversationHistoryRefusesWhatIsNotAConversationHere(t *testing.T) {
	sessionCwd := resolvedDirectoryForTest(t)
	otherCwd := resolvedDirectoryForTest(t)
	claudeConfigDirectory := claudeCodeHomeForTest(t)
	projectDirectoryName := projectDirectoryNameForTest(sessionCwd)
	writeTranscriptForTest(t, claudeConfigDirectory, projectDirectoryName, firstConversationIDForTest, time.Now(),
		conversationLineForTest("user", firstConversationIDForTest, sessionCwd, "2026-11-01T09:00:00Z", `"여기 대화"`, ""))
	// 이름이 겹친 다른 자리의 전사다 — 영숫자가 아닌 것을 모두 - 로 접으므로 생길 수 있다.
	writeTranscriptForTest(t, claudeConfigDirectory, projectDirectoryName, secondConversationIDForTest, time.Now(),
		conversationLineForTest("user", secondConversationIDForTest, otherCwd, "2026-11-01T09:00:00Z", `"다른 자리"`, ""))
	writeTranscriptForTest(t, claudeConfigDirectory, projectDirectoryName, thirdConversationIDForTest, time.Now(),
		conversationLineForTest("user", firstConversationIDForTest, sessionCwd, "2026-11-01T09:00:00Z", `"이름과 안이 다른 전사"`, ""))

	refusals := map[string]string{
		"uuid 가 아닌 값":          "not-a-conversation",
		"전사가 없는 id":            "00000000-0000-4000-8000-000000000000",
		"다른 cwd 의 전사":          secondConversationIDForTest,
		"안의 sessionId 가 다른 전사": thirdConversationIDForTest,
	}
	for name, conversationID := range refusals {
		if _, _, err := ReadClaudeCodeConversationHistory(sessionCwd, conversationID, 200); err == nil {
			t.Errorf("%s: ReadClaudeCodeConversationHistory(%s) = nil, 거절을 기대", name, conversationID)
		}
	}
	if _, _, err := ReadClaudeCodeConversationHistory(sessionCwd, firstConversationIDForTest, 200); err != nil {
		t.Errorf("이 자리의 대화를 거절했습니다: %v", err)
	}
}

func TestListClaudeCodeConversationsDoesNotTitleWithWhatClaudeCodeWroteOnThePersonsBehalf(t *testing.T) {
	// 목록의 제목과 지난 대화가 "사람이 쓴 글" 을 같은 규칙으로 가른다. isMeta 줄(다른 세션의 메시지 등)이
	// 제목이 되면 사람이 그 대화를 알아볼 수 없다.
	claudeConfigDirectory := claudeCodeHomeForTest(t)
	sessionCwd := resolvedDirectoryForTest(t)
	writeTranscriptForTest(t, claudeConfigDirectory, projectDirectoryNameForTest(sessionCwd), firstConversationIDForTest, time.Now(),
		conversationLineForTest("user", firstConversationIDForTest, sessionCwd, "2026-11-01T09:00:00Z", `"Base directory for this skill: /x"`, `"isMeta":true,`),
		conversationLineForTest("user", firstConversationIDForTest, sessionCwd, "2026-11-01T09:00:01Z", `"사람의 첫 글"`, ""))

	listing := listClaudeCodeConversationsForTest(t, t.TempDir(), sessionCwd)

	if len(listing.Conversations) != 1 || listing.Conversations[0].Title != "사람의 첫 글" {
		t.Errorf("대화 = %+v, 제목 \"사람의 첫 글\" 을 기대", listing.Conversations)
	}
}

func assertConversationEntriesForTest(t *testing.T, got, want []ClaudeCodeConversationEntry) {
	t.Helper()

	if len(got) != len(want) {
		t.Fatalf("항목 %d 개 = %+v, %d 개를 기대: %+v", len(got), got, len(want), want)
	}
	for index := range want {
		if !got[index].At.Equal(want[index].At) || got[index].Kind != want[index].Kind || got[index].Text != want[index].Text ||
			got[index].ToolName != want[index].ToolName || got[index].ToolSummary != want[index].ToolSummary ||
			got[index].OmittedCount != want[index].OmittedCount {
			t.Errorf("항목[%d] = %+v, %+v 를 기대", index, got[index], want[index])
		}
	}
}

func TestConversationHistorySkipsTheSyntheticAPIErrorLinesWithoutCountingThem(t *testing.T) {
	// 실측(claude 2.1.316): 사용량 한도 · API 오류 안내는 model "<synthetic>" 의 isApiErrorMessage 줄이다. 한 전사에
	// 수백 줄이 쌓이면 마지막 200 항목이 그 안내로만 채워진다.
	sessionCwd := resolvedDirectoryForTest(t)
	apiError := `"isApiErrorMessage":true,`
	writeHistoryTranscriptForTest(t, sessionCwd,
		conversationLineForTest("user", firstConversationIDForTest, sessionCwd, "2026-11-01T09:00:00Z", `"질문"`, ""),
		conversationLineForTest("assistant", firstConversationIDForTest, sessionCwd, "2026-11-01T09:00:01Z", `[{"type":"text","text":"You've hit your session limit"}]`, apiError),
		conversationLineForTest("assistant", firstConversationIDForTest, sessionCwd, "2026-11-01T09:00:02Z", `[{"type":"text","text":"API Error: 500"}]`, apiError))

	history := readConversationHistoryForTest(t, sessionCwd, firstConversationIDForTest, 200)

	assertConversationEntriesForTest(t, history.Entries, []ClaudeCodeConversationEntry{
		{Kind: ConversationEntryKindUser, Text: "질문", At: time.Date(2026, 11, 1, 9, 0, 0, 0, time.UTC)},
	})
	if history.TotalEntries != 1 {
		t.Errorf("전체 = %d, 합성 오류 줄은 세지 않아 1 을 기대", history.TotalEntries)
	}
}

func TestConversationHistoryAnswersInterruptionsAndSessionRelaysAsSystemLines(t *testing.T) {
	// 사람 자리의 줄이지만 사람이 쓴 글이 아닌 것 — claude 가 적은 중단 표지와, 이 도구가 PTY 로 넣는 세션 트리
	// 중계(상위에게 가는 "[하위 세션 <id> · …]", 하위에게 가는 "[상위 세션] "). 화면이 옅은 안내 줄로 그린다.
	sessionCwd := resolvedDirectoryForTest(t)
	at := func(second int) time.Time { return time.Date(2026, 11, 1, 9, 0, second, 0, time.UTC) }
	writeHistoryTranscriptForTest(t, sessionCwd,
		conversationLineForTest("user", firstConversationIDForTest, sessionCwd, "2026-11-01T09:00:00Z", `[{"type":"text","text":"[Request interrupted by user]"}]`, ""),
		conversationLineForTest("user", firstConversationIDForTest, sessionCwd, "2026-11-01T09:00:01Z",
			`[{"type":"tool_result","tool_use_id":"t1","content":"취소","is_error":true},{"type":"text","text":"[Request interrupted by user for tool use]\n"}]`, ""),
		conversationLineForTest("user", firstConversationIDForTest, sessionCwd, "2026-11-01T09:00:02Z", `"[하위 세션 child-1 · 턴 끝] 다 했습니다"`, ""),
		conversationLineForTest("user", firstConversationIDForTest, sessionCwd, "2026-11-01T09:00:03Z", `"[상위 세션] 포트는 5433 이다"`, ""),
		conversationLineForTest("user", firstConversationIDForTest, sessionCwd, "2026-11-01T09:00:04Z", `"[Request interrupted by user] 라는 문구를 왜 보나요?"`, ""))

	history := readConversationHistoryForTest(t, sessionCwd, firstConversationIDForTest, 200)

	assertConversationEntriesForTest(t, history.Entries, []ClaudeCodeConversationEntry{
		{Kind: ConversationEntryKindSystem, Text: "[Request interrupted by user]", At: at(0)},
		{Kind: ConversationEntryKindSystem, Text: "[Request interrupted by user for tool use]", At: at(1)},
		{Kind: ConversationEntryKindSystem, Text: "[하위 세션 child-1 · 턴 끝] 다 했습니다", At: at(2)},
		{Kind: ConversationEntryKindSystem, Text: "[상위 세션] 포트는 5433 이다", At: at(3)},
		// 중단 표지는 글 전체가 그것일 때만이다 — 사람이 그 문구를 인용할 수 있다.
		{Kind: ConversationEntryKindUser, Text: "[Request interrupted by user] 라는 문구를 왜 보나요?", At: at(4)},
	})
}

func TestConversationHistoryAnswersEveryTextBlockAPersonWrote(t *testing.T) {
	// 제목은 첫 글의 앞부분이면 되지만, 지난 대화는 전문이다. 붙여 넣은 글은 text 블록 여럿으로 온다.
	sessionCwd := resolvedDirectoryForTest(t)
	writeHistoryTranscriptForTest(t, sessionCwd,
		conversationLineForTest("user", firstConversationIDForTest, sessionCwd, "2026-11-01T09:00:00Z",
			`[{"type":"text","text":"<system-reminder>주입</system-reminder>"},{"type":"text","text":"앞 글"},{"type":"image","source":{}},{"type":"text","text":"뒤 글"}]`, ""))

	history := readConversationHistoryForTest(t, sessionCwd, firstConversationIDForTest, 200)

	assertConversationEntriesForTest(t, history.Entries, []ClaudeCodeConversationEntry{
		{Kind: ConversationEntryKindUser, Text: "앞 글\n\n뒤 글", At: time.Date(2026, 11, 1, 9, 0, 0, 0, time.UTC)},
	})
}

func TestConversationHistoryWarnsAboutATranscriptNamingAnotherConversationAndReadsTheNextCandidate(t *testing.T) {
	// 목록과 같은 자세다 — 이름과 안이 다른 전사는 사용자가 알아야 할 이상이지만, 같은 id 의 다른 후보를 막을 까닭은 없다.
	claudeConfigDirectory := claudeCodeHomeForTest(t)
	sessionCwd := filepath.Join(resolvedDirectoryForTest(t), strings.Repeat("a", 210))
	if err := os.MkdirAll(sessionCwd, 0o755); err != nil {
		t.Fatalf("디렉토리 생성 실패: %v", err)
	}
	hashedNamePrefix := projectDirectoryNameForTest(sessionCwd)[:200]
	writeTranscriptForTest(t, claudeConfigDirectory, hashedNamePrefix+"-aaa111", firstConversationIDForTest, time.Now(),
		conversationLineForTest("user", secondConversationIDForTest, sessionCwd, "2026-11-01T09:00:00Z", `"엇갈린 전사"`, ""))
	writeTranscriptForTest(t, claudeConfigDirectory, hashedNamePrefix+"-bbb222", firstConversationIDForTest, time.Now(),
		conversationLineForTest("user", firstConversationIDForTest, sessionCwd, "2026-11-01T09:00:00Z", `"맞는 전사"`, ""))

	history, warnings, err := ReadClaudeCodeConversationHistory(sessionCwd, firstConversationIDForTest, 200)

	if err != nil {
		t.Fatalf("ReadClaudeCodeConversationHistory() 실패: %v", err)
	}
	if len(history.Entries) != 1 || history.Entries[0].Text != "맞는 전사" {
		t.Errorf("항목 = %+v, 다음 후보의 대화를 기대", history.Entries)
	}
	if len(warnings) != 1 || !strings.Contains(warnings[0], secondConversationIDForTest) {
		t.Errorf("경고 = %q, 엇갈린 sessionId 를 말하는 경고 하나를 기대", warnings)
	}
}

func TestConversationHistorySaysWhenTheTranscriptIsThereButEmpty(t *testing.T) {
	// 파일은 있는데 대화 줄이 없으면 자리를 확인할 수 없다. "그 자리에 없다" 로 접으면 사람이 엉뚱한 곳을 찾는다.
	sessionCwd := resolvedDirectoryForTest(t)
	writeHistoryTranscriptForTest(t, sessionCwd, `{"type":"file-history-snapshot","messageId":"m-1","snapshot":{}}`)

	_, _, err := ReadClaudeCodeConversationHistory(sessionCwd, firstConversationIDForTest, 200)

	if err == nil || !strings.Contains(err.Error(), "비어") || strings.Contains(err.Error(), "대화 중에") {
		t.Errorf("err = %v, 전사가 비어 자리를 확인할 수 없다는 거절을 기대", err)
	}
}

func TestListClaudeCodeConversationsDoesNotTitleWithInterruptionsOrSessionRelays(t *testing.T) {
	// 지난 대화에서 system 으로 보이는 줄은 목록의 제목도 되지 않는다 — "사람이 쓴 글" 판정이 한 곳이다.
	claudeConfigDirectory := claudeCodeHomeForTest(t)
	sessionCwd := resolvedDirectoryForTest(t)
	writeTranscriptForTest(t, claudeConfigDirectory, projectDirectoryNameForTest(sessionCwd), firstConversationIDForTest, time.Now(),
		conversationLineForTest("user", firstConversationIDForTest, sessionCwd, "2026-11-01T09:00:00Z", `"[상위 세션] 이 일을 해라"`, ""),
		conversationLineForTest("user", firstConversationIDForTest, sessionCwd, "2026-11-01T09:00:01Z", `[{"type":"text","text":"[Request interrupted by user]"}]`, ""),
		conversationLineForTest("user", firstConversationIDForTest, sessionCwd, "2026-11-01T09:00:02Z", `"[하위 세션 child-1 · 질문] 어느 포트?"`, ""),
		conversationLineForTest("user", firstConversationIDForTest, sessionCwd, "2026-11-01T09:00:03Z", `[{"type":"text","text":"사람의 첫 글"},{"type":"text","text":"둘째 블록"}]`, ""))

	listing := listClaudeCodeConversationsForTest(t, t.TempDir(), sessionCwd)

	if len(listing.Conversations) != 1 || listing.Conversations[0].Title != "사람의 첫 글" {
		t.Errorf("대화 = %+v, 제목 \"사람의 첫 글\" 을 기대", listing.Conversations)
	}
}
