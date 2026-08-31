package session

import (
	"bytes"
	"os"
	"os/exec"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/creack/pty"
)

// attachClientTestCommand 는 이 테스트 바이너리를 raw 모드 클라이언트로 다시 실행하는 하위 명령이다.
//
// 감독을 __supervise 로 다시 실행해 받아주는 것과 같은 방침이다(TestMain) — 클라이언트도 mock
// 하지 않고 진짜로 띄운다. raw 모드는 터미널이 있어야 시험할 수 있고, 터미널을 다루는 코드를
// 대역으로 바꿔치기하면 정작 확인하고 싶은 것이 사라진다.
const attachClientTestCommand = "__attach-client-for-test"

// terminalOutputCollector 는 PTY 마스터에 나오는 것을 모은다.
//
// 마감 시각 대신 폴링으로 기다린다. PTY 마스터에 SetReadDeadline 이 통하는지는 플랫폼에 달려
// 있어서, 시험이 그 사정에 기대지 않게 한다.
type terminalOutputCollector struct {
	mutex     sync.Mutex
	collected bytes.Buffer
}

func collectTerminalOutput(terminal *os.File) *terminalOutputCollector {
	collector := &terminalOutputCollector{}
	go func() {
		buffer := make([]byte, 4096)
		for {
			read, err := terminal.Read(buffer)
			if read > 0 {
				collector.mutex.Lock()
				collector.collected.Write(buffer[:read])
				collector.mutex.Unlock()
			}
			if err != nil {
				return
			}
		}
	}()
	return collector
}

func (c *terminalOutputCollector) text() string {
	c.mutex.Lock()
	defer c.mutex.Unlock()
	return c.collected.String()
}

