package session

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"syscall"
	"time"
)

// readySignalDeadline 은 감독이 준비 신호를 보낼 때까지 기다리는 최대 시간이다.
//
// 최후의 안전장치이지 정상 경로가 아니다 — 감독이 걸려서 죽지도 답하지도 않는 경우를 위한
// 것이고, 정상 경로에서는 답이 오는 그 자리에서 끝난다(설계 문서 5.3).
const readySignalDeadline = 10 * time.Second

// aliveRequestDeadline 은 alive 조회 하나에 허용하는 최대 시간이다.
//
// GUI 대시보드가 3 초마다 목록을 부르므로 조회는 짧게 끝나야 한다. 걸린 감독 하나가 목록
// 전체를 붙잡으면 그 대시보드가 멈춘다.
const aliveRequestDeadline = 3 * time.Second

// killRequestDeadline 은 kill 요청 하나에 허용하는 최대 시간이다.
//
// 감독은 자식에게 SIGHUP 을 보내고 유예를 기다린 뒤에 답한다. 그 유예보다 넉넉해야 정상 종료를
// 시간 초과로 오해하지 않는다.
const killRequestDeadline = sessionTerminationGracePeriod + 7*time.Second

// errSessionSocketNotAnswering 은 소켓이 없거나 죽은 소켓일 때다.
//
// 연결이 ENOENT·ECONNREFUSED 로 끝나는 것이 "그 세션은 없다" 의 표현이다(설계 문서 5.4).
// 그것을 오류로 볼지 판정 결과로 흡수할지는 부르는 쪽이 정한다 — IsAlive 는 false 로,
// Kill 은 멱등의 nil 로 흡수한다.
var errSessionSocketNotAnswering = errors.New("세션 소켓이 없거나 응답하지 않습니다")

// errControlConnectionEndedBeforeResponse 는 감독이 답하기 전에 연결이 끝났을 때다.
var errControlConnectionEndedBeforeResponse = errors.New("감독이 답하기 전에 연결이 끝났습니다")

// SupervisorBackend 는 세션마다 감독 프로세스 하나를 두는 SessionBackend 구현이다.
//
// tmux 백엔드가 남의 프로그램에 위임하던 것을 이쪽은 kyu 자신이 떠받친다. 세션의 생존 근거가
// tmux 서버에서 그 세션의 감독으로 바뀔 뿐 성질은 같고, 반경만 좁다 — 감독이 죽으면 그 세션
// 하나가 죽는다(설계 문서 5.9).
type SupervisorBackend struct {
	// executablePath 는 감독으로 다시 실행할 이 바이너리의 경로다.
	// tmux 백엔드가 tmuxPath 를 생성 시점에 확정하는 것과 같은 이유로 한 번만 찾는다.
	executablePath string
}

// 인터페이스 충족 여부를 컴파일 시점에 확인한다.
// 메서드 시그니처가 어긋나면 실제 사용처가 생기기 전에 빌드가 깨진다.
var _ SessionBackend = (*SupervisorBackend)(nil)

// NewSupervisorBackend 는 감독 백엔드를 만든다.
func NewSupervisorBackend() (*SupervisorBackend, error) {
	executablePath, err := os.Executable()
	if err != nil {
		return nil, fmt.Errorf("감독으로 다시 실행할 이 바이너리의 경로를 찾지 못했습니다: %w", err)
	}
	return &SupervisorBackend{executablePath: executablePath}, nil
}

