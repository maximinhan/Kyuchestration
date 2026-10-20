package cli

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"

	"github.com/maximinhan/Kyuchestration/internal/mcpserver"
)

// 이 파일은 세션 트리 도구다 — 상위에게 다섯(session-tree-design.md 6.2), 하위에게 하나(6.3).
//
// **엔진은 통로다**(원칙 16). 하위를 띄우고, 말을 나르고, 끝내는 것은 앱이다. 이 도구들은 모델의
// 호출을 kind 가 붙은 한 줄로 바꿔 앱 소켓에 쓰고(6.4), 앱의 답 한 줄을 도구 결과로 되돌린다.
// 엔진이 하위 하나라도 따로 띄우면 화면에 없는 세션이 생긴다.
//
// 엔진이 직접 하는 판단은 둘뿐이다 — 인자가 모양을 갖췄는지, 그리고 await_session 의 시간 범위(6.7).
// 둘 다 앱까지 갈 필요 없이 모델에게 곧바로 고칠 것을 말할 수 있는 자리다.

// 모델이 부르는 도구 이름들이다. mcp__kyu__spawn_session 처럼 보인다.
const (
	spawnSessionToolName  = "spawn_session"
	sendToSessionToolName = "send_to_session"
	awaitSessionToolName  = "await_session"
	listSessionsToolName  = "list_sessions"
	endSessionToolName    = "end_session"
	askParentToolName     = "ask_parent"
)

// 앱 소켓 줄의 kind 들이다(6.4). 앱(PermissionRequestSocket.kt)이 이 낱말로 줄을 가른다.
const (
	spawnSessionRequestKind  = "spawn"
	sendToSessionRequestKind = "send"
	awaitSessionRequestKind  = "await"
	listSessionsRequestKind  = "list"
	endSessionRequestKind    = "end"
	askParentRequestKind     = "ask_parent"
)

// await_session 의 기다림 범위다(6.7).
//
// **최댓값은 서버 상한(orchestrationToolTimeoutMilliseconds, 1 시간)보다 짧아야 한다.** 같거나 길면
// claude 가 먼저 끊고 "timed out" 이 모델에게 간다 — 앱이 "아직 도는 중" 이라고 정직하게 답할
// 자리를 잃는다.
const (
	defaultAwaitWaitSeconds = 1800
	maximumAwaitWaitSeconds = 3000
)

// subagentOrChildSessionCriterionText 는 서브에이전트와 하위 세션 중 무엇을 고르는지의 기준이다(4.4).
//
// 시스템 프롬프트(parentSessionConventionText)와 spawn_session 설명이 이 한 글을 같이 쓴다. 4.4 가 두
// 자리 모두에 적으라고 했고, 두 자리의 글이 따로 고쳐져 갈라지면 모델은 어느 쪽을 따를지를 또 골라야 한다.
//
// 시스템 프롬프트가 본자리인 이유: 견줄 상대인 Agent 는 claude 의 내장 도구라 그 설명을 우리가 고칠 수
// 없다. 두 도구를 한자리에서 견주는 글은 어느 한 도구의 설명이 아니라 둘 다 보이는 자리에 있어야 하고,
// 규약을 시스템 프롬프트로 주었을 때 모델이 따른 것을 쟀다(3.5 나).
const subagentOrChildSessionCriterionText = "일을 맡길 때는 하위 세션(spawn_session)과 서브에이전트(Agent 도구) 중 하나를 고른다. " +
	"하위 세션은 다른 폴더(레포)에서 그 폴더의 설정으로 해야 하는 일, 오래 걸리는 일, 사람이 지켜보거나 끼어들어야 하는 일, " +
	"이어서 여러 번 시킬 일에 쓴다 — 하위 세션은 앱에 탭으로 보이고 승인 카드가 사람에게 간다. " +
	"이 턴 안에서 끝나는 짧은 조사·읽기·요약에는 하위 세션을 띄우지 않는다 — 서브에이전트를 쓰거나 직접 한다."

const spawnSessionToolDescription = `하위 세션 하나를 띄운다. 하위는 자기 cwd 에서 도는 별도의 claude 이고, 사람이 그 탭에서 직접 볼 수 있다.

` + subagentOrChildSessionCriterionText + `

첫 프롬프트를 넣은 순간 답한다 — 하위의 턴이 끝나기를 기다리지 않는다. 결과는 await_session 으로 받거나,
턴을 끝내고 쉬면 하위의 턴이 끝날 때 "[하위 세션 <id> · 턴 끝]" 메시지로 온다.

cwd 는 워크디렉토리 기준 상대경로다(생략하면 워크디렉토리 루트). 워크디렉토리 밖·.coord 는 안 된다.
없는 폴더에 띄우려면 createDirectory 를 참으로 준다 — 이미 있는 폴더에 createDirectory 를 주면 거절된다.
살아 있는 하위 둘은 같은 cwd 를 쓸 수 없다.`

