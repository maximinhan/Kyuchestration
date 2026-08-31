package session

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"os"
	"os/exec"
	"syscall"
	"time"

	"github.com/creack/pty"
)

// SuperviseCommandName 은 감독 프로세스를 띄우는 숨은 하위 명령의 이름이다.
//
// 감독은 별도 실행 파일이 아니라 kyu 바이너리 자신이다(설계 문서 5.1). 릴리스 자산이 늘지 않고,
// 앱이 동봉하는 엔진 하나에 감독이 이미 들어 있고, 무엇보다 클라이언트와 감독의 프로토콜 코드가
// 같은 패키지에 있어 어긋날 자리가 없다.
//
// 이름을 이 상수 하나로 둔다. 이 명령을 띄우는 쪽(SupervisorBackend.Create)과 받는 쪽(진입점의
// 라우팅)이 같은 것을 보므로 둘이 갈라질 수 없다. 사용자가 부를 명령이 아니라 usage 에는 싣지 않는다.
const SuperviseCommandName = "__supervise"

// readySignalFileDescriptor 는 감독이 준비 신호를 쓰는 파일 디스크립터다.
//
// 부모가 파이프의 쓰기 끝을 ExtraFiles 로 물려주면 감독에게서 3번이 된다(0·1·2 다음).
// 번호를 인자로 넘기지 않는 이유는 물려주는 쪽과 집어드는 쪽이 같은 패키지라 상수 하나면 되기 때문이다.
const readySignalFileDescriptor = 3

// insideSupervisorSessionEnvName 은 감독이 자기 세션 안에서 도는 프로세스에 심어두는 환경변수다.
//
// tmux 가 TMUX 를 심는 것과 같은 기전이다(tmux.go:18). 값을 소켓 경로가 아니라 세션 이름으로
// 두는 이유는 안내가 "이 터미널은 이미 세션 안입니다" 에서 "이 터미널은 이미 kyu-featureX-proj-a
// 세션 안입니다" 로 나아가기 때문이다 — 사용자가 어느 세션인지 모르는 채로 여기 도착하는 경우가
// 실제로 있다(설계 문서 5.3).
const insideSupervisorSessionEnvName = "KYU_SESSION"

// fallbackTerm 은 물려받은 TERM 이 없을 때 세션에 넣어주는 값이다.
//
// TERM 이 없으면 세션 안 프로그램이 화면 그리기를 포기한다. 감독은 떼어진 프로세스라 TERM 이
// 비어 올 수 있다 — GUI 런처에서 앱을 띄운 경우가 그렇다(설계 문서 5.3).
const fallbackTerm = "xterm-256color"

// sessionTerminationGracePeriod 는 SIGHUP 을 보내고 SIGKILL 까지 기다리는 유예다.
const sessionTerminationGracePeriod = 3 * time.Second

// controlConnectionDeadline 은 감독이 제어 연결 하나에 쓰는 최대 시간이다.
//
// 제어 요청은 한 줄 주고받고 끝나므로 동시성을 들이지 않고 하나씩 처리한다. 대신 답하지 않는
// 클라이언트가 감독을 붙잡지 못하도록 마감 시각을 둔다. kill 은 자식이 끝나기를 기다린 뒤에
// 답하므로 유예보다 넉넉해야 한다.
const controlConnectionDeadline = sessionTerminationGracePeriod + 7*time.Second

// errSessionSocketAlreadyBound 는 그 이름의 소켓을 이미 누군가 쥐고 있을 때다.
//
// 유닉스 도메인 소켓은 파일이 이미 있으면 바인딩에 실패한다. 그 실패가 곧 "같은 이름의 세션이
// 이미 있다" 이므로, 판정과 획득이 같은 한 번의 연산이라 그 사이에 낄 틈이 없다(설계 문서 5.3).
var errSessionSocketAlreadyBound = errors.New("이미 그 이름의 감독이 소켓을 쥐고 있습니다")

