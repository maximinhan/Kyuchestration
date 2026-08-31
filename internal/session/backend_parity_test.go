package session

import (
	"errors"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"testing"
	"time"
)

// 이 파일은 두 백엔드가 SessionBackend 계약을 같은 뜻으로 이행하는지를 한 벌의 시험으로 묻는다
// (설계 문서 9절 3 단계 · 5.9 대응표).
//
// 시험 본문이 하나이므로 "tmux 에는 있고 감독에는 없는 검증" 이 생길 자리가 없다. 백엔드마다
// 파일을 따로 두면 계약 시험도 함께 갈라지고, 갈라진 뒤에는 한쪽에만 붙은 검증이 다른 쪽에서
// 무엇을 뜻하는지 아무도 다시 묻지 않는다 — 그 상태로는 "같다" 를 증명할 수 없고, 증명되지
// 않은 백엔드는 기본값이 될 수 없다(설계 원칙 8).
//
// 여기 있는 것은 계약이 관찰 가능하게 말하는 것뿐이다. 그것을 무엇으로 떠받치는지(tmux 서버냐
// 감독 프로세스냐, 타깃 문법이냐 파일 이름이냐)는 백엔드 자기 파일에 남는다.

// backendUnderTest 는 매트릭스가 백엔드 하나를 돌리는 데 필요한 것을 모은다.
//
// 필드로 갈라 둔 것은 5.9 대응표가 "다르다" 로 적어둔 줄들이다. 같아야 하는 것을 필드로 빼면
// 그 순간 매트릭스가 무엇도 증명하지 못하므로, 여기 늘어나는 필드 하나하나가 곧 동등성의
// 예외 목록이다.
type backendUnderTest struct {
	// name 은 하위 시험의 이름이자 건너뛴 이유를 말할 때 쓰는 이름이다.
	// 백엔드를 고르는 환경변수의 값과 같은 문자열을 쓴다 — 시험 출력에서 본 이름을 그대로
	// KYU_SESSION_BACKEND 에 넣어 손으로 재현할 수 있다.
	name string

	// newBackend 는 사용자의 세션과 격리된 백엔드를 만든다.
	// 필요한 것이 없는 환경에서는 t.Skip 으로 그 줄만 넘어간다 — tmux 가 없는 머신이 그렇다.
	newBackend func(t *testing.T) SessionBackend

	// detachKey 는 이 백엔드가 답해야 하는 빠져나오기 키다.
	// 5.9 가 "다르다 — 5.8 이 인터페이스로 흡수" 로 적은 줄이다. 값은 백엔드마다 다르고
	// 계약은 하나다: 표시 계층이 그대로 옮길 키를 답한다.
	detachKey string

	// insideSessionEnvName 은 세션 안에서 도는 프로세스가 물려받는 표식의 이름이다.
	// 5.9 가 "같은 기전" 으로 적은 줄이다 — 이름과 값의 모양만 다르고, 판정의 근거는 하나다.
	insideSessionEnvName string

	// reportsMissingWorkingDirectory 는 없는 작업 디렉토리를 Create 가 실패로 말하는지다.
	//
	// 5.9 대응표에 없던 줄이고 이 매트릭스가 처음 드러낸 차이다. 어느 쪽도 고치지 않고 여기에
	// 적어 고정한다 — 까닭은 이 필드를 읽는 시험 본문에 있다.
	reportsMissingWorkingDirectory bool
}