// Create 는 cwd 에서 cmd 를 실행하는 백그라운드 세션을 만든다.
// 이미 같은 이름의 세션이 있으면 ErrSessionExists 를 반환한다.
//
// 프로토콜 요청을 보내지 않는다. 받을 상대가 아직 없기 때문이다 — 대신 자기를 다시 실행한다.
func (b *SupervisorBackend) Create(name, cwd string, cmd []string) error {
	if len(cmd) == 0 {
		return fmt.Errorf("세션에서 실행할 명령이 없습니다 (세션 %s)", name)
	}

	paths, err := newSessionRuntimePaths(name)
	if err != nil {
		return err
	}
	if err := ensureSessionsDir(paths.sessionsDir); err != nil {
		return err
	}

	// 감독은 떼어진 프로세스라 이 파일이 아니면 실패를 말할 곳이 없다.
	logFile, err := os.OpenFile(paths.log, os.O_CREATE|os.O_WRONLY|os.O_APPEND, sessionFilePermission)
	if err != nil {
		return fmt.Errorf("세션 기록 파일 열기 실패 (%s): %w", paths.log, err)
	}
	defer logFile.Close()

	readyReader, readyWriter, err := os.Pipe()
	if err != nil {
		return fmt.Errorf("준비 신호 파이프 생성 실패 (세션 %s): %w", name, err)
	}
	defer readyReader.Close()

	supervise := exec.Command(b.executablePath, superviseCommandArgs(name, cwd, cmd)...)
	supervise.Stdout = logFile
	supervise.Stderr = logFile
	supervise.ExtraFiles = []*os.File{readyWriter}

	// setsid 로 부모의 터미널·프로세스 그룹에서 떼어낸다. 이 한 줄이 "도구를 꺼도 작업이 안
	// 끊긴다" 를 만든다 — kyu 를 실행한 터미널이 닫혀도 감독에게 SIGHUP 이 가지 않는다.
	supervise.SysProcAttr = &syscall.SysProcAttr{Setsid: true}

	// 환경은 부모의 것을 그대로 물려준다. SessionBackend.Create 의 시그니처에 env 인자가 없고,
	// 환경변수를 얹어야 하는 호출부는 이미 명령을 env 로 감싸는 방식을 쓴다(설계 문서 5.3).
	supervise.Env = os.Environ()

	if err := supervise.Start(); err != nil {
		readyWriter.Close()
		return fmt.Errorf("감독 기동 실패 (세션 %s): %w", name, err)
	}

	// 부모가 쥔 쓰기 끝을 닫아야 감독이 자기 것을 닫을 때 읽기가 EOF 를 만난다.
	readyWriter.Close()

	// 감독을 Wait 하지 않는다. 감독은 세션과 함께 오래 살고 kyu 는 곧 끝나므로, 기다리는 것은
	// 이 명령이 세션이 끝날 때까지 돌아오지 않는다는 뜻이 된다. kyu 가 끝나면 감독은 init 에
	// 재부모되어 계속 산다.
	return awaitReadySignal(readyReader, name, paths.log)
}

