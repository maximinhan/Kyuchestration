package main

import (
	"bufio"
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"slices"
	"strings"
	"testing"
	"time"
)

// 이 파일은 하위 세션 조립의 끝에서 끝까지다(session-tree-design.md 7 절 3 단계 완료 확인).
//
// 단위 시험은 조립 결과의 모양을 본다. 여기서는 엔진이 답한 명령을 **그대로** 띄운다 — 하위의
// --mcp-config 에 실린 서버를 그 인자 그대로 실행해 도구 목록을 묻고(실 claude 없이), 실 claude 를
// 그 명령 그대로 띄워 system/init 의 도구 목록을 본다(KYU_REAL_CLAUDE=1 일 때만).

// childSessionAnswerForTest 는 kyu session-command --child --json 의 답 중 이 시험이 쓰는 자리다.
type childSessionAnswerForTest struct {
	Command []string          `json:"command"`
	Cwd     string            `json:"cwd"`
	Env     map[string]string `json:"env"`
}

// answerChildSessionForTest 는 빌드한 kyu 로 하위 세션 하나를 묻는다.
func answerChildSessionForTest(t *testing.T, kyuBinaryPath, workDirPath, socketPath string, extraArgs ...string) childSessionAnswerForTest {
	t.Helper()

	args := append([]string{"session-command", "--chat", "--child", "child-1", "--app-socket", socketPath}, extraArgs...)
	command := exec.Command(kyuBinaryPath, append(args, "--json")...)
	command.Dir = workDirPath

	var stdout, stderr bytes.Buffer
	command.Stdout = &stdout
	command.Stderr = &stderr
	if err := command.Run(); err != nil {
		t.Fatalf("kyu session-command --child 실패: %v (%s)", err, stderr.String())
	}

	var answer childSessionAnswerForTest
	if err := json.Unmarshal(stdout.Bytes(), &answer); err != nil {
		t.Fatalf("답을 JSON 으로 읽지 못했습니다: %v\n--- stdout ---\n%s", err, stdout.String())
	}
	return answer
}

// registeredServersForTest 는 답한 명령의 --mcp-config 를 되읽는다.
func registeredServersForTest(t *testing.T, command []string) map[string]struct {
	Command string   `json:"command"`
	Args    []string `json:"args"`
} {
	t.Helper()

	flagIndex := slices.Index(command, "--mcp-config")
	if flagIndex < 0 || flagIndex+1 >= len(command) {
		t.Fatalf("명령 = %q, --mcp-config 를 기대", command)
	}
	var registration struct {
		McpServers map[string]struct {
			Command string   `json:"command"`
			Args    []string `json:"args"`
		} `json:"mcpServers"`
	}
	if err := json.Unmarshal([]byte(command[flagIndex+1]), &registration); err != nil {
		t.Fatalf("--mcp-config 를 JSON 으로 읽지 못했습니다: %v", err)
	}
	return registration.McpServers
}

func TestTheServerRegisteredForAChildOpensOnlyAskParent(t *testing.T) {
	// 하위의 --mcp-config 에 spawn 이 없다(6.3). 등록의 모양이 아니라, 그 등록대로 띄운 프로세스가
	// tools/list 에 무엇을 답하는지로 판정한다 — claude 가 보는 것이 그것이다.
	kyuBinaryPath := buildKyuBinary(t)
	workDirPath := t.TempDir()

	answer := answerChildSessionForTest(t, kyuBinaryPath, workDirPath, "/run/user/1000/kyu/child-1.sock")
	servers := registeredServersForTest(t, answer.Command)

	server, registered := servers["kyu"]
	if !registered {
		t.Fatalf("등록한 서버 = %v, kyu 를 기대", servers)
	}

	process := exec.Command(server.Command, server.Args...)
	process.Stdin = strings.NewReader(strings.Join([]string{
		`{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"claude","version":"test"}}}`,
		`{"jsonrpc":"2.0","method":"notifications/initialized"}`,
		`{"jsonrpc":"2.0","id":2,"method":"tools/list"}`,
	}, "\n") + "\n")
	var stdout, stderr bytes.Buffer
	process.Stdout = &stdout
	process.Stderr = &stderr
	if err := process.Run(); err != nil {
		t.Fatalf("등록된 하위 서버 실행 실패: %v (%s)", err, stderr.String())
	}

	answers := readJSONRPCAnswers(t, &stdout, 2)
	var toolNames []string
	for _, tool := range answers[2].Result.Tools {
		toolNames = append(toolNames, tool.Name)
	}
	if !slices.Equal(toolNames, []string{"ask_parent"}) {
		t.Errorf("하위 서버의 tools/list = %q, ask_parent 하나만 기대", toolNames)
	}
}

// realClaudeEnvironmentVariable 이 1 일 때만 실 claude 실측을 돈다.
//
// 기본으로 돌지 않는 이유: 사용자 인증과 네트워크가 필요하고, claude 가 이 머신의 ~/.claude 에 전사를
// 남긴다. CI 와 다른 사람의 머신에서 조용히 그런 일을 하지 않게 한다.
const realClaudeEnvironmentVariable = "KYU_REAL_CLAUDE"

