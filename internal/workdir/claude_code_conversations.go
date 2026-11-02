package workdir

import (
	"bufio"
	"bytes"
	"cmp"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"regexp"
	"runtime"
	"slices"
	"strings"
	"time"
	"unicode/utf16"
)

// 이 파일은 Claude Code 가 남긴 대화를 읽는다 — 사용자가 터미널에서 claude 로 같은 폴더에서 해 온 대화.
//
// conversations.json 은 이 도구가 정해 준 대화만 안다. 사용자가 앱을 쓰기 전부터 그 폴더에서 claude 로
// 일해 왔다면 그 대화는 Claude Code 의 전사에만 있고, 앱이 폴더를 열 때 그것을 이어갈 길이 없었다.
// 이 파일이 그 전사를 찾아 목록으로 답하고(ListClaudeCodeConversations), 그중 하나를 라벨의 대화로
// 적는다(AdoptClaudeCodeConversation). 이어가기 자체는 전과 같이 --resume 하나다.
//
// 전사 자리는 Claude Code 의 살림이지 약속이 아니다(app-owned-sessions-design.md 5.5.1). 그래도 여기
// 기대는 이유와, 어긋났을 때 무엇이 되는지는 그 문서 5.5.6 에 적었다 — 어긋나면 목록이 비고, 이어가던
// 대화는 그대로 이어진다.

// claudeConfigDirectoryEnvironmentVariable 은 Claude Code 가 ~/.claude 대신 쓰는 자리를 가리키는 변수다.
//
// 앱이 세션에 이 변수를 주면 claude 는 그 아래에 전사를 남긴다. kyu 는 앱과 같은 환경에서 불리므로
// 같은 값을 보고 같은 자리를 읽는다.
const claudeConfigDirectoryEnvironmentVariable = "CLAUDE_CONFIG_DIR"

// claudeCodeProjectDirectoryNameLimit 은 전사 디렉토리 이름이 그대로 쓰이는 최대 길이다.
//
// 실측(claude 2.1.316, 2026-11-01): 인코딩한 이름이 200 자를 넘으면 앞 200 자 뒤에 "-<해시>" 가 붙는다.
// 269 자 경로에서 "-903mh7" 이 붙었고, 그 값은 원래 경로의 UTF-16 단위로 센 Java String.hashCode 의
// 절댓값을 36 진수로 쓴 것과 같았다.
const claudeCodeProjectDirectoryNameLimit = 200

// conversationTitleRuneLimit 은 첫 사용자 글로 제목을 지을 때 남기는 글자 수다.
const conversationTitleRuneLimit = 80

