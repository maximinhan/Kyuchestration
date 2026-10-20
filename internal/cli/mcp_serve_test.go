package cli

import (
	"encoding/json"
	"strings"
	"testing"
)

func TestServeOpensTheSameToolsWhenToldWhereTheAppSocketIs(t *testing.T) {
	// session-command 가 챗 메인 세션의 등록에 --app-socket 을 싣는다(session-tree-design.md 6.4).
	// 그 옵션을 모르는 serve 는 claude 가 띄우자마자 끝나고, 메인 세션은 위임 도구를 조용히 잃는다.
	workDirPath := makeWorkDir(t)

	var answered strings.Builder
	err := ManageOrchestrationTools(
		strings.NewReader(strings.Join([]string{
			`{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}`,
			`{"jsonrpc":"2.0","id":2,"method":"tools/list"}`,
		}, "\n")+"\n"),
		&answered,
		&strings.Builder{},
		[]string{mcpServeSubcommandName, workDirPath, appSocketOptionName, "/run/user/1000/kyu/m1.sock"},
	)
	if err != nil {
		t.Fatalf("--app-socket 을 받은 mcp serve 가 실패했습니다: %v", err)
	}

	var toolNames []string
	for _, line := range strings.Split(strings.TrimSpace(answered.String()), "\n") {
		var message mcpMessageForTest
		if err := json.Unmarshal([]byte(line), &message); err != nil {
			t.Fatalf("서버가 답한 줄을 JSON 으로 읽지 못했습니다: %v\n--- 줄 ---\n%s", err, line)
		}
		if string(message.ID) == "2" && message.Result != nil {
			for _, tool := range message.Result.Tools {
				toolNames = append(toolNames, tool.Name)
			}
		}
	}

	// 이 단계에서는 소켓을 받아 두기만 한다 — 세션 트리 도구는 4 단계가 더한다.
	if strings.Join(toolNames, ",") != "list_repos,run_in_repo" {
		t.Errorf("도구 목록 = %q, 소켓을 받아도 지금의 두 도구 그대로이기를 기대", toolNames)
	}
}

func TestServeRefusesAnAppSocketOptionWithoutAPath(t *testing.T) {
	// 값 없이 온 옵션을 조용히 넘기면 뒤의 인자가 소켓 경로로 읽힌다.
	err := ManageOrchestrationTools(strings.NewReader(""), &strings.Builder{}, &strings.Builder{},
		[]string{mcpServeSubcommandName, makeWorkDir(t), appSocketOptionName})

	if err == nil {
		t.Fatal("경로 없이 준 --app-socket 을 mcp serve 가 받았습니다 — 거절하기를 기대")
	}
	if !strings.Contains(err.Error(), appSocketOptionName) {
		t.Errorf("오류 = %q, %s 뒤에 경로가 필요하다는 안내를 기대", err, appSocketOptionName)
	}
}
