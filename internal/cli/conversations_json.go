package cli

import (
	"io"
	"time"

	"github.com/maximinhan/Kyuchestration/internal/workdir"
)

// 이 파일은 kyu conversations list --json 이 내보내는 문서를 정의한다. 직렬화 전용 타입을 따로 두는 이유는
// list_json.go 와 같다 — 이 모양은 앱과 맺는 계약이다.
//
// 스키마 (schemaVersion 1):
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
