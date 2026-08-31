package session

import (
	"bufio"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"log"
	"net"
	"os"
	"os/exec"
	"sync"
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

// sessionOutputChunkSize 는 PTY 마스터에서 한 번에 읽어 나르는 크기다.
//
// 붙어 있는 클라이언트에게 가는 OUTPUT 프레임 하나의 최대 크기이기도 하다. 크게 잡으면 프레임
// 수가 줄지만 사람이 타이핑하는 지연이 보이는 경로라 응답이 늦어 보이고, 작게 잡으면 빌드 로그
// 같은 쏟아지는 출력에서 프레임 머리말만 늘어난다.
const sessionOutputChunkSize = 32 * 1024

// 붙은 클라이언트가 없는 동안 세션이 갖는 화면 크기다.
//
// 크기가 0 이면 화면을 다 쓰는 프로그램이 그릴 자리가 없다고 보고 아무것도 그리지 않거나 0 으로
// 나눈다. tmux 도 떨어져 있는 세션에 80x24 를 주므로 그대로 따른다 — 의미론을 새로 정하지
// 않는다(설계 원칙 8).
const (
	defaultSessionColumns = 80
	defaultSessionRows    = 24
)

// sessionTerminationGracePeriod 는 SIGHUP 을 보내고 SIGKILL 까지 기다리는 유예다.
const sessionTerminationGracePeriod = 3 * time.Second

// socketProbeTimeout 은 소켓에 임자가 있는지 붙어보는 데 허용하는 시간이다.
//
// 붙는 데 성공하느냐만 보므로 짧다. 이 연결은 요청을 한 줄도 보내지 않는다.
const socketProbeTimeout = 2 * time.Second

// controlConnectionDeadline 은 감독이 제어 연결 하나에 쓰는 최대 시간이다.
//
// 제어 요청은 한 줄 주고받고 끝나므로 동시성을 들이지 않고 하나씩 처리한다. 대신 답하지 않는
// 클라이언트가 감독을 붙잡지 못하도록 마감 시각을 둔다. kill 은 자식이 끝나기를 기다린 뒤에
// 답하므로 유예보다 넉넉해야 한다.
//
// attach 연결도 첫 프레임까지는 이 마감 시각을 쓴다. 붙고 나면 푼다 — 사람이 다음 키를 누를
// 때까지의 사이는 얼마든 길 수 있다.
const controlConnectionDeadline = sessionTerminationGracePeriod + 7*time.Second

// errSessionNameAlreadyHeld 는 그 이름을 이미 다른 감독이 쥐고 있을 때다.
//
// 이름의 임자는 소켓 파일이 아니라 <이름>.lock 의 flock 이다. 잠금 획득이 곧 "같은 이름의
// 세션이 이미 있는가" 의 답이므로, 판정과 획득이 같은 한 번의 연산이라 그 사이에 낄 틈이
// 없다(설계 문서 5.3).
var errSessionNameAlreadyHeld = errors.New("이미 그 이름을 쥐고 있는 감독이 있습니다")

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
//  1. 이름 잠금을 잡는다  ← 이것이 배타성 판정이다
//  2. 소켓을 바인딩한다 (죽은 소켓은 잠금 아래에서 치운다)
//  3. PTY 쌍을 열고 자식을 실행한다
//  4. 준비 신호를 부모에게 보낸다
//  5. 서빙을 시작한다
//
// 1 번이 먼저인 이유는 잠금을 잡는 것이 곧 "이 이름의 세션은 내 것" 이라는 선언이기 때문이다.
// 실패하면 자식을 띄우기 전에 끝내야 한다 — 순서가 뒤집히면 중복 생성이 세션 안 프로그램을
// 하나 띄웠다 죽이는 일이 된다.
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
	case errors.Is(err, errSessionNameAlreadyHeld):
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
	nameLock     *os.File
	listener     *net.UnixListener
	childCommand *exec.Cmd
	ptyMaster    *os.File
	events       *log.Logger

	// scrollback 은 세션 출력이 지나는 유일한 길이다. 붙어 있는 클라이언트도 여기서 읽는다.
	scrollback *sessionScrollback

	// attachMutex 는 지금 붙어 있는 클라이언트를 지킨다. 한 번에 하나만 붙는다(설계 문서 5.6).
	attachMutex    sync.Mutex
	attachedClient *attachedClient

	// attachHandlers 는 붙어 있는 연결을 맡은 고루틴들이다. 세션이 끝날 때 그들이 EXIT 를
	// 전할 때까지 기다린다.
	attachHandlers sync.WaitGroup

	// childExited 는 자식을 거두면 닫힌다. childExitCode 는 그 뒤에만 읽는다.
	childExited   chan struct{}
	childExitCode int
}

