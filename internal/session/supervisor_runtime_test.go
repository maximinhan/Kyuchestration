package session

import (
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// unsetEnvForTest 는 환경변수를 지우고 테스트가 끝나면 되돌린다.
//
// testing 패키지에 Unsetenv 가 없어, t.Setenv 로 원복만 예약해두고 실제 해제는 직접 한다.
// tmux 테스트가 TMUX 를 걷어낼 때 쓰는 방법과 같다(tmux_test.go:35).
func unsetEnvForTest(t *testing.T, name string) {
	t.Helper()

	if existing, isSet := os.LookupEnv(name); isSet {
		t.Setenv(name, existing)
		os.Unsetenv(name)
	}
}

func TestSessionsDirPrefersTheExplicitRuntimeDirOverride(t *testing.T) {
	runtimeDir := t.TempDir()
	t.Setenv(runtimeDirEnvName, runtimeDir)
	t.Setenv(xdgRuntimeDirEnvName, filepath.Join(t.TempDir(), "xdg"))

	got, err := resolveSessionsDir()
	if err != nil {
		t.Fatalf("resolveSessionsDir() 실패: %v", err)
	}

	want := filepath.Join(runtimeDir, sessionsDirName)
	if got != want {
		t.Errorf("resolveSessionsDir() = %q, want %q", got, want)
	}
}

func TestSessionsDirLivesUnderXdgRuntimeDirWhenItIsSet(t *testing.T) {
	unsetEnvForTest(t, runtimeDirEnvName)
	xdgRuntimeDir := t.TempDir()
	t.Setenv(xdgRuntimeDirEnvName, xdgRuntimeDir)

	got, err := resolveSessionsDir()
	if err != nil {
		t.Fatalf("resolveSessionsDir() 실패: %v", err)
	}

	want := filepath.Join(xdgRuntimeDir, runtimeDirName, sessionsDirName)
	if got != want {
		t.Errorf("resolveSessionsDir() = %q, want %q", got, want)
	}
}

func TestSessionsDirFallsBackToLocalStateWhenXdgRuntimeDirIsAbsent(t *testing.T) {
	unsetEnvForTest(t, runtimeDirEnvName)
	unsetEnvForTest(t, xdgRuntimeDirEnvName)

	// macOS 에는 XDG_RUNTIME_DIR 이 없다. 이 폴백은 드문 경우가 아니라
	// 두 대상 플랫폼 중 하나의 정상 경로다(설계 문서 5.2).
	home := t.TempDir()
	t.Setenv("HOME", home)

	got, err := resolveSessionsDir()
	if err != nil {
		t.Fatalf("resolveSessionsDir() 실패: %v", err)
	}

	want := filepath.Join(home, ".local", "state", runtimeDirName, sessionsDirName)
	if got != want {
		t.Errorf("resolveSessionsDir() = %q, want %q", got, want)
	}
}

func TestSessionRuntimePathsPutSocketLockAndLogSideBySide(t *testing.T) {
	runtimeDir := t.TempDir()
	t.Setenv(runtimeDirEnvName, runtimeDir)
	const sessionName = "kyu-featureX-main"

	paths, err := newSessionRuntimePaths(sessionName)
	if err != nil {
		t.Fatalf("newSessionRuntimePaths() 실패: %v", err)
	}

	sessionsDir := filepath.Join(runtimeDir, sessionsDirName)
	for _, check := range []struct {
		label string
		got   string
		want  string
	}{
		{"sessionsDir", paths.sessionsDir, sessionsDir},
		{"socket", paths.socket, filepath.Join(sessionsDir, sessionName+sessionSocketExtension)},
		{"lock", paths.lock, filepath.Join(sessionsDir, sessionName+sessionLockExtension)},
		{"log", paths.log, filepath.Join(sessionsDir, sessionName+sessionLogExtension)},
	} {
		if check.got != check.want {
			t.Errorf("%s = %q, want %q", check.label, check.got, check.want)
		}
	}
}

func TestSessionRuntimePathsRejectNamesThatCouldLeaveTheSessionsDirectory(t *testing.T) {
	t.Setenv(runtimeDirEnvName, t.TempDir())

	// 이름을 만드는 곳(name.go)이 `/` 를 넣을 수 없게 되어 있어도, 파일로 쓰는 이 자리에서
	// 다시 확인한다. 두 곳이 멀어 그 사이에서 규칙이 바뀔 수 있다(설계 문서 5.2).
	for _, name := range []string{"", ".", "..", "kyu-a/b", "../escape", "kyu-a/../b"} {
		if _, err := newSessionRuntimePaths(name); err == nil {
			t.Errorf("newSessionRuntimePaths(%q) 가 성공했다, 거절해야 한다", name)
		}
	}
}

func TestSessionRuntimePathsRejectSocketPathsBeyondTheUnixSocketLimit(t *testing.T) {
	// sun_path 한도를 넘는 경로는 커널이 "invalid argument" 로만 답한다.
	// 조용히 자르지 않고 알아볼 수 있는 오류로 끊는지 확인한다(설계 문서 10절 6번).
	deepRuntimeDir := filepath.Join(t.TempDir(), strings.Repeat("d", 120))
	t.Setenv(runtimeDirEnvName, deepRuntimeDir)

	_, err := newSessionRuntimePaths("kyu-featureX-main")

	if !errors.Is(err, ErrSocketPathTooLong) {
		t.Fatalf("newSessionRuntimePaths() 에러 = %v, ErrSocketPathTooLong 으로 풀리기를 기대", err)
	}
	// 원인을 찾는 사람이 길이와 한도를 함께 볼 수 있어야 한다.
	if !strings.Contains(err.Error(), deepRuntimeDir) {
		t.Errorf("에러 = %q, 문제의 경로를 포함하기를 기대", err)
	}
}
