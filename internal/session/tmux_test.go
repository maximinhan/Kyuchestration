package session

import (
	"errors"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// newIsolatedTmuxBackend 는 사용자의 tmux 서버와 완전히 분리된 서버에 붙는 백엔드를 만든다.
//
// 통합 테스트가 tmux 바이너리를 실제로 실행하므로, 격리에 실패하면 사용자가 작업 중인
// 세션을 죽일 수 있다. 그래서 소켓 디렉토리를 테스트 전용으로 두고 끝나면 서버를 통째로 내린다.
func newIsolatedTmuxBackend(t *testing.T) *TmuxBackend {
	t.Helper()

	if _, err := exec.LookPath("tmux"); err != nil {
		t.Skip("tmux 가 PATH 에 없어 통합 테스트를 건너뜁니다")
	}

	socketDir, err := os.MkdirTemp("", "wd-tmux-test")
	if err != nil {
		t.Fatalf("테스트용 tmux 소켓 디렉토리 생성 실패: %v", err)
	}
	t.Setenv("TMUX_TMPDIR", socketDir)

	// tmux 는 TMUX 가 설정돼 있으면 TMUX_TMPDIR 을 무시하고 그 소켓에 붙는다.
	// 실측: TMUX 를 둔 채 TMUX_TMPDIR 을 지정하면 새 세션이 바깥(사용자) 서버에 생긴다.
	// 테스트가 tmux 세션 안에서 돌 때 사용자 세션을 오염시키지 않으려면 반드시 해제해야 한다.
	// t.Setenv 로 원복을 예약한 뒤 실제로는 Unsetenv 한다(테스트 API 에 Unsetenv 가 없다).
	if outerTmux, isInsideTmux := os.LookupEnv("TMUX"); isInsideTmux {
		t.Setenv("TMUX", outerTmux)
		os.Unsetenv("TMUX")
	}

	backend, err := NewTmuxBackend()
	if err != nil {
		t.Fatalf("NewTmuxBackend() 실패: %v", err)
	}

	t.Cleanup(func() {
		exec.Command(backend.tmuxPath, "kill-server").Run()
		os.RemoveAll(socketDir)
	})

	return backend
}

func TestTmuxBackendCreateMakesSessionAliveAndKillEndsIt(t *testing.T) {
	backend := newIsolatedTmuxBackend(t)
	const sessionName = "wd-test-lifecycle"

	if err := backend.Create(sessionName, t.TempDir(), []string{"sleep", "60"}); err != nil {
		t.Fatalf("Create() 실패: %v", err)
	}

	alive, err := backend.IsAlive(sessionName)
	if err != nil {
		t.Fatalf("IsAlive() 실패: %v", err)
	}
	if !alive {
		t.Fatalf("Create() 직후 IsAlive() = false, 세션이 살아있어야 한다")
	}

	if err := backend.Kill(sessionName); err != nil {
		t.Fatalf("Kill() 실패: %v", err)
	}

	alive, err = backend.IsAlive(sessionName)
	if err != nil {
		t.Fatalf("Kill() 후 IsAlive() 실패: %v", err)
	}
	if alive {
		t.Errorf("Kill() 후 IsAlive() = true, 세션이 종료되어야 한다")
	}
}

func TestTmuxBackendCreateRejectsDuplicateName(t *testing.T) {
	backend := newIsolatedTmuxBackend(t)
	const sessionName = "wd-test-duplicate"

	if err := backend.Create(sessionName, t.TempDir(), []string{"sleep", "60"}); err != nil {
		t.Fatalf("첫 Create() 실패: %v", err)
	}

	err := backend.Create(sessionName, t.TempDir(), []string{"sleep", "60"})
	if !errors.Is(err, ErrSessionExists) {
		t.Errorf("같은 이름으로 Create() 시 err = %v, ErrSessionExists 여야 한다", err)
	}
}

func TestTmuxBackendCreateRunsCommandInGivenDirectory(t *testing.T) {
	backend := newIsolatedTmuxBackend(t)
	const sessionName = "wd-test-cwd"

	workingDirectory := t.TempDir()
	recordedPath := filepath.Join(workingDirectory, "pwd.txt")

	// 세션이 곧바로 끝나면 cwd 를 확인하기 전에 사라지므로 sleep 으로 붙잡아둔다.
	if err := backend.Create(sessionName, workingDirectory, []string{"sh", "-c", "pwd > pwd.txt; sleep 60"}); err != nil {
		t.Fatalf("Create() 실패: %v", err)
	}

	content := waitForFileContent(t, recordedPath)
	// macOS 에서 /var 가 /private/var 심볼릭 링크라 t.TempDir() 과 pwd 결과가 다를 수 있다.
	resolvedWorkingDirectory, err := filepath.EvalSymlinks(workingDirectory)
	if err != nil {
		t.Fatalf("작업 디렉토리 심볼릭 링크 해석 실패: %v", err)
	}
	if content != resolvedWorkingDirectory {
		t.Errorf("세션의 cwd = %q, want %q", content, resolvedWorkingDirectory)
	}
}

func TestTmuxBackendIsAliveRequiresExactName(t *testing.T) {
	backend := newIsolatedTmuxBackend(t)

	// tmux 의 -t 는 기본적으로 접두사 매칭이라 `-t wd-test-exact` 가
	// `wd-test-exact-suffix` 에 걸린다. 이를 막지 않으면 Kill 이 엉뚱한 세션을 죽인다.
	if err := backend.Create("wd-test-exact-suffix", t.TempDir(), []string{"sleep", "60"}); err != nil {
		t.Fatalf("Create() 실패: %v", err)
	}

	alive, err := backend.IsAlive("wd-test-exact")
	if err != nil {
		t.Fatalf("IsAlive() 실패: %v", err)
	}
	if alive {
		t.Errorf("IsAlive(\"wd-test-exact\") = true, 접두사가 일치할 뿐인 세션에 걸리면 안 된다")
	}
}

func TestTmuxBackendIsAliveIsFalseWhenServerIsNotRunning(t *testing.T) {
	backend := newIsolatedTmuxBackend(t)

	// 세션을 한 번도 만들지 않았으므로 이 테스트 전용 tmux 서버 자체가 떠 있지 않다.
	alive, err := backend.IsAlive("wd-test-absent")
	if err != nil {
		t.Fatalf("서버가 없을 때 IsAlive() 가 에러를 반환했다: %v", err)
	}
	if alive {
		t.Errorf("IsAlive() = true, 서버가 없으면 false 여야 한다")
	}
}

func TestTmuxBackendKillIsIdempotent(t *testing.T) {
	backend := newIsolatedTmuxBackend(t)
	const sessionName = "wd-test-idempotent-kill"

	if err := backend.Kill(sessionName); err != nil {
		t.Errorf("서버도 세션도 없을 때 Kill() = %v, nil 이어야 한다", err)
	}

	if err := backend.Create(sessionName, t.TempDir(), []string{"sleep", "60"}); err != nil {
		t.Fatalf("Create() 실패: %v", err)
	}
	if err := backend.Kill(sessionName); err != nil {
		t.Fatalf("첫 Kill() 실패: %v", err)
	}
	if err := backend.Kill(sessionName); err != nil {
		t.Errorf("두 번째 Kill() = %v, 멱등해야 하므로 nil 이어야 한다", err)
	}
}

// waitForFileContent 는 세션 안에서 실행된 명령이 파일을 남길 때까지 기다린다.
// tmux new-session -d 는 명령의 완료를 기다리지 않고 곧바로 반환하므로 폴링이 필요하다.
func waitForFileContent(t *testing.T, path string) string {
	t.Helper()

	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		content, err := os.ReadFile(path)
		if err == nil && len(content) > 0 {
			return strings.TrimSpace(string(content))
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatalf("%s 가 5초 안에 생성되지 않았습니다", path)
	return ""
}
