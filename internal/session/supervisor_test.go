package session

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// TestMain 은 이 테스트 바이너리가 감독으로 다시 실행되는 것을 받아준다.
//
// 감독은 kyu 바이너리 자신을 __supervise 로 다시 실행한 것이고(설계 문서 5.1), 백엔드는 그
// 경로를 os.Executable() 로 얻는다. 테스트 프로세스에서 그것은 이 테스트 바이너리이므로,
// 여기서 같은 하위 명령을 받아주면 kyu 를 따로 빌드하지 않고도 실제 프로세스·실제 소켓으로
// 검증할 수 있다. tmux 테스트가 tmux 를 mock 하지 않는 것과 같은 방침이다.
func TestMain(m *testing.M) {
	if len(os.Args) > 1 && os.Args[1] == SuperviseCommandName {
		if err := RunSupervisor(os.Args[2:]); err != nil {
			fmt.Fprintln(os.Stderr, err)
			os.Exit(1)
		}
		os.Exit(0)
	}
	os.Exit(m.Run())
}

// newIsolatedSupervisorBackend 는 사용자의 세션과 완전히 분리된 런타임 디렉토리를 쓰는 백엔드를 만든다.
//
// 이 파일의 테스트는 감독을 실제로 띄우고 실제 유닉스 소켓으로 말한다. 그래서 격리에 실패하면
// 사용자가 작업 중인 세션을 죽인다 — tmux 테스트가 TMUX_TMPDIR 로 감당하는 것과 같은 위험이다.
//
// t.TempDir() 을 쓰지 않고 짧은 이름으로 직접 만든다. t.TempDir() 의 경로에는 테스트 함수
// 이름이 통째로 들어가는데, 그 위에 세션 이름까지 얹으면 소켓 경로가 sun_path 한도에 닿는다.
func newIsolatedSupervisorBackend(t *testing.T) *SupervisorBackend {
	t.Helper()

	runtimeDir, err := os.MkdirTemp("", "kyu-sv")
	if err != nil {
		t.Fatalf("테스트용 세션 런타임 디렉토리 생성 실패: %v", err)
	}
	t.Setenv(runtimeDirEnvName, runtimeDir)

	backend, err := NewSupervisorBackend()
	if err != nil {
		t.Fatalf("NewSupervisorBackend() 실패: %v", err)
	}

	t.Cleanup(func() {
		for _, name := range sessionNamesInRuntimeDir(t, runtimeDir) {
			backend.Kill(name)
		}
		os.RemoveAll(runtimeDir)
	})

	return backend
}

// sessionNamesInRuntimeDir 는 런타임 디렉토리에 소켓 파일을 남긴 세션 이름을 모은다.
func sessionNamesInRuntimeDir(t *testing.T, runtimeDir string) []string {
	t.Helper()

	entries, err := os.ReadDir(filepath.Join(runtimeDir, sessionsDirName))
	if err != nil {
		return nil
	}

	var names []string
	for _, entry := range entries {
		if strings.HasSuffix(entry.Name(), sessionSocketExtension) {
			names = append(names, strings.TrimSuffix(entry.Name(), sessionSocketExtension))
		}
	}
	return names
}

// socketPathFor 는 테스트가 소켓 파일 자체를 들여다볼 때 쓰는 경로다.
func socketPathFor(t *testing.T, name string) string {
	t.Helper()

	paths, err := newSessionRuntimePaths(name)
	if err != nil {
		t.Fatalf("newSessionRuntimePaths(%q) 실패: %v", name, err)
	}
	return paths.socket
}

