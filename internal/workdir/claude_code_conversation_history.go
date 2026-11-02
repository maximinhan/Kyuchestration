package workdir

import (
	"bufio"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"strings"
	"time"
)

// 이 파일은 Claude Code 대화 하나의 지난 내용을 읽는다 — 앱이 이어가는 대화의 채팅 창 앞에 깔 것.
//
// --resume 은 claude 의 맥락은 이어가지만 화면에는 아무것도 다시 그리지 않는다. 그래서 세션을 연 사람은
// 그 대화가 무엇이었는지 볼 수 없었다. 전사를 읽는 것이 엔진인 이유는 목록과 같다 — 전사의 모양을 아는
// 자리를 하나로 두고, 앱은 이 파일이 답하는 모양만 안다(app-owned-sessions-design.md 5.5.8).

// ConversationEntryKind 는 지난 대화 한 항목의 종류다.
type ConversationEntryKind string

const (
	// ConversationEntryKindUser 는 사람이 쓴 글이다 — 목록의 제목과 같은 규칙으로 가른다(userTextBlocks).
	ConversationEntryKindUser ConversationEntryKind = "user"

	// ConversationEntryKindSystem 은 사람 자리에 들어왔지만 사람이 쓰지 않은 안내다 — claude 의 중단 표지
	// ("[Request interrupted by user]")와 이 도구의 세션 트리 중계("[하위 세션 <id> · …]", "[상위 세션] …").
	// 화면이 사람의 말과 다른 옅은 줄로 그릴 수 있게 따로 둔다.
	ConversationEntryKindSystem ConversationEntryKind = "system"

	// ConversationEntryKindAssistant 는 claude 가 쓴 글이다. 생각(thinking)은 넣지 않는다 — 터미널도 접어 보인다.
	ConversationEntryKindAssistant ConversationEntryKind = "assistant"

	// ConversationEntryKindToolUse 는 claude 가 부른 도구 하나다. 결과는 넣지 않는다 — 크고, 사람이 대화를 다시
	// 읽을 때 필요한 것은 무엇을 했는지이지 그 출력 전부가 아니다.
	ConversationEntryKindToolUse ConversationEntryKind = "tool_use"

	// ConversationEntryKindOmitted 는 연달아 건너뛴 줄들의 자리다(하위 에이전트의 줄). 앞뒤 글이 끊김 없이
	// 이어진 것처럼 보이지 않게 한 항목으로 남긴다.
	ConversationEntryKindOmitted ConversationEntryKind = "omitted"
)

// toolUseSummaryRuneLimit 는 도구 한 번을 사람이 알아볼 한 줄의 최대 글자 수다.
const toolUseSummaryRuneLimit = 120

// toolUseSummaryInputKeys 는 도구 입력에서 한 줄 요약으로 쓸 자리를 찾는 차례다.
//
// 실측(claude 2.1.316): Bash 는 command 와 description 을 함께 갖는데, 무엇을 돌렸는지는 command 가 정확하다.
// Read · Edit · Write 는 file_path, Grep · Glob 은 pattern, Agent 는 description 이다.
var toolUseSummaryInputKeys = []string{"command", "file_path", "pattern", "description"}

// ClaudeCodeConversationEntry 는 지난 대화의 한 항목이다. 종류에 따라 쓰는 자리가 다르다.
type ClaudeCodeConversationEntry struct {
	Kind ConversationEntryKind

	// Text 는 user · system · assistant 의 글이다.
	Text string

	// ToolName 과 ToolSummary 는 tool_use 의 도구 이름과 한 줄 요약이다. 요약할 자리가 없으면 빈 문자열이다.
	ToolName    string
	ToolSummary string

	// At 은 그 줄의 시각이다. 알 수 없거나 omitted 면 영값이다.
	At time.Time

	// OmittedCount 는 omitted 가 대신하는 전사 줄의 수다.
	OmittedCount int
}

// ClaudeCodeConversationHistory 는 대화 하나의 마지막 항목들이다.
type ClaudeCodeConversationHistory struct {
	ConversationID string

	// TotalEntries 는 전사 전체의 항목 수다 — omitted 항목도 하나로 센다.
	TotalEntries int

	// Entries 는 마지막 항목들이고 오래된 것부터다. 없으면 nil 이 아니라 빈 조각이다. 그 앞에서 답하지 않은 수는
	// TotalEntries 에서 이 길이를 뺀 것이다.
	Entries []ClaudeCodeConversationEntry
}

