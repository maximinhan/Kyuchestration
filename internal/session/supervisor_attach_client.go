package session

import (
	"bufio"
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"net"
	"os"
	"os/signal"
	"sync"
	"syscall"
	"time"

	"golang.org/x/term"
)

// detachKeyByte 는 supervisorDetachKey 가 터미널에서 오는 바이트다: Ctrl-\ = 0x1c.
//
// 이름과 바이트가 갈라지면 안내와 동작이 어긋난다 — 사용자는 안내받은 키를 눌렀는데 나가지지
// 않는 상태가 되고, 그것은 세션 안에 갇힌 것과 같다. 둘을 붙여 두고 함께 읽는다.
const detachKeyByte = 0x1c

// attachDialTimeout 은 세션 소켓에 붙는 데 허용하는 시간이다.
//
// 커널 안에서 끝나는 연결이라 정상 경로에서는 즉시다. 이 숫자는 소켓 파일은 있는데 감독이
// 답하지 않는 상태에서 사용자를 무한정 세워두지 않기 위한 것이다.
const attachDialTimeout = 5 * time.Second

// terminalInputBufferSize 는 터미널에서 한 번에 읽어 나르는 키 입력의 크기다.
//
// 사람의 타이핑은 몇 바이트씩이지만 붙여넣기는 한 번에 쏟아진다. 작으면 붙여넣기가 프레임
// 수십 개로 쪼개질 뿐이라 정확성에는 영향이 없다.
const terminalInputBufferSize = 4096

// Attach 는 호출한 터미널을 해당 세션에 연결한다.
// 사용자가 빠져나올 때까지 블로킹된다.
// 이미 세션 안에서 실행 중이면 ErrNestedSession 을 반환한다.
//
// tmux 백엔드가 자기 표준 입출력을 tmux 에게 통째로 넘기던 자리다(tmux.go:83). 여기서는 넘길
// 상대가 없으므로 이 프로세스가 직접 터미널의 주인이 된다 — raw 모드로 바꾸고, 키를 감독에게
// 나르고, 세션 출력을 화면에 찍고, 창 크기가 바뀌면 그것을 전한다.
func (b *SupervisorBackend) Attach(name string) error {
	// 중첩 판정을 먼저 한다. 세션 안에서 부른 것이라면 터미널이 있든 없든 답이 같다.
	//
	// tmux 가 TMUX 를 보는 것과 같은 기전이다(tmux.go:73). 판정을 백엔드에 두면 "tmux 세션
	// 안에서 감독 백엔드로 붙는 것은 중첩이 아니다" 가 저절로 맞는다 — 서로 다른 두 계층이라
	// 겹치지 않는다(설계 문서 5.3).
	if currentSessionName, isInsideSession := os.LookupEnv(insideSupervisorSessionEnvName); isInsideSession && currentSessionName != "" {
		return fmt.Errorf("%w: %s", ErrNestedSession, currentSessionName)
	}

	terminalFd := int(os.Stdin.Fd())
	if !term.IsTerminal(terminalFd) {
		// 세션 화면은 터미널이 있어야 성립한다. tmux 백엔드도 여기서 tmux 의 "open terminal
		// failed" 로 끝나므로 결말은 같고, 무엇이 없어서 안 되는지만 우리 말로 말한다.
		return fmt.Errorf("세션에 붙으려면 터미널이 필요합니다 — 표준 입력이 터미널이 아닙니다 (세션 %s)", name)
	}

	paths, err := newSessionRuntimePaths(name)
	if err != nil {
		return err
	}

	connection, err := net.DialTimeout("unix", paths.socket, attachDialTimeout)
	if err != nil {
		if errors.Is(err, fs.ErrNotExist) || errors.Is(err, syscall.ECONNREFUSED) {
			return fmt.Errorf("%w (%s): %w", errSessionSocketNotAnswering, name, err)
		}
		return fmt.Errorf("세션 소켓 연결 실패 (%s): %w", paths.socket, err)
	}
	defer connection.Close()

	sessionExit, err := streamSessionOnThisTerminal(connection, terminalFd, name)
	if err != nil {
		return err
	}

	// 세션 안 프로그램이 끝나 돌아온 경우다. 아무 말 없이 셸로 돌아가면 사용자는 자기가 뗀 것인지
	// 세션이 끝난 것인지 모른다 — tmux 도 이 자리에서 [exited] 를 찍는다.
	//
	// 터미널을 되돌린 뒤에 찍는다. raw 모드에서 찍으면 개행이 줄 처음으로 돌아가지 않아
	// 들여쓰인 것처럼 보인다.
	if sessionExit != nil {
		fmt.Fprintf(os.Stderr, "세션이 끝났습니다 (종료 코드 %d)\n", sessionExit.Code)
	}
	return nil
}

