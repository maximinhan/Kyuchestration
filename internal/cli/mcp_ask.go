package cli

import (
	"bufio"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"runtime/debug"
	"strings"
	"time"

	"github.com/maximinhan/Kyuchestration/internal/mcpserver"
)

// 이 파일은 kyu mcp ask 다 — 승인 물음을 앱에게 중계하는 자리(chat-ui-design.md 5.2.2 · 5.4).
//
// **구조가 이 명령의 존재 이유다.** 승인이 필요한 도구 호출마다 claude 는
// --permission-prompt-tool 이 가리키는 MCP 도구를 부르는데(실측 3.5), 그 도구를 여는 서버는
// claude 의 **자식**이고 결정을 내리는 사람은 **앱(부모)** 앞에 있다. 자식에서 부모로 물어볼
// 통로가 있어야 하고, 그 통로가 유닉스 도메인 소켓이다(5.4 가 TCP 루프백과 파일 폴링을 기각한
// 이유도 그 자리에 적혀 있다).
//
// **모델은 이 도구를 볼 수 없다.** --permission-prompt-tool 로 지정된 도구는 init 의 tools
// 목록에서 사라진다(실측 3.5 · A.7) — 모델이 자기 승인 도구를 스스로 불러 통과하는 길이 애초에 없다.
//
// **판단하지 않는다.** 이 서버는 물음을 나르고 답을 옮길 뿐, 무엇을 허용할지 정하지 않는다.
// 그 판단은 사람이 앱에서 하고, 규칙을 두더라도 그것은 앱의 것이다 — 여기에 규칙을 두면
// 사용자가 보지 못하는 자리에서 승인이 일어난다.

// mcpAskSubcommandName 은 이 명령의 하위 명령 이름이다.
//
// approve 와 가깝다는 것을 알고 고른 이름이다(설계 10 절 열린 질문 7). 둘 다 승인이지만 대상이
// 다르다 — approve 는 레포를 한 번 승인하는 사람의 명령이고, ask 는 도구 호출 하나를 그때그때
// 묻는 기계의 명령이다. 사람이 칠 일이 없는 이름이라 짧은 쪽을 쓴다.
const mcpAskSubcommandName = "ask"

// requestPermissionToolName 은 이 서버가 여는 유일한 도구다.
//
// 이 이름은 argv 에 그대로 실린다 — --permission-prompt-tool mcp__kyu-ask__request_permission
// (claude_command.go 의 permissionPromptToolName). 여기서 이름을 바꾸면 claude 는 없는 도구를
// 가리키게 되고, 그 실패는 "관문이 원래 잘 안 열리나 보다" 로 오래 숨는다.
const requestPermissionToolName = "request_permission"

const requestPermissionToolDescription = `이 도구 호출을 사람이 승인했는지 앱에게 물어 답한다.

모델에게 보이는 도구가 아니다. claude 가 --permission-prompt-tool 로 이 도구를 가리키면
승인이 필요한 호출마다 claude 자신이 부른다.`

// appAnswerReadLimit 은 앱의 답 한 줄에서 읽을 최대 바이트다.
//
// 앱이 고친 인자가 이 줄에 실려 온다 — Write 의 내용이 통째로 들어갈 수 있어 넉넉해야 한다.
// 그래도 한계를 두는 이유는 소켓 너머가 앱이라는 보장이 코드에 없어서다: 끝없이 흘려보내는
// 상대를 만나면 이 프로세스가 메모리를 다 쓸 때까지 읽는다.
const appAnswerReadLimit = 8 << 20

// appConnectTimeout 은 앱 소켓에 붙는 데까지 기다리는 시간이다.
//
// **답을 기다리는 시간에는 시한이 없다.** 실측 3.7 이 150 초 뒤의 답도 통과하는 것을 쟀고,
// 상한을 우리가 정하면 그 숫자보다 오래 고민한 사용자가 이유 없이 거절당한다(설계 5.4.1).
// 여기 있는 시한은 "붙는" 데까지만이다 — 앱이 없으면 곧바로 실패하는 자리라 이 값이 걸리는
// 경우는 소켓 파일은 있는데 듣는 쪽이 멎어 있는 때뿐이다.
const appConnectTimeout = 5 * time.Second

// relayPermissionQuestions 는 그 소켓의 앱에게 승인을 묻는 stdio MCP 서버를 연다.
//
// 소켓 경로를 인자로 받는다. 이 프로세스는 자기가 어느 세션에 속하는지 알 방법이 없고 — claude
// 가 자식으로 띄운다 — 앱이 세션마다 소켓을 하나씩 여므로(5.4), 경로 자체가 곧 어느 세션인지다.
func relayPermissionQuestions(in io.Reader, out io.Writer, args []string) error {
	socketPath, err := parseAskArgs(args)
	if err != nil {
		return err
	}

	buildInfo, _ := debug.ReadBuildInfo()

	return mcpserver.Serve(in, out, permissionAskServerName, versionName(buildInfo),
		[]mcpserver.Tool{newRequestPermissionTool(socketPath)})
}