// errWorkingDirMissing 은 세션을 띄울 디렉토리가 없을 때다.
var errWorkingDirMissing = errors.New("세션의 작업 디렉토리가 없습니다")

// errCommandStartFailed 는 세션 안에서 실행할 명령을 띄우지 못했을 때다.
var errCommandStartFailed = errors.New("세션 명령을 실행하지 못했습니다")

// superviseOptions 는 감독이 자기 세션에 대해 아는 전부다.
//
// 다른 세션의 존재는 모르고, 알 필요도 없다(설계 원칙 10).
type superviseOptions struct {
	sessionName string
	workingDir  string
	command     []string
}

// RunSupervisor 는 kyu __supervise 의 본문이다 — 세션 하나를 떠받치는 프로세스가 여기서 산다.
//
// 순서가 중요하다(설계 문서 5.3).
//
//  1. 소켓을 바인딩한다  ← 이것이 배타성 판정이다
//  2. PTY 쌍을 연다
//  3. 자식을 실행한다
//  4. 준비 신호를 부모에게 보낸다
//  5. 서빙을 시작한다
//
// 1 번이 먼저인 이유는 바인딩이 곧 "이 이름의 세션은 내 것" 이라는 선언이기 때문이다. 실패하면
// 자식을 띄우기 전에 끝내야 한다 — 순서가 뒤집히면 중복 생성이 세션 안 프로그램을 하나 띄웠다
// 죽이는 일이 된다.
func RunSupervisor(args []string) error {
	// 감독의 표준 출력은 부모가 그 세션의 기록 파일로 돌려두었다. 생명주기 사건만 적는다 —
	// 입출력 바이트는 절대 적지 않는다. 세션의 내용은 사용자의 작업이고 도구의 관심사가
	// 아니며(설계 원칙 9), 적기 시작하면 기록이 무한히 자라 회전 장치가 필요해진다.
	events := log.New(os.Stderr, "", log.LstdFlags)

	readyPipe := adoptReadySignalPipe()
	defer readyPipe.Close()

	supervisor, err := startSupervisor(args, events)
	if err != nil {
		events.Printf("감독 기동 실패: %v", err)
		writeReadySignal(readyPipe, failureSignalFor(err), events)
		return err
	}

	writeReadySignal(readyPipe, readySignal{Version: controlProtocolVersion, OK: true}, events)
	// 부모는 파이프를 EOF 까지 읽는다. 여기서 닫아야 그 읽기가 끝난다.
	readyPipe.Close()

	events.Printf("세션 시작 (감독 pid %d, 자식 pid %d)", os.Getpid(), supervisor.childCommand.Process.Pid)
	return supervisor.serve()
}

// adoptReadySignalPipe 는 부모가 물려준 파이프의 쓰기 끝을 집어든다.
func adoptReadySignalPipe() *os.File {
	// 이 fd 는 물려받은 것이라 exec 시 닫히도록 표시되어 있지 않다. 표시하지 않으면 세션 안
	// 프로그램이 이 파이프를 함께 물려받아 열어둔 채로 살고, 그러면 부모의 읽기가 EOF 를 만나지
	// 못해 감독이 멀쩡히 떴는데도 Create 가 마감 시각까지 기다린다.
	syscall.CloseOnExec(readySignalFileDescriptor)
	return os.NewFile(readySignalFileDescriptor, "ready")
}

// writeReadySignal 은 준비 신호 한 줄을 파이프에 쓴다.
//
// 신호를 못 보내는 것이 감독을 멈출 이유는 아니다. 손으로 __supervise 를 실행하면 3번 fd 자체가
// 없는데, 그때도 세션은 떠 있어야 한다. 부모가 마감 시각에 걸리는 것이 그 경우의 결말이고,
// 그 사실은 기록에 남긴다.
func writeReadySignal(readyPipe *os.File, signal readySignal, events *log.Logger) {
	if err := json.NewEncoder(readyPipe).Encode(signal); err != nil {
		events.Printf("준비 신호 전달 실패: %v", err)
	}
}

