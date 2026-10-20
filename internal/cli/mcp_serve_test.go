package cli

import (
	"strings"
	"testing"
)

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
