package mcpserver

import (
	"encoding/json"
	"fmt"
	"io"
	"strings"
	"sync"
	"testing"
	"time"
)

// answeredMessage 는 서버가 내보낸 한 줄을 되읽은 것이다.
//
// 생산 코드의 직렬화 타입을 재사용하지 않는다. 이 모양은 도구 밖(claude 의 MCP 클라이언트)과 맺는
// 계약이라, 테스트가 그것을 따로 적어두어야 이름이나 중첩이 바뀌는 순간 여기에서 먼저 깨진다.
type answeredMessage struct {
	JSONRPC string          `json:"jsonrpc"`
	ID      json.RawMessage `json:"id"`
	Result  json.RawMessage `json:"result"`
	Error   *struct {
		Code    int    `json:"code"`
		Message string `json:"message"`
	} `json:"error"`
}

// serveLinesForTest 는 요청 줄들을 서버에 흘리고 답한 줄들을 되읽어 돌려준다.
//
// 답을 요청 순서로 기대하지 않는다. 서버는 요청마다 따로 답하므로(설계 문서 5.7 이 요구하는
// 동시 위임이 그 이유다) 오래 도는 도구 뒤의 요청이 먼저 답할 수 있다.
func serveLinesForTest(t *testing.T, tools []Tool, requestLines ...string) []answeredMessage {
	t.Helper()

	var answered strings.Builder
	if err := Serve(strings.NewReader(strings.Join(requestLines, "\n")+"\n"), &answered, "kyu-test", "v0", tools); err != nil {
		t.Fatalf("Serve() 실패: %v", err)
	}
	return decodeAnsweredLinesForTest(t, answered.String())
}

func decodeAnsweredLinesForTest(t *testing.T, answered string) []answeredMessage {
	t.Helper()

	var messages []answeredMessage
	for _, line := range strings.Split(strings.TrimSuffix(answered, "\n"), "\n") {
		if line == "" {
			continue
		}

		var message answeredMessage
		if err := json.Unmarshal([]byte(line), &message); err != nil {
			t.Fatalf("답한 줄을 JSON 으로 읽지 못했습니다: %v\n--- 줄 ---\n%s", err, line)
		}
		if message.JSONRPC != "2.0" {
			t.Errorf("jsonrpc = %q, want 2.0", message.JSONRPC)
		}
		messages = append(messages, message)
	}
	return messages
}

// answerWithID 는 답한 줄 중에서 그 요청 ID 에 대한 것을 고른다.
func answerWithID(t *testing.T, messages []answeredMessage, id string) answeredMessage {
	t.Helper()

	for _, message := range messages {
		if string(message.ID) == id {
			return message
		}
	}
	t.Fatalf("id %s 에 대한 답이 없습니다 (받은 답 %d 개)", id, len(messages))
	return answeredMessage{}
}

// probeTool 은 인자를 그대로 되돌려 주는 시험용 도구다.
func probeTool() Tool {
	return Tool{
		Name:        "repo_fingerprint",
		Description: "받은 인자를 그대로 돌려준다",
		InputSchema: map[string]any{
			"type":       "object",
			"properties": map[string]any{"repo": map[string]any{"type": "string"}},
			"required":   []any{"repo"},
		},
		Call: func(arguments json.RawMessage) (ToolResult, error) {
			return ToolResult{Text: "받은 인자: " + string(arguments)}, nil
		},
	}
}

func TestInitializeAnswersTheProtocolVersionCapabilitiesAndServerInfo(t *testing.T) {
	// 클라이언트는 이 답을 보고 이 서버와 무엇을 할 수 있는지 정한다. capabilities 에 tools 가
	// 없으면 도구를 한 번도 묻지 않는다 — 서버는 떠 있는데 도구가 보이지 않는 자리다.
	messages := serveLinesForTest(t, []Tool{probeTool()},
		`{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"claude","version":"2.1.252"}}}`)

	var result struct {
		ProtocolVersion string `json:"protocolVersion"`
		Capabilities    struct {
			Tools *struct{} `json:"tools"`
		} `json:"capabilities"`
		ServerInfo struct {
			Name    string `json:"name"`
			Version string `json:"version"`
		} `json:"serverInfo"`
	}
	if err := json.Unmarshal(answerWithID(t, messages, "1").Result, &result); err != nil {
		t.Fatalf("initialize 의 답을 읽지 못했습니다: %v", err)
	}

	// 클라이언트가 부른 판을 그대로 돌려준다. 우리가 아는 판을 우기면 그 클라이언트는 자기가
	// 말할 줄 모르는 판을 받는다.
	if result.ProtocolVersion != "2025-06-18" {
		t.Errorf("protocolVersion = %q, 클라이언트가 부른 판을 기대", result.ProtocolVersion)
	}
	if result.Capabilities.Tools == nil {
		t.Error("capabilities.tools 가 없습니다 — 클라이언트가 도구를 묻지 않게 됩니다")
	}
	if result.ServerInfo.Name != "kyu-test" || result.ServerInfo.Version != "v0" {
		t.Errorf("serverInfo = %+v, 부르는 쪽이 정해준 이름과 버전을 기대", result.ServerInfo)
	}
}