// streamSessionOnThisTerminal 은 이 터미널을 세션에 넘겼다가 돌려받는다.
//
// 세션 안 프로그램이 끝나 돌아온 것이면 그 종료 정보를, 사용자가 뗀 것이면 nil 을 돌려준다.
func streamSessionOnThisTerminal(connection net.Conn, terminalFd int, sessionName string) (*sessionExitPayload, error) {
	columns, rows, err := term.GetSize(terminalFd)
	if err != nil {
		return nil, fmt.Errorf("터미널 크기 확인 실패 (세션 %s): %w", sessionName, err)
	}

	request, err := json.Marshal(attachRequest{Version: attachProtocolVersion, Columns: columns, Rows: rows})
	if err != nil {
		return nil, fmt.Errorf("붙기 요청 인코딩 실패 (세션 %s): %w", sessionName, err)
	}
	if err := writeAttachFrame(connection, attachFrameType, request); err != nil {
		return nil, fmt.Errorf("붙기 요청 전송 실패 (세션 %s): %w", sessionName, err)
	}

	// 여기부터 이 터미널의 키와 화면은 세션의 것이다. raw 모드가 아니면 줄 편집기가 키를 붙들고
	// 있다가 개행에서야 넘겨주고, Ctrl-C 는 세션이 아니라 이 프로세스를 죽인다.
	previousTerminalState, err := term.MakeRaw(terminalFd)
	if err != nil {
		return nil, fmt.Errorf("터미널을 raw 모드로 바꾸지 못했습니다 (세션 %s): %w", sessionName, err)
	}
	// 어떤 길로 나가든 되돌린다. 되돌리지 못한 채 나가면 사용자는 셸로 돌아왔는데 키가 먹지 않는
	// 터미널을 받는다.
	defer func() {
		if err := term.Restore(terminalFd, previousTerminalState); err != nil {
			fmt.Fprintf(os.Stderr, "터미널 상태를 되돌리지 못했습니다: %v\n%s\n", err, "stty sane 으로 되돌릴 수 있습니다")
		}
	}()

	stream := &attachStream{
		connection:  connection,
		sessionName: sessionName,
		terminalFd:  terminalFd,
		detached:    make(chan struct{}),
	}

	go stream.forwardTerminalInput()
	go stream.forwardWindowSizeChanges()
	go stream.detachOnTerminationSignal()

	return stream.receiveSessionOutput(os.Stdout)
}

// attachStream 은 감독에 붙어 있는 동안의 이 터미널이다.
type attachStream struct {
	connection  net.Conn
	sessionName string
	terminalFd  int

	// writeMutex 는 이 연결에 쓰는 자리를 하나로 만든다. 키 입력과 크기 변경이 서로 다른
	// 고루틴에서 오므로 두 손이 같은 소켓에 닿는다.
	writeMutex sync.Mutex

	// detached 는 이 터미널이 세션에서 떨어졌을 때 닫힌다.
	detached   chan struct{}
	detachOnce sync.Once
}

// detach 는 세션에서 떨어진다.
//
// 감독에게 알릴 메시지가 없다 — 연결을 닫는 것이 곧 detach 다. 클라이언트가 죽어도 커널이
// 연결을 닫아주므로 같은 경로로 처리된다(설계 문서 5.6).
func (s *attachStream) detach() {
	s.detachOnce.Do(func() {
		close(s.detached)
		s.connection.Close()
	})
}

func (s *attachStream) isDetached() bool {
	select {
	case <-s.detached:
		return true
	default:
		return false
	}
}

func (s *attachStream) send(frameType byte, payload []byte) error {
	s.writeMutex.Lock()
	defer s.writeMutex.Unlock()
	return writeAttachFrame(s.connection, frameType, payload)
}