func TestARealClaudeLaunchedFromTheAnswerSeesOnlyAskParentFromKyu(t *testing.T) {
	// 7 절 3 단계의 실측 — 엔진이 답한 명령 그대로 띄운 하위가 tools/list 에 ask_parent 하나만 갖는다.
	// claude 가 MCP 서버의 도구를 모델에게 내놓는 목록이 system/init 의 tools 다. 승인 서버(kyu-ask)의
	// request_permission 은 --permission-prompt-tool 이 가리켜 그 목록에서 사라진다(실측 A.7).
	if os.Getenv(realClaudeEnvironmentVariable) != "1" {
		t.Skipf("%s=1 일 때만 실 claude 를 띄웁니다", realClaudeEnvironmentVariable)
	}
	if _, err := exec.LookPath("claude"); err != nil {
		t.Skip("claude 를 PATH 에서 찾을 수 없습니다")
	}

	kyuBinaryPath := buildKyuBinary(t)
	workDirPath := t.TempDir()
	socketPath := listenAsSilentAppForTest(t)

	answer := answerChildSessionForTest(t, kyuBinaryPath, workDirPath, socketPath, "--cwd", "scratch-x", "--create-directory")

	claude := exec.Command(answer.Command[0], answer.Command[1:]...)
	claude.Dir = answer.Cwd
	claude.Env = os.Environ()
	for name, value := range answer.Env {
		claude.Env = append(claude.Env, name+"="+value)
	}
	stdin, err := claude.StdinPipe()
	if err != nil {
		t.Fatalf("stdin 파이프 생성 실패: %v", err)
	}
	stdout, err := claude.StdoutPipe()
	if err != nil {
		t.Fatalf("stdout 파이프 생성 실패: %v", err)
	}
	var stderr bytes.Buffer
	claude.Stderr = &stderr
	if err := claude.Start(); err != nil {
		t.Fatalf("claude 실행 실패: %v", err)
	}
	t.Cleanup(func() {
		_ = claude.Process.Kill()
		_ = claude.Wait()
	})

	// stream-json 입력에서는 첫 사용자 메시지가 와야 init 이 나온다. init 을 읽는 즉시 프로세스를 끝내므로
	// 모델 호출 전에 멈춘다.
	fmt.Fprintln(stdin, `{"type":"user","message":{"role":"user","content":"OK 라고만 답하세요."}}`)

	initEvent := readSystemInitForTest(t, stdout, &stderr)
	t.Logf("system/init tools(kyu 것만) = %q · mcp_servers = %s", kyuToolsForTest(initEvent.Tools), initEvent.McpServers)

	if got := kyuToolsForTest(initEvent.Tools); !slices.Equal(got, []string{"mcp__kyu__ask_parent"}) {
		t.Errorf("하위 claude 가 kyu 에게서 받은 도구 = %q, mcp__kyu__ask_parent 하나만 기대", got)
	}
}

// listenAsSilentAppForTest 는 받기만 하고 답하지 않는 앱 소켓이다. init 까지는 아무도 묻지 않는다.
func listenAsSilentAppForTest(t *testing.T) string {
	t.Helper()

	// AF_UNIX 경로는 107 바이트가 한계라 테스트 이름이 들어가는 t.TempDir() 을 쓰지 않는다.
	socketDirectory, err := os.MkdirTemp("", "kyu-rc-*")
	if err != nil {
		t.Fatalf("소켓 디렉토리 생성 실패: %v", err)
	}
	t.Cleanup(func() { os.RemoveAll(socketDirectory) })

	socketPath := filepath.Join(socketDirectory, "app.sock")
	listener, err := net.Listen("unix", socketPath)
	if err != nil {
		t.Fatalf("앱 소켓을 열지 못했습니다: %v", err)
	}
	t.Cleanup(func() { listener.Close() })
	return socketPath
}

type systemInitForTest struct {
	Type       string          `json:"type"`
	Subtype    string          `json:"subtype"`
	Tools      []string        `json:"tools"`
	McpServers json.RawMessage `json:"mcp_servers"`
}

func readSystemInitForTest(t *testing.T, stdout io.Reader, stderr *bytes.Buffer) systemInitForTest {
	t.Helper()

	lines := make(chan string)
	go func() {
		defer close(lines)
		scanner := bufio.NewScanner(stdout)
		scanner.Buffer(make([]byte, 0, 1<<20), 16<<20)
		for scanner.Scan() {
			lines <- scanner.Text()
		}
	}()

	deadline := time.After(90 * time.Second)
	for {
		select {
		case line, open := <-lines:
			if !open {
				t.Fatalf("claude 가 system/init 전에 끝났습니다 (stderr: %s)", stderr.String())
			}
			var event systemInitForTest
			if json.Unmarshal([]byte(line), &event) == nil && event.Type == "system" && event.Subtype == "init" {
				return event
			}
		case <-deadline:
			t.Fatalf("90 초 안에 system/init 이 오지 않았습니다 (stderr: %s)", stderr.String())
		}
	}
}

// kyuToolsForTest 는 init 의 도구 중 우리 서버(kyu · kyu-ask)의 것만 고른다. 사용자가 따로 붙인
// 플러그인·커넥터의 도구는 이 판정의 대상이 아니다.
func kyuToolsForTest(tools []string) []string {
	var kyuTools []string
	for _, tool := range tools {
		if strings.HasPrefix(tool, "mcp__kyu__") || strings.HasPrefix(tool, "mcp__kyu-ask__") {
			kyuTools = append(kyuTools, tool)
		}
	}
	return kyuTools
}