// waitUntil 은 조건이 참이 될 때까지 기다린다.
//
// 감독은 떼어진 프로세스라 종료가 부모의 반환과 같은 순간이 아니다. 자연사처럼 우리가 요청하지
// 않은 전이는 폴링으로 본다.
func waitUntil(t *testing.T, description string, condition func() bool) {
	t.Helper()

	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		if condition() {
			return
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatalf("%s — 5초 안에 이뤄지지 않았습니다", description)
}

func TestSupervisorBackendCreateMakesSessionAliveAndKillEndsIt(t *testing.T) {
	backend := newIsolatedSupervisorBackend(t)
	const sessionName = "kyu-test-sv-lifecycle"

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

	// 정상 종료 경로가 소켓을 지운다. 그래야 죽은 소켓이 비정상 종료에서만 남는다(설계 문서 5.3).
	if _, err := os.Stat(socketPathFor(t, sessionName)); !errors.Is(err, os.ErrNotExist) {
		t.Errorf("Kill() 후 소켓 파일 stat = %v, 파일이 사라져 있어야 한다", err)
	}
}

func TestSupervisorBackendCreateRejectsDuplicateName(t *testing.T) {
	backend := newIsolatedSupervisorBackend(t)
	const sessionName = "kyu-test-sv-duplicate"

	if err := backend.Create(sessionName, t.TempDir(), []string{"sleep", "60"}); err != nil {
		t.Fatalf("첫 Create() 실패: %v", err)
	}

	// 소켓 바인딩 실패가 곧 중복 판정이다 — 판정과 획득이 같은 한 번의 연산이라
	// 그 사이에 낄 틈이 없다(설계 문서 5.3).
	err := backend.Create(sessionName, t.TempDir(), []string{"sleep", "60"})
	if !errors.Is(err, ErrSessionExists) {
		t.Errorf("같은 이름으로 Create() 시 err = %v, ErrSessionExists 여야 한다", err)
	}
}

func TestSupervisorBackendCreateRunsCommandInGivenDirectory(t *testing.T) {
	backend := newIsolatedSupervisorBackend(t)
	const sessionName = "kyu-test-sv-cwd"

	workingDirectory := t.TempDir()
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

func TestSupervisorBackendCreatePreservesCommandArgumentBoundaries(t *testing.T) {
	backend := newIsolatedSupervisorBackend(t)
	const sessionName = "kyu-test-sv-argv"

	workingDirectory := t.TempDir()

	// 인자는 __supervise 의 `--` 뒤에 그대로 실려 감독에게 건너간다. 셸을 거치지 않으므로
	// 공백 든 경로도 `;` 도 한 인자로 남아야 한다 — kyu start 가 넘기는 것이 그런 인자다.
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

func TestSupervisorBackendCreateMarksTheSessionForNestedDetection(t *testing.T) {
	backend := newIsolatedSupervisorBackend(t)
	const sessionName = "kyu-test-sv-marker"

	workingDirectory := t.TempDir()

	// tmux 가 TMUX 를 심는 자리다(tmux.go:18). 세션 안에서 도는 모든 프로세스가 물려받아야
	// 그 안에서 부른 kyu 가 자기가 세션 안에 있음을 안다(설계 문서 5.3).
	if err := backend.Create(sessionName, workingDirectory, []string{
		"sh", "-c", `printf "%s" "$KYU_SESSION" > marker.txt; sleep 60`,
	}); err != nil {
		t.Fatalf("Create() 실패: %v", err)
	}

	got := waitForFileContent(t, filepath.Join(workingDirectory, "marker.txt"))
	if got != sessionName {
		t.Errorf("세션 안의 %s = %q, want %q", insideSupervisorSessionEnvName, got, sessionName)
	}
}

func TestSupervisorBackendCreateGivesTheSessionATerminal(t *testing.T) {
	backend := newIsolatedSupervisorBackend(t)
	const sessionName = "kyu-test-sv-tty"

	workingDirectory := t.TempDir()

	// PTY 를 쥔 감독이 있어야 세션 안 프로그램이 자기 표준 입출력을 터미널로 본다. isatty 가
	// 거짓이 되는 순간 그 프로그램은 화면 그리기를 포기한다(설계 문서 3.6).
	if err := backend.Create(sessionName, workingDirectory, []string{
		"sh", "-c", `if [ -t 0 ] && [ -t 1 ]; then printf tty > tty.txt; else printf notty > tty.txt; fi; sleep 60`,
	}); err != nil {
		t.Fatalf("Create() 실패: %v", err)
	}

	if got := waitForFileContent(t, filepath.Join(workingDirectory, "tty.txt")); got != "tty" {
		t.Errorf("세션 안에서 본 표준 입출력 = %q, want %q", got, "tty")
	}
}

func TestSupervisorBackendCreateReportsMissingWorkingDirectory(t *testing.T) {
	backend := newIsolatedSupervisorBackend(t)
	const sessionName = "kyu-test-sv-nocwd"

	missingDirectory := filepath.Join(t.TempDir(), "없는-디렉토리")

	// 준비 파이프가 있어 부모가 "왜 안 떴는지" 를 안다. 폴링이었다면 "소켓이 아직 안 받는다"
	// 까지밖에 알 수 없었을 자리다(설계 문서 5.3).
	err := backend.Create(sessionName, missingDirectory, []string{"sleep", "60"})
	if err == nil {
		t.Fatalf("없는 디렉토리로 Create() 가 성공했다")
	}
	if errors.Is(err, ErrSessionExists) {
		t.Errorf("err = %v, 중복이 아니라 작업 디렉토리 문제로 끝나야 한다", err)
	}
	if !strings.Contains(err.Error(), missingDirectory) {
		t.Errorf("err = %q, 없는 디렉토리 경로를 포함하기를 기대", err)
	}

	// 자식을 못 띄운 이름은 죽은 소켓을 남기지 않아야 한다.
	if _, statErr := os.Stat(socketPathFor(t, sessionName)); !errors.Is(statErr, os.ErrNotExist) {
		t.Errorf("실패한 Create() 뒤 소켓 파일 stat = %v, 남아 있으면 안 된다", statErr)
	}
}

func TestSupervisorBackendSocketIsReadableOnlyByItsOwner(t *testing.T) {
	backend := newIsolatedSupervisorBackend(t)
	const sessionName = "kyu-test-sv-perm"

	if err := backend.Create(sessionName, t.TempDir(), []string{"sleep", "60"}); err != nil {
		t.Fatalf("Create() 실패: %v", err)
	}

	// 이 소켓에 연결할 수 있다는 것은 그 세션에 키를 보낼 수 있다는 뜻이다(설계 문서 5.2).
	info, err := os.Stat(socketPathFor(t, sessionName))
	if err != nil {
		t.Fatalf("소켓 파일 stat 실패: %v", err)
	}
	if got := info.Mode().Perm(); got != sessionFilePermission {
		t.Errorf("소켓 권한 = %#o, want %#o", got, sessionFilePermission)
	}
}

func TestSupervisorBackendSessionEndsWhenItsCommandEnds(t *testing.T) {
	backend := newIsolatedSupervisorBackend(t)
	const sessionName = "kyu-test-sv-natural-exit"

	// 감독은 자기 세션이 끝날 때 죽는다 — 정할 자동 종료 정책이 없다(설계 문서 5.3).
	if err := backend.Create(sessionName, t.TempDir(), []string{"sh", "-c", "exit 0"}); err != nil {
		t.Fatalf("Create() 실패: %v", err)
	}

	waitUntil(t, "명령이 끝난 세션이 목록에서 사라지기", func() bool {
		alive, err := backend.IsAlive(sessionName)
		return err == nil && !alive
	})

	if _, err := os.Stat(socketPathFor(t, sessionName)); !errors.Is(err, os.ErrNotExist) {
		t.Errorf("자연사 후 소켓 파일 stat = %v, 파일이 사라져 있어야 한다", err)
	}
}

func TestSupervisorBackendKillIsIdempotent(t *testing.T) {
	backend := newIsolatedSupervisorBackend(t)
	const sessionName = "kyu-test-sv-idempotent-kill"

	if err := backend.Kill(sessionName); err != nil {
		t.Errorf("세션을 만든 적 없을 때 Kill() = %v, nil 이어야 한다", err)
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

func TestSupervisorBackendIsAliveIsFalseWhenNothingWasEverCreated(t *testing.T) {
	backend := newIsolatedSupervisorBackend(t)

	// 세션 디렉토리조차 없는 상태다. 그것이 세션 0 개의 정상적인 표현이므로 오류가 아니다.
	alive, err := backend.IsAlive("kyu-test-sv-absent")
	if err != nil {
		t.Fatalf("소켓이 없을 때 IsAlive() 가 에러를 반환했다: %v", err)
	}
	if alive {
		t.Errorf("IsAlive() = true, 소켓이 없으면 false 여야 한다")
	}
}

func TestSupervisorBackendIsAliveRequiresExactName(t *testing.T) {
	backend := newIsolatedSupervisorBackend(t)

	// 세션 이름은 kyu-<workdir>-<repo> 라 서로 접두사 관계가 되기 쉽다. tmux 는 -t 가 접두사
	// 매칭이라 = 를 붙여 막았는데, 소켓 이름은 파일 이름이라 그 위험이 애초에 없다는 것을 고정한다.
	if err := backend.Create("kyu-test-sv-exact-suffix", t.TempDir(), []string{"sleep", "60"}); err != nil {
		t.Fatalf("Create() 실패: %v", err)
	}

	alive, err := backend.IsAlive("kyu-test-sv-exact")
	if err != nil {
		t.Fatalf("IsAlive() 실패: %v", err)
	}
	if alive {
		t.Errorf(`IsAlive("kyu-test-sv-exact") = true, 접두사가 일치할 뿐인 세션에 걸리면 안 된다`)
	}
}
