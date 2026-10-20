package cli

import (
	"encoding/json"
	"slices"
	"strings"
	"testing"
)

// runServeForTest 는 kyu mcp serve 를 stdio 그대로 세우고 initialize 뒤 요청들을 보낸다.
//
// 도구 함수를 직접 부르지 않는다. claude 가 실제로 지나는 길이 initialize · tools/list · tools/call
// 이라, 그 길로 부르지 않으면 역할에 따라 도구가 갈리는 자리를 시험이 지나치지 않는다.
func runServeForTest(t *testing.T, serveArgs []string, requestLines ...string) []mcpMessageForTest {
	t.Helper()

	lines := append([]string{
		`{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}`,
	}, requestLines...)

	var answered strings.Builder
	if err := ManageOrchestrationTools(
		strings.NewReader(strings.Join(lines, "\n")+"\n"),
		&answered,
		&strings.Builder{},
		append([]string{mcpServeSubcommandName}, serveArgs...),
	); err != nil {
		t.Fatalf("kyu mcp serve %q 실패: %v", serveArgs, err)
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

func listedToolNamesForTest(t *testing.T, serveArgs []string) []string {
	t.Helper()

	answered := messageWithIDForTest(t, runServeForTest(t, serveArgs, `{"jsonrpc":"2.0","id":2,"method":"tools/list"}`), "2")
	if answered.Result == nil {
		t.Fatalf("tools/list 의 답이 없습니다: %+v", answered)
	}

	var toolNames []string
	for _, tool := range answered.Result.Tools {
		toolNames = append(toolNames, tool.Name)
	}
	return toolNames
}

// toolCallResultForTest 는 도구 하나를 stdio 로 부른 결과의 본문과 isError 다.
type toolCallResultForTest struct {
	text    string
	isError bool
}

func callServeToolForTest(t *testing.T, serveArgs []string, toolName, argumentsJSON string) toolCallResultForTest {
	t.Helper()

	answered := messageWithIDForTest(t, runServeForTest(t, serveArgs,
		`{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"`+toolName+`","arguments":`+argumentsJSON+`}}`), "2")
	if answered.Error != nil {
		t.Fatalf("%s 가 프로토콜 오류로 끝났습니다: %s", toolName, answered.Error.Message)
	}
	if answered.Result == nil || len(answered.Result.Content) == 0 {
		t.Fatalf("%s 의 답에 본문이 없습니다: %+v", toolName, answered.Result)
	}
	return toolCallResultForTest{text: answered.Result.Content[0].Text, isError: answered.Result.IsError}
}

func childServeArgsForTest(socketPath string) []string {
	return []string{childRoleOptionName, appSocketOptionName, socketPath}
}

func mainServeArgsForTest(t *testing.T, socketPath string) []string {
	return []string{makeWorkDir(t), appSocketOptionName, socketPath}
}

// --- 하위 역할 (6.3) ---

func TestTheChildRoleOpensAskParentAndNothingElse(t *testing.T) {
	// 깊이 제한은 도구 가용성으로 강제한다(6.3). 하위의 서버에 spawn_session 이 하나라도 보이면
	// 하위가 하위를 낳고, 트리는 화면이 그리지 못하는 깊이가 된다.
	toolNames := listedToolNamesForTest(t, childServeArgsForTest("/run/user/1000/kyu/c1.sock"))

	if !slices.Equal(toolNames, []string{"ask_parent"}) {
		t.Errorf("하위 역할의 도구 = %q, ask_parent 하나만 기대", toolNames)
	}
}

func TestTheChildRoleNeedsTheAppSocket(t *testing.T) {
	// ask_parent 는 앱에게 묻는 것이 전부다. 소켓 없이 뜬 하위 서버는 모든 물음이 실패하는 서버라,
	// 뜨는 자리에서 거절한다.
	err := ManageOrchestrationTools(strings.NewReader(""), &strings.Builder{}, &strings.Builder{},
		[]string{mcpServeSubcommandName, childRoleOptionName})
	if err == nil || !strings.Contains(err.Error(), appSocketOptionName) {
		t.Errorf("오류 = %v, 하위 역할에는 %s 가 필요하다는 안내를 기대", err, appSocketOptionName)
	}
}

func TestTheChildRoleTakesNoWorkDir(t *testing.T) {
	// 하위의 도구는 워크디렉토리를 쓰지 않는다. 받아 두면 쓰이지 않는 값이 등록에 실려, 그것이
	// 무엇에 쓰이는지 읽는 사람이 찾아 헤맨다.
	err := ManageOrchestrationTools(strings.NewReader(""), &strings.Builder{}, &strings.Builder{},
		[]string{mcpServeSubcommandName, makeWorkDir(t), childRoleOptionName, appSocketOptionName, "/tmp/x.sock"})
	if err == nil {
		t.Error("하위 역할이 워크디렉토리 인자를 받았습니다 — 거절을 기대")
	}
}

func TestAskParentCarriesTheMessageToTheAppAndTheAnswerBack(t *testing.T) {
	// 하위의 kyu 서버는 상위에게 직접 말하지 않고 앱 소켓에 "상위에게 물어 달라" 를 쓴다(6.1).
	app := startFakeAppForTest(t, func([]byte) []byte { return []byte(`{"answer":"5433 을 쓰세요."}`) })

	result := callServeToolForTest(t, childServeArgsForTest(app.socketPath), "ask_parent",
		`{"message":"테스트 DB 포트는?"}`)

	question := app.answeredQuestion(t)
	if string(question["kind"]) != `"ask_parent"` || string(question["message"]) != `"테스트 DB 포트는?"` {
		t.Errorf("앱이 받은 줄 = %v, kind ask_parent 와 message 를 기대", question)
	}
	if result.isError || !strings.Contains(result.text, "5433 을 쓰세요.") {
		t.Errorf("ask_parent 의 답 = %+v, 앱이 준 answer 를 그대로 기대", result)
	}
}

func TestAskParentPassesAnEndedAnswerAsAnAnswerNotAnError(t *testing.T) {
	// 상위가 끝난 것은 도구의 고장이 아니라 물음의 답이다(6.3). 오류로 올리면 모델은 다시 묻는다.
	app := startFakeAppForTest(t, func([]byte) []byte { return []byte(`{"ended":"parent_ended"}`) })

	result := callServeToolForTest(t, childServeArgsForTest(app.socketPath), "ask_parent", `{"message":"계속할까요?"}`)

	if result.isError || !strings.Contains(result.text, "parent_ended") {
		t.Errorf("ask_parent 의 답 = %+v, ended 를 정상 결과로 기대", result)
	}
}

func TestAskParentRefusesAnEmptyMessageWithoutBotheringTheApp(t *testing.T) {
	app := startFakeAppForTest(t, func([]byte) []byte { return []byte(`{"answer":"?"}`) })

	result := callServeToolForTest(t, childServeArgsForTest(app.socketPath), "ask_parent", `{}`)

	if !result.isError || !strings.Contains(result.text, "message") {
		t.Errorf("빈 물음의 답 = %+v, message 가 필요하다는 도구 오류를 기대", result)
	}
	if len(app.questions) != 0 {
		t.Error("빈 물음이 앱까지 갔습니다")
	}
}

// --- 상위 역할 (6.2) ---

func TestTheMainRoleWithAnAppSocketAddsTheFiveSessionTreeTools(t *testing.T) {
	// 상위에게 다섯(6.2). run_in_repo 는 6 단계에서 은퇴할 때까지 그대로 둔다(6.16).
	toolNames := listedToolNamesForTest(t, mainServeArgsForTest(t, "/run/user/1000/kyu/m1.sock"))

	want := []string{"list_repos", "run_in_repo",
		"spawn_session", "send_to_session", "await_session", "list_sessions", "end_session"}
	if !slices.Equal(toolNames, want) {
		t.Errorf("상위 역할의 도구 = %q, want %q", toolNames, want)
	}
}

func TestTheMainRoleWithoutAnAppSocketHasNoSessionTreeTools(t *testing.T) {
	// 세션 트리 도구는 앱에게 묻는 통로가 전부다. 소켓 없이 뜬 서버(터미널 세션)에 그것을 보이면
	// 모델은 부를 때마다 실패하는 도구를 고른다.
	toolNames := listedToolNamesForTest(t, []string{makeWorkDir(t)})

	if !slices.Equal(toolNames, []string{"list_repos", "run_in_repo"}) {
		t.Errorf("소켓 없는 상위의 도구 = %q, 지금의 두 도구만 기대", toolNames)
	}
}

func TestSpawnSessionCarriesTheRequestToTheAppAndItsAnswerBack(t *testing.T) {
	// 엔진은 하위를 띄우지 않는다(원칙 16). 요청을 앱에게 옮기고 앱의 답을 옮길 뿐이다 — 줄의 모양이
	// 6.4 의 계약이다. 주지 않은 선택 인자는 null 로 간다: 앱이 "생략" 과 "빈 값" 을 가르게 한다.
	app := startFakeAppForTest(t, func([]byte) []byte {
		return []byte(`{"id":"child-1","cwd":"/w/proj-a","conversationId":"5e0c","state":"running"}`)
	})

	result := callServeToolForTest(t, mainServeArgsForTest(t, app.socketPath), "spawn_session",
		`{"prompt":"포트를 고쳐라","cwd":"proj-a"}`)

	question := app.answeredQuestion(t)
	wantFields := map[string]string{
		"kind": `"spawn"`, "prompt": `"포트를 고쳐라"`, "cwd": `"proj-a"`, "createDirectory": `false`, "title": `null`,
	}
	for field, want := range wantFields {
		if string(question[field]) != want {
			t.Errorf("앱이 받은 %s = %s, want %s", field, question[field], want)
		}
	}
	if result.isError || !strings.Contains(result.text, `"child-1"`) {
		t.Errorf("spawn_session 의 답 = %+v, 앱이 준 id 를 그대로 기대", result)
	}
}

func TestEachSessionTreeToolSpeaksItsOwnKind(t *testing.T) {
	// 앱은 줄의 kind 로 요청을 가른다(6.4). 도구 이름과 kind 의 짝이 어긋나면 앱은 엉뚱한 트리 연산을 한다.
	cases := []struct {
		toolName      string
		argumentsJSON string
		wantKind      string
		wantFields    map[string]string
	}{
		{"send_to_session", `{"id":"child-1","message":"5433"}`, "send", map[string]string{"id": `"child-1"`, "message": `"5433"`}},
		{"await_session", `{"ids":["child-1"],"waitSeconds":60}`, "await", map[string]string{"ids": `["child-1"]`, "waitSeconds": `60`}},
		{"list_sessions", `{}`, "list", map[string]string{}},
		{"end_session", `{"id":"child-2"}`, "end", map[string]string{"id": `"child-2"`}},
	}

	for _, testCase := range cases {
		t.Run(testCase.toolName, func(t *testing.T) {
			app := startFakeAppForTest(t, func([]byte) []byte { return []byte(`{"ok":true}`) })

			result := callServeToolForTest(t, mainServeArgsForTest(t, app.socketPath), testCase.toolName, testCase.argumentsJSON)

			question := app.answeredQuestion(t)
			if string(question["kind"]) != `"`+testCase.wantKind+`"` {
				t.Errorf("kind = %s, want %q", question["kind"], testCase.wantKind)
			}
			for field, want := range testCase.wantFields {
				if string(question[field]) != want {
					t.Errorf("앱이 받은 %s = %s, want %s", field, question[field], want)
				}
			}
			if result.isError {
				t.Errorf("%s 의 답 = %+v, 정상 결과를 기대", testCase.toolName, result)
			}
		})
	}
}

func TestAwaitSessionWaitsThirtyMinutesForEveryChildWhenToldNothing(t *testing.T) {
	// 기본 1,800 초(6.7). ids 를 생략하면 살아 있는 하위 전부다 — 그 판단은 앱이 하므로 null 로 넘긴다.
	app := startFakeAppForTest(t, func([]byte) []byte { return []byte(`{"events":[],"stillRunning":[]}`) })

	callServeToolForTest(t, mainServeArgsForTest(t, app.socketPath), "await_session", `{}`)

	question := app.answeredQuestion(t)
	if string(question["waitSeconds"]) != "1800" || string(question["ids"]) != "null" {
		t.Errorf("앱이 받은 await = %v, waitSeconds 1800 · ids null 을 기대", question)
	}
}

func TestAwaitSessionRefusesAWaitLongerThanTheServerAllows(t *testing.T) {
	// 최댓값 3,000 초는 서버 상한(1 시간)보다 짧아야 한다(6.7). 넘기면 claude 가 먼저 끊고 "timed out"
	// 이 모델에게 간다 — "아직 도는 중" 이라고 정직하게 답할 자리를 잃는다. 앱까지 가기 전에 거절한다.
	app := startFakeAppForTest(t, func([]byte) []byte { return []byte(`{"events":[]}`) })

	for _, argumentsJSON := range []string{`{"waitSeconds":3001}`, `{"waitSeconds":0}`, `{"ids":[]}`} {
		result := callServeToolForTest(t, mainServeArgsForTest(t, app.socketPath), "await_session", argumentsJSON)
		if !result.isError {
			t.Errorf("await_session(%s) = %+v, 도구 오류를 기대", argumentsJSON, result)
		}
	}
	if len(app.questions) != 0 {
		t.Error("범위 밖의 기다림이 앱까지 갔습니다")
	}
}

func TestAnAppRefusalBecomesAToolError(t *testing.T) {
	// 앱이 모르는 kind 나 트리 규칙(6.8 의 cwd 등)으로 거절하면 {"error": …} 로 답한다(6.4). 그 문구가
	// 모델에게 도구 오류로 가야 모델이 고칠 것을 안다 — 정상 결과로 싣으면 모델은 하위가 뜬 줄 안다.
	app := startFakeAppForTest(t, func([]byte) []byte {
		return []byte(`{"error":"이 앱은 'spawn' 요청을 아직 받지 않습니다 — 앱과 kyu 의 판이 어긋났습니다"}`)
	})

	result := callServeToolForTest(t, mainServeArgsForTest(t, app.socketPath), "spawn_session", `{"prompt":"x"}`)

	if !result.isError || !strings.Contains(result.text, "'spawn' 요청을 아직 받지 않습니다") {
		t.Errorf("spawn_session 의 답 = %+v, 앱의 거절 문구를 도구 오류로 기대", result)
	}
}

func TestNoAppListeningIsAToolErrorThatSaysSo(t *testing.T) {
	socketPath := shortDirForTest(t) + "/nobody.sock"

	result := callServeToolForTest(t, mainServeArgsForTest(t, socketPath), "list_sessions", `{}`)

	if !result.isError || !strings.Contains(result.text, "앱") {
		t.Errorf("list_sessions 의 답 = %+v, 앱에 닿지 못했다는 도구 오류를 기대", result)
	}
}

func TestSessionTreeToolsRefuseMissingRequiredArgumentsAndUnknownOnes(t *testing.T) {
	// 빈 prompt 로 뜬 하위는 무엇을 할지 모른 채 비용만 쓴다. 모르는 인자(오타)를 조용히 버리면
	// 모델은 그 인자가 먹힌 줄 안다.
	app := startFakeAppForTest(t, func([]byte) []byte { return []byte(`{}`) })

	cases := map[string]string{
		"spawn_session":   `{"cwd":"proj-a"}`,
		"send_to_session": `{"id":"child-1"}`,
		"end_session":     `{}`,
		"list_sessions":   `{"verbose":true}`,
	}
	for toolName, argumentsJSON := range cases {
		result := callServeToolForTest(t, mainServeArgsForTest(t, app.socketPath), toolName, argumentsJSON)
		if !result.isError {
			t.Errorf("%s(%s) = %+v, 도구 오류를 기대", toolName, argumentsJSON, result)
		}
	}
	if len(app.questions) != 0 {
		t.Error("인자가 잘못된 요청이 앱까지 갔습니다")
	}
}
