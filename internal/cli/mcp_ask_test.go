package cli

import (
	"encoding/json"
	"net"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"testing"
	"time"
)

// mcpMessageForTest 는 서버가 내보낸 한 줄을 되읽은 것이다.
//
// 생산 코드의 직렬화 타입을 재사용하지 않는다. 이 모양은 도구 밖(claude 의 MCP 클라이언트)과
// 맺는 계약이라, 테스트가 따로 적어두어야 이름이나 중첩이 바뀌는 순간 여기에서 먼저 깨진다.
type mcpMessageForTest struct {
	ID     json.RawMessage `json:"id"`
	Result *struct {
		Content []struct {
			Type string `json:"type"`
			Text string `json:"text"`
		} `json:"content"`
		IsError bool `json:"isError"`
		Tools   []struct {
			Name        string         `json:"name"`
			Description string         `json:"description"`
			InputSchema map[string]any `json:"inputSchema"`
		} `json:"tools"`
	} `json:"result"`
	Error *struct {
		Message string `json:"message"`
	} `json:"error"`
}

// permissionAnswerForTest 는 claude 가 승인 도구의 결과 텍스트 안에서 읽는 문서다(실측 3.5 · A.5).
//
// 이 모양이 곧 관문의 계약이다 — behavior 가 allow 면 updatedInput 대로 실행되고, deny 면
// message 가 tool_result 의 문구로 모델에게 간다.
type permissionAnswerForTest struct {
	Behavior     string          `json:"behavior"`
	UpdatedInput json.RawMessage `json:"updatedInput"`
	Message      string          `json:"message"`
}

// shortDirForTest 는 소켓을 놓을 짧은 디렉토리다.
//
// t.TempDir() 을 쓰지 않는다. 그 경로에는 테스트 이름이 통째로 들어가는데, AF_UNIX 경로는
// 107 바이트가 한계라(실측 3.7) 이름이 긴 테스트에서 소켓이 조용히 열리지 않는다 — 이 브리지가
// 실제로 죽는 방식 그대로다.
func shortDirForTest(t *testing.T) string {
	t.Helper()

	directoryPath, err := os.MkdirTemp("", "kyu-ask-*")
	if err != nil {
		t.Fatalf("임시 디렉토리를 만들지 못했습니다: %v", err)
	}
	t.Cleanup(func() { os.RemoveAll(directoryPath) })
	return directoryPath
}

// fakeAppForTest 는 앱 노릇을 하는 소켓이다 — 물음을 받아 respond 가 정한 답을 준다.
//
// 진짜 앱을 세우지 않는 이유는 이 쪽이 재려는 것이 왕복 자체여서다. 답을 주지 않고 끊는 앱도
// 여기서는 respond 가 nil 을 돌려주는 한 줄이다.
type fakeAppForTest struct {
	socketPath string

	// questions 는 앱이 실제로 받은 물음 원문들이다.
	questions chan []byte
}

func startFakeAppForTest(t *testing.T, respond func(question []byte) []byte) *fakeAppForTest {
	t.Helper()

	socketPath := filepath.Join(shortDirForTest(t), "app.sock")
	listener, err := net.Listen("unix", socketPath)
	if err != nil {
		t.Fatalf("가짜 앱 소켓을 열지 못했습니다 (%s): %v", socketPath, err)
	}
	t.Cleanup(func() { listener.Close() })

	app := &fakeAppForTest{socketPath: socketPath, questions: make(chan []byte, 8)}

	go func() {
		for {
			connection, err := listener.Accept()
			if err != nil {
				return
			}

			go func() {
				defer connection.Close()

				question := make([]byte, 64*1024)
				readCount, err := connection.Read(question)
				if err != nil {
					return
				}
				app.questions <- slices.Clone(question[:readCount])

				if answer := respond(question[:readCount]); answer != nil {
					connection.Write(append(answer, '\n'))
				}
			}()
		}
	}()

	return app
}

// answeredQuestion 은 앱이 받은 물음 하나를 기다려 돌려준다.
func (app *fakeAppForTest) answeredQuestion(t *testing.T) map[string]json.RawMessage {
	t.Helper()

	select {
	case question := <-app.questions:
		var fields map[string]json.RawMessage
		if err := json.Unmarshal(question, &fields); err != nil {
			t.Fatalf("앱이 받은 물음을 JSON 으로 읽지 못했습니다: %v\n--- 줄 ---\n%s", err, question)
		}
		return fields

	case <-time.After(5 * time.Second):
		t.Fatal("앱에 물음이 오지 않았습니다")
		return nil
	}
}