// backendsUnderTest 는 이 매트릭스가 돌리는 백엔드 전부다.
//
// 새 백엔드(윈도우 — 설계 문서 8절)가 생기면 여기에 한 줄을 더하는 것으로 계약 시험 전부가
// 그 백엔드에 적용된다.
func backendsUnderTest() []backendUnderTest {
	return []backendUnderTest{
		{
			name:                           TmuxBackendName,
			newBackend:                     func(t *testing.T) SessionBackend { return newIsolatedTmuxBackend(t) },
			detachKey:                      "Ctrl-b d",
			insideSessionEnvName:           insideTmuxEnvName,
			reportsMissingWorkingDirectory: false,
		},
		{
			name:                           SupervisorBackendName,
			newBackend:                     func(t *testing.T) SessionBackend { return newIsolatedSupervisorBackend(t) },
			detachKey:                      `Ctrl-\`,
			insideSessionEnvName:           insideSupervisorSessionEnvName,
			reportsMissingWorkingDirectory: true,
		},
	}
}

// forEachSessionBackend 는 시험 본문 하나를 모든 백엔드에서 돌린다.
//
// 백엔드는 본문에 들어가기 직전에 만든다. 하위 시험마다 자기 격리 자리(tmux 서버 하나 ·
// 런타임 디렉토리 하나)를 갖게 되므로, 두 백엔드가 같은 세션 이름을 써도 서로를 보지 못한다.
func forEachSessionBackend(t *testing.T, body func(t *testing.T, backendCase backendUnderTest, backend SessionBackend)) {
	t.Helper()

	for _, backendCase := range backendsUnderTest() {
		t.Run(backendCase.name, func(t *testing.T) {
			body(t, backendCase, backendCase.newBackend(t))
		})
	}
}

// assertSessionIsAlive 는 IsAlive 의 답을 확인한다.
//
// IsAlive 는 "묻지 못했다" 와 "없다" 를 갈라야 하는 자리라(설계 문서 5.4) 에러와 값을 늘
// 함께 본다. 그 두 줄을 시험마다 되풀이하지 않는다.
func assertSessionIsAlive(t *testing.T, backend SessionBackend, sessionName string, want bool) {
	t.Helper()

	got, err := backend.IsAlive(sessionName)
	if err != nil {
		t.Fatalf("IsAlive(%q) 실패: %v", sessionName, err)
	}
	if got != want {
		t.Errorf("IsAlive(%q) = %v, want %v", sessionName, got, want)
	}
}

// assertSessionIsListed 는 List 결과에 그 이름이 있는지를 확인한다.
func assertSessionIsListed(t *testing.T, backend SessionBackend, sessionName string, want bool) {
	t.Helper()

	names, err := backend.List()
	if err != nil {
		t.Fatalf("List() 실패: %v", err)
	}
	if got := slices.Contains(names, sessionName); got != want {
		t.Errorf("List() = %v, %q 가 %v 이기를 기대", names, sessionName, want)
	}
}

// waitForFileContent 는 세션 안에서 실행된 명령이 파일을 남길 때까지 기다린다.
//
// Create 는 명령의 완료를 기다리지 않고 곧바로 반환하므로(두 백엔드 모두) 세션 안에서 벌어진
// 일을 밖에서 보려면 폴링이 필요하다.
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

func TestBackendParityCreateMakesTheSessionAliveAndKillEndsIt(t *testing.T) {
	forEachSessionBackend(t, func(t *testing.T, backendCase backendUnderTest, backend SessionBackend) {
		const sessionName = "kyu-parity-lifecycle"

		if err := backend.Create(sessionName, t.TempDir(), []string{"sleep", "60"}); err != nil {
			t.Fatalf("Create() 실패: %v", err)
		}

		// 생존은 IsAlive 와 List 두 곳에 같은 사실로 드러나야 한다. 한쪽만 보면 목록에는 있는데
		// 붙을 수 없는 세션, 또는 그 반대를 잡지 못한다.
		assertSessionIsAlive(t, backend, sessionName, true)
		assertSessionIsListed(t, backend, sessionName, true)

		if err := backend.Kill(sessionName); err != nil {
			t.Fatalf("Kill() 실패: %v", err)
		}

		assertSessionIsAlive(t, backend, sessionName, false)
		assertSessionIsListed(t, backend, sessionName, false)
	})
}

func TestBackendParityCreateRejectsADuplicateNameWithErrSessionExists(t *testing.T) {
	forEachSessionBackend(t, func(t *testing.T, backendCase backendUnderTest, backend SessionBackend) {
		const sessionName = "kyu-parity-duplicate"

		if err := backend.Create(sessionName, t.TempDir(), []string{"sleep", "60"}); err != nil {
			t.Fatalf("첫 Create() 실패: %v", err)
		}

		// 호출부는 이 센티널로 "이미 떠 있으니 새로 만들지 말고 붙어라" 를 판단한다
		// (backend.go:12). 문자열이 아니라 errors.Is 로 갈리므로 두 백엔드가 같은 값을 써야 한다.
		err := backend.Create(sessionName, t.TempDir(), []string{"sleep", "60"})
		if !errors.Is(err, ErrSessionExists) {
			t.Errorf("같은 이름으로 Create() = %v, ErrSessionExists 로 풀리기를 기대", err)
		}
	})
}

func TestBackendParityConcurrentCreateOfTheSameNameLeavesOneSession(t *testing.T) {
	forEachSessionBackend(t, func(t *testing.T, backendCase backendUnderTest, backend SessionBackend) {
		const sessionName = "kyu-parity-concurrent"
		const attempts = 4

		// 터미널 둘에서 같은 워크디렉토리를 동시에 시작하면 벌어지는 일이다. 두 백엔드가
		// 배타성을 얻는 방법은 다르지만(tmux 는 has-session 선조회 후 생성, 감독은 이름 잠금
		// 획득이 곧 판정 — 5.9) 사용자가 보는 결과는 같아야 한다: 세션은 하나다.
		//
		// 진 쪽이 받는 에러의 종류는 여기서 묻지 않는다. 감독은 늘 ErrSessionExists 지만
		// tmux 는 선조회를 통과한 뒤 tmux 자신에게 거절당하면 그 실패를 그대로 감싸 올린다
		// (tmux.go:57 · 실측 5/5). 그 차이는 감독 쪽 시험이 더 센 주장으로 따로 고정한다
		// (supervisor_test.go 의 TestConcurrentCreateOfTheSameNameLeavesExactlyOneSession).
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
			if err := <-results; err == nil {
				created++
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
	})
}

func TestBackendParityCreateRunsTheCommandInTheGivenDirectory(t *testing.T) {
	forEachSessionBackend(t, func(t *testing.T, backendCase backendUnderTest, backend SessionBackend) {
		const sessionName = "kyu-parity-cwd"

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
	})
}

func TestBackendParityCreatePreservesCommandArgumentBoundaries(t *testing.T) {
	forEachSessionBackend(t, func(t *testing.T, backendCase backendUnderTest, backend SessionBackend) {
		const sessionName = "kyu-parity-argv"

		workingDirectory := t.TempDir()

		// cmd 를 공백으로 이어 붙여 넘기면 공백 든 경로가 두 인자로 쪼개지고 `;` 는 셸
		// 구분자가 된다. kyu start 가 `claude --add-dir <공백 든 경로>` 를 넘기므로
		// 실제로 걸리는 문제이고, 백엔드가 인자를 어떻게 나르든 경계는 그대로여야 한다.
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
	})
}

func TestBackendParityCreateGivesTheSessionATerminal(t *testing.T) {
	forEachSessionBackend(t, func(t *testing.T, backendCase backendUnderTest, backend SessionBackend) {
		const sessionName = "kyu-parity-tty"

		workingDirectory := t.TempDir()

		// 세션 안 프로그램이 자기 표준 입출력을 터미널로 봐야 화면을 그린다. isatty 가 거짓이
		// 되는 순간 claude 는 대화형 화면을 포기한다(설계 문서 3.6). 이것을 누가 떠받치는지는
		// 백엔드마다 다르지만(tmux 의 pane · 감독이 쥔 PTY) 세션 안에서 본 사실은 같아야 한다.
		if err := backend.Create(sessionName, workingDirectory, []string{
			"sh", "-c", `if [ -t 0 ] && [ -t 1 ]; then printf tty > tty.txt; else printf notty > tty.txt; fi; sleep 60`,
		}); err != nil {
			t.Fatalf("Create() 실패: %v", err)
		}

		if got := waitForFileContent(t, filepath.Join(workingDirectory, "tty.txt")); got != "tty" {
			t.Errorf("세션 안에서 본 표준 입출력 = %q, want %q", got, "tty")
		}
	})
}

func TestBackendParityCreateMarksTheSessionSoWhatRunsInsideCanTell(t *testing.T) {
	forEachSessionBackend(t, func(t *testing.T, backendCase backendUnderTest, backend SessionBackend) {
		const sessionName = "kyu-parity-marker"

		workingDirectory := t.TempDir()

		// 세션 안에서 도는 모든 프로세스가 표식을 물려받아야 그 안에서 부른 kyu 가 자기가 세션
		// 안임을 안다(5.9 "중첩 표식 — 같은 기전"). 값의 모양은 백엔드마다 다르므로
		// (tmux 는 소켓 경로·pid, 감독은 세션 이름) 있다는 것만 확인한다.
		//
		// 대괄호로 감싸는 것은 표식이 비었을 때 빈 파일이 되지 않게 하기 위해서다 —
		// 빈 파일이면 waitForFileContent 가 "파일이 없다" 로 끝나 원인이 가려진다.
		markerScript := `printf "[%s]" "$` + backendCase.insideSessionEnvName + `" > marker.txt; sleep 60`
		if err := backend.Create(sessionName, workingDirectory, []string{"sh", "-c", markerScript}); err != nil {
			t.Fatalf("Create() 실패: %v", err)
		}

		if got := waitForFileContent(t, filepath.Join(workingDirectory, "marker.txt")); got == "[]" {
			t.Errorf("세션 안의 %s = 빈 값, 세션 표식이 심어져 있어야 한다", backendCase.insideSessionEnvName)
		}
	})
}

func TestBackendParityAttachRefusesToNestInsideAnotherSession(t *testing.T) {
	forEachSessionBackend(t, func(t *testing.T, backendCase backendUnderTest, backend SessionBackend) {
		// 세션 안에서 도는 프로세스가 물려받는 표식을 그대로 흉내 낸다. 앞의 시험이 실제 세션에
		// 그 표식이 심긴다는 것을 확인하므로, 여기서는 그것을 본 Attach 가 무엇을 하는지만 묻는다.
		//
		// Attach 의 성공 경로는 이 매트릭스에 없다. TTY 를 요구하고 사용자가 뗄 때까지 블로킹되기
		// 때문인데(tmux_test.go 의 같은 자리), 중첩 판정은 터미널을 잡기 전에 끝나므로 이 분기만은
		// 두 백엔드에서 같은 본문으로 물을 수 있다. 사용자가 attach 에서 가장 자주 부딪히는
		// 실패이기도 하다.
		t.Setenv(backendCase.insideSessionEnvName, "kyu-parity-outer")

		err := backend.Attach("kyu-parity-inner")
		if !errors.Is(err, ErrNestedSession) {
			t.Errorf("세션 안에서 Attach() = %v, ErrNestedSession 으로 풀리기를 기대", err)
		}
	})
}

func TestBackendParitySessionEndsWhenItsCommandEnds(t *testing.T) {
	forEachSessionBackend(t, func(t *testing.T, backendCase backendUnderTest, backend SessionBackend) {
		const sessionName = "kyu-parity-natural-exit"

		// 세션의 수명은 그 안의 명령이 정한다. 도구가 세션을 붙잡아두면 사용자가 exit 로 끝낸
		// 세션이 목록에 남고, 그 이름으로 다시 시작할 수도 없다.
		if err := backend.Create(sessionName, t.TempDir(), []string{"sh", "-c", "exit 0"}); err != nil {
			t.Fatalf("Create() 실패: %v", err)
		}

		waitUntil(t, "명령이 끝난 세션이 죽은 것으로 보이기", func() bool {
			alive, err := backend.IsAlive(sessionName)
			return err == nil && !alive
		})
		assertSessionIsListed(t, backend, sessionName, false)
	})
}

func TestBackendParityCreateInAWorkingDirectoryThatDoesNotExist(t *testing.T) {
	forEachSessionBackend(t, func(t *testing.T, backendCase backendUnderTest, backend SessionBackend) {
		const sessionName = "kyu-parity-missing-cwd"

		missingDirectory := filepath.Join(t.TempDir(), "없는-디렉토리")
		err := backend.Create(sessionName, missingDirectory, []string{"sleep", "60"})

		// 두 백엔드가 갈리는 자리다. 5.9 대응표에 없던 줄이고 이 매트릭스가 처음 드러냈다.
		//
		// 어느 쪽도 고치지 않고 여기에 적어 고정한다. tmux 를 감독에 맞추려면 tmux 백엔드를
		// 손대야 하는데 그쪽은 검증된 폴백으로 남겨둔 코드이고(설계 원칙 7), 감독을 tmux 에
		// 맞추는 것은 "cwd 에서 cmd 를 실행한다" 는 Create 의 계약문(backend.go:44)을 스스로
		// 어기게 만드는 일이다 — 사용자가 보게 되는 것은 엉뚱한 디렉토리에서 뜬 세션이다.
		// 고정해두면 어느 한쪽이 바뀌는 날 이 시험이 먼저 말한다.
		if !backendCase.reportsMissingWorkingDirectory {
			// 실측(tmux 3.4): `new-session -c <없는 경로>` 는 종료 코드 0 으로 끝나고 세션은
			// $HOME 에서 뜬다. 요청한 곳이 아닌 곳에서 도는 세션을 성공으로 돌려준다.
			if err != nil {
				t.Fatalf("Create() = %v, 이 백엔드는 없는 작업 디렉토리를 실패로 말하지 않는다", err)
			}
			assertSessionIsAlive(t, backend, sessionName, true)
			return
		}

		// 감독은 자식을 띄우지 못한 까닭을 준비 파이프로 부모에게 되돌린다(설계 문서 5.3).
		if err == nil {
			t.Fatalf("없는 디렉토리로 Create() 가 성공했다")
		}
		if errors.Is(err, ErrSessionExists) {
			t.Errorf("err = %v, 중복이 아니라 작업 디렉토리 문제로 끝나야 한다", err)
		}
		if !strings.Contains(err.Error(), missingDirectory) {
			t.Errorf("err = %q, 없는 디렉토리 경로를 포함하기를 기대", err)
		}
		assertSessionIsAlive(t, backend, sessionName, false)
	})
}

func TestBackendParityIsAliveTakesTheExactNameNotAPrefix(t *testing.T) {
	forEachSessionBackend(t, func(t *testing.T, backendCase backendUnderTest, backend SessionBackend) {
		// 세션 이름이 kyu-<workdir>-<repo> 라 서로 접두사 관계가 되기 쉽다. 이름을 접두사로
		// 맞히는 백엔드가 있으면 IsAlive 가 남의 세션을 자기 것으로 답한다.
		if err := backend.Create("kyu-parity-exact-suffix", t.TempDir(), []string{"sleep", "60"}); err != nil {
			t.Fatalf("Create() 실패: %v", err)
		}

		assertSessionIsAlive(t, backend, "kyu-parity-exact", false)
	})
}

func TestBackendParityKillTakesTheExactNameNotAPrefix(t *testing.T) {
	forEachSessionBackend(t, func(t *testing.T, backendCase backendUnderTest, backend SessionBackend) {
		const (
			targetSession    = "kyu-parity-kill-exact"
			bystanderSession = "kyu-parity-kill-exact-suffix"
		)

		// IsAlive 가 접두사에 걸리는 것은 오답이지만, Kill 이 걸리는 것은 남의 작업을 죽이는
		// 일이다. 같은 성질을 파괴적인 연산에서 한 번 더 묻는다.
		for _, name := range []string{targetSession, bystanderSession} {
			if err := backend.Create(name, t.TempDir(), []string{"sleep", "60"}); err != nil {
				t.Fatalf("Create(%q) 실패: %v", name, err)
			}
		}

		if err := backend.Kill(targetSession); err != nil {
			t.Fatalf("Kill(%q) 실패: %v", targetSession, err)
		}

		assertSessionIsAlive(t, backend, targetSession, false)
		assertSessionIsAlive(t, backend, bystanderSession, true)
	})
}

func TestBackendParityIsAliveIsFalseWhenNoSessionWasEverCreated(t *testing.T) {
	forEachSessionBackend(t, func(t *testing.T, backendCase backendUnderTest, backend SessionBackend) {
		// 세션을 한 번도 만들지 않은 상태다. 백엔드가 기대는 것이 아직 아무것도 없는
		// (tmux 서버도 소켓 디렉토리도 없는) 이 상태는 오류가 아니라 세션 0 개의 표현이다.
		assertSessionIsAlive(t, backend, "kyu-parity-absent", false)
	})
}

func TestBackendParityKillIsIdempotent(t *testing.T) {
	forEachSessionBackend(t, func(t *testing.T, backendCase backendUnderTest, backend SessionBackend) {
		const sessionName = "kyu-parity-idempotent-kill"

		// kill --all 은 목록을 얻은 뒤 하나씩 죽인다(internal/cli/kill.go:83). 그 사이에 세션이
		// 자연사할 수 있으므로, 없는 세션을 죽이라는 요청은 오류가 아니라 이미 이뤄진 결과다.
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
	})
}

func TestBackendParityListIsEmptyWhenNoSessionWasEverCreated(t *testing.T) {
	forEachSessionBackend(t, func(t *testing.T, backendCase backendUnderTest, backend SessionBackend) {
		names, err := backend.List()
		if err != nil {
			t.Fatalf("세션이 하나도 없을 때 List() 가 에러를 반환했다: %v", err)
		}
		if len(names) != 0 {
			t.Errorf("List() = %v, 빈 목록이어야 한다", names)
		}
	})
}

func TestBackendParityListReturnsTheNamesItCreated(t *testing.T) {
	forEachSessionBackend(t, func(t *testing.T, backendCase backendUnderTest, backend SessionBackend) {
		want := []string{"kyu-parity-list-a", "kyu-parity-list-b"}

		for _, name := range want {
			if err := backend.Create(name, t.TempDir(), []string{"sleep", "60"}); err != nil {
				t.Fatalf("Create(%q) 실패: %v", name, err)
			}
		}

		got, err := backend.List()
		if err != nil {
			t.Fatalf("List() 실패: %v", err)
		}

		// 출력 순서에 기대지 않는다. 이름 집합이 맞는지만 본다.
		slices.Sort(got)
		if !slices.Equal(got, want) {
			t.Errorf("List() = %v, want %v", got, want)
		}
	})
}

func TestBackendParityListNamesAnswerToTheWorkDirPrefixFilter(t *testing.T) {
	forEachSessionBackend(t, func(t *testing.T, backendCase backendUnderTest, backend SessionBackend) {
		// 조율 계층은 List() 결과를 WorkDirSessionPrefix 로 걸러 이 워크디렉토리의 세션만
		// 고른다 — kill --all 이 그렇게 돈다(internal/cli/kill.go:69). 이름을 만드는 규칙은
		// name.go 하나뿐이므로, 그 규칙이 만든 이름이 두 백엔드에서 똑같이 오가야 그 코드가
		// 백엔드를 모른 채로 남는다(5.9 "세션 명명" · "접두사 필터").
		//
		// 워크디렉토리 이름에 `.` 을, 레포 이름에 `:` 을 넣는다. 치환은 tmux 의 타깃 문법 때문에
		// 생겼지만 감독에서는 파일 이름이 되므로, 규칙 하나가 양쪽에서 같은 이름을 내는지가
		// 백엔드를 되돌렸을 때 사용자가 자기 세션을 다시 찾을 수 있는지를 정한다(5.9 아래 문단).
		const workDirName = "feature.X"
		inThisWorkDir := []string{
			MainSessionName(workDirName),
			RepoSessionName(workDirName, "proj:a"),
		}
		for _, name := range append(slices.Clone(inThisWorkDir), RepoSessionName("otherWorkDir", "proj-a")) {
			if err := backend.Create(name, t.TempDir(), []string{"sleep", "60"}); err != nil {
				t.Fatalf("Create(%q) 실패: %v", name, err)
			}
		}

		names, err := backend.List()
		if err != nil {
			t.Fatalf("List() 실패: %v", err)
		}

		var got []string
		for _, name := range names {
			if strings.HasPrefix(name, WorkDirSessionPrefix(workDirName)) {
				got = append(got, name)
			}
		}

		slices.Sort(got)
		slices.Sort(inThisWorkDir)
		if !slices.Equal(got, inThisWorkDir) {
			t.Errorf("접두사로 거른 세션 = %v, want %v", got, inThisWorkDir)
		}
	})
}

func TestBackendParityDetachKeyIsTheKeyTheDisplayLayerWillShow(t *testing.T) {
	forEachSessionBackend(t, func(t *testing.T, backendCase backendUnderTest, backend SessionBackend) {
		// 표시 계층은 이 답을 그대로 옮긴다(internal/cli/attach.go:26). 값이 틀리면 사용자는
		// 세션에 갇힌 채 틀린 키를 누른다 — 백엔드마다 다른 유일한 계약이라 답이 백엔드에 있다.
		if got := backend.DetachKey(); got != backendCase.detachKey {
			t.Errorf("DetachKey() = %q, want %q", got, backendCase.detachKey)
		}
	})
}
