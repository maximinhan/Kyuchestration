package session

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"strings"
)

// runtimeDirEnvName 은 감독의 런타임 디렉토리를 통째로 덮어쓰는 환경변수다.
//
// 테스트가 사용자의 세션을 건드리지 않고 자기 세션을 띄우기 위해 있다(설계 문서 5.2).
// tmux 테스트의 TMUX_TMPDIR 격리와 정확히 같은 자리이고, 격리에 실패하면 사용자가 작업 중인
// 세션을 죽인다는 사정까지 같다(tmux_test.go:16).
const runtimeDirEnvName = "KYU_RUNTIME_DIR"

// xdgRuntimeDirEnvName 은 리눅스가 사용자별 런타임 파일에 정해둔 자리다.
//
// 이 디렉토리는 정확히 이 용도로 존재한다 — 사용자 소유 0700, tmpfs, 로그인 세션이 끝나면
// 정리된다. 재부팅 후 남은 소켓 파일을 우리가 치울 일이 없다(설계 문서 5.2).
const xdgRuntimeDirEnvName = "XDG_RUNTIME_DIR"

// runtimeDirName 은 런타임 디렉토리 아래에서 이 도구가 쓰는 디렉토리 이름이다.
const runtimeDirName = "kyu"

// sessionsDirName 은 세션 하나당 파일 셋이 모여 사는 디렉토리 이름이다.
//
// 세션 목록이라는 사실을 어느 프로세스도 소유하지 않고 이 디렉토리가 갖는다(설계 원칙 10).
const sessionsDirName = "sessions"

// 세션 하나가 쓰는 파일 셋. 이름이 곧 파일 이름이고 쓰임이 확장자로 갈린다(설계 문서 5.2).
const (
	sessionSocketExtension = ".sock"
	sessionLockExtension   = ".lock"
	sessionLogExtension    = ".log"
)

// ErrSocketPathTooLong 은 세션 소켓 경로가 sun_path 한도를 넘을 때 반환한다.
//
// 유닉스 도메인 소켓의 경로는 sockaddr_un.sun_path 에 통째로 들어가고 그 크기가
// macOS 104 · 리눅스 108 바이트다. 넘으면 커널은 "invalid argument" 로만 답하는데,
// 그 문장에서 경로 길이를 떠올리는 사람은 없다 — 원인을 찾는 데 오래 걸리는 종류의 문제라
// 이름 그대로 말하고 끊는다.
//
// 한도에 닿았을 때의 처방(소켓 디렉토리로 chdir 한 뒤 상대 경로로 바인딩)은 설계 문서 10절
// 6번의 열린 질문으로 남아 있다. 정해지기 전까지 경로를 조용히 자르지 않는다 — 자르면 서로
// 다른 두 세션이 같은 소켓을 가리키게 된다.
var ErrSocketPathTooLong = errors.New("세션 소켓 경로가 유닉스 도메인 소켓 한도를 넘습니다")

// sessionRuntimePaths 는 세션 하나가 런타임 디렉토리에서 차지하는 파일들이다.
//
// 네 경로가 한 이름에서 파생되므로 한 번에 만들어 함께 넘긴다. 따로 만들면 규칙이 여러 곳에
// 흩어지고, 그중 하나만 바뀌었을 때 감독과 클라이언트가 다른 파일을 보게 된다.
type sessionRuntimePaths struct {
	sessionsDir string
	socket      string
	lock        string
	log         string
}

// newSessionRuntimePaths 는 세션 이름 하나로 그 세션의 파일 경로들을 만든다.
//
// 파일을 만들지는 않는다 — 경로를 정하고 그 경로가 쓸 수 있는 것인지만 판정한다.
func newSessionRuntimePaths(name string) (sessionRuntimePaths, error) {
	if err := validateSessionNameIsSafeAsFileName(name); err != nil {
		return sessionRuntimePaths{}, err
	}

	sessionsDir, err := resolveSessionsDir()
	if err != nil {
		return sessionRuntimePaths{}, err
	}

	socketPath := filepath.Join(sessionsDir, name+sessionSocketExtension)
	if limit := maxSessionSocketPathLength(); len(socketPath) > limit {
		return sessionRuntimePaths{}, fmt.Errorf(
			"%w: %s (%d 바이트, 한도 %d)\n%s 로 더 짧은 경로를 지정하세요",
			ErrSocketPathTooLong, socketPath, len(socketPath), limit, runtimeDirEnvName)
	}

	return sessionRuntimePaths{
		sessionsDir: sessionsDir,
		socket:      socketPath,
		lock:        filepath.Join(sessionsDir, name+sessionLockExtension),
		log:         filepath.Join(sessionsDir, name+sessionLogExtension),
	}, nil
}

