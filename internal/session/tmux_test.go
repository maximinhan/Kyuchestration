package session

import (
	"errors"
	"os"
	"os/exec"
	"path/filepath"
	"slices"
	"testing"
)

// newIsolatedTmuxBackend 는 사용자의 tmux 서버와 완전히 분리된 서버에 붙는 백엔드를 만든다.
//
// 이 파일의 테스트는 tmux 바이너리를 mock 하지 않고 실제로 실행한다. 그래서 격리에 실패하면
// 사용자가 작업 중인 세션을 죽일 수 있다. 소켓 디렉토리를 테스트 전용으로 두고, 끝나면
// 그 서버를 통째로 내린다.
func newIsolatedTmuxBackend(t *testing.T) *TmuxBackend {
	t.Helper()

	if _, err := exec.LookPath("tmux"); err != nil {
		t.Skip("tmux 가 PATH 에 없어 통합 테스트를 건너뜁니다")
	}

	socketDir, err := os.MkdirTemp("", "kyu-tmux-test")
	if err != nil {
		t.Fatalf("테스트용 tmux 소켓 디렉토리 생성 실패: %v", err)
	}
	t.Setenv("TMUX_TMPDIR", socketDir)

	// tmux 는 TMUX 가 설정돼 있으면 TMUX_TMPDIR 을 무시하고 그 소켓에 붙는다.
	// 실측: TMUX 를 둔 채 TMUX_TMPDIR 을 지정하면 새 세션이 바깥(사용자) 서버에 생긴다.
	// 테스트가 tmux 세션 안에서 돌 때 사용자 세션을 오염시키지 않으려면 반드시 해제해야 한다.
	// testing 패키지에 Unsetenv 가 없어, t.Setenv 로 원복만 예약해두고 실제 해제는 직접 한다.
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
	const sessionName = "kyu-test-lifecycle"

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
	const sessionName = "kyu-test-duplicate"

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
	const sessionName = "kyu-test-cwd"

	workingDirectory := t.TempDir()

	// 세션이 곧바로 끝나면 결과를 읽기 전에 사라지므로 sleep 으로 붙잡아둔다.
	if err := backend.Create(sessionName, workingDirectory, []string{"sh", "-c", "pwd > pwd.txt; sleep 60"}); err != nil {
		t.Fatalf("Create() 실패: %v", err)
	}

	got := waitForFileContent(t, filepath.Join(workingDirectory, "pwd.txt"))

	// macOS 는 /var 가 /private/var 심볼릭 링크라 t.TempDir() 과 pwd 결과의 표기가 다르다.
	want, err := filepath.EvalSymlinks(workingDirectory)
	if err != nil {
		t.Fatalf("작업 디렉토리 심볼릭 링크 해석 실패: %v", err)
	}
	if got != want {
		t.Errorf("세션의 cwd = %q, want %q", got, want)
	}
}

func TestTmuxBackendCreatePreservesCommandArgumentBoundaries(t *testing.T) {
	backend := newIsolatedTmuxBackend(t)
	const sessionName = "kyu-test-argv"

	workingDirectory := t.TempDir()

	// cmd 를 공백으로 이어 붙여 넘기면 공백 든 경로가 두 인자로 쪼개지고 `;` 는 셸 구분자가 된다.
	// `kyu start` 가 `claude --add-dir <공백 든 경로>` 를 넘기게 되므로 실제로 걸리는 문제다.
	if err := backend.Create(sessionName, workingDirectory, []string{
		"sh", "-c", `printf "[%s]" "$@" > argv.txt; sleep 60`, "_", "a b", "c;d",
	}); err != nil {
		t.Fatalf("Create() 실패: %v", err)
	}

	got := waitForFileContent(t, filepath.Join(workingDirectory, "argv.txt"))
	const want = "[a b][c;d]"
	if got != want {
		t.Errorf("세션에 전달된 인자 = %q, want %q", got, want)
	}
}

func TestTmuxBackendIsAliveRequiresExactName(t *testing.T) {
	backend := newIsolatedTmuxBackend(t)

	// tmux 의 -t 는 기본이 접두사 매칭이라 `-t kyu-test-exact` 가
	// `kyu-test-exact-suffix` 에 걸린다. 막지 않으면 Kill 이 엉뚱한 세션을 죽인다.
	if err := backend.Create("kyu-test-exact-suffix", t.TempDir(), []string{"sleep", "60"}); err != nil {
		t.Fatalf("Create() 실패: %v", err)
	}

	alive, err := backend.IsAlive("kyu-test-exact")
	if err != nil {
		t.Fatalf("IsAlive() 실패: %v", err)
	}
	if alive {
		t.Errorf(`IsAlive("kyu-test-exact") = true, 접두사가 일치할 뿐인 세션에 걸리면 안 된다`)
	}
}