func (c *terminalOutputCollector) waitFor(t *testing.T, want string) string {
	t.Helper()

	deadline := time.Now().Add(15 * time.Second)
	for time.Now().Before(deadline) {
		if got := c.text(); strings.Contains(got, want) {
			return got
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatalf("터미널에 %q 가 나타나지 않았습니다 — 지금까지 본 것: %q", want, c.text())
	return ""
}

func TestAttachClientCarriesTheTerminalToTheSessionAndGivesItBack(t *testing.T) {
	backend := newIsolatedSupervisorBackend(t)
	const sessionName = "kyu-test-sv-client-terminal"

	// 세션은 받은 줄을 되돌려주면서 자기 화면 크기도 함께 답한다. stty size 는 "행 열" 순이다.
	if err := backend.Create(sessionName, t.TempDir(), []string{
		"sh", "-c", `stty -echo; printf "준비\n"; while read line; do printf "%s " "$line"; stty size; done`,
	}); err != nil {
		t.Fatalf("Create() 실패: %v", err)
	}

	// 클라이언트가 끝난 뒤 같은 터미널에서 stty -a 를 찍게 한다. 그러면 raw 모드를 되돌려놓았는지
	// 를 밖에서 확인할 수 있다 — 클라이언트가 죽은 뒤에도 그 터미널은 셸이 쓰고 있다.
	clientScript := `"$1" ` + attachClientTestCommand + ` "$2" || printf "클라이언트실패\n"; stty -a`
	client := exec.Command("sh", "-c", clientScript, "sh", os.Args[0], sessionName)

	terminal, err := pty.StartWithSize(client, &pty.Winsize{Cols: 120, Rows: 40})
	if err != nil {
		t.Fatalf("클라이언트를 PTY 안에서 띄우지 못했습니다: %v", err)
	}
	t.Cleanup(func() {
		terminal.Close()
		client.Process.Kill()
		client.Wait()
	})

	screen := collectTerminalOutput(terminal)
	screen.waitFor(t, "준비")

	// 키가 세션까지 가고 세션의 출력이 이 터미널로 돌아온다.
	if _, err := terminal.Write([]byte("첫키\n")); err != nil {
		t.Fatalf("터미널에 쓰기 실패: %v", err)
	}
	// 붙을 때 보낸 크기가 세션 화면에 적용되어 있어야 한다.
	screen.waitFor(t, "첫키 40 120")

	// 창 크기를 바꾸면 커널이 클라이언트에게 SIGWINCH 를 보내고, 클라이언트가 그것을 감독에게
	// 옮긴다. tmux 클라이언트가 대신 해주던 일이다(설계 문서 7절).
	if err := pty.Setsize(terminal, &pty.Winsize{Cols: 60, Rows: 20}); err != nil {
		t.Fatalf("터미널 크기 변경 실패: %v", err)
	}
	if _, err := terminal.Write([]byte("크기바꾼뒤\n")); err != nil {
		t.Fatalf("터미널에 쓰기 실패: %v", err)
	}
	screen.waitFor(t, "크기바꾼뒤 20 60")

	// Ctrl-\ 로 빠져나온다. raw 모드가 아니었다면 이 바이트는 SIGQUIT 이 되어 클라이언트를
	// 죽인다 — 깨끗이 끝났다는 것이 곧 ISIG 가 꺼져 있었다는 뜻이다(설계 문서 5.8).
	if _, err := terminal.Write([]byte{0x1c}); err != nil {
		t.Fatalf("빠져나오기 키 전달 실패: %v", err)
	}

	// 클라이언트가 물러난 뒤의 터미널 상태다. -icanon·-isig 가 남아 있으면 사용자는 셸로
	// 돌아왔는데 키가 먹지 않는 터미널을 받는다.
	restored := screen.waitFor(t, "icanon")
	if strings.Contains(restored, "클라이언트실패") {
		t.Errorf("클라이언트가 0 이 아닌 코드로 끝났다 — 화면: %q", restored)
	}
	if strings.Contains(restored, "-icanon") || strings.Contains(restored, "-isig") {
		t.Errorf("빠져나온 뒤 터미널이 raw 모드로 남아 있다 — stty: %q", restored)
	}

	// 빠져나온 것은 detach 다. 세션은 그대로 살아 있어야 한다(설계 원칙 5).
	alive, err := backend.IsAlive(sessionName)
	if err != nil {
		t.Fatalf("IsAlive() 실패: %v", err)
	}
	if !alive {
		t.Errorf("빠져나온 뒤 IsAlive() = false, 세션은 살아 있어야 한다")
	}
}

func TestSupervisorBackendAttachRequiresATerminal(t *testing.T) {
	backend := newIsolatedSupervisorBackend(t)
	unsetEnvForTest(t, insideSupervisorSessionEnvName)

	// 세션 화면은 터미널이 있어야 성립한다. tmux 백엔드도 터미널이 아니면 붙지 못하고 실패로
	// 끝나므로(tmux 의 "open terminal failed") 같은 자리에서 같은 결말을 내되, 무엇이 없어서
	// 안 되는지는 우리 말로 말한다.
	//
	// 표준 입력을 갈아 끼워 시험한다. 터미널이냐 아니냐는 이 프로세스의 사실이라, 그것을
	// 인자로 받게 만들면 시험 하나를 위해 계약이 넓어진다.
	notATerminal, err := os.Open(os.DevNull)
	if err != nil {
		t.Fatalf("%s 열기 실패: %v", os.DevNull, err)
	}
	defer notATerminal.Close()

	originalStdin := os.Stdin
	os.Stdin = notATerminal
	t.Cleanup(func() { os.Stdin = originalStdin })

	err = backend.Attach("kyu-featureX-proj-a")
	if err == nil {
		t.Fatalf("터미널이 아닌 표준 입력으로 Attach() = nil, 실패해야 한다")
	}
	if !strings.Contains(err.Error(), "터미널") {
		t.Errorf("Attach() 에러 = %q, 터미널이 필요하다는 말을 포함하기를 기대", err)
	}
}
