package cli

import (
	"bytes"
	"fmt"
	"io"
	"path/filepath"
	"strconv"
	"strings"
	"text/tabwriter"
	"time"

	"github.com/maximinhan/Kyuchestration/internal/workdir"
)

// 이 파일은 kyu conversations 다 — 그 자리에서 사용자가 Claude Code 로 나눈 대화를 보여 주는 자리.
//
// 앱은 폴더를 열 때 이 목록을 물어, 터미널에서 해 온 대화를 이어갈지 새로 시작할지 사람에게 고르게 한다.
// 고른 것은 kyu session-command --adopt-conversation 으로 돌아온다. 전사를 읽는 것이 엔진인 이유는
// 대화 ID 정책의 주인이 엔진이어서다 — 앱이 ~/.claude 의 모양을 알기 시작하면 그 지식이 두 곳에 놓인다.

const conversationsUsageText = `사용법: kyu conversations list <워크디렉토리 경로> [repo] [--json]
       kyu conversations show <워크디렉토리 경로> [repo] <대화 id> [--last N] [--json]

  kyu conversations list <경로>          메인 세션 자리(워크디렉토리 루트)에서 Claude Code 로 나눈 대화
  kyu conversations list <경로> <repo>   그 레포 세션 자리에서 나눈 대화
  kyu conversations show <경로> <id>     그 자리에서 나눈 대화 하나의 지난 내용 (마지막 항목들)

옵션:
  --json             사람용 출력 대신 기계용 JSON 을 낸다 (GUI·스크립트 연동용)
  --last N           show 가 답할 마지막 항목 수 (기본 200)

고른 대화는 kyu session-command [repo] --adopt-conversation <id> --json 으로 이어간다.`

// lastEntriesOptionName 은 show 가 답할 마지막 항목 수를 받는 옵션이다.
const lastEntriesOptionName = "--last"

// defaultLastEntryCount 는 --last 없이 show 가 답하는 항목 수다.
//
// 앱이 이어가는 대화의 앞에 까는 양이다. 사람이 다시 읽어 맥락을 잡기에는 넉넉하고, 수천 항목의 대화를 열 때마다
// 채팅 창이 그 전부를 그리지 않을 만큼이다.
const defaultLastEntryCount = 200

// ManageClaudeCodeConversations 는 kyu conversations 를 실행한다. 하위 명령은 list 와 show 다.
//
// 읽다가 버린 전사에 대한 경고는 두 모드 모두 errOut 으로 간다. 기계용 문서의 모양은 앱이 그대로 쓰는
// 계약이라 경고 자리를 따로 두지 않았고, 경고가 없어도 목록은 그 자체로 참이다.
func ManageClaudeCodeConversations(out, errOut io.Writer, args []string) error {
	if len(args) == 0 {
		return fmt.Errorf("conversations 의 하위 명령은 list 와 show 입니다\n\n%s", conversationsUsageText)
	}
	switch args[0] {
	case "list":
		return listClaudeCodeConversations(out, errOut, args[1:])
	case "show":
		return showClaudeCodeConversation(out, errOut, args[1:])
	default:
		return fmt.Errorf("conversations 의 하위 명령은 list 와 show 입니다 (%s 를 받음)\n\n%s", args[0], conversationsUsageText)
	}
}

func listClaudeCodeConversations(out, errOut io.Writer, args []string) error {
	request, err := parseConversationsListArgs(args)
	if err != nil {
		return err
	}
	workDirAbsolutePath, sessionCwd, err := conversationSessionCwdFor(request.workDirPath, request.repoName)
	if err != nil {
		return err
	}

	listing, err := workdir.ListClaudeCodeConversations(workDirAbsolutePath, sessionCwd)
	if err != nil {
		return err
	}
	if err := writeConversationWarnings(errOut, listing.Warnings); err != nil {
		return err
	}

	if request.asJSON {
		return writeClaudeCodeConversationsAsJSON(out, sessionCwd, listing.Conversations)
	}
	return writeClaudeCodeConversationsTable(out, sessionCwd, listing.Conversations)
}

// showClaudeCodeConversation 은 대화 하나의 지난 내용을 낸다. 아무것도 적지 않는다.
//
// 워크디렉토리의 대화 기록(conversations.json)을 보지 않는다. 위임 · 하위 세션 대화도 답하므로 가를 것이 없고,
// 전사가 그 자리의 것인지는 전사 안의 cwd 가 정한다. 경고는 list 와 같이 errOut 으로 가고, 실패해도 먼저 낸다 —
// 실패의 까닭이 그 경고일 수 있다.
func showClaudeCodeConversation(out, errOut io.Writer, args []string) error {
	request, err := parseConversationsShowArgs(args)
	if err != nil {
		return err
	}
	_, sessionCwd, err := conversationSessionCwdFor(request.workDirPath, request.repoName)
	if err != nil {
		return err
	}

	history, warnings, err := workdir.ReadClaudeCodeConversationHistory(sessionCwd, request.conversationID, request.lastEntryCount)
	if warningErr := writeConversationWarnings(errOut, warnings); warningErr != nil {
		return warningErr
	}
	if err != nil {
		return err
	}
	if request.asJSON {
		return writeClaudeCodeConversationHistoryAsJSON(out, sessionCwd, history)
	}
	return writeClaudeCodeConversationHistoryLines(out, sessionCwd, history)
}

