// Command kyu 는 여러 레포가 모인 워크디렉토리를 조율하는 CLI 다.
//
// 이 파일은 얇게 유지한다 — 인자를 명령으로 가르고, 명령이 쓸 것을 조립하고, 종료 코드를 정하는 것까지다.
// 명령이 실제로 하는 일은 internal/cli 에 있다(설계 문서 9.1).
//
// 명령 파싱에 외부 프레임워크를 쓰지 않는다. 서브커맨드가 열 개 남짓이라 switch 한 번이면 끝나고,
// 파싱 프레임워크를 들이지 않는 한 릴리스는 새 머신에서 바이너리 하나 복사로 끝난다(설계 문서 8.1).
package main

import (
	"errors"
	"fmt"
	"io"
	"os"

	"github.com/maximinhan/Kyuchestration/internal/cli"
	"github.com/maximinhan/Kyuchestration/internal/github"
	"github.com/maximinhan/Kyuchestration/internal/secretstore"
	"github.com/maximinhan/Kyuchestration/internal/session"
)

// usageText 는 이 도구의 명령 전부다(설계 문서 9.3).
//
// 이 도구가 무엇을 하는 도구인지가 이 한 화면에서 드러나야 하므로, 명령 목록을 여기 한 곳에만 둔다.
// 명령별 자세한 사용법은 각 명령이 자기 거절 메시지에 함께 싣는다.
const usageText = `사용법: kyu <명령> [인자]

  kyu init [name]        워크디렉토리 초기화 (.coord/plan.md 생성)
  kyu clone [옵션]       GitHub 레포 목록에서 화살표로 골라 이 디렉토리에 클론
  kyu list [path]        레포 목록 + 상태
  kyu repos <owners|list>
                         GitHub 의 소유자·레포 목록 — 기계용 (--json 전용)
  kyu session-command [repo]
                         세션이 실행할 명령·cwd·환경 — 기계용 (--json 전용)
  kyu auth <add|list|remove>
                         저장한 GitHub 토큰 프로필 관리 (add 는 토큰을 stdin 으로 받는다)
  kyu version            이 바이너리의 버전

옵션 (kyu session-command):
  --bypass-permissions   claude 를 권한 확인 없이 띄운다 — 신뢰하는 워크디렉토리에서만
  --repo-claude-md       메인 세션이 각 레포의 CLAUDE.md 까지 읽는다 (메인 세션 전용)
  --forget-conversation  적혀 있는 대화를 버리고 새 대화로 답한다

옵션 (kyu clone):
  --profile <이름>       어느 토큰으로 붙을지 — 묻지 않는 클론에 필요
  --repo <owner/name>    묻지 않고 클론할 레포. 여러 번 적을 수 있다

옵션 (kyu list, kyu clone, kyu repos, kyu session-command, kyu auth add, kyu auth list):
  --json                 사람용 출력 대신 기계용 JSON 을 낸다 (GUI·스크립트 연동용)

kyu 는 엔진이다 — 세션을 열고 화면을 그리는 것은 데스크톱 앱의 몫이다.
세션을 만들고 붙고 죽이던 명령(start · attach · kill)과 인자 없는 진입은 은퇴했다
(app-owned-sessions-design.md 6절).`

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
	// 인자 없는 kyu 는 사용법으로 끝난다. 목록으로 보내지 않는 이유는 그것이 이 도구가 하는
	// 일을 대표하지 않아서다 — kyu list 는 앱과 스크립트를 위한 읽기 표면이고, 사람이 이 도구
	// 앞에서 하려던 일은 이제 앱에서 한다. 아무 디렉토리에서 친 kyu 가 그 디렉토리를 훑어
	// 목록을 내면 "여기가 워크디렉토리다" 라고 답한 셈이 되는데, 그것을 정하는 것은 앱이다.
	//
	// 진입 명령이 이 자리에 있던 때와 달리 - 로 시작하는 인자를 따로 받지 않는다. 남은 명령은
	// 모두 이름으로 시작하므로, 옵션만 적힌 실행은 "알 수 없는 명령" 으로 끝나는 것이 맞다.
	if len(args) == 0 {
		return errors.New(usageText)
	}

	commandName, commandArgs := args[0], args[1:]

	switch commandName {
	// list 는 세션 백엔드를 거치지 않는다. 무엇이 떠 있는지를 묻던 명령이었지만 이제는 레포와
	// git 만 보므로(app-owned-sessions-design.md 6절), 백엔드를 먼저 조립하면 tmux 없는 머신에서
	// 대시보드가 3 초마다 부르는 명령이 통째로 선다.
	case "list":
		return cli.ListWorkDir(out, errOut, commandArgs)

	// clone 은 GitHub 에 붙어 레포를 받아 오는 일이라 세션과 무관하다. 클론이 끝난 뒤 떠 있는
	// 메인 세션에 새 레포가 닿는지를 묻던 걸음이 있었지만, 그 세션은 이제 앱의 것이라 엔진이
	// 물을 상대가 없다(app-owned-sessions-design.md 6절).
	case "clone":
		return withTokenStore(func(tokenStore secretstore.TokenStore) error {
			return cli.CloneRepos(in, out, errOut, commandArgs, newGitHubAccess, tokenStore)
		})

	// repos 도 GitHub 에 무엇이 있는지 묻기만 하는 명령이라 워크디렉토리를 보지 않는다 —
	// 앱이 아직 워크디렉토리를 만들기 전에 부르는 자리다.
	case "repos":
		return withTokenStore(func(tokenStore secretstore.TokenStore) error {
			return cli.BrowseGitHubRepositories(out, errOut, commandArgs, newGitHubAccess, tokenStore)
		})

	// session-command 는 세션을 띄우지 않고 무엇을 띄울지 답한다. 앱이 보유할 세션은 엔진이
	// 만들거나 죽이거나 셀 수 있는 것이 아니다(설계 문서 5.1).
	case "session-command":
		return cli.AnswerSessionCommand(out, errOut, commandArgs)

	// auth 는 토큰을 등록하고 보고 지우는 일이다.
	case "auth":
		return withTokenStore(func(tokenStore secretstore.TokenStore) error {
			return cli.ManageTokenProfiles(in, out, errOut, commandArgs, newGitHubAccess, tokenStore)
		})

	case "init":
		return cli.InitWorkDir(out, commandArgs)

	case "version":
		return cli.PrintVersion(out, commandArgs)

	// 감독 프로세스의 본문이다. 세션 백엔드를 조립하지 않는다 — 이 프로세스가 그 백엔드가
	// 말을 거는 상대이지 그것을 쓰는 쪽이 아니다. 사용자가 부를 명령이 아니라 usage 에도 없다.
	case session.SuperviseCommandName:
		return session.RunSupervisor(commandArgs)

	default:
		return fmt.Errorf("알 수 없는 명령: %s\n\n%s", commandName, usageText)
	}
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