const sendToSessionToolDescription = `하위 세션에게 말을 보낸다.

하위가 ask_parent 로 묻고 기다리는 중이면 그 물음의 답이 되고(delivered: as_answer), 아니면 하위의
새 사용자 메시지로 들어간다(delivered: as_message — 하위의 새 턴을 연다).`

const awaitSessionToolDescription = `하위 세션들에게 전할 일이 생길 때까지 기다린다 — 턴이 끝났다, 상위에게 물었다, 세션이 끝났다,
사람이 상위에게 말했다(user_spoke) 중 하나라도 생기면 그때까지 쌓인 사건을 다 담아 답한다.

ids 를 생략하면 살아 있는 하위 전부를 기다린다. waitSeconds 는 기본 1800, 최대 3000 이다.
아무 일 없이 그 시간이 지나면 events 가 빈 채로 stillRunning 을 답한다.`

const listSessionsToolDescription = `이 세션이 띄운 하위 세션 전부를 답한다 — 끝난 것까지. 각 하위의 cwd · 상태 ·
지금 무엇을 기다리는지(waitingFor: nothing · model · parent · approval)가 담긴다.`

const endSessionToolDescription = `하위 세션 하나를 끝낸다. 하위가 ask_parent 로 기다리던 중이면 그 물음은 "상위가 세션을 끝냈습니다" 로 답해진다.`

const askParentToolDescription = `너를 띄운 상위 세션에게 묻고 답을 기다린다.

답이 있어야 일을 이어갈 수 있을 때만 쓴다. 결과를 알리는 데는 쓰지 않는다 — 네 턴이 끝나면 그 결과는
이미 상위에게 간다. 상위가 끝났거나 세션이 끝나면 answer 대신 ended 로 답한다.`

// sessionTreeTools 는 상위 역할의 서버에 더해질 다섯 도구다(6.2).
func sessionTreeTools(appSocketPath string) []mcpserver.Tool {
	return []mcpserver.Tool{
		{
			Name:        spawnSessionToolName,
			Description: spawnSessionToolDescription,
			InputSchema: objectSchema([]string{"prompt"}, map[string]any{
				"prompt":          map[string]any{"type": "string", "description": "하위의 첫 메시지"},
				"cwd":             map[string]any{"type": "string", "description": "워크디렉토리 기준 상대경로. 생략하면 워크디렉토리 루트"},
				"createDirectory": map[string]any{"type": "boolean", "description": "참이면 cwd 를 새로 만든다"},
				"title":           map[string]any{"type": "string", "description": "화면의 탭 이름. 생략하면 cwd 의 마지막 이름"},
			}),
			Call: func(ctx context.Context, arguments json.RawMessage) (mcpserver.ToolResult, error) {
				var request spawnSessionRequest
				if refusal, refused := decodeToolArguments(arguments, &request); refused {
					return refusal, nil
				}
				if request.Prompt == "" {
					return missingArgumentResult(spawnSessionToolName, "prompt"), nil
				}
				request.Kind = spawnSessionRequestKind
				return relaySessionTreeRequest(ctx, appSocketPath, request)
			},
		},
		{
			Name:        sendToSessionToolName,
			Description: sendToSessionToolDescription,
			InputSchema: objectSchema([]string{"id", "message"}, map[string]any{
				"id":      map[string]any{"type": "string", "description": "spawn_session 이 답한 하위의 id"},
				"message": map[string]any{"type": "string"},
			}),
			Call: func(ctx context.Context, arguments json.RawMessage) (mcpserver.ToolResult, error) {
				var request sendToSessionRequest
				if refusal, refused := decodeToolArguments(arguments, &request); refused {
					return refusal, nil
				}
				if request.ID == "" {
					return missingArgumentResult(sendToSessionToolName, "id"), nil
				}
				if request.Message == "" {
					return missingArgumentResult(sendToSessionToolName, "message"), nil
				}
				request.Kind = sendToSessionRequestKind
				return relaySessionTreeRequest(ctx, appSocketPath, request)
			},
		},
		{
			Name:        awaitSessionToolName,
			Description: awaitSessionToolDescription,
			InputSchema: objectSchema(nil, map[string]any{
				"ids":         map[string]any{"type": "array", "items": map[string]any{"type": "string"}, "minItems": 1},
				"waitSeconds": map[string]any{"type": "integer", "minimum": 1, "maximum": maximumAwaitWaitSeconds},
			}),
			Call: func(ctx context.Context, arguments json.RawMessage) (mcpserver.ToolResult, error) {
				return awaitSessions(ctx, appSocketPath, arguments)
			},
		},
		{
			Name:        listSessionsToolName,
			Description: listSessionsToolDescription,
			InputSchema: objectSchema(nil, map[string]any{}),
			Call: func(ctx context.Context, arguments json.RawMessage) (mcpserver.ToolResult, error) {
				var request listSessionsRequest
				if refusal, refused := decodeToolArguments(arguments, &request); refused {
					return refusal, nil
				}
				request.Kind = listSessionsRequestKind
				return relaySessionTreeRequest(ctx, appSocketPath, request)
			},
		},
		{
			Name:        endSessionToolName,
			Description: endSessionToolDescription,
			InputSchema: objectSchema([]string{"id"}, map[string]any{
				"id": map[string]any{"type": "string"},
			}),
			Call: func(ctx context.Context, arguments json.RawMessage) (mcpserver.ToolResult, error) {
				var request endSessionRequest
				if refusal, refused := decodeToolArguments(arguments, &request); refused {
					return refusal, nil
				}
				if request.ID == "" {
					return missingArgumentResult(endSessionToolName, "id"), nil
				}
				request.Kind = endSessionRequestKind
				return relaySessionTreeRequest(ctx, appSocketPath, request)
			},
		},
	}
}