// conversationSessionCwdFor 는 워크디렉토리의 절대경로와, 그 레포(비면 메인) 세션이 뜰 자리다.
//
// session-command 와 같은 sessionTargetFor 로 정한다 — 두 자리가 갈라지면 고른 대화를 다른 자리에서 --resume 한다.
func conversationSessionCwdFor(workDirPath, repoName string) (workDirAbsolutePath, sessionCwd string, err error) {
	workDirAbsolutePath, err = filepath.Abs(workDirPath)
	if err != nil {
		return "", "", fmt.Errorf("워크디렉토리 경로의 절대경로 변환 실패 (%s): %w", workDirPath, err)
	}
	repos, err := workdir.ScanRepos(workDirAbsolutePath)
	if err != nil {
		return "", "", err
	}
	target, err := sessionTargetFor(workDirAbsolutePath, repos, repoName)
	if err != nil {
		return "", "", err
	}
	return workDirAbsolutePath, target.cwd, nil
}

// conversationsListRequest 는 파싱이 끝난 kyu conversations list 요청이다.
type conversationsListRequest struct {
	workDirPath string

	// repoName 이 비어 있으면 메인 세션 자리다.
	repoName string

	asJSON bool
}

// parseConversationsListArgs 는 인자를 목록 요청으로 옮긴다.
//
// 경로를 생략하면 현재 디렉토리로 짐작하지 않는다. 앱은 늘 고른 폴더를 주고, 짐작한 자리의 대화를 그 폴더의
// 것으로 보이면 사람이 고른 대화가 다른 자리에서 이어진다.
func parseConversationsListArgs(args []string) (conversationsListRequest, error) {
	var request conversationsListRequest
	var positionalArgs []string
	for _, arg := range args {
		switch {
		case arg == machineJSONOptionName:
			request.asJSON = true
		case strings.HasPrefix(arg, "-"):
			return conversationsListRequest{}, fmt.Errorf("알 수 없는 옵션: %s\n\n%s", arg, conversationsUsageText)
		default:
			positionalArgs = append(positionalArgs, arg)
		}
	}

	switch len(positionalArgs) {
	case 1:
		request.workDirPath = positionalArgs[0]
	case 2:
		request.workDirPath, request.repoName = positionalArgs[0], positionalArgs[1]
	default:
		return conversationsListRequest{}, fmt.Errorf("conversations list 는 워크디렉토리 경로와, 있다면 레포 이름 하나를 받습니다 (인자 %d 개를 받음)\n\n%s",
			len(positionalArgs), conversationsUsageText)
	}
	return request, nil
}

// conversationsShowRequest 는 파싱이 끝난 kyu conversations show 요청이다.
type conversationsShowRequest struct {
	workDirPath string

	// repoName 이 비어 있으면 메인 세션 자리다.
	repoName string

	conversationID string
	lastEntryCount int
	asJSON         bool
}

// parseConversationsShowArgs 는 인자를 보기 요청으로 옮긴다. 경로를 짐작하지 않는 이유는 list 와 같다.
//
// 대화 id 의 모양은 여기서 보지 않는다 — 읽는 쪽(ReadClaudeCodeConversationHistory)이 같은 IsConversationID 로 거절한다.
func parseConversationsShowArgs(args []string) (conversationsShowRequest, error) {
	request := conversationsShowRequest{lastEntryCount: defaultLastEntryCount}
	var positionalArgs []string
	for argIndex := 0; argIndex < len(args); argIndex++ {
		arg := args[argIndex]
		switch {
		case arg == machineJSONOptionName:
			request.asJSON = true
		case arg == lastEntriesOptionName:
			if argIndex+1 >= len(args) {
				return conversationsShowRequest{}, fmt.Errorf("%s 뒤에 항목 수가 필요합니다\n\n%s", lastEntriesOptionName, conversationsUsageText)
			}
			lastEntryCount, err := strconv.Atoi(args[argIndex+1])
			if err != nil || lastEntryCount < 1 {
				return conversationsShowRequest{}, fmt.Errorf("%s 는 1 이상의 정수를 받습니다 (%q 를 받음)", lastEntriesOptionName, args[argIndex+1])
			}
			request.lastEntryCount = lastEntryCount
			argIndex++
		case strings.HasPrefix(arg, "-"):
			return conversationsShowRequest{}, fmt.Errorf("알 수 없는 옵션: %s\n\n%s", arg, conversationsUsageText)
		default:
			positionalArgs = append(positionalArgs, arg)
		}
	}

	switch len(positionalArgs) {
	case 2:
		request.workDirPath, request.conversationID = positionalArgs[0], positionalArgs[1]
	case 3:
		request.workDirPath, request.repoName, request.conversationID = positionalArgs[0], positionalArgs[1], positionalArgs[2]
	default:
		return conversationsShowRequest{}, fmt.Errorf("conversations show 는 워크디렉토리 경로, 있다면 레포 이름, 대화 id 를 받습니다 (인자 %d 개를 받음)\n\n%s",
			len(positionalArgs), conversationsUsageText)
	}
	return request, nil
}

