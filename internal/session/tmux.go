package session

import (
	"bytes"
	"errors"
	"fmt"
	"os/exec"
	"strings"
)

// ErrTmuxNotInstalled 는 PATH 에서 tmux 를 찾지 못했을 때 반환한다.
//
// 자동 설치는 하지 않는다(설계 문서 8.2). 사용자의 패키지 관리자를 도구가 건드리는 것은
// 실행 권한 범위를 넘고, 실패했을 때 되돌리기 어렵다. 안내만 하고 종료한다.
var ErrTmuxNotInstalled = errors.New("tmux 를 찾을 수 없습니다. 설치 후 다시 실행하세요 — macOS: brew install tmux, Debian/Ubuntu: sudo apt install tmux")

// TmuxBackend 는 tmux 에 세션 관리를 위임하는 SessionBackend 구현이다.
//
// PTY 관리·리사이즈·스크롤백·detach 재접속·도구와 독립적인 세션 생존을 전부 tmux 가 맡는다.
// 우리가 쓰는 것은 명령 다섯 줄뿐이다(설계 문서 5.2).
type TmuxBackend struct {
	// tmuxPath 는 생성 시점에 확정한 tmux 실행 파일의 절대 경로다.
	// 매 호출마다 PATH 를 다시 뒤지지 않고, 실행 도중 PATH 가 바뀌어도 같은 바이너리를 쓴다.
	tmuxPath string
}

// NewTmuxBackend 는 tmux 백엔드를 만든다.
// tmux 가 설치되어 있지 않으면 ErrTmuxNotInstalled 를 반환한다.
func NewTmuxBackend() (*TmuxBackend, error) {
	tmuxPath, err := exec.LookPath("tmux")
	if err != nil {
		return nil, fmt.Errorf("%w: %w", ErrTmuxNotInstalled, err)
	}
	return &TmuxBackend{tmuxPath: tmuxPath}, nil
}

// Create 는 cwd 에서 cmd 를 실행하는 백그라운드 세션을 만든다.
// 이미 같은 이름의 세션이 있으면 ErrSessionExists 를 반환한다.
func (b *TmuxBackend) Create(name, cwd string, cmd []string) error {
	// tmux 도 중복 이름을 거부하지만, 그 판정은 stderr 의 "duplicate session" 문자열로만 드러난다.
	// 문자열 매칭은 tmux 버전이 바뀌면 조용히 깨지므로 has-session 으로 미리 판정한다.
	alive, err := b.IsAlive(name)
	if err != nil {
		return err
	}
	if alive {
		return fmt.Errorf("%w: %s", ErrSessionExists, name)
	}

	args := append([]string{"new-session", "-d", "-s", name, "-c", cwd}, cmd...)
	if _, err := b.runTmuxCommand(args...); err != nil {
		return err
	}
	return nil
}

// Kill 은 세션을 종료한다. 없으면 nil 을 반환한다(멱등).
func (b *TmuxBackend) Kill(name string) error {
	alive, err := b.IsAlive(name)
	if err != nil {
		return err
	}
	if !alive {
		return nil
	}

	if _, err := b.runTmuxCommand("kill-session", "-t", exactTarget(name)); err != nil {
		return err
	}
	return nil
}

// IsAlive 는 세션의 생존 여부를 반환한다.
//
// tmux 서버 자체가 떠 있지 않은 경우도 "살아있지 않음"이다 — 에러가 아니다.
func (b *TmuxBackend) IsAlive(name string) (bool, error) {
	_, err := b.runTmuxCommand("has-session", "-t", exactTarget(name))
	if err == nil {
		return true, nil
	}

	// tmux 는 세션이 없을 때도, 서버가 아예 없을 때도 종료 코드 1 로 끝난다.
	// 두 경우 모두 "살아있지 않음"이므로 종료 코드로 끝난 실패는 판정 결과로 흡수한다.
	// 반대로 tmux 를 실행조차 못 한 경우(권한·바이너리 손상)는 판정이 불가능하므로 전파한다.
	var exitErr *exec.ExitError
	if errors.As(err, &exitErr) {
		return false, nil
	}
	return false, err
}

// exactTarget 은 tmux 타깃을 정확 일치로 못박는다.
//
// tmux 의 `-t <name>` 은 기본이 접두사 매칭이라 `-t wd-x-proj` 가 `wd-x-proj-a` 에 걸린다.
// 세션 이름이 `wd-<workdir>-<repo>` 로 서로 접두사 관계가 되기 쉬운 구조라,
// 이것을 막지 않으면 Kill 이 의도하지 않은 레포의 세션을 죽인다.
func exactTarget(name string) string {
	return "=" + name
}

// runTmuxCommand 는 tmux 를 실행하고 표준 출력을 돌려준다.
// 실패 시 tmux 가 남긴 stderr 를 함께 감싸 원인이 유실되지 않게 한다.
func (b *TmuxBackend) runTmuxCommand(args ...string) (string, error) {
	command := exec.Command(b.tmuxPath, args...)

	var stdout, stderr bytes.Buffer
	command.Stdout = &stdout
	command.Stderr = &stderr

	if err := command.Run(); err != nil {
		return "", fmt.Errorf("tmux %s 실패: %w (%s)",
			strings.Join(args, " "), err, strings.TrimSpace(stderr.String()))
	}
	return stdout.String(), nil
}
