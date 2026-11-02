package cli

import (
	"fmt"
	"io"
	"time"

	"github.com/maximinhan/Kyuchestration/internal/workdir"
)

// 이 파일은 kyu conversations 의 기계용 문서 둘을 정의한다 — list --json(고를 대화 목록)과 show --json(대화 하나의
// 지난 내용). 직렬화 전용 타입을 따로 두는 이유는 list_json.go 와 같다 — 이 모양은 앱과 맺는 계약이다.
//
// list 의 스키마 (schemaVersion 1):
//
//	{
//	  "schemaVersion": 1,
//	  "cwd": "/home/me/work/WorkDir-featureX",     // 그 세션이 뜰 자리 — session-command 가 답하는 cwd 와 같다
//	  "conversations": [                           // 마지막 활동이 늦은 것부터. 없으면 []
//	    {
//	      "id": "654ce605-b030-40e8-8448-2e9003ad32b1",   // --adopt-conversation 에 그대로 넘길 값
//	      "title": "workdir-orchestrator-design.md 검토",
//	      "startedAt": "2026-08-27T06:05:50Z",            // 첫 메시지 시각. 알 수 없으면 null
//	      "lastActiveAt": "2026-11-01T09:00:56Z",         // 전사 파일이 마지막으로 바뀐 시각
//	      "recordedFor": "main"                           // conversations.json 에 적힌 라벨. 바깥 대화면 null
//	    }
//	  ]
//	}
//
// 시각은 모두 RFC 3339 UTC 초 단위다. 이 도구의 위임 · 하위 세션 대화는 목록에 없다(채택할 수 없다).
// 판 규약은 list --json 과 같다 — 필드를 더하는 변경으로는 올리지 않는다.
const conversationsListJSONSchemaVersion = 1

type conversationsListJSONDocument struct {
	SchemaVersion int                `json:"schemaVersion"`
	Cwd           string             `json:"cwd"`
	Conversations []conversationJSON `json:"conversations"`
}

type conversationJSON struct {
	ID           string  `json:"id"`
	Title        string  `json:"title"`
	StartedAt    *string `json:"startedAt"`
	LastActiveAt string  `json:"lastActiveAt"`

	// RecordedFor 는 앱이 자기가 적은 대화와 바깥 대화를 가르는 근거다. 빈 문자열이 아니라 null 이다 —
	// "적힌 라벨 없음" 을 두 모양으로 다루게 하지 않는다.
	RecordedFor *string `json:"recordedFor"`
}

func writeClaudeCodeConversationsAsJSON(out io.Writer, cwd string, conversations []workdir.ClaudeCodeConversation) error {
	conversationDocuments := make([]conversationJSON, 0, len(conversations))
	for _, conversation := range conversations {
		document := conversationJSON{
			ID:           conversation.ID,
			Title:        conversation.Title,
			LastActiveAt: conversation.LastActiveAt.UTC().Format(time.RFC3339),
		}
		if !conversation.StartedAt.IsZero() {
			startedAt := conversation.StartedAt.UTC().Format(time.RFC3339)
			document.StartedAt = &startedAt
		}
		if conversation.RecordedFor != "" {
			recordedFor := conversation.RecordedFor
			document.RecordedFor = &recordedFor
		}
		conversationDocuments = append(conversationDocuments, document)
	}

	return writeJSONDocument(out, conversationsListJSONDocument{
		SchemaVersion: conversationsListJSONSchemaVersion,
		Cwd:           cwd,
		Conversations: conversationDocuments,
	})
}

