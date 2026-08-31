package session

import (
	"errors"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"slices"
	"strconv"
	"strings"
	"syscall"
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

	// 같은 이유로 raw 모드 클라이언트 호출도 받아준다. 이쪽은 진짜 PTY 안에서 돌아야 하므로
	// 시험이 이 바이너리를 자식으로 띄운다(supervisor_attach_client_test.go).
	if len(os.Args) > 2 && os.Args[1] == attachClientTestCommand {
		backend, err := NewSupervisorBackend()
		if err != nil {
			fmt.Fprintln(os.Stderr, err)
			os.Exit(1)
		}
		if err := backend.Attach(os.Args[2]); err != nil {
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

// createSessionRecordingItsSupervisorPid 는 세션을 만들고 그 감독의 pid 를 돌려준다.
//
// 감독의 pid 는 세션 안 프로그램의 부모 pid 다 — 감독이 자식을 직접 띄우므로 $PPID 가 곧
// 그것이다. 이 방법이면 pid 를 알아내려고 프로토콜에 연산을 하나 더 만들지 않아도 된다.
func createSessionRecordingItsSupervisorPid(t *testing.T, backend *SupervisorBackend, name string) int {
	t.Helper()

	workingDirectory := t.TempDir()
	if err := backend.Create(name, workingDirectory, []string{
		"sh", "-c", `printf "%s" "$PPID" > ppid.txt; sleep 60`,
	}); err != nil {
		t.Fatalf("Create(%q) 실패: %v", name, err)
	}

	raw := waitForFileContent(t, filepath.Join(workingDirectory, "ppid.txt"))
	supervisorPid, err := strconv.Atoi(raw)
	if err != nil {
		t.Fatalf("감독 pid 를 해석하지 못했습니다 (%q): %v", raw, err)
	}
	return supervisorPid
}

// killSupervisorAbruptly 는 감독을 SIGKILL 한다 — 소켓을 지울 틈을 주지 않는 종료다.
func killSupervisorAbruptly(t *testing.T, backend *SupervisorBackend, name string, supervisorPid int) {
	t.Helper()

	if err := syscall.Kill(supervisorPid, syscall.SIGKILL); err != nil {
		t.Fatalf("감독(pid %d) SIGKILL 실패: %v", supervisorPid, err)
	}
	waitUntil(t, fmt.Sprintf("SIGKILL 한 감독의 세션 %s 이 죽은 것으로 보이기", name), func() bool {
		alive, err := backend.IsAlive(name)
		return err == nil && !alive
	})
}

func TestSupervisorBackendListLeavesTheSocketOfAKilledSupervisorInPlace(t *testing.T) {
	backend := newIsolatedSupervisorBackend(t)
	const sessionName = "kyu-test-sv-dead-socket"

	supervisorPid := createSessionRecordingItsSupervisorPid(t, backend, sessionName)
	killSupervisorAbruptly(t, backend, sessionName, supervisorPid)

	names, err := backend.List()
	if err != nil {
		t.Fatalf("List() 실패: %v", err)
	}
	if slices.Contains(names, sessionName) {
		t.Errorf("List() = %v, 죽은 감독의 세션이 들어 있으면 안 된다", names)
	}

	// 읽기 연산은 파일을 지우지 않는다. 치우는 시점은 그 이름으로 다시 Create 할 때다(설계 문서 5.4).
	if _, err := os.Stat(socketPathFor(t, sessionName)); err != nil {
		t.Errorf("죽은 소켓 파일 stat = %v, List() 는 파일을 지우지 않아야 한다", err)
	}
}

func TestSupervisorBackendCreateReclaimsTheNameLeftByAKilledSupervisor(t *testing.T) {
	backend := newIsolatedSupervisorBackend(t)
	const sessionName = "kyu-test-sv-reclaim"

	supervisorPid := createSessionRecordingItsSupervisorPid(t, backend, sessionName)
	killSupervisorAbruptly(t, backend, sessionName, supervisorPid)

	// 남은 소켓 파일 때문에 바인딩은 실패한다. 그것이 살아있는 감독 때문인지 죽은 감독이 남긴
	// 파일 때문인지는 붙어봐야 알고, 죽은 것이면 치우고 다시 잡아야 한다(설계 문서 5.3).
	// macOS 폴백 경로에서는 재부팅도 이 파일을 치우지 않으므로 드문 경우가 아니다.
	if err := backend.Create(sessionName, t.TempDir(), []string{"sleep", "60"}); err != nil {
		t.Fatalf("죽은 소켓이 남은 이름으로 Create() 실패: %v", err)
	}

	alive, err := backend.IsAlive(sessionName)
	if err != nil {
		t.Fatalf("IsAlive() 실패: %v", err)
	}
	if !alive {
		t.Errorf("다시 만든 세션의 IsAlive() = false, 살아있어야 한다")
	}
}

func TestKillingOneSupervisorLeavesTheOtherSessionsRunning(t *testing.T) {
	backend := newIsolatedSupervisorBackend(t)
	const (
		firstSession  = "kyu-test-sv-iso-a"
		middleSession = "kyu-test-sv-iso-b"
		lastSession   = "kyu-test-sv-iso-c"
	)

	// 이 설계를 고른 이유가 실제로 성립하는지를 가장 먼저 확인한다(설계 문서 9절 1 단계).
	// 중앙 데몬이었다면 하나가 죽을 때 셋이 함께 죽는다 — 세션별 감독은 반경을 하나로 만든다.
	for _, name := range []string{firstSession, lastSession} {
		if err := backend.Create(name, t.TempDir(), []string{"sleep", "60"}); err != nil {
			t.Fatalf("Create(%q) 실패: %v", name, err)
		}
	}
	middleSupervisorPid := createSessionRecordingItsSupervisorPid(t, backend, middleSession)

	killSupervisorAbruptly(t, backend, middleSession, middleSupervisorPid)
	t.Logf("가운데 세션 %s 의 감독(pid %d)을 SIGKILL 했다", middleSession, middleSupervisorPid)

	names, err := backend.List()
	if err != nil {
		t.Fatalf("List() 실패: %v", err)
	}
	slices.Sort(names)
	t.Logf("남은 세션: %v", names)

	want := []string{firstSession, lastSession}
	if !slices.Equal(names, want) {
		t.Fatalf("List() = %v, want %v", names, want)
	}

	for _, name := range want {
		alive, err := backend.IsAlive(name)
		if err != nil {
			t.Fatalf("IsAlive(%q) 실패: %v", name, err)
		}
		if !alive {
			t.Errorf("IsAlive(%q) = false, 남의 감독이 죽어도 이 세션은 살아 있어야 한다", name)
		}
	}
}

func TestConcurrentCreateOfTheSameNameLeavesExactlyOneSession(t *testing.T) {
	backend := newIsolatedSupervisorBackend(t)
	const sessionName = "kyu-test-sv-concurrent"
	const attempts = 4

	// 이름 잠금이 곧 배타성이라는 주장을 실제로 겨뤄본다. tmux 는 has-session 으로 먼저 묻고
	// 만들었으므로 그 둘 사이에 틈이 있었는데, 여기서는 판정과 획득이 같은 한 번의
	// 연산이라 그 틈이 없다(설계 문서 5.3·5.9).
	//
	// 성공한 Create 의 수를 세는 것이 곧 감독의 수를 세는 것이다. 이름을 빼앗긴 감독은 그 사실을
	// 모른 채 자기 Create 에 성공을 알렸을 것이므로, 둘이 되면 이 숫자가 둘이 된다.
	workingDirectories := make([]string, attempts)
	for i := range workingDirectories {
		workingDirectories[i] = t.TempDir()
	}

	results := make(chan error, attempts)
	for i := range attempts {
		go func() {
			results <- backend.Create(sessionName, workingDirectories[i], []string{"sleep", "60"})
		}()
	}

	created := 0
	for range attempts {
		switch err := <-results; {
		case err == nil:
			created++
		case errors.Is(err, ErrSessionExists):
		default:
			t.Errorf("Create() = %v, nil 이거나 ErrSessionExists 여야 한다", err)
		}
	}

	if created != 1 {
		t.Errorf("성공한 Create() = %d 개, 정확히 1 개여야 한다", created)
	}

	names, err := backend.List()
	if err != nil {
		t.Fatalf("List() 실패: %v", err)
	}
	if !slices.Equal(names, []string{sessionName}) {
		t.Errorf("List() = %v, want [%s]", names, sessionName)
	}
}

func TestProbingALiveSupervisorIsNotRecordedAsAFailure(t *testing.T) {
	backend := newIsolatedSupervisorBackend(t)
	const sessionName = "kyu-test-sv-probe-log"

	if err := backend.Create(sessionName, t.TempDir(), []string{"sleep", "60"}); err != nil {
		t.Fatalf("Create() 실패: %v", err)
	}

	// 감독은 잠금을 쥐지 않는 옛 판 감독이 그 이름으로 도는지 붙어보고 판정한다(bindSessionSocket).
	// 그 탐지 연결은 요청을 한 줄도 보내지 않고 끊는 모양이라, 감독이 그것을 요청 해석 실패로
	// 적으면 정상적인 일이 기록에 오류로 쌓인다. 같은 모양을 여기서 그대로 만든다.
	probe, err := net.DialTimeout("unix", socketPathFor(t, sessionName), socketProbeTimeout)
	if err != nil {
		t.Fatalf("탐지 연결 실패: %v", err)
	}
	probe.Close()

	// 첫 감독은 연결을 하나씩 처리한다. 이 조회에 답했다는 것은 앞의 탐지 연결을 이미
	// 끝냈다는 뜻이므로, 기록을 읽는 시점이 그 뒤임이 보장된다.
	if _, err := backend.IsAlive(sessionName); err != nil {
		t.Fatalf("IsAlive() 실패: %v", err)
	}

	paths, pathsErr := newSessionRuntimePaths(sessionName)
	if pathsErr != nil {
		t.Fatalf("newSessionRuntimePaths() 실패: %v", pathsErr)
	}
	recorded, err := os.ReadFile(paths.log)
	if err != nil {
		t.Fatalf("세션 기록 읽기 실패: %v", err)
	}

	// 기록은 세션 하나의 일생 몇 줄이라, 무엇이 잘못됐는지 찾는 사람이 읽는 곳이다.
	if strings.Contains(string(recorded), "제어 요청 해석 실패") {
		t.Errorf("세션 기록 = %q, 탐지 연결이 실패로 남아 있다", recorded)
	}
}

// bindSocketWithoutListening 은 bind 는 마쳤지만 아직 listen 하지 않은 소켓을 남긴다.
//
// 감독이 기동 도중 잠깐 지나는 상태를 실제 커널 상태로 만든다 — 이 소켓 파일에 붙으면
// ECONNREFUSED 로 끝난다. 즉 "죽은 소켓" 과 겉모습이 같다.
func bindSocketWithoutListening(t *testing.T, socketPath string) {
	t.Helper()

	fileDescriptor, err := syscall.Socket(syscall.AF_UNIX, syscall.SOCK_STREAM, 0)
	if err != nil {
		t.Fatalf("소켓 생성 실패: %v", err)
	}
	t.Cleanup(func() {
		syscall.Close(fileDescriptor)
		os.Remove(socketPath)
	})

	if err := syscall.Bind(fileDescriptor, &syscall.SockaddrUnix{Name: socketPath}); err != nil {
		t.Fatalf("소켓 바인딩 실패 (%s): %v", socketPath, err)
	}
}

// holdSessionNameLockForTest 는 감독이 하듯 세션 이름 잠금을 잡고 테스트가 끝날 때까지 쥔다.
func holdSessionNameLockForTest(t *testing.T, lockPath string) {
	t.Helper()

	lockFile, err := os.OpenFile(lockPath, os.O_CREATE|os.O_RDWR, sessionFilePermission)
	if err != nil {
		t.Fatalf("잠금 파일 열기 실패 (%s): %v", lockPath, err)
	}
	t.Cleanup(func() { lockFile.Close() })

	if err := syscall.Flock(int(lockFile.Fd()), syscall.LOCK_EX|syscall.LOCK_NB); err != nil {
		t.Fatalf("세션 이름 잠금 실패 (%s): %v", lockPath, err)
	}
}

func TestSupervisorHoldsItsSessionNameLockForAsLongAsItLives(t *testing.T) {
	backend := newIsolatedSupervisorBackend(t)
	const sessionName = "kyu-test-sv-name-lock"

	if err := backend.Create(sessionName, t.TempDir(), []string{"sleep", "60"}); err != nil {
		t.Fatalf("Create() 실패: %v", err)
	}

	paths, err := newSessionRuntimePaths(sessionName)
	if err != nil {
		t.Fatalf("newSessionRuntimePaths() 실패: %v", err)
	}

	// 이름의 임자는 소켓 파일이 아니라 이 잠금이다. 감독이 사는 내내 쥐고 있어야 "소켓이 아직
	// 답하지 않는 순간" 에도 남이 그 이름을 가져가지 못한다.
	lockFile, err := os.OpenFile(paths.lock, os.O_CREATE|os.O_RDWR, sessionFilePermission)
	if err != nil {
		t.Fatalf("잠금 파일 열기 실패: %v", err)
	}
	defer lockFile.Close()

	if err := syscall.Flock(int(lockFile.Fd()), syscall.LOCK_EX|syscall.LOCK_NB); err == nil {
		syscall.Flock(int(lockFile.Fd()), syscall.LOCK_UN)
		t.Errorf("살아있는 감독의 세션 이름 잠금을 잡을 수 있었다 — 감독이 수명 내내 쥐고 있어야 한다")
	}
}

func TestCreateRefusesAHeldSessionNameEvenWhileItsSocketStillRefusesConnections(t *testing.T) {
	backend := newIsolatedSupervisorBackend(t)
	const sessionName = "kyu-test-sv-bind-listen-race"

	paths, err := newSessionRuntimePaths(sessionName)
	if err != nil {
		t.Fatalf("newSessionRuntimePaths() 실패: %v", err)
	}
	if err := ensureSessionsDir(paths.sessionsDir); err != nil {
		t.Fatalf("ensureSessionsDir() 실패: %v", err)
	}

	// 감독이 이름을 잡고 소켓을 바인딩했지만 아직 듣지는 않는 순간을 그대로 만든다.
	// 지연을 주입해 그 순간을 노리는 대신 그 순간의 상태를 만든다 — 겨루기가 아니라 판정이므로
	// 이 시험은 언제 돌려도 같은 답을 낸다.
	//
	// 이 상태에서 소켓에 붙으면 죽은 소켓과 똑같이 ECONNREFUSED 다. 붙어보는 것으로 임자를
	// 판정하면 남의 소켓을 지우고 같은 이름의 감독이 둘이 된다.
	holdSessionNameLockForTest(t, paths.lock)
	bindSocketWithoutListening(t, paths.socket)

	err = backend.Create(sessionName, t.TempDir(), []string{"sleep", "60"})
	if !errors.Is(err, ErrSessionExists) {
		t.Fatalf("이름이 잡혀 있을 때 Create() = %v, ErrSessionExists 여야 한다", err)
	}

	if _, statErr := os.Stat(paths.socket); statErr != nil {
		t.Errorf("남의 소켓 파일 stat = %v, 임자가 있는 소켓을 지우면 안 된다", statErr)
	}
}

func TestRefusedCreateDoesNotStartTheSessionCommand(t *testing.T) {
	backend := newIsolatedSupervisorBackend(t)
	const sessionName = "kyu-test-sv-refused-no-child"

	const markerCommand = `printf started > started.txt; sleep 60`
	if err := backend.Create(sessionName, t.TempDir(), []string{"sh", "-c", markerCommand}); err != nil {
		t.Fatalf("첫 Create() 실패: %v", err)
	}

	// 배타성 판정이 자식보다 먼저여야 한다. 순서가 뒤집히면 중복 생성이 세션 안 프로그램을
	// 하나 띄웠다 죽이는 일이 된다(설계 문서 5.3) — claude 라면 사용자가 그 깜빡임을 본다.
	//
	// 거절당한 Create 가 돌아온 시점에는 그쪽 감독이 이미 끝나 있다. 그래서 이 확인에는
	// 기다림이 필요 없다 — 흔적이 생길 것이었다면 이미 생겼다.
	refusedWorkingDirectory := t.TempDir()
	if err := backend.Create(sessionName, refusedWorkingDirectory, []string{"sh", "-c", markerCommand}); !errors.Is(err, ErrSessionExists) {
		t.Fatalf("두 번째 Create() = %v, ErrSessionExists 여야 한다", err)
	}

	if _, err := os.Stat(filepath.Join(refusedWorkingDirectory, "started.txt")); !errors.Is(err, os.ErrNotExist) {
		t.Errorf("거절당한 Create() 의 작업 디렉토리에 흔적 stat = %v, 명령이 실행되면 안 된다", err)
	}
}