// childSessionTools 는 하위 역할의 서버가 여는 도구 전부다 — ask_parent 하나(6.3).
//
// **깊이 제한이 이 목록이다.** 하위의 서버에 spawn_session 이 없으므로 하위는 하위를 낳지 못한다.
// 모델의 판단에 기대는 것이 아니라 도구가 없다는 사실에 기댄다.
func childSessionTools(appSocketPath string) []mcpserver.Tool {
	return []mcpserver.Tool{
		{
			Name:        askParentToolName,
			Description: askParentToolDescription,
			InputSchema: objectSchema([]string{"message"}, map[string]any{
				"message": map[string]any{"type": "string"},
			}),
			Call: func(ctx context.Context, arguments json.RawMessage) (mcpserver.ToolResult, error) {
				var request askParentRequest
				if refusal, refused := decodeToolArguments(arguments, &request); refused {
					return refusal, nil
				}
				if request.Message == "" {
					return missingArgumentResult(askParentToolName, "message"), nil
				}
				request.Kind = askParentRequestKind
				return relaySessionTreeRequest(ctx, appSocketPath, request)
			},
		},
	}
}

// 아래 요청 타입들이 앱 소켓 줄의 모양이다(6.4). 모델의 인자를 그대로 흘리지 않고 타입으로 받는
// 이유는 이 줄이 앱과 맺는 계약이어서다 — 모델이 무엇을 더 붙여 보내든 앱이 받는 모양은 여기서 정해진다.
//
// 선택 인자는 포인터다. 주지 않은 값은 null 로 가고, 앱이 "생략"(기본값을 쓴다)과 "빈 값" 을 가른다.

type spawnSessionRequest struct {
	Kind            string  `json:"kind"`
	Prompt          string  `json:"prompt"`
	Cwd             *string `json:"cwd"`
	CreateDirectory bool    `json:"createDirectory"`
	Title           *string `json:"title"`
}

type sendToSessionRequest struct {
	Kind    string `json:"kind"`
	ID      string `json:"id"`
	Message string `json:"message"`
}

type awaitSessionRequest struct {
	Kind        string   `json:"kind"`
	IDs         []string `json:"ids"`
	WaitSeconds *int     `json:"waitSeconds"`
}

type listSessionsRequest struct {
	Kind string `json:"kind"`
}

type endSessionRequest struct {
	Kind string `json:"kind"`
	ID   string `json:"id"`
}

type askParentRequest struct {
	Kind    string `json:"kind"`
	Message string `json:"message"`
}