// failureSignalFor 는 기동 실패를 부모가 분기할 수 있는 코드로 옮긴다.
//
// 소켓 폴링 대신 파이프를 고른 이유가 이 함수다. 폴링은 "소켓이 아직 안 받는다" 만 알 수 있어서
// 이름 충돌인지 cwd 가 없어서인지 명령을 못 찾아서인지 구별하지 못한다(설계 문서 5.3).
func failureSignalFor(err error) readySignal {
	signal := readySignal{Version: controlProtocolVersion, OK: false, Message: err.Error()}
	switch {
	case errors.Is(err, errSessionSocketAlreadyBound):
		signal.Code = sessionExistsCode
	case errors.Is(err, errWorkingDirMissing):
		signal.Code = workingDirMissingCode
	case errors.Is(err, errCommandStartFailed):
		signal.Code = commandStartFailedCode
	case errors.Is(err, errSuperviseArguments):
		signal.Code = superviseArgumentsCode
	default:
		signal.Code = supervisorInternalCode
	}
	return signal
}

// supervisorProcess 는 서빙에 들어간 감독이다.
type supervisorProcess struct {
	sessionName  string
	listener     *net.UnixListener
	childCommand *exec.Cmd
	ptyMaster    *os.File
	events       *log.Logger

	// childExited 는 자식을 거두면 닫힌다. childExitCode 는 그 뒤에만 읽는다.
	childExited   chan struct{}
	childExitCode int
}

// startSupervisor 는 소켓을 잡고 PTY 를 열고 자식을 띄운다 — 준비 신호 직전까지다.
func startSupervisor(args []string, events *log.Logger) (*supervisorProcess, error) {
	options, err := parseSuperviseArguments(args)
	if err != nil {
		return nil, err
	}

	paths, err := newSessionRuntimePaths(options.sessionName)
	if err != nil {
		return nil, err
	}
	if err := ensureSessionsDir(paths.sessionsDir); err != nil {
		return nil, err
	}

	listener, err := bindSessionSocket(paths)
	if err != nil {
		return nil, err
	}

	childCommand, ptyMaster, err := startSessionChild(options)
	if err != nil {
		// 리스너를 닫으면 소켓 파일도 함께 지워진다. 자식을 못 띄운 이름이 죽은 소켓으로 남으면,
		// 다음 Create 가 그것을 치우기 전까지 없는 세션이 있는 것처럼 보인다.
		listener.Close()
		return nil, err
	}

	return &supervisorProcess{
		sessionName:  options.sessionName,
		listener:     listener,
		childCommand: childCommand,
		ptyMaster:    ptyMaster,
		events:       events,
		childExited:  make(chan struct{}),
	}, nil
}

// bindSessionSocket 은 세션 소켓을 바인딩한다. 이 함수의 성공이 곧 "이 이름의 세션은 내 것" 이다.
func bindSessionSocket(paths sessionRuntimePaths) (*net.UnixListener, error) {
	listener, err := listenOnSessionSocket(paths.socket)
	if errors.Is(err, syscall.EADDRINUSE) {
		return nil, fmt.Errorf("%w: %s", errSessionSocketAlreadyBound, paths.socket)
	}
	return listener, err
}

// listenOnSessionSocket 은 소켓을 바인딩하고 권한을 사용자 전용으로 좁힌다.
func listenOnSessionSocket(socketPath string) (*net.UnixListener, error) {
	address, err := net.ResolveUnixAddr("unix", socketPath)
	if err != nil {
		return nil, fmt.Errorf("세션 소켓 주소 해석 실패 (%s): %w", socketPath, err)
	}

	listener, err := net.ListenUnix("unix", address)
	if err != nil {
		return nil, err
	}

	// 이 소켓에 연결할 수 있다는 것은 그 세션에 키를 보낼 수 있다는 뜻이다(설계 문서 5.2).
	// 바인딩과 이 한 줄 사이에 잠깐 넓은 권한이 남지만, 세션 디렉토리 자체가 0700 이라
	// 그 사이에 다른 사용자가 이 파일에 닿을 길이 없다.
	if err := os.Chmod(socketPath, sessionFilePermission); err != nil {
		listener.Close()
		return nil, fmt.Errorf("세션 소켓 권한 설정 실패 (%s): %w", socketPath, err)
	}
	return listener, nil
}

