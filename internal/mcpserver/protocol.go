// Package mcpserver 는 이 도구가 모델에게 도구를 보이는 통로다 — stdio 위의 MCP 서버 한 벌.
//
// 설계 문서 5.1 이 "메인 세션의 Claude 가 세 번째 표시 계층이 된다" 라고 적은 자리다. CLI 가
// 사람에게 표를 보이고, 앱이 사람에게 카드를 보이고, 이 패키지가 모델에게 도구를 보인다.
// 셋 다 같은 조율 계층(internal/workdir)을 부른다.
//
// 이 패키지는 이 도구가 무엇을 하는지 모른다. 프로토콜(줄 단위 JSON-RPC 2.0)과 도구 등록만
// 알고, 도구가 실제로 하는 일은 부르는 쪽이 넘긴다 — 그래야 프로토콜의 옳고 그름을 이 도구의
// 도구들과 따로 검증할 수 있다.
//
// 라이브러리를 들이지 않는다. 릴리스는 새 머신에서 바이너리 하나 복사로 끝나야 하고
// (설계 문서 8.1), 이 서버가 답해야 하는 메서드는 다섯 개다.
package mcpserver

import (
	"bufio"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"sync"
)

// defaultProtocolVersion 은 클라이언트가 판을 적지 않았을 때 이 서버가 부르는 판이다.
//
// 클라이언트가 부른 판이 있으면 그것을 그대로 돌려준다 — 이 서버가 쓰는 것은 판이 갈려도
// 모양이 바뀌지 않은 부분(initialize · tools/list · tools/call)뿐이라, 아는 판을 우겨서
// 클라이언트가 말할 줄 모르는 판을 받게 만들 이유가 없다.
const defaultProtocolVersion = "2025-06-18"

// JSON-RPC 2.0 이 정한 오류 코드 중 이 서버가 쓰는 것들이다.
const (
	parseErrorCode          = -32700
	invalidRequestErrorCode = -32600
	methodNotFoundErrorCode = -32601
	invalidParamsErrorCode  = -32602
	internalErrorCode       = -32603
)

// ToolResult 는 도구 하나가 모델에게 돌려주는 답이다.
type ToolResult struct {
	// Text 는 모델이 읽는 본문이다.
	Text string

	// IsError 는 도구가 시킨 일을 하지 못했다는 뜻이다.
	//
	// 프로토콜 오류로 올리지 않고 결과에 싣는 이유가 이 도구에는 둘 있다 — 권한에 막힌 위임과
	// (설계 문서 5.4.2) 아직 승인되지 않은 레포의 관문(5.6). 둘 다 모델이 이유를 읽고 사용자에게
	// 전해야 하는 자리이고, JSON-RPC 오류로 올리면 그 이유가 모델에게 닿지 않는다.
	IsError bool
}

// Tool 은 모델에게 보이는 도구 하나다.
type Tool struct {
	// Name 은 모델이 부르는 이름이다.
	Name string

	// Description 은 모델이 이 도구를 언제 부를지 정하는 근거다.
	Description string

	// InputSchema 는 인자의 JSON Schema 다. 이것이 없으면 모델이 인자를 지어낸다.
	InputSchema map[string]any

	// Call 은 도구가 실제로 하는 일이다.
	//
	// 인자를 파싱된 값이 아니라 원문으로 받는다. 도구마다 인자 모양이 다르고, 그 모양을 아는
	// 것은 도구 자신이지 이 패키지가 아니다.
	//
	// 에러를 돌려주는 것과 IsError 를 세우는 것은 다른 일이다. 에러는 "서버가 이 요청을 처리할
	// 수 없었다" 이고 클라이언트의 오류 화면으로 간다. IsError 는 "도구가 일을 못 했다" 이고
	// 모델이 읽는 답이 된다.
	Call func(arguments json.RawMessage) (ToolResult, error)
}

// Serve 는 in 에서 요청을 읽어 out 으로 답한다. in 이 끝나면(클라이언트가 닫으면) 돌아온다.
//
// out 에는 JSON-RPC 문서만 나간다. 이 서버의 stdout 은 프로토콜 채널이라, 진단 한 줄이 섞이면
// 클라이언트가 그 자리에서 연결을 잃는다 — 부르는 쪽은 로그를 stderr 로 보내야 한다.
//
// 요청마다 따로 처리한다. 한 줄로 세우면 오래 도는 위임 하나가 그동안의 모든 요청을 막고,
// 설계 문서 5.7 이 열어둔 동시 위임이 성립하지 않는다.
func Serve(in io.Reader, out io.Writer, serverName, serverVersion string, tools []Tool) error {
	server := &server{
		name:     serverName,
		version:  serverVersion,
		tools:    toolsByName(tools),
		toolList: append([]Tool(nil), tools...),
		out:      out,
	}
	return server.readRequests(in)
}