// callRequestPermissionForTest 는 진짜 stdio 서버를 세우고 승인 도구를 한 번 부른다.
//
// 도구 함수를 직접 부르지 않는다. claude 가 실제로 지나는 길이 initialize · tools/call 이라,
// 그 길로 부르지 않으면 도구 이름이 틀려도 시험이 통과한다.
func callRequestPermissionForTest(t *testing.T, socketPath, argumentsJSON string) permissionAnswerForTest {
	t.Helper()

	messages := runAskServerForTest(t, socketPath,
		`{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}`,
		`{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"`+requestPermissionToolName+`","arguments":`+argumentsJSON+`}}`)

	answered := messageWithIDForTest(t, messages, "2")
	if answered.Error != nil {
		t.Fatalf("승인 도구가 프로토콜 오류로 끝났습니다: %s", answered.Error.Message)
	}
	if answered.Result == nil || len(answered.Result.Content) == 0 {
		t.Fatalf("승인 도구의 답에 본문이 없습니다: %+v", answered.Result)
	}

	// 거절도 도구가 답한 것이지 도구가 못 한 일이 아니다. isError 로 올리면 claude 가 그것을
	// 관문의 답이 아니라 서버의 고장으로 읽는다.
	if answered.Result.IsError {
		t.Errorf("isError = true, 관문의 답은 늘 정상 결과이기를 기대: %s", answered.Result.Content[0].Text)
	}

	var answer permissionAnswerForTest
	if err := json.Unmarshal([]byte(answered.Result.Content[0].Text), &answer); err != nil {
		t.Fatalf("승인 도구의 답을 JSON 으로 읽지 못했습니다: %v\n--- 본문 ---\n%s", err, answered.Result.Content[0].Text)
	}
	return answer
}

func runAskServerForTest(t *testing.T, socketPath string, requestLines ...string) []mcpMessageForTest {
	t.Helper()

	var answered strings.Builder
	if err := ManageOrchestrationTools(
		strings.NewReader(strings.Join(requestLines, "\n")+"\n"),
		&answered,
		&strings.Builder{},
		[]string{mcpAskSubcommandName, socketPath},
	); err != nil {
		t.Fatalf("kyu mcp ask 실패: %v", err)
	}

	var messages []mcpMessageForTest
	for _, line := range strings.Split(strings.TrimSuffix(answered.String(), "\n"), "\n") {
		if line == "" {
			continue
		}

		var message mcpMessageForTest
		if err := json.Unmarshal([]byte(line), &message); err != nil {
			t.Fatalf("서버가 답한 줄을 JSON 으로 읽지 못했습니다: %v\n--- 줄 ---\n%s", err, line)
		}
		messages = append(messages, message)
	}
	return messages
}

func messageWithIDForTest(t *testing.T, messages []mcpMessageForTest, id string) mcpMessageForTest {
	t.Helper()

	for _, message := range messages {
		if string(message.ID) == id {
			return message
		}
	}
	t.Fatalf("id %s 에 대한 답이 없습니다 (받은 답 %d 개)", id, len(messages))
	return mcpMessageForTest{}
}

func TestTheAskServerCarriesTheAppsAllowWithTheInputTheAppSendsBack(t *testing.T) {
	// 실측 3.5: allow 의 updatedInput 이 곧 실행되는 인자다. 앱이 고친 것을 그대로 실어야
	// 사용자가 카드에서 고친 명령이 실제로 도는 것이 된다.
	app := startFakeAppForTest(t, func([]byte) []byte {
		return []byte(`{"decision":"allow","updatedInput":{"command":"echo MODIFIED-BY-APP"}}`)
	})

	answer := callRequestPermissionForTest(t, app.socketPath,
		`{"tool_name":"Bash","input":{"command":"echo ORIGINAL"},"tool_use_id":"toolu_01"}`)

	if answer.Behavior != "allow" {
		t.Fatalf("behavior = %q, want allow (%s)", answer.Behavior, answer.Message)
	}
	if string(answer.UpdatedInput) != `{"command":"echo MODIFIED-BY-APP"}` {
		t.Errorf("updatedInput = %s, 앱이 고친 인자를 기대", answer.UpdatedInput)
	}
}