// newRequestPermissionTool 은 그 소켓에 묻는 승인 도구 하나를 만든다.
func newRequestPermissionTool(socketPath string) mcpserver.Tool {
	return mcpserver.Tool{
		Name:        requestPermissionToolName,
		Description: requestPermissionToolDescription,

		// 인자 이름은 claude 가 정한 것이다(실측 A.5) — snake_case 이고, 앱에게 넘길 때
		// 우리 표기(camelCase)로 옮긴다. 두 이름을 한 자리에서 마주 보게 두는 것이
		// permissionRequestArguments 와 permissionQuestion 이다.
		InputSchema: map[string]any{
			"type": "object",
			"properties": map[string]any{
				"tool_name":   map[string]any{"type": "string"},
				"input":       map[string]any{"type": "object"},
				"tool_use_id": map[string]any{"type": "string"},
			},
			"required": []any{"tool_name", "input"},
		},

		Call: func(arguments json.RawMessage) (mcpserver.ToolResult, error) {
			return answerPermissionQuestion(socketPath, arguments)
		},
	}
}

// permissionRequestArguments 는 claude 가 승인 도구에 넘기는 인자다(실측 3.5 · A.5).
type permissionRequestArguments struct {
	ToolName  string          `json:"tool_name"`
	Input     json.RawMessage `json:"input"`
	ToolUseID string          `json:"tool_use_id"`
}

// permissionQuestion 은 이 서버가 앱에게 보내는 줄이다(설계 5.4.1).
//
// 줄 단위 JSON 이다 — kyu 의 MCP 서버가 이미 줄 단위 JSON-RPC 를 말하고(internal/mcpserver),
// 새 인코딩을 들일 이유가 없다.
type permissionQuestion struct {
	ToolName  string          `json:"toolName"`
	Input     json.RawMessage `json:"input"`
	ToolUseID string          `json:"toolUseId"`
}

// appPermissionAnswer 는 앱이 돌려주는 줄이다(설계 5.4.1).
type appPermissionAnswer struct {
	Decision     string          `json:"decision"`
	UpdatedInput json.RawMessage `json:"updatedInput"`
	Reason       string          `json:"reason"`
}

// 앱이 말할 수 있는 결정 둘. 그 밖의 낱말은 모르는 답이고, 모르는 답은 거절이다.
const (
	appDecisionAllow = "allow"
	appDecisionDeny  = "deny"
)

// permissionDecisionDocument 는 claude 가 도구 결과의 텍스트 안에서 읽는 문서다(실측 3.5 · A.5).
//
// **이 모양이 관문의 계약 전부다.** allow 면 updatedInput 대로 실행되고 — 바꿔 보낸 인자로
// 실제로 돈다 — deny 면 message 가 tool_result 의 문구가 되어 모델에게 간다.
type permissionDecisionDocument struct {
	Behavior string `json:"behavior"`

	// UpdatedInput 은 allow 일 때 실제로 실행될 인자다. deny 에는 싣지 않는다.
	UpdatedInput json.RawMessage `json:"updatedInput,omitempty"`

	// Message 는 deny 의 이유다. 모델이 읽고 사용자에게 전한다.
	Message string `json:"message,omitempty"`
}

// answerPermissionQuestion 은 claude 의 물음을 앱에게 옮기고 앱의 답을 claude 의 말로 옮긴다.
//
// 어디서 실패하든 거절로 끝난다. 물어보지 못한 것을 허용으로 읽으면 사람이 한 번도 보지 않은
// 도구가 실행되고, 그 실패는 조용하다 — 안전한 쪽으로 기우는 것이 이 자리의 규율이다.
func answerPermissionQuestion(socketPath string, arguments json.RawMessage) (mcpserver.ToolResult, error) {
	var request permissionRequestArguments
	if err := json.Unmarshal(arguments, &request); err != nil {
		return deniedToolResult(fmt.Sprintf("승인 물음을 읽지 못해 실행하지 않았습니다: %v", err))
	}

	answer, err := askTheApp(socketPath, permissionQuestion{
		ToolName:  request.ToolName,
		Input:     request.Input,
		ToolUseID: request.ToolUseID,
	})
	if err != nil {
		return deniedToolResult(fmt.Sprintf(
			"승인을 물어볼 앱에 닿지 못해 실행하지 않았습니다 (%v) — 이 세션을 연 앱 창이 살아 있는지 사용자에게 확인하세요", err))
	}

	switch answer.Decision {
	case appDecisionAllow:
		// 앱이 인자를 고치지 않았다는 뜻이다. 원본을 그대로 실어야 모델이 요청한 그대로 돈다 —
		// 비워 보내면 claude 는 인자 없는 호출로 읽는다.
		updatedInput := answer.UpdatedInput
		if len(updatedInput) == 0 {
			updatedInput = request.Input
		}
		return allowedToolResult(updatedInput)

	case appDecisionDeny:
		reason := answer.Reason
		if reason == "" {
			reason = "사용자가 이 도구 호출을 거절했습니다"
		}
		return deniedToolResult(reason)

	default:
		// 모르는 낱말을 허용으로 읽지 않는다. 앱과 이 서버의 판이 어긋난 자리이고, 그 어긋남을
		// 사용자가 알게 되는 통로는 모델이 전하는 이 문구뿐이다.
		return deniedToolResult(fmt.Sprintf(
			"앱이 보낸 답(%q)을 이 엔진이 알아듣지 못해 실행하지 않았습니다 — 앱과 kyu 의 판이 어긋났습니다", answer.Decision))
	}
}