// resolveSessionsDir 은 세션 파일들이 사는 디렉토리를 정한다.
//
// $XDG_RUNTIME_DIR 이 있으면 그 아래, 없으면 $HOME/.local/state 아래다. 폴백은 예외가 아니라
// macOS 의 정상 경로다 — macOS 에는 XDG_RUNTIME_DIR 이 없다. 그래서 폴백 경로에서 벌어지는 일
// (재부팅 후에도 소켓 파일이 남는다)이 오히려 흔한 쪽이고, 죽은 소켓 처리를 예외 취급하면
// 안 되는 이유가 그것이다(설계 문서 5.2).
//
// $TMPDIR 은 쓰지 않는다. macOS 의 $TMPDIR 은 사용자별로 갈려 있어 그 점은 맞지만, 시스템 정리
// 작업이 오래된 파일을 지운다. 잠금 파일과 기록이 함께 사는 자리로는 맞지 않는다.
func resolveSessionsDir() (string, error) {
	runtimeDir, err := resolveRuntimeDir()
	if err != nil {
		return "", err
	}

	// 상대 경로로 들어와도 절대 경로로 고정한다. 감독은 부모의 cwd 를 물려받는 별개 프로세스라,
	// 상대 경로를 그대로 두면 부모와 감독이 같은 이름의 서로 다른 파일을 볼 여지가 생긴다.
	absoluteRuntimeDir, err := filepath.Abs(runtimeDir)
	if err != nil {
		return "", fmt.Errorf("세션 런타임 디렉토리 경로 확정 실패 (%s): %w", runtimeDir, err)
	}
	return filepath.Join(absoluteRuntimeDir, sessionsDirName), nil
}

func resolveRuntimeDir() (string, error) {
	if override, isSet := os.LookupEnv(runtimeDirEnvName); isSet && override != "" {
		return override, nil
	}

	if xdgRuntimeDir, isSet := os.LookupEnv(xdgRuntimeDirEnvName); isSet && xdgRuntimeDir != "" {
		return filepath.Join(xdgRuntimeDir, runtimeDirName), nil
	}

	home, err := os.UserHomeDir()
	if err != nil {
		return "", fmt.Errorf("홈 디렉토리를 찾을 수 없어 세션 런타임 경로를 정하지 못했습니다: %w", err)
	}
	return filepath.Join(home, ".local", "state", runtimeDirName), nil
}

// validateSessionNameIsSafeAsFileName 은 세션 이름이 파일 이름 하나로 쓰이기에 안전한지 본다.
//
// 이름은 워크디렉토리 이름과 레포 이름에서 오고 둘 다 디렉토리 이름이라 `/` 가 들어갈 수 없다.
// 그런데도 확인하는 이유는 이름을 만드는 곳(name.go)과 그것을 파일로 쓰는 이 자리가 멀기
// 때문이다. 그 사이에서 규칙이 바뀌면 세션 이름 하나가 런타임 디렉토리 밖의 파일을 가리키게
// 되고, 그때 우리는 남의 파일을 지우거나 그 위에 바인딩한다. 확인 한 줄이 그 어긋남을
// 소켓을 만들기 전에 끊는다(설계 문서 5.2).
func validateSessionNameIsSafeAsFileName(name string) error {
	if name == "" {
		return errors.New("세션 이름이 비어 있습니다")
	}
	if name == "." || name == ".." || strings.Contains(name, "..") {
		return fmt.Errorf("세션 이름에 상위 디렉토리 표기를 쓸 수 없습니다: %q", name)
	}
	if strings.ContainsRune(name, '/') || strings.ContainsRune(name, filepath.Separator) {
		return fmt.Errorf("세션 이름에 경로 구분자를 쓸 수 없습니다: %q", name)
	}
	if strings.ContainsRune(name, 0) {
		return fmt.Errorf("세션 이름에 NUL 을 쓸 수 없습니다: %q", name)
	}
	return nil
}

// maxSessionSocketPathLength 는 이 플랫폼에서 소켓 경로가 가질 수 있는 최대 길이다.
//
// sun_path 는 NUL 로 끝나는 문자열이라 쓸 수 있는 경로는 배열 크기보다 한 바이트 짧다.
func maxSessionSocketPathLength() int {
	if runtime.GOOS == "linux" {
		return 107
	}
	// macOS 는 104 바이트다. 대상 플랫폼 밖의 OS 도 더 좁은 이쪽으로 재 둔다 —
	// 넉넉히 잡아 커널에게 거절당하는 것보다 좁게 잡아 우리가 설명하는 편이 낫다.
	return 103
}