func TestTmuxBackendIsAliveIsFalseWhenServerIsNotRunning(t *testing.T) {
	backend := newIsolatedTmuxBackend(t)

	// 세션을 한 번도 만들지 않았으므로 이 테스트 전용 tmux 서버 자체가 떠 있지 않다.
	alive, err := backend.IsAlive("kyu-test-absent")
	if err != nil {
		t.Fatalf("서버가 없을 때 IsAlive() 가 에러를 반환했다: %v", err)
	}
	if alive {
		t.Errorf("IsAlive() = true, 서버가 없으면 false 여야 한다")
	}
}

func TestTmuxBackendKillIsIdempotent(t *testing.T) {
	backend := newIsolatedTmuxBackend(t)
	const sessionName = "kyu-test-idempotent-kill"

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

func TestTmuxBackendListIsEmptyWhenServerIsNotRunning(t *testing.T) {
	backend := newIsolatedTmuxBackend(t)

	// tmux 서버는 마지막 세션이 죽으면 함께 종료된다. 즉 "세션 0개"의 정상적인 표현이
	// "서버 없음"이므로, 서버가 없다는 사실을 에러로 올리면 안 된다.
	names, err := backend.List()
	if err != nil {
		t.Fatalf("서버가 없을 때 List() 가 에러를 반환했다: %v", err)
	}
	if len(names) != 0 {
		t.Errorf("List() = %v, 빈 목록이어야 한다", names)
	}
}

func TestTmuxBackendListReturnsCreatedSessionNames(t *testing.T) {
	backend := newIsolatedTmuxBackend(t)
	want := []string{"kyu-test-list-a", "kyu-test-list-b"}

	for _, name := range want {
		if err := backend.Create(name, t.TempDir(), []string{"sleep", "60"}); err != nil {
			t.Fatalf("Create(%q) 실패: %v", name, err)
		}
	}

	got, err := backend.List()
	if err != nil {
		t.Fatalf("List() 실패: %v", err)
	}

	// tmux 의 출력 순서에 기대지 않는다. 이름 집합이 맞는지만 본다.
	slices.Sort(got)
	if !slices.Equal(got, want) {
		t.Errorf("List() = %v, want %v", got, want)
	}
}

// Attach 는 자동 테스트하지 않는다.
//
// `tmux attach-session` 은 호출한 프로세스의 stdin 이 TTY 여야 동작하고, 성공하면
// 사용자가 detach 할 때까지 블로킹된다. `go test` 는 TTY 없이 실행되므로 이 함수를 부르면
// "open terminal failed: not a terminal" 로 끝날 뿐이고, 그 실패를 검증해봐야
// 실제 attach 동작에 대해 아무것도 말해주지 않는다. 가짜 PTY 를 붙이는 방법은 있으나
// 검증 대상이 결국 tmux 자체의 동작이라 테스트가 감당할 복잡도에 비해 얻는 것이 없다.
//
// Attach 는 명령 한 줄에 stdin/stdout/stderr 를 그대로 연결하는 것이 전부이며,
// 설계 문서 9.2 의 5단계(`kyu attach`)에서 손으로 확인한다.

func TestTmuxBackendAttachRefusesToNestInsideAnotherSession(t *testing.T) {
	backend := newIsolatedTmuxBackend(t)

	// 실제 attach 는 TTY 를 요구해 자동 테스트에서 부를 수 없다(그래서 이 파일에 Attach 성공 경로
	// 테스트가 없다). 중첩 판정은 TTY 를 잡기 전에 끝나므로 이 분기만은 검증할 수 있고,
	// 사용자가 attach 에서 가장 자주 부딪히는 실패이기도 하다.
	t.Setenv("TMUX", "/tmp/tmux-1000/default,1234,0")

	err := backend.Attach("kyu-test-nested")

	if !errors.Is(err, ErrNestedSession) {
		t.Errorf("Attach() 에러 = %v, ErrNestedSession 으로 풀리기를 기대", err)
	}
}

func TestTmuxBackendDetachKeyIsTheTmuxPrefixSequence(t *testing.T) {
	backend := newIsolatedTmuxBackend(t)

	// 표시 계층은 이 답을 그대로 옮긴다. 값이 바뀌면 사용자는 세션에 갇힌 채로 틀린 키를 누른다.
	if got := backend.DetachKey(); got != "Ctrl-b d" {
		t.Errorf("DetachKey() = %q, want %q", got, "Ctrl-b d")
	}
}