func toolsByName(tools []Tool) map[string]Tool {
	byName := make(map[string]Tool, len(tools))
	for _, tool := range tools {
		byName[tool.Name] = tool
	}
	return byName
}

type server struct {
	name    string
	version string

	tools map[string]Tool

	// toolList 는 tools/list 가 답할 순서다. map 은 순회 순서가 매번 달라서, 그것으로 답하면
	// 같은 서버가 실행마다 다른 순서의 목록을 낸다.
	toolList []Tool

	out io.Writer

	// writeMutex 는 답 한 줄이 다른 답과 섞이지 않게 한다. 요청을 동시에 처리하므로 두 답이
	// 같은 줄에 겹쳐 쓰이면 클라이언트가 그 자리에서 문서를 잃는다.
	writeMutex sync.Mutex

	// inFlight 는 아직 답하지 않은 요청들이다. in 이 끝나도 도는 중인 도구의 답까지 내보낸 뒤에
	// 돌아가야, 클라이언트가 마지막 위임의 결과를 잃지 않는다.
	inFlight sync.WaitGroup
}

// readRequests 는 줄 단위로 요청을 읽어 각각을 따로 처리한다.
//
// bufio.Scanner 를 쓰지 않는다. 그것은 줄 길이에 상한이 있고, 위임 프롬프트는 계획의 확정
// 인터페이스를 그대로 옮겨 적은 것이라 그 상한을 넘을 수 있다 — 넘으면 조용히 잘린다.
func (server *server) readRequests(in io.Reader) error {
	reader := bufio.NewReader(in)

	for {
		line, err := reader.ReadBytes('\n')

		// 마지막 줄에 개행이 없어도 요청이다. 읽은 것을 먼저 처리하고 끝낸다.
		if len(line) > 0 {
			server.handleLine(line)
		}

		if errors.Is(err, io.EOF) {
			break
		}
		if err != nil {
			server.inFlight.Wait()
			return fmt.Errorf("MCP 요청 읽기 실패: %w", err)
		}
	}

	server.inFlight.Wait()
	return nil
}

// jsonRPCRequest 는 받은 한 줄이다.
//
// ID 를 원문으로 들고 있는다. JSON-RPC 의 ID 는 문자열일 수도 숫자일 수도 있고, 답은 받은 것과
// 같은 모양이어야 한다 — 숫자로 읽어 숫자로 답하면 문자열 ID 를 쓰는 클라이언트의 짝이 어긋난다.
type jsonRPCRequest struct {
	ID     json.RawMessage `json:"id"`
	Method string          `json:"method"`
	Params json.RawMessage `json:"params"`
}

func (server *server) handleLine(line []byte) {
	var request jsonRPCRequest
	if err := json.Unmarshal(line, &request); err != nil {
		// 깨진 줄에는 ID 가 없다. JSON-RPC 는 그 경우 id 를 null 로 답하라고 정한다.
		server.writeError(nil, parseErrorCode, fmt.Sprintf("요청을 JSON 으로 읽지 못했습니다: %v", err))
		return
	}

	// ID 가 없는 것은 알림이다. 답을 흘리면 클라이언트가 짝 없는 답을 받는다.
	if len(request.ID) == 0 {
		return
	}

	if request.Method == "" {
		server.writeError(request.ID, invalidRequestErrorCode, "method 가 비어 있습니다")
		return
	}

	server.inFlight.Add(1)
	go func() {
		defer server.inFlight.Done()
		server.handleRequest(request)
	}()
}

func (server *server) handleRequest(request jsonRPCRequest) {
	switch request.Method {
	case "initialize":
		server.writeResult(request.ID, server.initializeResult(request.Params))

	case "ping":
		// 빈 객체가 곧 답이다. nil 로 두면 result 필드가 사라져 오류 응답처럼 보인다.
		server.writeResult(request.ID, map[string]any{})

	case "tools/list":
		server.writeResult(request.ID, map[string]any{"tools": server.describeTools()})

	case "tools/call":
		server.callTool(request)

	default:
		server.writeError(request.ID, methodNotFoundErrorCode, fmt.Sprintf("이 서버가 모르는 메서드입니다: %s", request.Method))
	}
}