// forwardTerminalInput 은 사용자가 누른 키를 세션으로 나른다.
//
// 이 고루틴은 os.Stdin 을 읽는 중에 멈춰 있을 수 있고, 그 읽기를 밖에서 깨울 방법이 없다.
// 그래서 detach 뒤에도 남는데, kyu attach 는 붙어 있는 동안만 사는 프로세스라 그 뒤로 곧 끝난다.
func (s *attachStream) forwardTerminalInput() {
	buffer := make([]byte, terminalInputBufferSize)
	for {
		read, err := os.Stdin.Read(buffer)
		if read > 0 {
			typed := buffer[:read]

			// 빠져나오기 키는 세션에 전하지 않고 여기서 잡는다. 한 번에 읽힌 덩어리 가운데
			// 있을 수 있으므로(붙여넣기·빠른 타이핑) 그 앞까지는 마저 보내고 뗀다.
			if detachAt := bytes.IndexByte(typed, detachKeyByte); detachAt >= 0 {
				if detachAt > 0 {
					s.send(inputFrameType, typed[:detachAt])
				}
				s.detach()
				return
			}

			if err := s.send(inputFrameType, typed); err != nil {
				s.detach()
				return
			}
		}
		if err != nil {
			s.detach()
			return
		}
	}
}

// forwardWindowSizeChanges 는 창 크기가 바뀔 때마다 그것을 감독에게 전한다.
//
// tmux 클라이언트가 대신 해주던 일이다. 지금 리사이즈 경로는 창 크기 변경 → SIGWINCH → 이
// 핸들러 → RESIZE 프레임 → 감독이 세션 PTY 에 적용 → 세션 안 프로그램에 SIGWINCH 다(설계 문서 7절).
func (s *attachStream) forwardWindowSizeChanges() {
	windowChanged := make(chan os.Signal, 1)
	signal.Notify(windowChanged, syscall.SIGWINCH)
	defer signal.Stop(windowChanged)

	for {
		select {
		case <-s.detached:
			return
		case <-windowChanged:
			columns, rows, err := term.GetSize(s.terminalFd)
			if err != nil {
				// 크기를 모르는 채로 아무 숫자나 보내면 세션 화면이 엉뚱해진다. 다음 신호를 기다린다.
				continue
			}
			if err := s.send(resizeFrameType, encodeWindowSize(columns, rows)); err != nil {
				s.detach()
				return
			}
		}
	}
}

// detachOnTerminationSignal 은 밖에서 온 종료 신호에 터미널을 되돌리고 물러난다.
//
// raw 모드에서는 ISIG 를 끄므로 키보드가 신호를 만들지 않지만, 창을 닫으면 SIGHUP 이 오고
// 다른 프로세스가 SIGTERM 을 보낼 수 있다. 기본 처리에 맡기면 그 자리에서 죽어 터미널이 raw
// 모드로 남는다 — 붙어 있던 흔적이 사용자의 셸에 남는 것이다.
func (s *attachStream) detachOnTerminationSignal() {
	terminating := make(chan os.Signal, 1)
	signal.Notify(terminating, syscall.SIGTERM, syscall.SIGHUP)
	defer signal.Stop(terminating)

	select {
	case <-s.detached:
	case <-terminating:
		s.detach()
	}
}

// receiveSessionOutput 은 감독이 보내는 프레임을 받아 화면에 옮긴다.
func (s *attachStream) receiveSessionOutput(screen io.Writer) (*sessionExitPayload, error) {
	reader := bufio.NewReader(s.connection)

	for {
		frameType, payload, err := readAttachFrame(reader)
		if err != nil {
			if s.isDetached() {
				// 사용자가 뗀 것이다. 우리가 닫은 연결에서 나는 오류이므로 실패가 아니다.
				return nil, nil
			}
			if errors.Is(err, io.EOF) {
				return nil, fmt.Errorf("세션 감독과의 연결이 끊겼습니다 (세션 %s)", s.sessionName)
			}
			return nil, err
		}

		switch frameType {
		case outputFrameType:
			if _, err := screen.Write(payload); err != nil {
				return nil, fmt.Errorf("세션 출력을 화면에 쓰지 못했습니다 (세션 %s): %w", s.sessionName, err)
			}

		case exitFrameType:
			var sessionExit sessionExitPayload
			if err := json.Unmarshal(payload, &sessionExit); err != nil {
				return nil, fmt.Errorf("세션 종료 알림 해석 실패 (세션 %s, 받은 것 %q): %w", s.sessionName, payload, err)
			}
			return &sessionExit, nil

		case errorFrameType:
			var refusal attachErrorPayload
			if err := json.Unmarshal(payload, &refusal); err != nil {
				return nil, fmt.Errorf("붙기 거절 해석 실패 (세션 %s, 받은 것 %q): %w", s.sessionName, payload, err)
			}
			return nil, controlFailureError(s.sessionName, refusal.Code, refusal.Message)

		default:
			// 모르는 프레임에 나가지 않는다. 감독이 우리가 아직 모르는 프레임을 보내는 것이
			// 프로토콜이 늘어날 때의 정상적인 모습이고, 화면에 찍지 않는 한 해가 없다.
		}
	}
}
