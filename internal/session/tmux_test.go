package session

import (
	"os"
	"os/exec"
	"testing"
)

// 이 파일에는 tmux 백엔드의 계약 시험이 없다.
//
// SessionBackend 가 관찰 가능하게 약속하는 것은 backend_parity_test.go 의 매트릭스가 두 백엔드에
// 같은 본문으로 묻는다. 그 자리에 tmux 전용 사본을 남겨두면 둘이 서로 다르게 자라고, 그때부터는
// "같다" 를 말할 근거가 없어진다(설계 문서 9절 3 단계).
//
// tmux 만의 구현 지식(`-t` 앞의 `=`, 서버 없음을 종료 코드로 알리는 것)도 여기 따로 시험하지
// 않는다. 그 지식이 사라지면 매트릭스가 먼저 깨지기 때문이다 — exactTarget 을 빼면 IsAlive 와
// Kill 이 접두사만 같은 세션에 걸리고, 종료 코드 흡수를 빼면 세션 0 개가 오류가 된다.
// 행동으로 잡히는 것을 구현으로 한 번 더 적으면 tmux 의 내부를 시험에 고정하는 셈이 된다.
//
// Attach 의 성공 경로는 자동 시험하지 않는다. `tmux attach-session` 은 호출한 프로세스의 stdin 이
// TTY 여야 하고 성공하면 사용자가 detach 할 때까지 블로킹된다. 가짜 PTY 를 붙일 수는 있으나
// 검증 대상이 결국 tmux 자체의 동작이라 시험이 감당할 복잡도에 비해 얻는 것이 없다.
// 중첩 판정만은 터미널을 잡기 전에 끝나므로 매트릭스가 두 백엔드에서 함께 묻는다.

// newIsolatedTmuxBackend 는 사용자의 tmux 서버와 완전히 분리된 서버에 붙는 백엔드를 만든다.
//
// 매트릭스의 tmux 줄은 tmux 바이너리를 mock 하지 않고 실제로 실행한다. 그래서 격리에 실패하면
// 사용자가 작업 중인 세션을 죽인다. 소켓 디렉토리를 시험 전용으로 두고, 끝나면 그 서버를 통째로 내린다.
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
	if outerTmux, isInsideTmux := os.LookupEnv(insideTmuxEnvName); isInsideTmux {
		t.Setenv(insideTmuxEnvName, outerTmux)
		os.Unsetenv(insideTmuxEnvName)
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