// startSupervisor 는 이름을 잡고 소켓을 열고 자식을 띄운다 — 준비 신호 직전까지다.
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

	nameLock, err := holdSessionNameLock(paths.lock)
	if err != nil {
		return nil, err
	}

	listener, err := bindSessionSocket(paths)
	if err != nil {
		nameLock.Close()
		return nil, err
	}

	childCommand, ptyMaster, err := startSessionChild(options)
	if err != nil {
		// 리스너를 닫으면 소켓 파일도 함께 지워진다. 자식을 못 띄운 이름이 죽은 소켓으로 남으면,
		// 다음 Create 가 그것을 치우기 전까지 없는 세션이 있는 것처럼 보인다.
		listener.Close()
		nameLock.Close()
		return nil, err
	}

	return &supervisorProcess{
		sessionName:  options.sessionName,
		nameLock:     nameLock,
		listener:     listener,
		childCommand: childCommand,
		ptyMaster:    ptyMaster,
		events:       events,
		scrollback:   newSessionScrollback(scrollbackRingCapacity),
		childExited:  make(chan struct{}),
	}, nil
}

// holdSessionNameLock 은 세션 이름의 배타 잠금을 잡는다. 이 함수의 성공이 곧 "이 이름의 세션은 내 것" 이다.
//
// 감독은 이 잠금을 수명 내내 쥔다. 잠깐 쥐었다 놓으면 소켓이 아직 답하지 않는 기동 도중의
// 순간에 남이 그 이름을 죽은 것으로 보고 가져간다 — 붙어보는 것으로는 "아직 안 뜬 감독" 과
// "죽은 감독" 을 가를 수 없기 때문이다(설계 문서 5.3).
//
// 잠금은 열린 파일과 함께 살고 프로세스가 죽으면 커널이 푼다. 그래서 감독을 SIGKILL 해도
// 그 이름은 곧바로 다시 쓸 수 있고, 되돌리는 절차가 따로 필요 없다.
//
// 기다리지 않는다(LOCK_NB). 기다리면 중복 생성이 앞 세션이 끝날 때까지 멈춰 서고, 그것은
// ErrSessionExists 로 곧바로 답해야 하는 자리다.
//
// 잠금 파일을 지우지 않는다. 잠금을 쥔 채로 파일을 지우면 다음 사람이 같은 경로에 새로 만든
// 다른 inode 를 잠그게 되어, 둘이 서로 다른 잠금을 쥐고 같은 이름을 만진다. 0 바이트 파일
// 하나가 남는 값으로 그 어긋남을 없앤다.
func holdSessionNameLock(lockPath string) (*os.File, error) {
	lockFile, err := os.OpenFile(lockPath, os.O_CREATE|os.O_RDWR, sessionFilePermission)
	if err != nil {
		return nil, fmt.Errorf("세션 잠금 파일 열기 실패 (%s): %w", lockPath, err)
	}

	if err := syscall.Flock(int(lockFile.Fd()), syscall.LOCK_EX|syscall.LOCK_NB); err != nil {
		lockFile.Close()
		if errors.Is(err, syscall.EWOULDBLOCK) {
			return nil, fmt.Errorf("%w: %s", errSessionNameAlreadyHeld, lockPath)
		}
		return nil, fmt.Errorf("세션 잠금 실패 (%s): %w", lockPath, err)
	}
	return lockFile, nil
}