// startSessionChild 는 PTY 쌍을 열고 그 슬레이브를 터미널로 삼는 자식을 띄운다.
func startSessionChild(options superviseOptions) (*exec.Cmd, *os.File, error) {
	// 없는 디렉토리는 exec 가 알아채기는 하지만 "chdir 실패" 로만 말한다. 부모가 사용자에게
	// 무엇이 없는지 그대로 옮기려면 여기서 이름 붙여 끊는 편이 낫다.
	info, err := os.Stat(options.workingDir)
	if err != nil {
		return nil, nil, fmt.Errorf("%w: %s: %w", errWorkingDirMissing, options.workingDir, err)
	}
	if !info.IsDir() {
		return nil, nil, fmt.Errorf("%w: %s 는 디렉토리가 아닙니다", errWorkingDirMissing, options.workingDir)
	}

	childCommand := exec.Command(options.command[0], options.command[1:]...)
	childCommand.Dir = options.workingDir
	childCommand.Env = sessionChildEnvironment(options.sessionName)

	// pty.Start 는 자식을 setsid + setctty 로 띄우고 PTY 슬레이브를 그 제어 터미널로 삼는다.
	// 그래서 자식의 프로세스 그룹 번호가 자기 pid 와 같아지고, 종료할 때 그룹 전체에 신호를
	// 보낼 수 있다(설계 문서 5.3).
	ptyMaster, err := pty.Start(childCommand)
	if err != nil {
		return nil, nil, fmt.Errorf("%w (%s): %w", errCommandStartFailed, options.command[0], err)
	}
	return childCommand, ptyMaster, nil
}

// sessionChildEnvironment 는 세션 안 프로세스가 물려받을 환경을 만든다.
func sessionChildEnvironment(sessionName string) []string {
	// os/exec 는 같은 키가 여러 번 나오면 뒤엣것을 남긴다. 물려받은 환경에 이미 KYU_SESSION 이
	// 있어도(세션 안에서 kyu 를 실행한 경우) 지금 세션의 이름이 이긴다.
	environment := append(os.Environ(), insideSupervisorSessionEnvName+"="+sessionName)

	if term, isSet := os.LookupEnv("TERM"); !isSet || term == "" {
		environment = append(environment, "TERM="+fallbackTerm)
	}
	return environment
}

// serve 는 제어 연결을 받는다. 자식이 끝나거나 kill 을 받으면 돌아온다.
func (s *supervisorProcess) serve() error {
	defer s.ptyMaster.Close()

	go s.watchChild()
	go s.drainSessionOutput()

	for {
		connection, err := s.listener.Accept()
		if err != nil {
			// 자식이 끝나면 watchChild 가 리스너를 닫는다. 그 닫힘이 이 루프의 정상 종료다.
			select {
			case <-s.childExited:
				s.events.Printf("세션 종료 (종료 코드 %d)", s.childExitCode)
				return nil
			default:
				return fmt.Errorf("제어 연결 수신 실패 (세션 %s): %w", s.sessionName, err)
			}
		}

		if killRequested := s.handleControlConnection(connection); killRequested {
			s.events.Printf("세션 종료 (kill 요청, 종료 코드 %d)", s.childExitCode)
			return nil
		}
	}
}