func TestInitializeFallsBackToAVersionItKnowsWhenTheClientNamesNone(t *testing.T) {
	// 판을 적지 않은 클라이언트에게 빈 문자열을 돌려주면 그것은 "판 없음" 이라는 답이 된다.
	messages := serveLinesForTest(t, nil, `{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}`)

	var result struct {
		ProtocolVersion string `json:"protocolVersion"`
	}
	if err := json.Unmarshal(answerWithID(t, messages, "1").Result, &result); err != nil {
		t.Fatalf("initialize 의 답을 읽지 못했습니다: %v", err)
	}
	if result.ProtocolVersion != defaultProtocolVersion {
		t.Errorf("protocolVersion = %q, want %q", result.ProtocolVersion, defaultProtocolVersion)
	}
}

func TestNotificationsGetNoAnswer(t *testing.T) {
	// ID 가 없는 것은 알림이고, JSON-RPC 는 알림에 답하지 않는다. 답을 하나 흘리면 클라이언트는
	// 짝이 없는 답을 받고, 그 자리에서 연결을 접는 구현이 있다.
	messages := serveLinesForTest(t, nil,
		`{"jsonrpc":"2.0","method":"notifications/initialized"}`,
		`{"jsonrpc":"2.0","id":7,"method":"ping"}`)

	if len(messages) != 1 {
		t.Fatalf("답한 줄 = %d 개, 알림을 뺀 하나를 기대: %+v", len(messages), messages)
	}
	if string(messages[0].ID) != "7" {
		t.Errorf("답한 id = %s, want 7", messages[0].ID)
	}
}

func TestToolsListAnswersEveryRegisteredToolWithItsInputSchema(t *testing.T) {
	// 모델은 이 답만 보고 도구를 부른다. 스키마가 빠지면 인자를 지어내고, 그 실패는
	// "도구가 이상하게 동작한다" 로만 보인다.
	messages := serveLinesForTest(t, []Tool{probeTool()}, `{"jsonrpc":"2.0","id":2,"method":"tools/list"}`)

	var result struct {
		Tools []struct {
			Name        string         `json:"name"`
			Description string         `json:"description"`
			InputSchema map[string]any `json:"inputSchema"`
		} `json:"tools"`
	}
	if err := json.Unmarshal(answerWithID(t, messages, "2").Result, &result); err != nil {
		t.Fatalf("tools/list 의 답을 읽지 못했습니다: %v", err)
	}

	if len(result.Tools) != 1 {
		t.Fatalf("도구 = %d 개, 등록한 하나를 기대", len(result.Tools))
	}
	if result.Tools[0].Name != "repo_fingerprint" || result.Tools[0].Description == "" {
		t.Errorf("도구 = %+v, 이름과 설명을 기대", result.Tools[0])
	}
	if result.Tools[0].InputSchema["type"] != "object" {
		t.Errorf("inputSchema = %v, JSON Schema 객체를 기대", result.Tools[0].InputSchema)
	}
}