// bindSessionSocket 은 세션 소켓을 바인딩한다. 이름 잠금을 쥔 채로만 부른다.
//
// 잠금을 쥐고 있으므로 이 이름의 감독은 나뿐이다. 그러면 남아 있는 소켓 파일은 죽은 감독이
// 남긴 것이라, 치우기와 다시 바인딩하기 사이에 남이 끼어들 수 없다 — 잠금이 그 구간을
// 통째로 덮는다(설계 문서 5.3).
func bindSessionSocket(paths sessionRuntimePaths) (*net.UnixListener, error) {
	// 잠금을 쥐지 않는 옛 판 감독이 아직 이 이름으로 돌고 있을 수 있다. 그쪽은 소켓으로만
	// 자기 존재를 말하므로, 지우기 전에 임자가 있는지 한 번 붙어본다. 새 판끼리는 잠금에서
	// 이미 갈렸으니 이 확인에 걸리는 것은 옛 판뿐이다.
	bound, err := isSocketBound(paths.socket)
	if err != nil {
		return nil, err
	}
	if bound {
		return nil, fmt.Errorf("%w: %s", errSessionNameAlreadyHeld, paths.socket)
	}

	if err := os.Remove(paths.socket); err != nil && !errors.Is(err, fs.ErrNotExist) {
		return nil, fmt.Errorf("죽은 세션 소켓 정리 실패 (%s): %w", paths.socket, err)
	}

	return listenOnSessionSocket(paths.socket)
}

// isSocketBound 는 그 경로의 소켓을 지금 누군가 듣고 있는지 본다.
//
// 연결이 되면 임자가 있는 것이고, ECONNREFUSED 면 임자 없는 파일만 남은 것이다.
// 파일이 아예 없으면(ENOENT) 역시 임자가 없다.
func isSocketBound(socketPath string) (bool, error) {
	connection, err := net.DialTimeout("unix", socketPath, socketProbeTimeout)
	if err == nil {
		connection.Close()
		return true, nil
	}
	if errors.Is(err, fs.ErrNotExist) || errors.Is(err, syscall.ECONNREFUSED) {
		return false, nil
	}
	return false, fmt.Errorf("세션 소켓 상태 확인 실패 (%s): %w", socketPath, err)
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

	// pty.StartWithSize 는 자식을 setsid + setctty 로 띄우고 PTY 슬레이브를 그 제어 터미널로
	// 삼는다. 그래서 자식의 프로세스 그룹 번호가 자기 pid 와 같아지고, 종료할 때 그룹 전체에
	// 신호를 보낼 수 있다(설계 문서 5.3).
	//
	// 크기를 함께 준다. 아무도 붙지 않은 채로 뜨는 세션이라 크기를 정해주지 않으면 0x0 이 되고,
	// 화면을 다 쓰는 프로그램은 그 크기로는 그리지 못한다.
	ptyMaster, err := pty.StartWithSize(childCommand, &pty.Winsize{
		Cols: defaultSessionColumns,
		Rows: defaultSessionRows,
	})
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
	// 이름 잠금을 가장 나중에 놓는다(defer 는 역순이다). 소켓이 사라진 뒤에 놓아야, 다음 감독이
	// 잠금을 잡은 순간 그 이름 자리에 남의 소켓이 없다.
	defer s.nameLock.Close()
	defer s.ptyMaster.Close()
	// 붙어 있는 클라이언트가 EXIT 를 전해 받을 때까지 기다린다(가장 먼저 도는 defer 다).
	// 기다리지 않으면 세션이 끝났다는 사실이 프로세스 종료에 잘려 클라이언트는 "연결이 끊겼다"
	// 만 보게 된다 — 그 둘은 사용자에게 다른 말을 해야 하는 사건이다(설계 문서 5.6).
	defer s.attachHandlers.Wait()

	go s.watchChild()
	go s.pumpSessionOutputIntoScrollback()

	for {
		connection, err := s.listener.Accept()
		if err != nil {
			// 자식이 끝나면 watchChild 가 리스너를 닫는다. 그 닫힘이 이 루프의 정상 종료다.
			select {
			case <-s.childExited:
				s.events.Printf("세션 종료 (종료 코드 %d)", s.childExitCode)
				return nil
			default:
				return fmt.Errorf("연결 수신 실패 (세션 %s): %w", s.sessionName, err)
			}
		}

		if killRequested := s.handleAcceptedConnection(connection); killRequested {
			s.events.Printf("세션 종료 (kill 요청, 종료 코드 %d)", s.childExitCode)
			return nil
		}
	}
}