// kyu conversations show --json 이 내보내는 문서다. 앱이 이어가는 대화의 채팅 창 앞에 이것을 깐다.
//
// 스키마 (schemaVersion 1):
//
//	{
//	  "schemaVersion": 1,
//	  "conversationId": "654ce605-b030-40e8-8448-2e9003ad32b1",
//	  "cwd": "/home/me/work/WorkDir-featureX",     // 그 세션이 뜰 자리 — list 의 cwd 와 같다
//	  "totalEntries": 412,                         // 전사 전체의 항목 수 (omitted 항목도 하나로 센다).
//	                                               // 앞에서 답하지 않은 수는 totalEntries - entries 의 길이다
//	  "entries": [                                 // 마지막 --last 개, 오래된 것부터. 없으면 []
//	    {"kind": "user", "text": "…", "at": "2026-11-01T09:06:18Z"},             // 사람이 쓴 글 (text 블록 여럿은 빈 줄로 잇는다)
//	    {"kind": "system", "text": "[Request interrupted by user]", "at": "…"},  // 사람 자리의 안내 — 중단 표지 · 세션 트리 중계
//	    {"kind": "assistant", "text": "…", "at": "…"},                          // claude 의 글 (thinking 은 없다)
//	    {"kind": "tool_use", "toolName": "Bash", "summary": "ls -la", "at": "…"}, // summary 는 없으면 ""
//	    {"kind": "omitted", "count": 3}                                         // 연달아 건너뛴 전사 줄(하위 에이전트)
//	  ]
//	}
//
// 항목은 종류마다 그 자리만 갖는다. at 은 RFC 3339 UTC 초 단위이고, 그 줄의 시각을 알 수 없으면 null 이다.
// tool_result 와 claude 의 합성 오류 줄(isApiErrorMessage)은 없고 세지도 않는다. 판 규약은 list --json 과 같다 — 필드나 kind 를 더하는 변경으로는 올리지 않는다.
// 모르는 kind 는 건너뛰면 된다.
const conversationsShowJSONSchemaVersion = 1

type conversationsShowJSONDocument struct {
	SchemaVersion  int    `json:"schemaVersion"`
	ConversationID string `json:"conversationId"`
	Cwd            string `json:"cwd"`
	TotalEntries   int    `json:"totalEntries"`

	// Entries 의 원소는 아래 세 모양 중 하나다. 한 타입에 모든 자리를 두면 user 항목에 "toolName": "" 같은
	// 뜻 없는 자리가 생기고, 읽는 쪽이 빈 값과 없는 값을 가려야 한다.
	Entries []any `json:"entries"`
}

type conversationTextEntryJSON struct {
	Kind string  `json:"kind"`
	Text string  `json:"text"`
	At   *string `json:"at"`
}

type conversationToolUseEntryJSON struct {
	Kind     string  `json:"kind"`
	ToolName string  `json:"toolName"`
	Summary  string  `json:"summary"`
	At       *string `json:"at"`
}

type conversationOmittedEntryJSON struct {
	Kind  string `json:"kind"`
	Count int    `json:"count"`
}

func writeClaudeCodeConversationHistoryAsJSON(out io.Writer, cwd string, history workdir.ClaudeCodeConversationHistory) error {
	entryDocuments := make([]any, 0, len(history.Entries))
	for _, entry := range history.Entries {
		var at *string
		if !entry.At.IsZero() {
			formattedAt := entry.At.UTC().Format(time.RFC3339)
			at = &formattedAt
		}
		switch entry.Kind {
		case workdir.ConversationEntryKindUser, workdir.ConversationEntryKindSystem, workdir.ConversationEntryKindAssistant:
			entryDocuments = append(entryDocuments, conversationTextEntryJSON{Kind: string(entry.Kind), Text: entry.Text, At: at})
		case workdir.ConversationEntryKindToolUse:
			entryDocuments = append(entryDocuments, conversationToolUseEntryJSON{
				Kind: string(entry.Kind), ToolName: entry.ToolName, Summary: entry.ToolSummary, At: at})
		case workdir.ConversationEntryKindOmitted:
			entryDocuments = append(entryDocuments, conversationOmittedEntryJSON{Kind: string(entry.Kind), Count: entry.OmittedCount})
		default:
			return fmt.Errorf("지난 대화의 항목 종류 %q 를 문서로 옮기는 자리가 없습니다", entry.Kind)
		}
	}

	return writeJSONDocument(out, conversationsShowJSONDocument{
		SchemaVersion:  conversationsShowJSONSchemaVersion,
		ConversationID: history.ConversationID,
		Cwd:            cwd,
		TotalEntries:   history.TotalEntries,
		Entries:        entryDocuments,
	})
}