// conversationIDShape 는 --resume 이 받는 대화 ID 의 모양이다. 전사 파일 이름이 이 꼴이 아니면 대화가 아니다.
var conversationIDShape = regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`)

// IsConversationID 는 값이 claude 가 대화 ID 로 받는 모양인지다. 채택할 id 를 받는 자리가 같은 모양을 본다.
func IsConversationID(value string) bool {
	return conversationIDShape.MatchString(value)
}

// ClaudeCodeConversation 은 한 자리에서 Claude Code 로 나눈 대화 하나다.
type ClaudeCodeConversation struct {
	// ID 는 전사 파일 이름의 uuid 다 — claude 에게 --resume 으로 넘길 값이다.
	ID string

	// Title 은 사람이 그 대화를 알아볼 이름이다. Claude Code 가 지은 제목이 있으면 그것이고, 없으면
	// 사람이 쓴 첫 글의 앞부분이다.
	Title string

	// StartedAt 은 첫 메시지의 시각이다. 알 수 없으면 영값이다.
	StartedAt time.Time

	// LastActiveAt 은 전사 파일이 마지막으로 바뀐 시각이다.
	LastActiveAt time.Time

	// RecordedFor 는 이 대화가 conversations.json 의 세션 지도에서 어느 라벨에 적혀 있는지다. 없으면 비어 있다.
	RecordedFor string
}

// ClaudeCodeConversationListing 은 한 자리의 대화 목록과, 읽다가 버린 것에 대한 경고다.
type ClaudeCodeConversationListing struct {
	// Conversations 는 마지막 활동이 늦은 것부터다. 없으면 nil 이 아니라 빈 조각이다.
	Conversations []ClaudeCodeConversation

	Warnings []string
}

// ListClaudeCodeConversations 는 sessionCwd 에서 Claude Code 로 나눈 대화를 모은다.
//
// 아무것도 적지 않는 조회다. 전사 디렉토리가 없는 것은 그 자리에서 claude 를 돌린 적이 없다는 정상
// 상태라 빈 목록이다.
//
// 이 도구의 다른 대화(위임 · 하위 세션)는 빼고 답한다. 같은 자리에서 돈 claude 라 전사는 같은 디렉토리에
// 있지만, 그것을 세션이 채택하면 하나의 UUID 를 둘이 써서 뒤에 오는 쪽이 already in use 로 거절된다
// (orchestration-tools-design.md 5.5.1).
func ListClaudeCodeConversations(workDirPath, sessionCwd string) (ClaudeCodeConversationListing, error) {
	records, _, err := readConversations(workDirPath)
	if err != nil {
		return ClaudeCodeConversationListing{}, err
	}

	resolvedSessionCwd, projectDirectories, err := claudeCodeTranscriptPlacesFor(sessionCwd)
	if err != nil {
		return ClaudeCodeConversationListing{}, err
	}

	listing := ClaudeCodeConversationListing{Conversations: []ClaudeCodeConversation{}}
	conversationsOfThisTool := records.conversationIDsOfThisTool()
	labelByConversationID := map[string]string{}
	for label, conversationID := range records.sessions {
		labelByConversationID[conversationID] = label
	}

	for _, projectDirectory := range projectDirectories {
		entries, err := os.ReadDir(projectDirectory)
		if err != nil {
			return ClaudeCodeConversationListing{}, fmt.Errorf("Claude Code 전사 디렉토리 읽기 실패 (%s): %w", projectDirectory, err)
		}

		for _, entry := range entries {
			conversationID, isTranscript := strings.CutSuffix(entry.Name(), ".jsonl")
			if !isTranscript || entry.IsDir() || !IsConversationID(conversationID) || conversationsOfThisTool[conversationID] {
				continue
			}

			transcriptPath := filepath.Join(projectDirectory, entry.Name())
			conversation, belongsHere, warning := readClaudeCodeTranscript(transcriptPath, conversationID, resolvedSessionCwd)
			if warning != "" {
				listing.Warnings = append(listing.Warnings, warning)
			}
			if !belongsHere {
				continue
			}
			conversation.RecordedFor = labelByConversationID[conversationID]
			listing.Conversations = append(listing.Conversations, conversation)
		}
	}

	slices.SortFunc(listing.Conversations, func(left, right ClaudeCodeConversation) int {
		if byRecency := right.LastActiveAt.Compare(left.LastActiveAt); byRecency != 0 {
			return byRecency
		}
		return cmp.Compare(left.ID, right.ID)
	})
	return listing, nil
}

// AdoptClaudeCodeConversation 은 그 자리의 Claude Code 대화 하나를 이 라벨의 대화로 적는다.
//
// 적힌 것이 없어도, 다른 대화가 적혀 있어도 이 id 로 바꾼다 — 사람이 고른 것이다. 이미 같은 id 면 쓰지 않는다.
//
// 전사가 그 자리에 실제로 있는지를 목록과 같은 읽기(readClaudeCodeTranscript)로 확인하고, 없으면 거절한다.
// 없는 id 를 적으면 다음 실행의 --resume 이 종료 코드 1 로 끝나는데(설계 문서 5.5.4), 그 실패는 적는 지금
// 막을 수 있다. 목록을 통째로 다시 만들지 않고 그 id 의 전사 하나만 읽는다 — 확인할 것이 그 하나뿐이다.
func AdoptClaudeCodeConversation(workDirPath, label, sessionCwd, conversationID string) (ConversationAssignment, error) {
	records, warnings, err := readConversations(workDirPath)
	if err != nil {
		return ConversationAssignment{}, err
	}
	if records.conversationIDsOfThisTool()[conversationID] {
		return ConversationAssignment{}, fmt.Errorf(
			"%s 는 이 워크디렉토리의 위임 · 하위 세션 대화라 세션이 채택할 수 없습니다 (%s) — 한 대화를 둘이 쓰면 뒤에 연 쪽이 거절됩니다",
			conversationID, conversationsFilePath(workDirPath))
	}

	resolvedSessionCwd, transcriptPaths, err := claudeCodeTranscriptPathsOf(sessionCwd, conversationID)
	if err != nil {
		return ConversationAssignment{}, err
	}
	transcriptFound := false
	for _, transcriptPath := range transcriptPaths {
		_, belongsHere, warning := readClaudeCodeTranscript(transcriptPath, conversationID, resolvedSessionCwd)
		if warning != "" {
			warnings = append(warnings, warning)
		}
		if belongsHere {
			transcriptFound = true
			break
		}
	}
	if !transcriptFound {
		return ConversationAssignment{}, fmt.Errorf(
			"%s 에서 Claude Code 로 나눈 대화 중에 %s 가 없습니다 — kyu conversations list 로 고를 수 있는 대화를 확인하세요",
			sessionCwd, conversationID)
	}

	if records.sessions[label] != conversationID {
		records.sessions[label] = conversationID
		if err := writeConversations(workDirPath, records); err != nil {
			return ConversationAssignment{}, err
		}
	}
	return ConversationAssignment{ConversationID: conversationID, Warnings: warnings}, nil
}

// conversationIDsOfThisTool 은 세션이 아닌 이 도구의 대화(위임 · 하위 세션)의 ID 들이다.
func (records conversationRecords) conversationIDsOfThisTool() map[string]bool {
	conversationIDs := map[string]bool{}
	for _, conversationID := range records.delegations {
		conversationIDs[conversationID] = true
	}
	for _, childrenOfParent := range records.childSessions {
		for _, child := range childrenOfParent {
			conversationIDs[child.ConversationID] = true
		}
	}
	return conversationIDs
}

// claudeCodeTranscriptPathsOf 는 그 대화의 전사일 수 있는 파일들과, 세션 cwd 의 링크를 편 값이다.
//
// 이름만으로 찾은 후보다. 그 자리의 것인지는 읽는 쪽이 안의 cwd 로 다시 확인한다 — 이름은 서로 다른 cwd 가
// 겹칠 수 있다(claudeCodeProjectDirectoryName).
func claudeCodeTranscriptPathsOf(sessionCwd, conversationID string) (resolvedSessionCwd string, transcriptPaths []string, err error) {
	resolvedSessionCwd, projectDirectories, err := claudeCodeTranscriptPlacesFor(sessionCwd)
	if err != nil {
		return "", nil, err
	}
	for _, projectDirectory := range projectDirectories {
		transcriptPath := filepath.Join(projectDirectory, conversationID+".jsonl")
		if _, err := os.Stat(transcriptPath); errors.Is(err, os.ErrNotExist) {
			continue
		}
		transcriptPaths = append(transcriptPaths, transcriptPath)
	}
	return resolvedSessionCwd, transcriptPaths, nil
}

// claudeCodeTranscriptPlacesFor 는 세션 cwd 의 링크를 편 값과, 그 cwd 의 전사가 있을 수 있는 디렉토리들이다.
//
// 링크를 편다. claude 는 편 경로로 전사 자리를 정하고 그 경로를 cwd 로 적는다(실측) — 맥의 /tmp 처럼
// 링크 아래의 자리에서 편 값을 쓰지 않으면 디렉토리도 못 찾고 cwd 비교도 늘 어긋난다.
func claudeCodeTranscriptPlacesFor(sessionCwd string) (resolvedSessionCwd string, projectDirectories []string, err error) {
	resolvedSessionCwd, err = filepath.EvalSymlinks(sessionCwd)
	if err != nil {
		return "", nil, fmt.Errorf("세션 자리의 링크를 펴지 못했습니다 (%s): %w", sessionCwd, err)
	}
	projectDirectories, err = claudeCodeProjectDirectoriesFor(resolvedSessionCwd)
	if err != nil {
		return "", nil, err
	}
	return resolvedSessionCwd, projectDirectories, nil
}

// claudeCodeProjectDirectoriesFor 는 그 cwd 의 전사가 있을 수 있는 디렉토리들이다. 없는 것은 답하지 않는다.
//
// 이름이 길면 꼬리의 해시를 다시 만들지 않고 앞 200 자로 찾는다. 해시는 claude 의 구현 세부라 판이나
// 실행 환경이 바뀌면 다를 수 있는데, 앞자리로 모은 뒤 전사 안의 cwd 로 다시 확인하면 꼬리가 무엇이든
// 맞는 대화만 남는다.
//
// 케이스를 무시하는 파일 시스템에서는 앞자리 비교도 케이스를 무시한다. 짧은 이름의 Stat 은 그 파일 시스템이
// 이미 케이스를 무시해 주지만, 앞자리 비교는 이 코드가 하는 문자열 비교라 그렇지 않다.
func claudeCodeProjectDirectoriesFor(resolvedSessionCwd string) ([]string, error) {
	projectsDirectory, err := claudeCodeProjectsDirectory()
	if err != nil {
		return nil, err
	}

	directoryName := claudeCodeProjectDirectoryName(resolvedSessionCwd)
	if len(directoryName) <= claudeCodeProjectDirectoryNameLimit {
		projectDirectory := filepath.Join(projectsDirectory, directoryName)
		if _, err := os.Stat(projectDirectory); errors.Is(err, os.ErrNotExist) {
			return nil, nil
		} else if err != nil {
			return nil, fmt.Errorf("Claude Code 전사 디렉토리 확인 실패 (%s): %w", projectDirectory, err)
		}
		return []string{projectDirectory}, nil
	}

	entries, err := os.ReadDir(projectsDirectory)
	if errors.Is(err, os.ErrNotExist) {
		return nil, nil
	}
	if err != nil {
		return nil, fmt.Errorf("Claude Code 전사 디렉토리 읽기 실패 (%s): %w", projectsDirectory, err)
	}
	hashedNamePrefix := directoryName[:claudeCodeProjectDirectoryNameLimit] + "-"
	var projectDirectories []string
	for _, entry := range entries {
		if entry.IsDir() && directoryNameStartsWith(entry.Name(), hashedNamePrefix, fileSystemIgnoresCase) {
			projectDirectories = append(projectDirectories, filepath.Join(projectsDirectory, entry.Name()))
		}
	}
	return projectDirectories, nil
}

// fileSystemIgnoresCase 는 이 플랫폼의 기본 파일 시스템이 글자 케이스를 무시하는지다 — 맥(APFS 기본)과 윈도우(NTFS).
//
// 케이스만 다른 두 경로가 같은 자리이므로, 터미널에서 친 cwd 와 앱이 준 cwd 의 케이스가 다르면 바이트 비교로는
// 같은 폴더의 대화가 빈 목록이 된다. 리눅스는 케이스가 다르면 실제로 다른 자리라 바이트 비교를 그대로 둔다.
// 맥에서 케이스를 가르게 포맷한 볼륨은 드물고, 그때 케이스만 다른 두 폴더의 대화가 섞여 보이는 것이 대가다.
// 변수인 이유는 리눅스 CI 에서 그 길을 시험하기 위해서다.
var fileSystemIgnoresCase = runtime.GOOS == "darwin" || runtime.GOOS == "windows"

// cwdsNameTheSamePlace 는 전사에 적힌 cwd 와 세션 cwd 가 같은 자리인지다.
func cwdsNameTheSamePlace(recordedCwd, resolvedSessionCwd string, ignoreCase bool) bool {
	recordedCwd = filepath.Clean(recordedCwd)
	if ignoreCase {
		return strings.EqualFold(recordedCwd, resolvedSessionCwd)
	}
	return recordedCwd == resolvedSessionCwd
}

// directoryNameStartsWith 는 전사 디렉토리 이름이 그 앞자리로 시작하는지다. 인코딩한 이름은 ASCII 라 바이트로 자른다.
func directoryNameStartsWith(directoryName, prefix string, ignoreCase bool) bool {
	if ignoreCase {
		return len(directoryName) >= len(prefix) && strings.EqualFold(directoryName[:len(prefix)], prefix)
	}
	return strings.HasPrefix(directoryName, prefix)
}

// claudeCodeProjectsDirectory 는 Claude Code 가 전사를 cwd 별로 나눠 두는 자리다 — <설정 자리>/projects.
func claudeCodeProjectsDirectory() (string, error) {
	if claudeConfigDirectory := os.Getenv(claudeConfigDirectoryEnvironmentVariable); claudeConfigDirectory != "" {
		return filepath.Join(claudeConfigDirectory, "projects"), nil
	}
	homeDirectory, err := os.UserHomeDir()
	if err != nil {
		return "", fmt.Errorf("홈 디렉토리를 알 수 없어 Claude Code 전사 자리를 정하지 못했습니다: %w", err)
	}
	return filepath.Join(homeDirectory, ".claude", "projects"), nil
}

// claudeCodeProjectDirectoryName 은 절대 cwd 를 Claude Code 의 전사 디렉토리 이름으로 옮긴다.
//
// 실측(claude 2.1.316, 2026-11-01): ASCII 영숫자가 아닌 것은 UTF-16 단위 하나마다 - 하나가 된다.
//
//	/home/yawn/Workspace/Kyuchestration  → -home-yawn-Workspace-Kyuchestration
//	…/enc test/a.b_c-d                   → …-enc-test-a-b-c-d   (공백 · 점 · 밑줄 · 하이픈 모두 -)
//	…/enc-한글폴더                        → …-enc-----            (한글 한 글자에 - 하나, 바이트 수가 아니다)
//	…/encemoji😀x                        → …-encemoji--x         (BMP 밖의 글자는 대리쌍이라 - 둘)
//
// 200 자를 넘으면 꼬리가 붙는다(claudeCodeProjectDirectoryNameLimit). 이 함수는 꼬리 앞까지만 만든다.
//
// 영숫자가 아닌 것을 모두 같은 - 로 접으므로 서로 다른 cwd 가 같은 이름이 될 수 있다("/a/b-c" 와
// "/a/b/c"). 그래서 이름으로 찾은 전사를 안에 적힌 cwd 로 다시 확인한다(readClaudeCodeTranscript).
func claudeCodeProjectDirectoryName(absoluteCwd string) string {
	var directoryName strings.Builder
	for _, codeUnit := range utf16.Encode([]rune(absoluteCwd)) {
		if isASCIIAlphanumeric(codeUnit) {
			directoryName.WriteByte(byte(codeUnit))
		} else {
			directoryName.WriteByte('-')
		}
	}
	return directoryName.String()
}

func isASCIIAlphanumeric(codeUnit uint16) bool {
	return (codeUnit >= 'a' && codeUnit <= 'z') || (codeUnit >= 'A' && codeUnit <= 'Z') || (codeUnit >= '0' && codeUnit <= '9')
}

// claudeCodeTranscriptLine 은 전사 한 줄에서 이 파일이 쓰는 자리다. 나머지는 읽지 않는다.
type claudeCodeTranscriptLine struct {
	Type      string `json:"type"`
	SessionID string `json:"sessionId"`
	Cwd       string `json:"cwd"`
	Timestamp string `json:"timestamp"`

	// AITitle 은 ai-title 줄의 제목이다 — claude 2.1.316 의 전사가 제목을 남기는 모양이다(실측).
	AITitle string `json:"aiTitle"`

	// Summary 는 summary 줄의 제목이다 — 앞선 판의 전사가 남기던 모양이다.
	Summary string `json:"summary"`

	// IsSidechain 은 하위 에이전트의 줄이다. claude 2.1.316 은 그것을 <id>/subagents/ 아래 따로 적어 본 전사에는
	// 없지만(실측), 앞선 판은 본 전사에 섞어 적었다.
	IsSidechain bool `json:"isSidechain"`

	// IsMeta 는 claude 가 사람 자리에 적은 user 줄이다 — 다른 세션이 보낸 메시지 · 스킬 본문 · 사용량 안내(실측).
	IsMeta bool `json:"isMeta"`

	// IsCompactSummary 는 대화를 압축하고 남긴 요약 줄이다 — user 줄이지만 claude 가 쓴 것이다(실측).
	IsCompactSummary bool `json:"isCompactSummary"`

	Message *struct {
		Content json.RawMessage `json:"content"`
	} `json:"message"`
}

// textAPersonWrote 는 이 user 줄에서 사람이 쓴 글이다. 사람이 쓴 줄이 아니면 빈 문자열이다.
//
// 목록의 제목과 지난 대화 보기가 같은 규칙을 쓴다 — 둘이 갈리면 제목으로 본 글이 지난 대화에 없거나 그 반대가 된다.
// 주입 태그만으로는 isMeta 줄을 가를 수 없다. "Another Claude session sent a message:" 처럼 태그 없이 시작한다(실측).
func (line claudeCodeTranscriptLine) textAPersonWrote() string {
	if line.Type != "user" || line.Message == nil || line.IsSidechain || line.IsMeta || line.IsCompactSummary {
		return ""
	}
	return textAPersonWrote(line.Message.Content)
}

// readClaudeCodeTranscript 는 전사 하나를 줄 단위로 읽어 대화 하나로 옮긴다.
//
// belongsHere 가 거짓이면 목록에서 뺀다 — 사람이 쓴 글이 없거나, 안에 적힌 cwd 가 이 자리가 아니거나,
// 줄의 sessionId 가 파일 이름과 다를 때다. 마지막 것만 경고를 낸다. 앞의 둘은 정상인 사실(빈 대화 ·
// 이름이 겹친 다른 자리)이지만, 이름과 안이 다른 전사는 --resume <파일 이름> 이 무엇을 여는지 알 수 없는
// 이상이라 사용자가 알아야 한다.
//
// 파일을 통째로 올리지 않는다. 전사는 수십 MB 가 흔하고(이 저장소의 대화 하나가 14MB) 앱이 폴더를 열 때마다
// 목록이 그것을 여럿 읽는다. 그래서 두 가지를 아낀다. 이 리더가 쓰는 종류의 줄만 디코드하고
// (relevantTranscriptLineTypes), 안의 cwd 가 정해져 이 자리가 아니면 그 줄에서 읽기를 멈춘다.
// 깨진 줄은 건너뛴다 — claude 가 쓰는 중인 전사의 마지막 줄은 잘려 있을 수 있다.
func readClaudeCodeTranscript(transcriptPath, conversationID, resolvedSessionCwd string) (conversation ClaudeCodeConversation, belongsHere bool, warning string) {
	transcriptFile, err := os.Open(transcriptPath)
	if err != nil {
		return ClaudeCodeConversation{}, false, fmt.Sprintf("Claude Code 전사를 열지 못해 목록에서 뺍니다 (%s): %v", transcriptPath, err)
	}
	defer transcriptFile.Close()

	transcriptInfo, err := transcriptFile.Stat()
	if err != nil {
		return ClaudeCodeConversation{}, false, fmt.Sprintf("Claude Code 전사의 시각을 읽지 못해 목록에서 뺍니다 (%s): %v", transcriptPath, err)
	}

	var (
		recordedCwd        string
		titleFromClaude    string
		titleFromSummary   string
		firstTextOfAPerson string
	)
	conversation = ClaudeCodeConversation{ID: conversationID, LastActiveAt: transcriptInfo.ModTime().UTC()}

	lineReader := bufio.NewReader(transcriptFile)
	for {
		lineBytes, readErr := lineReader.ReadBytes('\n')
		if isRelevantTranscriptLine(lineBytes) {
			var line claudeCodeTranscriptLine
			if json.Unmarshal(lineBytes, &line) == nil {
				if line.SessionID != "" && line.SessionID != conversationID {
					return ClaudeCodeConversation{}, false, fmt.Sprintf(
						"Claude Code 전사의 이름과 안의 sessionId(%s)가 달라 목록에서 뺍니다 (%s)", line.SessionID, transcriptPath)
				}

				switch line.Type {
				case "ai-title":
					if line.AITitle != "" {
						titleFromClaude = line.AITitle
					}
				case "summary":
					if line.Summary != "" {
						titleFromSummary = line.Summary
					}
				case "user", "assistant":
					if recordedCwd == "" && line.Cwd != "" {
						recordedCwd = line.Cwd
						if !cwdsNameTheSamePlace(recordedCwd, resolvedSessionCwd, fileSystemIgnoresCase) {
							return ClaudeCodeConversation{}, false, ""
						}
					}
					if conversation.StartedAt.IsZero() {
						if startedAt, err := time.Parse(time.RFC3339Nano, line.Timestamp); err == nil {
							conversation.StartedAt = startedAt.UTC()
						}
					}
					if firstTextOfAPerson == "" {
						firstTextOfAPerson = line.textAPersonWrote()
					}
				}
			}
		}
		if errors.Is(readErr, io.EOF) {
			break
		}
		if readErr != nil {
			return ClaudeCodeConversation{}, false, fmt.Sprintf("Claude Code 전사를 끝까지 읽지 못해 목록에서 뺍니다 (%s): %v", transcriptPath, readErr)
		}
	}

	// 사람이 쓴 글이 없으면(/status 만 치고 나간 세션) 이어갈 대화가 아니다. cwd 가 끝내 적히지 않은 전사는
	// 이 자리의 것인지 확인할 수 없어 뺀다.
	if firstTextOfAPerson == "" || recordedCwd == "" {
		return ClaudeCodeConversation{}, false, ""
	}

	conversation.Title = cmp.Or(
		layoutWhitespaceAsSpaces(titleFromClaude),
		layoutWhitespaceAsSpaces(titleFromSummary),
		firstRunes(layoutWhitespaceAsSpaces(firstTextOfAPerson), conversationTitleRuneLimit))
	return conversation, true, ""
}

// relevantTranscriptLineTypes 는 이 리더가 디코드하는 줄 종류의 표지다.
//
// 전사의 대부분은 api-request-blob · attachment 같은 큰 줄이라, 디코드 전에 바이트로 거른다. claude 는 줄을
// 공백 없는 JSON 으로 적는다(실측) — 이 표지가 맞지 않는 줄이 섞여도 디코드 뒤에 type 을 다시 보므로
// 잘못 고른 줄은 무시될 뿐이다. 엇갈린 sessionId 검사도 이 줄들에서만 한다. 다른 대화를 가리키는 전사라면
// 대화 줄에서 드러난다.
var relevantTranscriptLineTypes = [][]byte{
	[]byte(`"type":"user"`),
	[]byte(`"type":"assistant"`),
	[]byte(`"type":"ai-title"`),
	[]byte(`"type":"summary"`),
}

func isRelevantTranscriptLine(lineBytes []byte) bool {
	for _, lineType := range relevantTranscriptLineTypes {
		if bytes.Contains(lineBytes, lineType) {
			return true
		}
	}
	return false
}

// textAPersonWrote 는 사용자 줄의 content 에서 사람이 쓴 첫 글을 꺼낸다. 없으면 빈 문자열이다.
//
// content 는 문자열이거나 블록 배열이다. 배열에서는 text 블록만 본다 — tool_result 블록은 도구가 돌려준 것이다.
func textAPersonWrote(content json.RawMessage) string {
	var plainText string
	if json.Unmarshal(content, &plainText) == nil {
		return withoutInjectedTags(plainText)
	}

	var blocks []struct {
		Type string `json:"type"`
		Text string `json:"text"`
	}
	if json.Unmarshal(content, &blocks) != nil {
		return ""
	}
	for _, block := range blocks {
		if block.Type != "text" {
			continue
		}
		if text := withoutInjectedTags(block.Text); text != "" {
			return text
		}
	}
	return ""
}

// injectedTagOpening 은 claude 가 사용자 줄 앞에 주입하는 덩어리의 여는 태그다
// (<system-reminder> · <command-name> · <local-command-caveat> …).
var injectedTagOpening = regexp.MustCompile(`^<([A-Za-z][A-Za-z0-9_-]*)[^>]*>`)

// withoutInjectedTags 는 글 앞에 붙은 주입 덩어리를 걷어내고 사람이 쓴 부분을 돌려준다.
//
// 덩어리는 여러 줄에 걸치므로 줄 단위로 거르면 태그 안의 줄이 제목이 된다. 닫는 태그까지를 한 덩어리로
// 걷는다. 닫는 태그가 없으면 글 전체를 주입된 것으로 본다 — 사람이 쓴 글이 태그로 시작하는 일보다
// claude 가 남긴 단독 태그가 훨씬 흔하다.
func withoutInjectedTags(text string) string {
	for {
		text = strings.TrimSpace(text)
		opening := injectedTagOpening.FindStringSubmatch(text)
		if opening == nil {
			return text
		}
		closingTag := "</" + opening[1] + ">"
		closingIndex := strings.Index(text, closingTag)
		if closingIndex < 0 {
			return ""
		}
		text = text[closingIndex+len(closingTag):]
	}
}

// layoutWhitespaceAsSpaces 는 제목이 목록의 한 줄 한 칸에 들어가도록 줄바꿈과 탭을 공백으로 편다.
//
// 탭도 함께인 이유는 사람용 표가 tabwriter 라서다 — 제목 안의 탭을 열 경계로 읽어 표가 깨진다.
func layoutWhitespaceAsSpaces(text string) string {
	return strings.TrimSpace(strings.NewReplacer("\r\n", " ", "\n", " ", "\r", " ", "\t", " ").Replace(text))
}

// firstRunes 는 앞 limit 글자만 남긴다. 바이트로 자르면 한글 한 글자가 반으로 갈린다.
func firstRunes(text string, limit int) string {
	runes := []rune(text)
	if len(runes) <= limit {
		return text
	}
	return string(runes[:limit])
}
