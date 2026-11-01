package cli

import (
	"bytes"
	"fmt"
	"io"
	"path/filepath"
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

  kyu conversations list <경로>          메인 세션 자리(워크디렉토리 루트)에서 Claude Code 로 나눈 대화
  kyu conversations list <경로> <repo>   그 레포 세션 자리에서 나눈 대화

옵션:
  --json             사람용 표 대신 기계용 JSON 을 낸다 (GUI·스크립트 연동용)

고른 대화는 kyu session-command [repo] --adopt-conversation <id> --json 으로 이어간다.`

// ManageClaudeCodeConversations 는 kyu conversations 를 실행한다. 지금 하위 명령은 list 하나다.
//
// 읽다가 버린 전사에 대한 경고는 두 모드 모두 errOut 으로 간다. 기계용 문서의 모양은 앱이 그대로 쓰는
// 계약이라 경고 자리를 따로 두지 않았고, 경고가 없어도 목록은 그 자체로 참이다.
func ManageClaudeCodeConversations(out, errOut io.Writer, args []string) error {
	if len(args) == 0 || args[0] != "list" {
		return fmt.Errorf("conversations 의 하위 명령은 list 하나입니다\n\n%s", conversationsUsageText)
	}
	request, err := parseConversationsListArgs(args[1:])
	if err != nil {
		return err
	}

	workDirAbsolutePath, err := filepath.Abs(request.workDirPath)
	if err != nil {
		return fmt.Errorf("워크디렉토리 경로의 절대경로 변환 실패 (%s): %w", request.workDirPath, err)
	}
	repos, err := workdir.ScanRepos(workDirAbsolutePath)
	if err != nil {
		return err
	}
	target, err := sessionTargetFor(workDirAbsolutePath, repos, request.repoName)
	if err != nil {
		return err
	}

	listing, err := workdir.ListClaudeCodeConversations(workDirAbsolutePath, target.cwd)
	if err != nil {
		return err
	}
	if err := writeConversationWarnings(errOut, listing.Warnings); err != nil {
		return err
	}

	if request.asJSON {
		return writeClaudeCodeConversationsAsJSON(out, target.cwd, listing.Conversations)
	}
	return writeClaudeCodeConversationsTable(out, target.cwd, listing.Conversations)
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
