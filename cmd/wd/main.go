// Command wd 는 여러 레포가 모인 워크디렉토리를 조율하는 CLI 다.
//
// 이 파일은 얇게 유지한다 — 인자를 명령으로 가르고, 세션 백엔드를 조립하고, 종료 코드를 정하는 것까지다.
// 명령이 실제로 하는 일은 internal/cli 에 있다(설계 문서 9.1).
//
// 명령 파싱에 외부 프레임워크를 쓰지 않는다. 서브커맨드가 다섯 개뿐이라 switch 한 번이면 끝나고,
// 의존을 0 으로 두면 새 머신에서 바이너리 하나 복사로 끝난다는 Go 선택의 이유(설계 문서 8.1)가 유지된다.
package main

import (
	"fmt"
	"io"
	"os"

	"github.com/maximinhan/Kyuchestration/internal/cli"
	"github.com/maximinhan/Kyuchestration/internal/session"
)

// usageText 는 설계 문서 9.3 의 명령 다섯 개를 그대로 싣는다.
//
// 아직 동작하지 않는 명령까지 적는 이유는, 이 도구가 무엇을 하는 도구인지가 사용법 한 화면에서
// 드러나야 하기 때문이다. 라우팅은 미구현 명령을 따로 안내하므로 사용자가 헛물을 켜지는 않는다.
const usageText = `사용법: wd <명령> [인자]

  wd init [name]        워크디렉토리 초기화 (.coord/plan.md 생성)
  wd list [path]        레포 목록 + 상태 (기본 명령, 인자 없이 실행 시)
  wd start [repo]       세션 시작. 인자 없으면 main
  wd attach <repo>      세션 진입. main 도 가능
  wd kill [repo|--all]  세션 종료`

// defaultCommandName 은 인자 없이 실행했을 때의 명령이다(설계 문서 9.3).
// 이 도구 앞에서 가장 자주 하는 일이 "지금 어떻게 돼 있지?" 라서 목록이 기본이다.
const defaultCommandName = "list"

func main() {
	if err := runCommand(os.Args[1:], os.Stdout); err != nil {
		// 실패 안내는 stderr 로 보낸다. stdout 은 목록처럼 다른 명령의 입력으로 넘길 수 있는 것만 쓴다.
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}

// runCommand 는 인자를 명령으로 갈라 실행한다. 에러를 반환하면 그것이 곧 종료 코드 1 이다.
func runCommand(args []string, out io.Writer) error {
	commandName := defaultCommandName
	var commandArgs []string
	if len(args) > 0 {
		commandName, commandArgs = args[0], args[1:]
	}

	switch commandName {
	case "list":
		return runWithSessionBackend(out, commandArgs, cli.ListWorkDir)
	case "start":
		return runWithSessionBackend(out, commandArgs, cli.StartSession)
	case "attach":
		return runWithSessionBackend(out, commandArgs, cli.AttachSession)
	case "kill":
		return runWithSessionBackend(out, commandArgs, cli.KillSessions)

	// 아직 없는 명령을 빈 함수로 만들어두지 않는다. 호출하면 아무 일도 하지 않고 성공한 것처럼 끝나는
	// 자리가 생기고, 그 자리는 다음 PR 이 채우기 전까지 사용자를 속인다.
	case "init":
		return fmt.Errorf("아직 구현되지 않은 명령입니다: %s (PR 7 예정)", commandName)

	default:
		return fmt.Errorf("알 수 없는 명령: %s\n\n%s", commandName, usageText)
	}
}

// sessionCommand 는 세션 백엔드를 받아 동작하는 명령 하나다. 네 명령이 모두 같은 모양이다.
type sessionCommand func(out io.Writer, args []string, backend session.SessionBackend) error

// runWithSessionBackend 는 명령에 세션 백엔드를 조립해 넘긴다.
//
// 백엔드를 여기서 만드는 이유: 어느 플랫폼 구현을 쓸지는 진입점의 결정이다.
// 그래야 internal/cli 가 tmux 를 모른 채로 남고, 백엔드가 늘어날 때 바뀌는 파일이 이 하나로 끝난다.
//
// 조립을 명령마다 되풀이하지 않고 한 곳으로 모은다. tmux 미설치 안내처럼 모든 명령이 똑같이
// 해야 하는 일이 여기 있으므로, 새 명령이 그것을 빠뜨릴 자리 자체가 없다.
func runWithSessionBackend(out io.Writer, args []string, command sessionCommand) error {
	backend, err := session.NewTmuxBackend()
	if err != nil {
		return err
	}
	return command(out, args, backend)
}