func (server *server) initializeResult(params json.RawMessage) map[string]any {
	var initializeParams struct {
		ProtocolVersion string `json:"protocolVersion"`
	}
	// 읽지 못해도 초기화는 성립한다 — 판을 알아내지 못했을 뿐이고, 그때 쓸 값이 아래에 있다.
	_ = json.Unmarshal(params, &initializeParams)

	protocolVersion := initializeParams.ProtocolVersion
	if protocolVersion == "" {
		protocolVersion = defaultProtocolVersion
	}

	return map[string]any{
		"protocolVersion": protocolVersion,

		// tools 를 비워 두어도 "도구가 있다" 는 선언이다. 이것이 없으면 클라이언트는 tools/list 를
		// 한 번도 부르지 않는다.
		"capabilities": map[string]any{"tools": map[string]any{}},
		"serverInfo":   map[string]any{"name": server.name, "version": server.version},
	}
}

func (server *server) describeTools() []map[string]any {
	described := make([]map[string]any, 0, len(server.toolList))
	for _, tool := range server.toolList {
		described = append(described, map[string]any{
			"name":        tool.Name,
			"description": tool.Description,
			"inputSchema": tool.InputSchema,
		})
	}
	return described
}

func (server *server) callTool(request jsonRPCRequest) {
	var callParams struct {
		Name      string          `json:"name"`
		Arguments json.RawMessage `json:"arguments"`
	}
	if err := json.Unmarshal(request.Params, &callParams); err != nil {
		server.writeError(request.ID, invalidParamsErrorCode, fmt.Sprintf("도구 호출 인자를 읽지 못했습니다: %v", err))
		return
	}

	tool, registered := server.tools[callParams.Name]
	if !registered {
		server.writeError(request.ID, invalidParamsErrorCode, fmt.Sprintf("이 서버에 없는 도구입니다: %s", callParams.Name))
		return
	}

	result, err := tool.Call(callParams.Arguments)
	if err != nil {
		server.writeError(request.ID, internalErrorCode, err.Error())
		return
	}

	server.writeResult(request.ID, map[string]any{
		"content": []map[string]any{{"type": "text", "text": result.Text}},
		"isError": result.IsError,
	})
}

// jsonRPCResponse 는 내보내는 한 줄이다.
//
// Result 와 Error 중 하나만 실린다. 둘 다 비면 클라이언트는 무엇도 아닌 답을 받으므로,
// 부르는 쪽이 반드시 둘 중 하나를 채운다.
type jsonRPCResponse struct {
	JSONRPC string          `json:"jsonrpc"`
	ID      json.RawMessage `json:"id"`
	Result  any             `json:"result,omitempty"`
	Error   *jsonRPCError   `json:"error,omitempty"`
}

type jsonRPCError struct {
	Code    int    `json:"code"`
	Message string `json:"message"`
}

func (server *server) writeResult(id json.RawMessage, result any) {
	server.writeResponse(jsonRPCResponse{JSONRPC: "2.0", ID: id, Result: result})
}

func (server *server) writeError(id json.RawMessage, code int, message string) {
	if id == nil {
		id = json.RawMessage("null")
	}
	server.writeResponse(jsonRPCResponse{JSONRPC: "2.0", ID: id, Error: &jsonRPCError{Code: code, Message: message}})
}

// writeResponse 는 답 한 줄을 내보낸다.
//
// 쓰기 실패를 삼킨다. 이 통로가 stdout 이고 그것이 닫혔다는 것은 클라이언트가 이미 사라졌다는
// 뜻이라, 알릴 상대가 없다. 다음 읽기가 EOF 로 끝나면서 서버도 함께 끝난다.
func (server *server) writeResponse(response jsonRPCResponse) {
	document, err := json.Marshal(response)
	if err != nil {
		// 답을 조립하지 못하는 것은 도구가 직렬화할 수 없는 값을 돌려준 경우뿐이다.
		// 그것도 답이 없는 것보다는 낫게 알린다 — 짝 없는 요청으로 남으면 클라이언트가 멈춘다.
		document, err = json.Marshal(jsonRPCResponse{
			JSONRPC: "2.0",
			ID:      response.ID,
			Error:   &jsonRPCError{Code: internalErrorCode, Message: fmt.Sprintf("답을 조립하지 못했습니다: %v", err)},
		})
		if err != nil {
			return
		}
	}

	server.writeMutex.Lock()
	defer server.writeMutex.Unlock()

	// 한 번에 쓴다. 문서와 개행을 나눠 쓰면 그 사이에 다른 답이 끼어들 수 있는데, 잠금 안이라도
	// 두 번의 Write 는 부분 쓰기를 두 번 겪는다.
	server.out.Write(append(document, '\n'))
}