// 이름으로 찾은 후보를 읽고 나서 그것이 답할 전사가 아닐 때의 까닭들이다. 셋 다 다음 후보를 본다.
var (
	// errTranscriptOfAnotherPlace 는 안에 적힌 cwd 가 이 자리가 아니라는 뜻이다 — 이름이 겹친 다른 자리의 정상 전사다.
	errTranscriptOfAnotherPlace = errors.New("다른 자리의 전사")

	// errTranscriptWithoutConversationLines 는 cwd 가 적힌 대화 줄이 하나도 없어 자리를 확인할 수 없다는 뜻이다.
	errTranscriptWithoutConversationLines = errors.New("대화 줄이 없는 전사")
)

// transcriptNamingAnotherConversationError 는 줄의 sessionId 가 파일 이름과 다른 전사다. --resume <파일 이름> 이 무엇을
// 여는지 알 수 없는 이상이라 사용자에게 경고로 알리고 다음 후보를 본다 — 목록과 같은 자세다(readClaudeCodeTranscript).
type transcriptNamingAnotherConversationError struct {
	transcriptPath    string
	recordedSessionID string
}

func (err transcriptNamingAnotherConversationError) Error() string {
	return fmt.Sprintf("Claude Code 전사의 이름과 안의 sessionId(%s)가 달라 읽지 않습니다 (%s)", err.recordedSessionID, err.transcriptPath)
}

// ReadClaudeCodeConversationHistory 는 sessionCwd 에서 나눈 대화 conversationID 의 마지막 lastEntryLimit 개 항목을 읽는다.
//
// 아무것도 적지 않는 조회다. 위임 · 하위 세션의 대화도 답한다 — 목록이 그것을 빼는 이유는 채택하면 한 대화를
// 둘이 쓰기 때문인데(ListClaudeCodeConversations), 읽기에는 그 위험이 없다.
//
// 전사를 찾는 규칙은 목록 · 채택과 같다(claudeCodeTranscriptPathsOf). 이름으로 찾은 후보마다 안의 cwd 로 이 자리의
// 것인지 확인한다 — 앱은 이 답을 그 자리에서 이어가는 대화의 앞에 깐다. 줄의 sessionId 가 파일 이름과 다른 후보는
// 경고로 알리고 넘어간다. warnings 는 실패해도 함께 돌려준다 — 실패의 까닭이 그 경고일 수 있다.
func ReadClaudeCodeConversationHistory(sessionCwd, conversationID string, lastEntryLimit int) (history ClaudeCodeConversationHistory, warnings []string, err error) {
	if !IsConversationID(conversationID) {
		return ClaudeCodeConversationHistory{}, nil, fmt.Errorf("%q 는 Claude Code 대화 ID 모양(uuid)이 아닙니다", conversationID)
	}
	if lastEntryLimit < 1 {
		return ClaudeCodeConversationHistory{}, nil, fmt.Errorf("마지막 항목 수는 1 이상이어야 합니다 (%d 를 받음)", lastEntryLimit)
	}

	resolvedSessionCwd, transcriptPaths, err := claudeCodeTranscriptPathsOf(sessionCwd, conversationID)
	if err != nil {
		return ClaudeCodeConversationHistory{}, nil, err
	}
	var transcriptsWithoutConversationLines []string
	for _, transcriptPath := range transcriptPaths {
		history, err := readClaudeCodeConversationHistory(transcriptPath, conversationID, resolvedSessionCwd, lastEntryLimit)
		var namingAnotherConversation transcriptNamingAnotherConversationError
		switch {
		case err == nil:
			return history, warnings, nil
		case errors.Is(err, errTranscriptOfAnotherPlace):
			continue
		case errors.Is(err, errTranscriptWithoutConversationLines):
			transcriptsWithoutConversationLines = append(transcriptsWithoutConversationLines, transcriptPath)
		case errors.As(err, &namingAnotherConversation):
			warnings = append(warnings, namingAnotherConversation.Error())
		default:
			return ClaudeCodeConversationHistory{}, warnings, err
		}
	}

	// 파일은 있는데 자리를 확인하지 못한 것과, 그 자리에 그 대화가 없는 것은 사람이 할 일이 다르다.
	if len(transcriptsWithoutConversationLines) > 0 {
		return ClaudeCodeConversationHistory{}, warnings, fmt.Errorf(
			"%s 의 전사가 비어 있어 이 자리(%s)의 대화인지 확인할 수 없습니다 (%s)",
			conversationID, sessionCwd, strings.Join(transcriptsWithoutConversationLines, ", "))
	}
	return ClaudeCodeConversationHistory{}, warnings, fmt.Errorf(
		"%s 에서 Claude Code 로 나눈 대화 중에 %s 가 없습니다 — kyu conversations list 로 그 자리의 대화를 확인하세요",
		sessionCwd, conversationID)
}