// watchChild 는 자식을 거두고, 거둔 사실로 감독의 삶을 끝낸다.
//
// 감독은 자기 세션이 끝날 때 죽는다. 정할 자동 종료 정책이 없다(설계 문서 5.3).
func (s *supervisorProcess) watchChild() {
	s.childExitCode = exitCodeOf(s.childCommand.Wait())
	// 종료 코드를 먼저 적고 닫는다. 채널이 닫힌 뒤에 읽는 쪽은 적힌 값을 본다.
	close(s.childExited)

	// 자식이 없으면 이 감독의 존재 이유가 없다. 리스너를 닫아 accept 루프를 깨우고, 그 닫힘이
	// 소켓 파일까지 지운다. 정상 종료 경로가 소켓을 지우므로 죽은 소켓은 비정상 종료
	// (SIGKILL·크래시·재부팅)에서만 남는다(설계 문서 5.3).
	s.listener.Close()
}

// drainSessionOutput 은 PTY 마스터에 쌓이는 세션 출력을 계속 읽어 버린다.
//
// 아직 attach 가 없어 읽어갈 사람이 없다. 그렇다고 두면 커널 버퍼가 차는 순간 세션 안 프로그램의
// 쓰기가 막혀 세션이 통째로 멈춘다 — 조금 떠들썩한 명령 하나에 걸릴 함정이다.
//
// 이 자리가 나중에 스크롤백 링에 적으면서 붙어 있는 클라이언트에게 보내는 곳이 된다(설계 문서 5.7).
func (s *supervisorProcess) drainSessionOutput() {
	io.Copy(io.Discard, s.ptyMaster)
}

// handleControlConnection 은 요청 한 줄을 받아 한 줄로 답한다. kill 이었으면 true 를 돌려준다.
func (s *supervisorProcess) handleControlConnection(connection net.Conn) bool {
	defer connection.Close()

	if err := connection.SetDeadline(time.Now().Add(controlConnectionDeadline)); err != nil {
		s.events.Printf("제어 연결 마감 시각 설정 실패: %v", err)
		return false
	}

	var request controlRequest
	if err := json.NewDecoder(connection).Decode(&request); err != nil {
		s.events.Printf("제어 요청 해석 실패: %v", err)
		return false
	}

	if request.Version != controlProtocolVersion {
		s.respond(connection, controlResponse{
			Version: controlProtocolVersion,
			Code:    badProtocolVersionCode,
			Message: fmt.Sprintf("이 세션은 프로토콜 %d 판으로 떴습니다 (요청은 %d 판)",
				controlProtocolVersion, request.Version),
		})
		return false
	}

	switch request.Operation {
	case aliveOperation:
		// 감독이 살아있다는 것과 세션이 살아있다는 것이 같은 사실이므로, 답이 가는 것으로
		// 판정이 끝난다(설계 문서 5.4).
		s.respond(connection, controlResponse{Version: controlProtocolVersion, OK: true, Alive: true})
		return false

	case killOperation:
		s.terminateSession()

		// 소켓을 먼저 지우고 답한다. 답이 먼저 가면 Kill 이 돌아온 직후의 IsAlive 가 아직 남아
		// 있는 소켓에 붙어 true 로 답할 수 있다 — tmux 백엔드에는 없는 어긋남이다.
		// 리스너를 닫아도 이미 받아둔 이 연결은 살아 있으므로 답은 그대로 나간다.
		s.listener.Close()
		s.respond(connection, controlResponse{Version: controlProtocolVersion, OK: true})
		return true

	default:
		s.respond(connection, controlResponse{
			Version: controlProtocolVersion,
			Code:    unknownOperationCode,
			Message: fmt.Sprintf("모르는 연산입니다: %q", request.Operation),
		})
		return false
	}
}