// askTheApp 은 소켓 하나를 열어 물음 한 줄을 보내고 답 한 줄을 받는다.
//
// 물음마다 새로 연다. 연결 하나를 계속 쥐고 있으면 두 물음이 겹칠 때 어느 답이 어느 물음의
// 것인지를 이 프로토콜 안에 새로 만들어야 하는데(요청 ID 와 그것을 맞추는 자리), 물음마다
// 연결을 여는 한 그 짝은 연결 자체가 진다.
func askTheApp(socketPath string, question permissionQuestion) (appPermissionAnswer, error) {
	connection, err := net.DialTimeout("unix", socketPath, appConnectTimeout)
	if err != nil {
		return appPermissionAnswer{}, err
	}
	defer connection.Close()

	askedLine, err := json.Marshal(question)
	if err != nil {
		return appPermissionAnswer{}, fmt.Errorf("물음을 조립하지 못했습니다: %w", err)
	}
	if _, err := connection.Write(append(askedLine, '\n')); err != nil {
		return appPermissionAnswer{}, fmt.Errorf("물음을 보내지 못했습니다: %w", err)
	}

	// 시한을 두지 않는다(appConnectTimeout 의 주석). 앱이 죽으면 소켓이 닫히면서 이 읽기가
	// EOF 로 끝나므로, 사람이 사라진 경우와 앱이 사라진 경우는 여기서 갈린다.
	answerLine, err := bufio.NewReader(io.LimitReader(connection, appAnswerReadLimit)).ReadBytes('\n')
	if err != nil && len(answerLine) == 0 {
		return appPermissionAnswer{}, fmt.Errorf("앱이 답하지 않고 연결을 닫았습니다: %w", err)
	}

	var answer appPermissionAnswer
	if err := json.Unmarshal(answerLine, &answer); err != nil {
		return appPermissionAnswer{}, fmt.Errorf("앱의 답을 JSON 으로 읽지 못했습니다: %w", err)
	}
	return answer, nil
}

func allowedToolResult(updatedInput json.RawMessage) (mcpserver.ToolResult, error) {
	return permissionToolResult(permissionDecisionDocument{
		Behavior:     appDecisionAllow,
		UpdatedInput: updatedInput,
	})
}

func deniedToolResult(reason string) (mcpserver.ToolResult, error) {
	return permissionToolResult(permissionDecisionDocument{
		Behavior: appDecisionDeny,
		Message:  reason,
	})
}

// permissionToolResult 는 결정 문서를 도구 결과의 본문으로 옮긴다.
//
// 다른 도구의 답과 달리 들여쓰지 않는다(renderToolAnswer 를 쓰지 않는 이유다). 저쪽은 모델이
// 눈으로 읽는 문서라 사람이 읽기 좋은 모양이 뜻을 지지만, 이 문서를 읽는 것은 claude 의 권한
// 계층이고 그 안에 실린 updatedInput 은 앱이 보낸 인자 원문이다 — 다시 찍으면 사용자가 카드에서
// 고친 그 글자가 이 자리에서 한 번 더 손질된다.
//
// **거절도 IsError 로 올리지 않는다.** 거절은 도구가 못 한 일이 아니라 도구가 답한 것이다.
// IsError 로 올리면 claude 는 그것을 관문의 답이 아니라 서버의 고장으로 읽는다.
func permissionToolResult(decision permissionDecisionDocument) (mcpserver.ToolResult, error) {
	rendered, err := json.Marshal(decision)
	if err != nil {
		return mcpserver.ToolResult{}, fmt.Errorf("승인 결정 조립 실패: %w", err)
	}
	return mcpserver.ToolResult{Text: string(rendered)}, nil
}

func parseAskArgs(args []string) (string, error) {
	var socketPath string

	for _, arg := range args {
		switch {
		// 모르는 옵션을 경로로 흘려보내면 그 서버는 있지도 않은 소켓에 묻는 서버가 되고,
		// 모든 승인이 거절된다.
		case strings.HasPrefix(arg, "-"):
			return "", fmt.Errorf("알 수 없는 옵션: %s\n\n%s", arg, mcpUsageText)

		case socketPath != "":
			return "", fmt.Errorf("mcp %s 는 소켓 경로 하나만 받습니다 (인자 %d 개를 받음)\n\n%s",
				mcpAskSubcommandName, len(args), mcpUsageText)

		default:
			socketPath = arg
		}
	}

	if socketPath == "" {
		return "", fmt.Errorf("mcp %s 는 어느 소켓에 물을지 알아야 합니다\n\n%s", mcpAskSubcommandName, mcpUsageText)
	}
	return socketPath, nil
}