func TestTheAskServerCarriesTheAppsDenyReasonToTheModel(t *testing.T) {
	// 거절의 문구는 tool_result 로 모델에게 그대로 간다(A.5). 그것이 모델이 왜 못 했는지를
	// 사용자에게 말할 수 있는 유일한 통로다.
	app := startFakeAppForTest(t, func([]byte) []byte {
		return []byte(`{"decision":"deny","reason":"사용자가 거절했습니다 — 이 파일은 손대지 마세요"}`)
	})

	answer := callRequestPermissionForTest(t, app.socketPath,
		`{"tool_name":"Write","input":{"file_path":"/tmp/x","content":"hi"},"tool_use_id":"toolu_02"}`)

	if answer.Behavior != "deny" {
		t.Fatalf("behavior = %q, want deny", answer.Behavior)
	}
	if answer.Message != "사용자가 거절했습니다 — 이 파일은 손대지 마세요" {
		t.Errorf("message = %q, 앱이 적은 이유 그대로를 기대", answer.Message)
	}
}

func TestTheAppIsAskedWithTheToolNameAndInputAndToolUseIdClaudeSent(t *testing.T) {
	// claude 가 주는 세 인자(3.5)를 그대로 앱에게 넘긴다. 하나라도 빠뜨리면 카드가 무엇을
	// 승인하는지 말할 수 없고, tool_use_id 가 빠지면 카드를 그 도구 호출과 잇지 못한다.
	app := startFakeAppForTest(t, func([]byte) []byte {
		return []byte(`{"decision":"allow","updatedInput":{}}`)
	})

	callRequestPermissionForTest(t, app.socketPath,
		`{"tool_name":"Write","input":{"file_path":"/tmp/probe.txt","content":"WRITTEN\n"},"tool_use_id":"toolu_01EK4pNY"}`)

	question := app.answeredQuestion(t)
	if string(question["toolName"]) != `"Write"` {
		t.Errorf("toolName = %s, want \"Write\"", question["toolName"])
	}
	if string(question["toolUseId"]) != `"toolu_01EK4pNY"` {
		t.Errorf("toolUseId = %s, want \"toolu_01EK4pNY\"", question["toolUseId"])
	}
	if string(question["input"]) != `{"file_path":"/tmp/probe.txt","content":"WRITTEN\n"}` {
		t.Errorf("input = %s, claude 가 준 인자 그대로를 기대", question["input"])
	}
}

func TestAnAllowWithoutAChangedInputRunsWhatTheModelAskedFor(t *testing.T) {
	// 앱이 인자를 고치지 않았다는 뜻이다. 원본을 실어야 claude 가 모델이 요청한 그대로 실행한다 —
	// updatedInput 을 비운 채 보내면 claude 는 인자 없는 호출로 읽는다.
	app := startFakeAppForTest(t, func([]byte) []byte {
		return []byte(`{"decision":"allow"}`)
	})

	answer := callRequestPermissionForTest(t, app.socketPath,
		`{"tool_name":"Bash","input":{"command":"ls -al"},"tool_use_id":"toolu_03"}`)

	if answer.Behavior != "allow" {
		t.Fatalf("behavior = %q, want allow (%s)", answer.Behavior, answer.Message)
	}
	if string(answer.UpdatedInput) != `{"command":"ls -al"}` {
		t.Errorf("updatedInput = %s, 모델이 요청한 인자 그대로를 기대", answer.UpdatedInput)
	}
}

func TestNoAppListeningIsADenialAndSaysSo(t *testing.T) {
	// 앱이 사라진 뒤에 도구가 실행되는 것보다 거절이 낫다(설계 5.4.1). 이유를 함께 적어야
	// 모델이 "권한이 없어서" 가 아니라 "물어볼 상대가 없어서" 라고 전할 수 있다.
	socketPath := filepath.Join(shortDirForTest(t), "nobody.sock")

	answer := callRequestPermissionForTest(t, socketPath,
		`{"tool_name":"Write","input":{"file_path":"/tmp/x"},"tool_use_id":"toolu_04"}`)

	if answer.Behavior != "deny" {
		t.Fatalf("behavior = %q, 물어볼 앱이 없으면 deny 를 기대", answer.Behavior)
	}
	if !strings.Contains(answer.Message, "앱") {
		t.Errorf("message = %q, 앱에 닿지 못했다는 사실이 적히기를 기대", answer.Message)
	}
}