// noConversationsGuidance 는 고를 대화가 없을 때의 한 줄이다. 빈 화면만 보이면 못 찾은 것과 없는 것이 같아 보인다.
const noConversationsGuidance = "이 자리에서 Claude Code 로 나눈 대화가 없습니다"

// writeClaudeCodeConversationsTable 은 목록을 사람이 읽는 표로 낸다. 시각은 이 머신의 시간대로 보인다.
func writeClaudeCodeConversationsTable(out io.Writer, cwd string, conversations []workdir.ClaudeCodeConversation) error {
	var rendered bytes.Buffer
	fmt.Fprintf(&rendered, "Claude Code 대화: %s   (%d 개)\n\n", cwd, len(conversations))
	if len(conversations) == 0 {
		fmt.Fprintf(&rendered, "%s%s\n", rowIndent, noConversationsGuidance)
	}

	table := tabwriter.NewWriter(&rendered, 0, 0, tableColumnPadding, ' ', 0)
	for _, conversation := range conversations {
		recordedFor := "-"
		if conversation.RecordedFor != "" {
			recordedFor = conversation.RecordedFor
		}
		fmt.Fprintf(table, "%s%s\t%s\t%s\t%s\n", rowIndent, conversation.ID,
			conversation.LastActiveAt.Local().Format(time.DateTime), recordedFor, conversation.Title)
	}
	if err := table.Flush(); err != nil {
		return fmt.Errorf("대화 표 정렬 실패: %w", err)
	}

	if _, err := out.Write(rendered.Bytes()); err != nil {
		return fmt.Errorf("대화 목록 출력 실패: %w", err)
	}
	return nil
}

// historyLineRuneLimit 는 사람용 지난 대화에서 한 항목이 차지하는 한 줄의 최대 글자 수다.
const historyLineRuneLimit = 160

// writeClaudeCodeConversationHistoryLines 는 지난 대화를 사람이 훑는 줄들로 낸다 — 항목마다 시각 · 화자 · 한 줄.
//
// 글 전체가 아니라 한 줄이다. 사람용 출력은 무엇을 이야기했는지 훑는 자리고, 글 전체는 --json 이 답한다.
func writeClaudeCodeConversationHistoryLines(out io.Writer, cwd string, history workdir.ClaudeCodeConversationHistory) error {
	var rendered bytes.Buffer
	fmt.Fprintf(&rendered, "Claude Code 대화 %s: %s   (전체 %d 항목 중 마지막 %d 개)\n\n",
		history.ConversationID, cwd, history.TotalEntries, len(history.Entries))

	table := tabwriter.NewWriter(&rendered, 0, 0, tableColumnPadding, ' ', 0)
	for _, entry := range history.Entries {
		at := "-"
		if !entry.At.IsZero() {
			at = entry.At.Local().Format(time.DateTime)
		}
		speaker, line := "", ""
		switch entry.Kind {
		case workdir.ConversationEntryKindUser:
			speaker, line = "사람", entry.Text
		case workdir.ConversationEntryKindSystem:
			speaker, line = "안내", entry.Text
		case workdir.ConversationEntryKindAssistant:
			speaker, line = "Claude", entry.Text
		case workdir.ConversationEntryKindToolUse:
			speaker, line = "도구", strings.TrimSpace(entry.ToolName+" "+entry.ToolSummary)
		case workdir.ConversationEntryKindOmitted:
			speaker, line = "생략", fmt.Sprintf("하위 에이전트의 줄 %d 개", entry.OmittedCount)
		}
		// 목록의 제목과 같은 규칙으로 한 줄을 만든다 — 탭이 남으면 tabwriter 가 열 경계로 읽는다.
		fmt.Fprintf(table, "%s%s\t%s\t%s\n", rowIndent, at, speaker, workdir.FirstRunesOnOneLine(line, historyLineRuneLimit))
	}
	if err := table.Flush(); err != nil {
		return fmt.Errorf("지난 대화 정렬 실패: %w", err)
	}

	if _, err := out.Write(rendered.Bytes()); err != nil {
		return fmt.Errorf("지난 대화 출력 실패: %w", err)
	}
	return nil
}