// handleAcceptedConnection 은 받은 연결이 제어인지 attach 인지 가른다.
//
// 첫 바이트 하나로 갈린다 — ATTACH 프레임의 종류 바이트(0x01)이면 그 연결은 그때부터 그 세션의
// 스트림이고(설계 문서 5.6), 아니면 NDJSON 제어 요청이다(`{` 는 0x7b). 엿보기만 하므로 그
// 바이트는 소비되지 않고 뒤쪽 해석기가 처음부터 읽는다.
//
// 제어 연결은 여기서 하나씩 처리하고 attach 만 고루틴으로 넘긴다. attach 는 세션이 사는 내내
// 유지되는 연결이라 이 루프에 두면 그 뒤로 아무 조회도 받지 못한다.
func (s *supervisorProcess) handleAcceptedConnection(connection net.Conn) bool {
	if err := connection.SetDeadline(time.Now().Add(controlConnectionDeadline)); err != nil {
		s.events.Printf("연결 마감 시각 설정 실패: %v", err)
		connection.Close()
		return false
	}

	reader := bufio.NewReader(connection)
	firstByte, err := reader.Peek(1)
	if err != nil {
		// 한 바이트도 오지 않은 채 끝난 연결은 임자가 있는지 붙어본 것이다. 죽은 소켓과 살아있는
		// 감독을 가르는 그 판정이 정확히 이 모양이라(bindSessionSocket 의 isSocketBound) 실패로
		// 적지 않는다.
		if !errors.Is(err, io.EOF) {
			s.events.Printf("연결의 첫 바이트 읽기 실패: %v", err)
		}
		connection.Close()
		return false
	}

	if firstByte[0] == attachFrameType {
		s.attachHandlers.Add(1)
		go func() {
			defer s.attachHandlers.Done()
			s.serveAttachConnection(connection, reader)
		}()
		return false
	}

	defer connection.Close()
	return s.handleControlRequest(connection, reader)
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

// pumpSessionOutputIntoScrollback 은 PTY 마스터에 쌓이는 세션 출력을 링으로 옮긴다.
//
// 붙어 있는 사람이 없어도 계속 읽어야 한다. 그대로 두면 커널 버퍼가 차는 순간 세션 안 프로그램의
// 쓰기가 막혀 세션이 통째로 멈춘다 — 조금 떠들썩한 명령 하나에 걸릴 함정이다.
//
// 붙어 있는 클라이언트에게 여기서 직접 보내지 않는다. 클라이언트도 같은 링에서 읽으므로 재생과
// 실시간이 한 경로가 되고, 그 둘이 만나는 경계 자체가 없어진다(설계 문서 5.7).
func (s *supervisorProcess) pumpSessionOutputIntoScrollback() {
	// 마스터가 EOF 를 주면 더 올 바이트가 없다. 링에서 기다리는 클라이언트를 깨워 끝을 알린다.
	defer s.scrollback.finish()

	buffer := make([]byte, sessionOutputChunkSize)
	for {
		read, err := s.ptyMaster.Read(buffer)
		if read > 0 {
			s.scrollback.append(buffer[:read])
		}
		if err != nil {
			return
		}
	}
}

// handleControlRequest 는 요청 한 줄을 받아 한 줄로 답한다. kill 이었으면 true 를 돌려준다.
//
// 연결의 마감 시각과 닫기는 부르는 쪽이 맡는다 — 그쪽이 이 연결이 제어인지 attach 인지 가르는
// 자리라 두 갈래에 같은 규칙을 한 번만 적을 수 있다.
func (s *supervisorProcess) handleControlRequest(connection net.Conn, reader io.Reader) bool {
	var request controlRequest
	if err := json.NewDecoder(reader).Decode(&request); err != nil {
		// 요청을 쓰다 만 연결이다. 한 바이트도 보내지 않은 임자 확인 연결은 첫 바이트를 엿보는
		// 자리에서 이미 갈렸으므로 여기 오지 않는다.
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
