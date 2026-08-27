// Command kyu 는 여러 레포가 모인 워크디렉토리를 조율하는 CLI 다.
//
// 이 파일은 얇게 유지한다 — 인자를 명령으로 가르고, 세션 백엔드를 조립하고, 종료 코드를 정하는 것까지다.
// 명령이 실제로 하는 일은 internal/cli 에 있다(설계 문서 9.1).
//
// 명령 파싱에 외부 프레임워크를 쓰지 않는다. 서브커맨드가 여섯 개뿐이라 switch 한 번이면 끝나고,
// 의존을 0 으로 두면 새 머신에서 바이너리 하나 복사로 끝난다는 Go 선택의 이유(설계 문서 8.1)가 유지된다.
package main

import (
	"fmt"
	"io"
	"os"

	"github.com/maximinhan/Kyuchestration/internal/cli"
	"github.com/maximinhan/Kyuchestration/internal/session"
)

// usageText 는 이 도구의 명령 전부다(설계 문서 9.3).
//
// 이 도구가 무엇을 하는 도구인지가 이 한 화면에서 드러나야 하므로, 명령 목록을 여기 한 곳에만 둔다.
// 명령별 자세한 사용법은 각 명령이 자기 거절 메시지에 함께 싣는다.
const usageText = `사용법: kyu [명령] [인자]

  kyu                    이 디렉토리에서 작업 시작 — 초기화·메인 세션 생성·진입까지 한 번에

  kyu init [name]        워크디렉토리 초기화 (.coord/plan.md 생성)
  kyu list [path]        레포 목록 + 상태
  kyu start [repo]       세션 시작. 인자 없으면 main
  kyu attach <repo>      세션 진입. main 도 가능
  kyu kill [repo|--all]  세션 종료
  kyu version            이 바이너리의 버전`

func main() {
	if err := runCommand(os.Args[1:], os.Stdout, os.Stderr); err != nil {
		// 실패 안내는 stderr 로 보낸다. stdout 은 목록처럼 다른 명령의 입력으로 넘길 수 있는 것만 쓴다.
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}

// runCommand 는 인자를 명령으로 갈라 실행한다. 에러를 반환하면 그것이 곧 종료 코드 1 이다.
//
// 두 스트림을 모두 받는다. 명령이 내는 말에는 다른 명령의 입력으로 넘길 수 있는 것(out)과
// 사람에게만 하는 말(errOut)이 섞여 있고, 그 구분은 명령마다 다르다.
func runCommand(args []string, out, errOut io.Writer) error {
	// 인자 없는 kyu 는 서브커맨드의 기본값이 아니라 그 자체로 하나의 명령이다 — 이 도구를 여는 문이다.
	// 목록을 기본으로 두던 때(설계 문서 9.3)와 달라진 자리이고, 그 결정의 근거는 사용자가 이 도구
	// 앞에서 실제로 하는 일이 "상태를 본다" 가 아니라 "여기서 작업을 시작한다" 였다는 것이다.
	if len(args) == 0 {
		return withSessionBackend(func(backend session.SessionBackend) error {
			return cli.EnterWorkDir(out, errOut, backend)
		})
	}

	commandName, commandArgs := args[0], args[1:]

	switch commandName {
	case "list":
		return withSessionBackend(func(backend session.SessionBackend) error {
			return cli.ListWorkDir(out, errOut, commandArgs, backend)
		})
	case "start":
		return withSessionBackend(func(backend session.SessionBackend) error {
			return cli.StartSession(out, commandArgs, backend)
		})
	case "attach":
		return withSessionBackend(func(backend session.SessionBackend) error {
			return cli.AttachSession(out, commandArgs, backend)
		})
	case "kill":
		return withSessionBackend(func(backend session.SessionBackend) error {
			return cli.KillSessions(out, commandArgs, backend)
		})

	// init 과 version 은 세션 백엔드를 거치지 않는다. 초기화는 파일을 만드는 일이고 버전은
	// 이 바이너리 자신에 대한 질문이라 둘 다 tmux 가 필요 없는데, 백엔드를 먼저 조립하면
	// tmux 가 없는 머신에서 워크디렉토리를 만들지도, 무엇을 받았는지 확인하지도 못하게 된다.
	// 릴리스 바이너리가 tmux 를 아직 깔지 않은 새 머신에 먼저 도착하는 것이 오히려 흔한 순서다.
	case "init":
		return cli.InitWorkDir(out, commandArgs)

	case "version":
		return cli.PrintVersion(out, commandArgs)

	default:
		return fmt.Errorf("알 수 없는 명령: %s\n\n%s", commandName, usageText)
	}
}

// withSessionBackend 는 명령에 세션 백엔드를 조립해 넘긴다.
//
// 백엔드를 여기서 만드는 이유: 어느 플랫폼 구현을 쓸지는 진입점의 결정이다.
// 그래야 internal/cli 가 tmux 를 모른 채로 남고, 백엔드가 늘어날 때 바뀌는 파일이 이 하나로 끝난다.
//
// 조립을 명령마다 되풀이하지 않고 한 곳으로 모은다. tmux 미설치 안내처럼 모든 명령이 똑같이
// 해야 하는 일이 여기 있으므로, 새 명령이 그것을 빠뜨릴 자리 자체가 없다.
//
// 명령의 시그니처를 하나로 못박지 않고 클로저를 받는다. list 는 stderr 까지 쓰지만 나머지는
// 그렇지 않은데, 공통 시그니처를 강요하면 세 명령이 쓰지도 않는 인자를 받게 된다.
func withSessionBackend(command func(session.SessionBackend) error) error {
	backend, err := session.NewTmuxBackend()
	if err != nil {
		return err
	}
	return command(backend)
}
