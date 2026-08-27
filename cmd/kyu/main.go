// Command kyu 는 여러 레포가 모인 워크디렉토리를 조율하는 CLI 다.
//
// 이 파일은 얇게 유지한다 — 인자를 명령으로 가르고, 세션 백엔드를 조립하고, 종료 코드를 정하는 것까지다.
// 명령이 실제로 하는 일은 internal/cli 에 있다(설계 문서 9.1).
//
// 명령 파싱에 외부 프레임워크를 쓰지 않는다. 서브커맨드가 여덟 개뿐이라 switch 한 번이면 끝나고,
// 파싱 프레임워크를 들이지 않는 한 릴리스는 새 머신에서 바이너리 하나 복사로 끝난다(설계 문서 8.1).
package main

import (
	"fmt"
	"io"
	"os"
	"strings"

	"github.com/maximinhan/Kyuchestration/internal/cli"
	"github.com/maximinhan/Kyuchestration/internal/github"
	"github.com/maximinhan/Kyuchestration/internal/secretstore"
	"github.com/maximinhan/Kyuchestration/internal/session"
)

// usageText 는 이 도구의 명령 전부다(설계 문서 9.3).
//
// 이 도구가 무엇을 하는 도구인지가 이 한 화면에서 드러나야 하므로, 명령 목록을 여기 한 곳에만 둔다.
// 명령별 자세한 사용법은 각 명령이 자기 거절 메시지에 함께 싣는다.
const usageText = `사용법: kyu [명령] [인자]

  kyu                    이 디렉토리에서 작업 시작 — 초기화·메인 세션 생성·진입까지 한 번에

  kyu init [name]        워크디렉토리 초기화 (.coord/plan.md 생성)
  kyu clone              GitHub 레포 목록에서 골라 이 디렉토리에 클론
  kyu list [path]        레포 목록 + 상태
  kyu start [repo]       세션 시작. 인자 없으면 main
  kyu attach <repo>      세션 진입. main 도 가능
  kyu kill [repo|--all]  세션 종료
  kyu auth <list|remove> 저장한 GitHub 토큰 프로필 관리
  kyu version            이 바이너리의 버전

옵션 (kyu, kyu start):
  --bypass-permissions   claude 를 권한 확인 없이 띄운다 — 신뢰하는 워크디렉토리에서만
  --repo-claude-md       메인 세션이 각 레포의 CLAUDE.md 까지 읽는다 (kyu start 전용)`

func main() {
	if err := runCommand(os.Args[1:], os.Stdin, os.Stdout, os.Stderr); err != nil {
		// 실패 안내는 stderr 로 보낸다. stdout 은 목록처럼 다른 명령의 입력으로 넘길 수 있는 것만 쓴다.
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}

// runCommand 는 인자를 명령으로 갈라 실행한다. 에러를 반환하면 그것이 곧 종료 코드 1 이다.
//
// 두 출력 스트림을 모두 받는다. 명령이 내는 말에는 다른 명령의 입력으로 넘길 수 있는 것(out)과
// 사람에게만 하는 말(errOut)이 섞여 있고, 그 구분은 명령마다 다르다.
//
// 입력도 받는다. kyu clone 은 처음부터 끝까지 대화라, 어디서 답을 읽을지가 진입점의 결정이어야
// 표시 계층이 os.Stdin 을 직접 붙들지 않는다.
func runCommand(args []string, in io.Reader, out, errOut io.Writer) error {
	// 인자 없는 kyu 는 서브커맨드의 기본값이 아니라 그 자체로 하나의 명령이다 — 이 도구를 여는 문이다.
	// 목록을 기본으로 두던 때(설계 문서 9.3)와 달라진 자리이고, 그 결정의 근거는 사용자가 이 도구
	// 앞에서 실제로 하는 일이 "상태를 본다" 가 아니라 "여기서 작업을 시작한다" 였다는 것이다.
	//
	// 옵션으로 시작하는 인자도 이 문으로 보낸다. 진입 명령이 옵션을 받게 된 뒤로는 첫 인자가
	// - 로 시작하는 실행이 "알 수 없는 명령: --bypass-permissions" 로 끝나서는 안 된다.
	// 서브커맨드 이름은 - 로 시작하지 않으므로 이 갈림에 걸릴 명령은 없다.
	if len(args) == 0 || strings.HasPrefix(args[0], "-") {
		return withSessionBackend(func(backend session.SessionBackend) error {
			return cli.EnterWorkDir(out, errOut, args, backend)
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
			return cli.StartSession(out, errOut, commandArgs, backend)
		})
	case "attach":
		return withSessionBackend(func(backend session.SessionBackend) error {
			return cli.AttachSession(out, commandArgs, backend)
		})
	case "kill":
		return withSessionBackend(func(backend session.SessionBackend) error {
			return cli.KillSessions(out, commandArgs, backend)
		})

	// clone 은 세션 백엔드를 거친다. 클론이 끝난 뒤 "떠 있는 메인 세션은 새 레포를 보지 못한다" 를
	// 알리려면 세션 생존을 물어야 하기 때문이다. tmux 가 없으면 애초에 그 세션도 없지만, 그 사실을
	// 알기 위해 빈 백엔드 구현을 하나 더 두는 것은 이 안내 한 줄에 비해 큰 장치다.
	case "clone":
		return withSessionBackend(func(backend session.SessionBackend) error {
			return withTokenStore(func(tokenStore secretstore.TokenStore) error {
				return cli.CloneRepos(in, out, errOut, commandArgs, newGitHubAccess, tokenStore, backend)
			})
		})

	// auth 는 세션 백엔드를 거치지 않는다. 저장해둔 토큰을 보고 지우는 일이라 tmux 와 무관하고,
	// 백엔드를 먼저 조립하면 tmux 가 없는 머신에서 자기 토큰 목록조차 볼 수 없게 된다.
	case "auth":
		return withTokenStore(func(tokenStore secretstore.TokenStore) error {
			return cli.ManageTokenProfiles(out, commandArgs, tokenStore)
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
// 명령의 시그니처를 하나로 못박지 않고 클로저를 받는다. 진입·list·start 는 stderr 까지 쓰지만
// attach·kill 은 그렇지 않은데, 공통 시그니처를 강요하면 두 명령이 쓰지도 않는 인자를 받게 된다.
func withSessionBackend(command func(session.SessionBackend) error) error {
	backend, err := session.NewTmuxBackend()
	if err != nil {
		return err
	}
	return command(backend)
}

// withTokenStore 는 명령에 토큰 저장소를 조립해 넘긴다.
//
// 세션 백엔드와 같은 이유로 여기서 만든다 — 키체인을 쓸지 파일에 쓸지는 이 머신을 보고 정하는
// 진입점의 결정이고, 표시 계층이 그것을 알면 저장 방식이 늘어날 때마다 함께 바뀐다.
func withTokenStore(command func(secretstore.TokenStore) error) error {
	tokenStore, err := secretstore.NewTokenStore()
	if err != nil {
		return err
	}
	return command(tokenStore)
}

// newGitHubAccess 는 토큰 하나를 GitHub 접근 경로로 바꾼다.
//
// 이 함수가 있어야 표시 계층이 github.TokenAccess 라는 구현을 모른 채로 남는다. 어느 구현을
// 쓸지는 세션 백엔드와 마찬가지로 진입점의 결정이다.
func newGitHubAccess(token string) github.RepositoryAccess {
	return github.NewTokenAccess(token)
}