func TestToolsCallCarriesTheToolAnswerAsText(t *testing.T) {
	messages := serveLinesForTest(t, []Tool{probeTool()},
		`{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"repo_fingerprint","arguments":{"repo":"proj-a"}}}`)

	var result struct {
		Content []struct {
			Type string `json:"type"`
			Text string `json:"text"`
		} `json:"content"`
		IsError bool `json:"isError"`
	}
	if err := json.Unmarshal(answerWithID(t, messages, "3").Result, &result); err != nil {
		t.Fatalf("tools/call 의 답을 읽지 못했습니다: %v", err)
	}

	if len(result.Content) != 1 || result.Content[0].Type != "text" {
		t.Fatalf("content = %+v, 텍스트 한 덩어리를 기대", result.Content)
	}
	if !strings.Contains(result.Content[0].Text, `"repo":"proj-a"`) {
		t.Errorf("답 본문 = %q, 넘긴 인자가 도구에 그대로 닿기를 기대", result.Content[0].Text)
	}
	if result.IsError {
		t.Error("isError = true, 성공한 도구 호출을 기대")
	}
}

func TestAToolThatCouldNotDoItsJobAnswersAsIsErrorNotAsAProtocolError(t *testing.T) {
	// 도구가 일을 못 한 것은 프로토콜 실패가 아니다. JSON-RPC 오류로 올리면 모델은 그 이유를
	// 읽지 못하고, 사용자에게 무엇이 막혔는지 전할 수 없다 — 이 도구에서는 권한 거절과 관문이
	// 그 자리다(설계 문서 5.4.2 · 5.6).
	refusingTool := Tool{
		Name:        "run_in_repo",
		InputSchema: map[string]any{"type": "object"},
		Call: func(json.RawMessage) (ToolResult, error) {
			return ToolResult{Text: "레포의 .mcp.json 이 아직 승인되지 않았습니다", IsError: true}, nil
		},
	}

	messages := serveLinesForTest(t, []Tool{refusingTool},
		`{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"run_in_repo","arguments":{}}}`)

	answer := answerWithID(t, messages, "4")
	if answer.Error != nil {
		t.Fatalf("error = %+v, 도구의 거절은 결과로 오기를 기대", answer.Error)
	}

	var result struct {
		Content []struct {
			Text string `json:"text"`
		} `json:"content"`
		IsError bool `json:"isError"`
	}
	if err := json.Unmarshal(answer.Result, &result); err != nil {
		t.Fatalf("tools/call 의 답을 읽지 못했습니다: %v", err)
	}
	if !result.IsError {
		t.Error("isError = false, 도구가 일을 못 했음을 모델이 알아야 합니다")
	}
	if len(result.Content) != 1 || !strings.Contains(result.Content[0].Text, ".mcp.json") {
		t.Errorf("content = %+v, 거절 이유를 본문에 싣기를 기대", result.Content)
	}
}

func TestCallingAToolThatDoesNotExistIsAProtocolError(t *testing.T) {
	messages := serveLinesForTest(t, []Tool{probeTool()},
		`{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"없는_도구","arguments":{}}}`)

	answer := answerWithID(t, messages, "5")
	if answer.Error == nil {
		t.Fatal("없는 도구 호출이 성공했습니다")
	}
	if answer.Error.Code != invalidParamsErrorCode {
		t.Errorf("error.code = %d, want %d", answer.Error.Code, invalidParamsErrorCode)
	}
	if !strings.Contains(answer.Error.Message, "없는_도구") {
		t.Errorf("error.message = %q, 부른 이름을 함께 알리기를 기대", answer.Error.Message)
	}
}

func TestAMethodTheServerDoesNotKnowIsRefusedWithoutEndingTheConnection(t *testing.T) {
	// 모르는 메서드에 연결을 끊으면, 클라이언트가 선택적 기능(resources/list 등)을 한 번 물어본
	// 것만으로 서버가 사라진다.
	messages := serveLinesForTest(t, nil,
		`{"jsonrpc":"2.0","id":6,"method":"resources/list"}`,
		`{"jsonrpc":"2.0","id":7,"method":"ping"}`)

	if answerWithID(t, messages, "6").Error.Code != methodNotFoundErrorCode {
		t.Errorf("error.code = %d, want %d", answerWithID(t, messages, "6").Error.Code, methodNotFoundErrorCode)
	}
	if answerWithID(t, messages, "7").Error != nil {
		t.Error("모르는 메서드 뒤의 ping 이 답을 받지 못했습니다")
	}
}