func TestAnAppThatClosesWithoutAnsweringIsADenial(t *testing.T) {
	// 앱이 죽으면 소켓이 닫히고 이쪽은 읽기에서 EOF 를 받는다(설계 5.4.1). 그 EOF 를 답으로
	// 읽으면 사람이 본 적 없는 도구가 실행된다.
	app := startFakeAppForTest(t, func([]byte) []byte { return nil })

	answer := callRequestPermissionForTest(t, app.socketPath,
		`{"tool_name":"Write","input":{"file_path":"/tmp/x"},"tool_use_id":"toolu_05"}`)

	if answer.Behavior != "deny" {
		t.Fatalf("behavior = %q, 답 없이 끊긴 앱에는 deny 를 기대", answer.Behavior)
	}
}

func TestAnAnswerThisServerDoesNotUnderstandIsADenial(t *testing.T) {
	// 모르는 낱말을 허용으로 읽지 않는다. 판이 어긋났을 때 안전한 쪽으로 기우는 것이 이 자리의 규율이다.
	app := startFakeAppForTest(t, func([]byte) []byte {
		return []byte(`{"decision":"maybe"}`)
	})

	answer := callRequestPermissionForTest(t, app.socketPath,
		`{"tool_name":"Bash","input":{"command":"rm -rf /"},"tool_use_id":"toolu_06"}`)

	if answer.Behavior != "deny" {
		t.Fatalf("behavior = %q, 모르는 답에는 deny 를 기대", answer.Behavior)
	}
}

func TestTheAskServerWaitsWhileThePersonIsStillDeciding(t *testing.T) {
	// 실측 3.7 은 150 초 뒤의 답도 통과하는 것을 쟀다. 그래서 이 서버에는 읽기 시한이 없다 —
	// 시한을 우리가 정하면 그 숫자보다 오래 고민한 사용자가 이유 없이 거절당한다.
	//
	// 이 시험이 재는 것은 그 사실의 모양뿐이다(짧은 시한이 새로 생기면 여기서 깨진다).
	app := startFakeAppForTest(t, func([]byte) []byte {
		time.Sleep(300 * time.Millisecond)
		return []byte(`{"decision":"allow","updatedInput":{"command":"echo LATE"}}`)
	})

	answer := callRequestPermissionForTest(t, app.socketPath,
		`{"tool_name":"Bash","input":{"command":"echo LATE"},"tool_use_id":"toolu_07"}`)

	if answer.Behavior != "allow" {
		t.Fatalf("behavior = %q, 늦은 답도 그대로 나르기를 기대 (%s)", answer.Behavior, answer.Message)
	}
}

func TestTheAskServerOpensExactlyOneToolAndTheModelNeverSeesIt(t *testing.T) {
	// 도구가 하나인 것이 요점이다. 이 서버에 다른 도구가 붙으면 그것은 --permission-prompt-tool
	// 로 가려지지 않아 모델에게 그대로 보인다(A.7).
	app := startFakeAppForTest(t, func([]byte) []byte { return nil })

	messages := runAskServerForTest(t, app.socketPath,
		`{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}`)

	listed := messageWithIDForTest(t, messages, "1")
	if listed.Result == nil || len(listed.Result.Tools) != 1 {
		t.Fatalf("도구 목록 = %+v, 하나를 기대", listed.Result)
	}
	if listed.Result.Tools[0].Name != requestPermissionToolName {
		t.Errorf("도구 이름 = %q, want %q", listed.Result.Tools[0].Name, requestPermissionToolName)
	}
	if listed.Result.Tools[0].InputSchema == nil {
		t.Error("inputSchema 가 없습니다 — 이 도구는 claude 가 세 인자로 부른다(3.5)")
	}
}

func TestAskNeedsToKnowWhichSocketToAsk(t *testing.T) {
	// 경로 없이 뜬 서버는 물어볼 상대가 없어 모든 호출을 거절하는 서버가 된다. 그 실패는
	// 세션이 한참 돈 뒤에야 "왜 다 거절되지" 로 드러난다.
	err := ManageOrchestrationTools(strings.NewReader(""), &strings.Builder{}, &strings.Builder{},
		[]string{mcpAskSubcommandName})

	if err == nil {
		t.Fatal("소켓 경로 없이 부른 mcp ask 가 성공했습니다 — 거절하기를 기대")
	}
	if !strings.Contains(err.Error(), mcpAskSubcommandName) {
		t.Errorf("오류 = %q, 사용법을 함께 알리기를 기대", err)
	}
}