// awaitSessions 는 기다림의 범위를 정해 앱에게 넘긴다.
//
// 범위 밖을 깎아 맞추지 않고 거절한다. 3600 을 준 모델에게 조용히 3000 을 기다리게 하면, 모델은
// 자기가 정한 시간을 기다렸다고 믿고 그 다음 판단을 한다.
func awaitSessions(ctx context.Context, appSocketPath string, arguments json.RawMessage) (mcpserver.ToolResult, error) {
	var request awaitSessionRequest
	if refusal, refused := decodeToolArguments(arguments, &request); refused {
		return refusal, nil
	}

	// 빈 배열은 "아무도 기다리지 않는다" 와 "전부 기다린다" 사이에서 뜻이 갈린다. 전부를 원하면 생략한다.
	if request.IDs != nil && len(request.IDs) == 0 {
		return toolErrorResult(fmt.Sprintf("%s 의 ids 가 비어 있습니다 — 살아 있는 하위 전부를 기다리려면 ids 를 생략합니다",
			awaitSessionToolName)), nil
	}

	waitSeconds := defaultAwaitWaitSeconds
	if request.WaitSeconds != nil {
		waitSeconds = *request.WaitSeconds
	}
	if waitSeconds < 1 || waitSeconds > maximumAwaitWaitSeconds {
		return toolErrorResult(fmt.Sprintf("%s 의 waitSeconds 는 1 이상 %d 이하입니다 (받은 값: %d) — 더 오래 기다리려면 답을 받은 뒤 다시 부릅니다",
			awaitSessionToolName, maximumAwaitWaitSeconds, waitSeconds)), nil
	}

	request.Kind = awaitSessionRequestKind
	request.WaitSeconds = &waitSeconds
	return relaySessionTreeRequest(ctx, appSocketPath, request)
}

// relaySessionTreeRequest 는 요청 한 줄을 앱에게 보내고, 앱의 답을 도구 결과로 옮긴다.
//
// 앱의 거절은 {"error": "…"} 한 모양이다(6.4) — 모르는 kind 도, 트리 규칙에 걸린 요청도 같다. 그것을
// 도구 오류로 올려야 모델이 하위가 뜨지 않았다는 것과 고칠 것을 함께 안다.
func relaySessionTreeRequest(ctx context.Context, appSocketPath string, request any) (mcpserver.ToolResult, error) {
	answerLine, err := exchangeLineWithApp(ctx, appSocketPath, request)
	if err != nil {
		return toolErrorResult(fmt.Sprintf(
			"이 세션을 연 앱에 닿지 못했습니다 (%v) — 앱 창이 살아 있는지 사용자에게 확인하세요", err)), nil
	}

	var answerFields map[string]json.RawMessage
	if err := json.Unmarshal(answerLine, &answerFields); err != nil {
		return toolErrorResult(fmt.Sprintf(
			"앱의 답을 읽지 못했습니다 (%v) — 앱과 kyu 의 판이 어긋났을 수 있습니다: %s", err, bytes.TrimSpace(answerLine))), nil
	}

	if refusal, refused := answerFields["error"]; refused {
		var refusalText string
		if err := json.Unmarshal(refusal, &refusalText); err != nil {
			refusalText = string(refusal)
		}
		return toolErrorResult(refusalText), nil
	}

	return renderToolAnswer(json.RawMessage(answerLine))
}

// decodeToolArguments 는 모델이 준 인자를 요청 타입으로 읽는다. 읽지 못하면 그 이유를 도구 오류로 돌려준다.
//
// 모르는 인자를 거절한다. 오타(waitSecond)를 조용히 버리면 모델은 그 인자가 먹힌 줄 안다.
func decodeToolArguments(arguments json.RawMessage, request any) (mcpserver.ToolResult, bool) {
	if len(bytes.TrimSpace(arguments)) == 0 {
		arguments = json.RawMessage(`{}`)
	}

	decoder := json.NewDecoder(bytes.NewReader(arguments))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(request); err != nil {
		return toolErrorResult(fmt.Sprintf("도구 인자를 읽지 못했습니다: %v", err)), true
	}
	return mcpserver.ToolResult{}, false
}

func missingArgumentResult(toolName, argumentName string) mcpserver.ToolResult {
	return toolErrorResult(fmt.Sprintf("%s 에는 %s 가 필요합니다", toolName, argumentName))
}

func toolErrorResult(message string) mcpserver.ToolResult {
	return mcpserver.ToolResult{Text: message, IsError: true}
}

// objectSchema 는 추가 인자를 받지 않는 객체 스키마다.
func objectSchema(required []string, properties map[string]any) map[string]any {
	schema := map[string]any{
		"type":                 "object",
		"properties":           properties,
		"additionalProperties": false,
	}
	if len(required) > 0 {
		schema["required"] = required
	}
	return schema
}