// readClaudeCodeConversationHistory 는 전사 하나를 줄 단위로 읽어 마지막 항목들로 옮긴다.
//
// 전사는 수십 MB 가 흔하다(이 저장소의 대화 하나가 16MB). 줄을 하나씩 읽고 항목은 마지막 lastEntryLimit 개만
// 고리에 남겨, 전사 크기와 상관없이 메모리는 답하는 항목만큼이다. 목록 리더처럼 대화 줄만 디코드하고 깨진
// 줄은 건너뛴다 — 쓰는 중인 전사의 마지막 줄은 잘려 있을 수 있다.
func readClaudeCodeConversationHistory(transcriptPath, conversationID, resolvedSessionCwd string, lastEntryLimit int) (ClaudeCodeConversationHistory, error) {
	transcriptFile, err := os.Open(transcriptPath)
	if err != nil {
		return ClaudeCodeConversationHistory{}, fmt.Errorf("Claude Code 전사를 열지 못했습니다 (%s): %w", transcriptPath, err)
	}
	defer transcriptFile.Close()

	ring := newConversationEntryRing(lastEntryLimit)
	recordedCwd := ""
	omittedLineCount := 0
	pushEntry := func(entry ClaudeCodeConversationEntry) {
		if omittedLineCount > 0 {
			ring.push(ClaudeCodeConversationEntry{Kind: ConversationEntryKindOmitted, OmittedCount: omittedLineCount})
			omittedLineCount = 0
		}
		ring.push(entry)
	}

	lineReader := bufio.NewReader(transcriptFile)
	for {
		lineBytes, readErr := lineReader.ReadBytes('\n')
		if isRelevantTranscriptLine(lineBytes) {
			var line claudeCodeTranscriptLine
			if json.Unmarshal(lineBytes, &line) == nil && (line.Type == "user" || line.Type == "assistant") {
				if line.SessionID != "" && line.SessionID != conversationID {
					return ClaudeCodeConversationHistory{}, transcriptNamingAnotherConversationError{
						transcriptPath: transcriptPath, recordedSessionID: line.SessionID}
				}
				if recordedCwd == "" && line.Cwd != "" {
					recordedCwd = line.Cwd
					if !cwdsNameTheSamePlace(recordedCwd, resolvedSessionCwd, fileSystemIgnoresCase) {
						return ClaudeCodeConversationHistory{}, errTranscriptOfAnotherPlace
					}
				}

				switch {
				case line.IsAPIErrorMessage:
					// omitted 로도 세지 않는다 — 대화에서 빠진 것이 아니라 대화가 아니다.
				case line.IsSidechain:
					omittedLineCount++
				default:
					for _, entry := range conversationEntriesOf(line) {
						pushEntry(entry)
					}
				}
			}
		}
		if errors.Is(readErr, io.EOF) {
			break
		}
		if readErr != nil {
			return ClaudeCodeConversationHistory{}, fmt.Errorf("Claude Code 전사를 끝까지 읽지 못했습니다 (%s): %w", transcriptPath, readErr)
		}
	}

	// cwd 가 끝내 적히지 않은 전사는 이 자리의 것인지 확인할 수 없다 — 목록도 같은 전사를 뺀다.
	if recordedCwd == "" {
		return ClaudeCodeConversationHistory{}, errTranscriptWithoutConversationLines
	}
	if omittedLineCount > 0 {
		ring.push(ClaudeCodeConversationEntry{Kind: ConversationEntryKindOmitted, OmittedCount: omittedLineCount})
	}
	return ClaudeCodeConversationHistory{ConversationID: conversationID, TotalEntries: ring.total, Entries: ring.inOrder()}, nil
}