// supervisorDetachKey 는 감독 세션에서 빠져나오는 키다.
//
// Ctrl-\ 를 고른 이유(설계 문서 5.8): 대화형 프로그램이 거의 쓰지 않고, 세션마다 프로세스
// 하나에 소켓 하나라는 같은 배치를 오래 써온 dtach·abduco 의 관례이기도 하다. raw 모드에서는
// ISIG 를 끄므로 이 키가 SIGQUIT 이 되지 않는다.
//
// tmux 처럼 접두 키 + d 로 두지 않는다. tmux 의 Ctrl-b 는 readline·emacs 계열에서 "한 글자
// 뒤로" 라 세션 안 프로그램에게서 그 키를 뺏고, 다른 접두 키를 골라도 상태를 가진 키 파싱이
// 하나 생긴다.
const supervisorDetachKey = `Ctrl-\`

// DetachKey 는 감독 세션에서 빠져나오는 키다.
func (b *SupervisorBackend) DetachKey() string {
	return supervisorDetachKey
}

// Kill 은 세션을 종료한다. 없으면 nil 을 반환한다(멱등).
func (b *SupervisorBackend) Kill(name string) error {
	paths, err := newSessionRuntimePaths(name)
	if err != nil {
		return err
	}

	response, err := sendControlRequest(paths.socket, killOperation, killRequestDeadline)
	if err != nil {
		if errors.Is(err, errSessionSocketNotAnswering) {
			return nil
		}
		if errors.Is(err, errControlConnectionEndedBeforeResponse) {
			// 감독이 답 직전에 자연사했을 수 있다. 그렇다면 kill 이 원한 결과가 이미 나 있다.
			alive, aliveErr := b.IsAlive(name)
			if aliveErr != nil {
				return aliveErr
			}
			if !alive {
				return nil
			}
		}
		return err
	}

	if !response.OK {
		return controlFailureError(name, response.Code, response.Message)
	}
	return nil
}

// List 는 이 백엔드가 관리하는 세션 이름 전체를 반환한다.
//
// 세션 목록이라는 사실을 어느 프로세스도 소유하지 않는다. 파일 시스템이 갖는다(설계 원칙 10).
// 손으로 유지하는 목록은 한 번 잊는 순간 조용히 어긋나므로, 조율 계층이 워크디렉토리의 레포
// 목록을 스캔으로 얻는 것과 같은 자세로 소켓 디렉토리를 훑는다.
func (b *SupervisorBackend) List() ([]string, error) {
	sessionsDir, err := resolveSessionsDir()
	if err != nil {
		return nil, err
	}

	entries, err := os.ReadDir(sessionsDir)
	if err != nil {
		// 디렉토리가 없으면 빈 목록이다. 세션을 한 번도 만들지 않은 상태의 정상적인 표현이라
		// 오류가 아니다 — tmux 백엔드가 "서버 없음" 을 그렇게 다루는 것과 같다(tmux.go:119).
		if errors.Is(err, fs.ErrNotExist) {
			return nil, nil
		}
		return nil, fmt.Errorf("세션 디렉토리 읽기 실패 (%s): %w", sessionsDir, err)
	}

	var names []string
	for _, entry := range entries {
		if !strings.HasSuffix(entry.Name(), sessionSocketExtension) {
			continue
		}
		socketPath := filepath.Join(sessionsDir, entry.Name())

		response, err := sendControlRequest(socketPath, aliveOperation, aliveRequestDeadline)
		if err != nil {
			// 답하지 않는 소켓은 목록에서 빠질 뿐 그 자리에 남는다. 읽기 연산이 파일을 지우면
			// 안 된다 — 3 초마다 도는 대시보드 조회가 파일을 치우는 것은 놀랄 일이고, 막 뜨는
			// 중인 감독의 소켓과도 경쟁한다. 치우는 시점은 그 이름으로 다시 Create 할
			// 때다(설계 문서 5.4).
			if errors.Is(err, errSessionSocketNotAnswering) {
				continue
			}
			// 그 밖의 실패는 "없다" 가 아니라 "묻지 못했다" 이므로 삼키지 않는다.
			// 조용히 빼면 사용자는 살아 있는 세션을 사라진 것으로 본다.
			return nil, err
		}
		if !response.OK {
			// 다른 판의 감독이 거절한 경우다. 이런 세션을 목록에 어떻게 드러낼지는 아직
			// 정해지지 않았다(설계 문서 10절 4번).
			continue
		}
		if !response.Alive {
			continue
		}

		names = append(names, strings.TrimSuffix(entry.Name(), sessionSocketExtension))
	}
	return names, nil
}

// IsAlive 는 세션의 생존 여부를 반환한다.
//
// 디렉토리를 훑지 않는다 — 이름을 이미 알고 있으므로 그 세션의 소켓 경로를 곧바로 만든다.
func (b *SupervisorBackend) IsAlive(name string) (bool, error) {
	paths, err := newSessionRuntimePaths(name)
	if err != nil {
		return false, err
	}

	response, err := sendControlRequest(paths.socket, aliveOperation, aliveRequestDeadline)
	if err != nil {
		if errors.Is(err, errSessionSocketNotAnswering) {
			return false, nil
		}
		return false, err
	}

	if !response.OK {
		return false, controlFailureError(name, response.Code, response.Message)
	}
	return response.Alive, nil
}

// awaitReadySignal 은 감독이 떴는지를 준비 파이프로 확인한다.
//
// 폴링하지 않는 이유가 둘이다. 폴링은 얼마나 기다릴지를 숫자로 정해야 하는데 파이프는 답이
// 오면 그 자리에서 끝나고, 폴링은 왜 안 떴는지를 모르는데 파이프는 코드로 말한다(설계 문서 5.3).
func awaitReadySignal(readyReader *os.File, sessionName, logPath string) error {
	if err := readyReader.SetReadDeadline(time.Now().Add(readySignalDeadline)); err != nil {
		return fmt.Errorf("준비 신호 마감 시각 설정 실패 (세션 %s): %w", sessionName, err)
	}

	rawSignal, err := io.ReadAll(readyReader)
	if err != nil {
		return fmt.Errorf("감독이 %s 안에 준비 신호를 보내지 않았습니다 (세션 %s) — 기록: %s: %w",
			readySignalDeadline, sessionName, logPath, err)
	}

	rawSignal = bytes.TrimSpace(rawSignal)
	if len(rawSignal) == 0 {
		// 아무것도 없이 EOF 면 감독이 답하기 전에 죽은 것이다. 그 이유는 기록에만 남아 있다.
		return fmt.Errorf("감독이 준비 신호를 보내기 전에 종료했습니다 (세션 %s) — 기록: %s",
			sessionName, logPath)
	}

	var signal readySignal
	if err := json.Unmarshal(rawSignal, &signal); err != nil {
		return fmt.Errorf("준비 신호를 해석하지 못했습니다 (세션 %s, 받은 것 %q) — 기록: %s: %w",
			sessionName, rawSignal, logPath, err)
	}

	if !signal.OK {
		return controlFailureError(sessionName, signal.Code, signal.Message)
	}
	return nil
}

// sendControlRequest 는 제어 연결 하나를 열어 요청 한 줄을 보내고 응답 한 줄을 받는다.
//
// 연결은 짧게 산다 — 요청 하나를 보내고 응답을 받고 닫는다(설계 문서 5.5).
func sendControlRequest(socketPath, operation string, deadline time.Duration) (controlResponse, error) {
	connection, err := net.DialTimeout("unix", socketPath, deadline)
	if err != nil {
		if errors.Is(err, fs.ErrNotExist) || errors.Is(err, syscall.ECONNREFUSED) {
			return controlResponse{}, fmt.Errorf("%w (%s): %w", errSessionSocketNotAnswering, socketPath, err)
		}
		return controlResponse{}, fmt.Errorf("세션 소켓 연결 실패 (%s): %w", socketPath, err)
	}
	defer connection.Close()

	if err := connection.SetDeadline(time.Now().Add(deadline)); err != nil {
		return controlResponse{}, fmt.Errorf("제어 연결 마감 시각 설정 실패 (%s): %w", socketPath, err)
	}

	request := controlRequest{Version: controlProtocolVersion, Operation: operation}
	if err := json.NewEncoder(connection).Encode(request); err != nil {
		return controlResponse{}, fmt.Errorf("제어 요청 전송 실패 (%s, %s): %w", socketPath, operation, err)
	}

	var response controlResponse
	if err := json.NewDecoder(connection).Decode(&response); err != nil {
		if errors.Is(err, io.EOF) || errors.Is(err, io.ErrUnexpectedEOF) {
			return controlResponse{}, fmt.Errorf("%w (%s, %s)",
				errControlConnectionEndedBeforeResponse, socketPath, operation)
		}
		return controlResponse{}, fmt.Errorf("제어 응답 해석 실패 (%s, %s): %w", socketPath, operation, err)
	}
	return response, nil
}