func TestABrokenLineDoesNotTakeTheServerDown(t *testing.T) {
	// 한 줄이 깨졌다고 서버가 끝나면, 그 세션의 모델은 남은 대화 내내 도구를 잃는다.
	messages := serveLinesForTest(t, nil,
		`{ 이건 JSON 이 아니다`,
		`{"jsonrpc":"2.0","id":8,"method":"ping"}`)

	if len(messages) != 2 {
		t.Fatalf("답한 줄 = %d 개, 깨진 줄의 거절과 ping 의 답을 기대: %+v", len(messages), messages)
	}
	if messages[0].Error == nil || messages[0].Error.Code != parseErrorCode {
		t.Errorf("깨진 줄의 답 = %+v, 파싱 오류를 기대", messages[0].Error)
	}
	if answerWithID(t, messages, "8").Error != nil {
		t.Error("깨진 줄 뒤의 ping 이 답을 받지 못했습니다")
	}
}

func TestALongRequestLineIsNotCutOff(t *testing.T) {
	// 위임 프롬프트는 계획의 확정 인터페이스를 그대로 옮겨 적은 것이라 길다(설계 문서 9 절 6 번).
	// 줄 길이에 상한을 두면 그 프롬프트가 조용히 잘리고, 위임은 딴 것을 만든다.
	longPrompt := strings.Repeat("가", 200_000)

	messages := serveLinesForTest(t, []Tool{probeTool()},
		fmt.Sprintf(`{"jsonrpc":"2.0","id":9,"method":"tools/call","params":{"name":"repo_fingerprint","arguments":{"repo":%q}}}`, longPrompt))

	var result struct {
		Content []struct {
			Text string `json:"text"`
		} `json:"content"`
	}
	if err := json.Unmarshal(answerWithID(t, messages, "9").Result, &result); err != nil {
		t.Fatalf("tools/call 의 답을 읽지 못했습니다: %v", err)
	}
	if len(result.Content) != 1 || !strings.Contains(result.Content[0].Text, longPrompt) {
		t.Error("긴 프롬프트가 도구에 그대로 닿지 않았습니다")
	}
}

func TestALongRunningToolDoesNotBlockTheOtherRequests(t *testing.T) {
	// 설계 문서 5.7 이 동시 위임을 막지 않기로 했다. 요청을 한 줄로 세우면 두 번째 레포의 위임이
	// 첫 번째가 끝날 때까지 시작조차 못 한다.
	released := make(chan struct{})
	waitingTool := Tool{
		Name:        "run_in_repo",
		InputSchema: map[string]any{"type": "object"},
		Call: func(json.RawMessage) (ToolResult, error) {
			<-released
			return ToolResult{Text: "끝났다"}, nil
		},
	}

	requestReader, requestWriter := io.Pipe()
	var answered lockedStringBuilder

	served := make(chan error, 1)
	go func() { served <- Serve(requestReader, &answered, "kyu-test", "v0", []Tool{waitingTool}) }()

	fmt.Fprintln(requestWriter, `{"jsonrpc":"2.0","id":10,"method":"tools/call","params":{"name":"run_in_repo","arguments":{}}}`)
	fmt.Fprintln(requestWriter, `{"jsonrpc":"2.0","id":11,"method":"ping"}`)

	// 막힌 도구가 도는 동안 ping 이 답해야 한다.
	waitUntilAnswered(t, &answered, `"id":11`)
	close(released)
	waitUntilAnswered(t, &answered, `"id":10`)

	requestWriter.Close()
	if err := <-served; err != nil {
		t.Fatalf("Serve() 실패: %v", err)
	}
}

// lockedStringBuilder 는 서버의 쓰기와 테스트의 읽기가 겹쳐도 되는 버퍼다.
type lockedStringBuilder struct {
	mutex   sync.Mutex
	builder strings.Builder
}

func (buffer *lockedStringBuilder) Write(bytesToWrite []byte) (int, error) {
	buffer.mutex.Lock()
	defer buffer.mutex.Unlock()
	return buffer.builder.Write(bytesToWrite)
}

func (buffer *lockedStringBuilder) String() string {
	buffer.mutex.Lock()
	defer buffer.mutex.Unlock()
	return buffer.builder.String()
}

func waitUntilAnswered(t *testing.T, answered *lockedStringBuilder, wanted string) {
	t.Helper()

	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		if strings.Contains(answered.String(), wanted) {
			return
		}
		time.Sleep(2 * time.Millisecond)
	}
	t.Fatalf("%s 를 담은 답이 오지 않았습니다: %s", wanted, answered.String())
}