// conversationEntriesOf 는 user · assistant 한 줄을 항목들로 옮긴다. 보일 것이 없는 줄은 nil 이다.
//
// assistant 의 블록 차례를 지킨다. 이어진 text 블록은 한 글로 잇고, tool_use 는 블록마다 한 항목이다.
// 실측(claude 2.1.316)에서는 한 줄에 블록이 하나라 대개 줄 하나가 항목 하나다.
func conversationEntriesOf(line claudeCodeTranscriptLine) []ClaudeCodeConversationEntry {
	at := time.Time{}
	if parsedAt, err := time.Parse(time.RFC3339Nano, line.Timestamp); err == nil {
		at = parsedAt.UTC()
	}

	if line.Type == "user" {
		// 제목과 달리 전문이다 — 붙여 넣은 글은 text 블록 여럿으로 온다.
		switch author, texts := line.userTextBlocks(); author {
		case userLineByPerson:
			return []ClaudeCodeConversationEntry{{Kind: ConversationEntryKindUser, Text: strings.Join(texts, "\n\n"), At: at}}
		case userLineBySystem:
			return []ClaudeCodeConversationEntry{{Kind: ConversationEntryKindSystem, Text: strings.Join(texts, "\n\n"), At: at}}
		default:
			return nil
		}
	}
	if line.Message == nil {
		return nil
	}

	var plainText string
	if json.Unmarshal(line.Message.Content, &plainText) == nil {
		if text := strings.TrimSpace(plainText); text != "" {
			return []ClaudeCodeConversationEntry{{Kind: ConversationEntryKindAssistant, Text: text, At: at}}
		}
		return nil
	}

	var blocks []struct {
		Type  string                     `json:"type"`
		Text  string                     `json:"text"`
		Name  string                     `json:"name"`
		Input map[string]json.RawMessage `json:"input"`
	}
	if json.Unmarshal(line.Message.Content, &blocks) != nil {
		return nil
	}
	var entries []ClaudeCodeConversationEntry
	var pendingTexts []string
	flushPendingTexts := func() {
		if len(pendingTexts) > 0 {
			entries = append(entries, ClaudeCodeConversationEntry{Kind: ConversationEntryKindAssistant, Text: strings.Join(pendingTexts, "\n\n"), At: at})
			pendingTexts = nil
		}
	}
	for _, block := range blocks {
		switch block.Type {
		case "text":
			if text := strings.TrimSpace(block.Text); text != "" {
				pendingTexts = append(pendingTexts, text)
			}
		case "tool_use":
			flushPendingTexts()
			entries = append(entries, ClaudeCodeConversationEntry{
				Kind: ConversationEntryKindToolUse, ToolName: block.Name, ToolSummary: toolUseSummaryOf(block.Input), At: at})
		}
	}
	flushPendingTexts()
	return entries
}

// toolUseSummaryOf 는 도구 입력에서 사람이 알아볼 한 줄이다. 쓸 자리가 없으면 빈 문자열이다.
func toolUseSummaryOf(input map[string]json.RawMessage) string {
	for _, key := range toolUseSummaryInputKeys {
		var value string
		if json.Unmarshal(input[key], &value) != nil {
			continue
		}
		if summary := FirstRunesOnOneLine(value, toolUseSummaryRuneLimit); summary != "" {
			return summary
		}
	}
	return ""
}

// conversationEntryRing 은 마지막 limit 개 항목만 남기는 고리다. 그 앞의 것은 수만 센다.
//
// limit 만큼 미리 잡지 않는다 — --last 가 크고 전사가 짧으면 쓰지 않을 자리를 잡는다.
type conversationEntryRing struct {
	entries []ClaudeCodeConversationEntry
	limit   int
	next    int
	total   int
}

func newConversationEntryRing(limit int) *conversationEntryRing {
	return &conversationEntryRing{entries: []ClaudeCodeConversationEntry{}, limit: limit}
}

func (ring *conversationEntryRing) push(entry ClaudeCodeConversationEntry) {
	ring.total++
	if len(ring.entries) < ring.limit {
		ring.entries = append(ring.entries, entry)
		return
	}
	ring.entries[ring.next] = entry
	ring.next = (ring.next + 1) % ring.limit
}

func (ring *conversationEntryRing) inOrder() []ClaudeCodeConversationEntry {
	return append(append([]ClaudeCodeConversationEntry{}, ring.entries[ring.next:]...), ring.entries[:ring.next]...)
}