// terminateSession 은 세션 안 프로그램을 끝낸다.
func (s *supervisorProcess) terminateSession() {
	select {
	case <-s.childExited:
		// 이미 끝나 거둬들였다. 거둔 pid 에 신호를 보내면 그 번호를 물려받은 남의 프로세스에 닿는다.
		return
	default:
	}

	// 자식의 프로세스 그룹 전체에 보낸다. 세션 안 프로그램이 자식을 띄워두었으면 리더만 죽여서는
	// 그 자식이 PTY 를 쥔 채 남는다(설계 문서 5.3).
	childProcessGroup := -s.childCommand.Process.Pid
	if err := syscall.Kill(childProcessGroup, syscall.SIGHUP); err != nil {
		s.events.Printf("세션에 SIGHUP 전달 실패: %v", err)
	}

	select {
	case <-s.childExited:
	case <-time.After(sessionTerminationGracePeriod):
		s.events.Printf("세션이 SIGHUP 을 받고도 %s 안에 끝나지 않아 SIGKILL 합니다", sessionTerminationGracePeriod)
		if err := syscall.Kill(childProcessGroup, syscall.SIGKILL); err != nil {
			s.events.Printf("세션에 SIGKILL 전달 실패: %v", err)
		}
		<-s.childExited
	}
}

func (s *supervisorProcess) respond(connection net.Conn, response controlResponse) {
	if err := json.NewEncoder(connection).Encode(response); err != nil {
		s.events.Printf("제어 응답 전송 실패: %v", err)
	}
}

func exitCodeOf(waitErr error) int {
	if waitErr == nil {
		return 0
	}
	var exitErr *exec.ExitError
	if errors.As(waitErr, &exitErr) {
		return exitErr.ExitCode()
	}
	return -1
}

// errSuperviseArguments 는 숨은 하위 명령의 인자가 틀렸을 때다.
//
// 사용자가 아니라 우리 코드가 만드는 인자라, 이 오류가 나면 띄우는 쪽이 잘못한 것이다.
var errSuperviseArguments = errors.New("__supervise 인자가 올바르지 않습니다")

// superviseCommandArgs 는 감독을 띄우는 인자를 만든다. parseSuperviseArguments 의 반대편이다.
func superviseCommandArgs(sessionName, workingDir string, command []string) []string {
	args := []string{SuperviseCommandName, "--name", sessionName, "--cwd", workingDir, "--"}
	return append(args, command...)
}

// parseSuperviseArguments 는 감독이 받은 인자를 읽는다.
//
// 명령 파싱에 프레임워크를 쓰지 않는 진입점의 방침을 여기서도 따른다 — 읽을 것이 셋뿐이다.
// `--` 뒤는 전부 세션이 실행할 명령이라 그 자리에서 파싱을 멈춘다. 그래야 세션 명령이 --name
// 같은 것을 자기 옵션으로 갖고 있어도 우리 것으로 오해하지 않는다.
func parseSuperviseArguments(args []string) (superviseOptions, error) {
	var options superviseOptions

	index := 0
	for index < len(args) {
		switch args[index] {
		case "--name":
			if index+1 >= len(args) {
				return options, fmt.Errorf("%w: --name 뒤에 세션 이름이 없습니다", errSuperviseArguments)
			}
			options.sessionName = args[index+1]
			index += 2
		case "--cwd":
			if index+1 >= len(args) {
				return options, fmt.Errorf("%w: --cwd 뒤에 경로가 없습니다", errSuperviseArguments)
			}
			options.workingDir = args[index+1]
			index += 2
		case "--":
			options.command = args[index+1:]
			index = len(args)
		default:
			return options, fmt.Errorf("%w: 모르는 인자 %q", errSuperviseArguments, args[index])
		}
	}

	if options.sessionName == "" {
		return options, fmt.Errorf("%w: --name 이 없습니다", errSuperviseArguments)
	}
	if options.workingDir == "" {
		return options, fmt.Errorf("%w: --cwd 가 없습니다", errSuperviseArguments)
	}
	if len(options.command) == 0 {
		return options, fmt.Errorf("%w: -- 뒤에 실행할 명령이 없습니다", errSuperviseArguments)
	}
	return options, nil
}
