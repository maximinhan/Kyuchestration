package cli

import (
	"errors"
	"fmt"
	"io"
	"slices"
	"strings"

	"github.com/maximinhan/Kyuchestration/internal/session"
	"github.com/maximinhan/Kyuchestration/internal/workdir"
)

// sessionCommandName 은 세션 안에서 실행할 명령이다.
//
// v1 은 하드코딩한다. 설정으로 빼려면 어디에 두고 어떤 형식으로 읽고 인자를 어떻게 쪼갤지까지
// 정해야 하는데, 지금 이 도구를 쓰는 사람이 세션에서 띄우는 명령은 하나뿐이다.
// 두 번째 명령이 실제로 필요해지면 그때의 요구를 보고 정한다.
const sessionCommandName = "claude"

// addDirFlagName 은 메인 세션에 하위 레포를 파일 접근으로 붙이는 플래그다(설계 문서 5.4).
const addDirFlagName = "--add-dir"

// repoClaudeMdOptionName 은 메인 세션이 각 레포의 지침 파일까지 읽게 하는 옵션이다.
const repoClaudeMdOptionName = "--repo-claude-md"

// bypassPermissionsOptionName 은 세션의 claude 를 권한 확인 없이 띄우는 옵션이다.
//
// claude 의 원래 플래그 이름을 그대로 노출하지 않는다. 이 도구의 사용자가 정하는 것은
// "이 세션에서 권한 확인을 생략한다" 이고, 그것을 claude 의 어느 플래그로 옮길지는 이 도구의 사정이다.
const bypassPermissionsOptionName = "--bypass-permissions"

// skipPermissionsFlagName 은 위 옵션이 claude 에게 전달되는 플래그다.
const skipPermissionsFlagName = "--dangerously-skip-permissions"

// repoClaudeMdEnvName 과 repoClaudeMdEnvValue 는 추가 디렉토리의 CLAUDE.md 와 .claude/rules/ 를
// 로드시키는 환경변수다.
//
// 기본으로 켜지 않는다. 붙인 레포 수만큼 컨텍스트가 늘어나므로, 계획이 레포 컨벤션과 어긋나는
// 문제가 실제로 생겼을 때만 켜는 것이 설계의 판단이다(설계 문서 5.4).
//
// 이름과 값을 갈라 둔다. 이것을 얹는 두 자리가 서로 다른 모양을 요구하기 때문이다 — CLI 는
// 명령을 env NAME=VALUE 로 감싸고, kyu session-command 는 환경을 객체로 답한다(설계 문서 5.2).
// 붙여둔 한 문자열로 두면 뒤엣것이 = 를 다시 쪼개야 하고, 그 쪼개기가 이름에 = 가 없다는 전제를 또 만든다.
const (
	repoClaudeMdEnvName  = "CLAUDE_CODE_ADDITIONAL_DIRECTORIES_CLAUDE_MD"
	repoClaudeMdEnvValue = "1"
)

// envCommandName 은 환경변수를 얹어 다른 명령을 실행하는 POSIX 표준 명령이다.
//
// SessionBackend.Create 에 환경변수 인자를 새로 뚫는 대신 명령을 이것으로 감싼다.
// 세션 계층은 이 도구의 유일한 플랫폼 의존부라, 인터페이스를 넓힐수록 새 플랫폼 구현이 따라 해야
// 할 것이 늘어난다(설계 문서 5.2 의 "플랫폼 의존부는 5개 함수가 전부다").
const envCommandName = "env"

const startUsageText = `사용법: kyu start [repo] [옵션]

  kyu start                워크디렉토리 최상위에서 메인 세션을 띄운다
  kyu start <repo>         해당 레포 디렉토리에서 세션을 띄운다

옵션:
  --bypass-permissions     claude 를 권한 확인 없이 띄운다 — 신뢰하는 워크디렉토리에서만
  --repo-claude-md         메인 세션이 각 레포의 CLAUDE.md 까지 읽게 한다 (메인 세션 전용)`

// StartSession 은 kyu start 를 실행한다. 인자가 없으면 메인 세션을, 레포 이름이 있으면 그 레포의 세션을 띄운다.
//
// 대상 워크디렉토리는 명령을 실행한 디렉토리다. list 처럼 경로 인자를 받지 않는 이유는,
// 세션을 띄우는 일은 그 워크디렉토리에서 작업을 시작하겠다는 뜻이라 다른 디렉토리를 가리킬 일이 없어서다.
//
// errOut 은 이번 실행이 요구한 것이 세션에 닿지 않았을 때만 쓴다. 그 말은 사용자에게만 하는
// 말이라, 다음 명령으로 넘길 수 있는 안내(out)와 섞이면 안 된다.
func StartSession(out, errOut io.Writer, args []string, backend session.SessionBackend) error {
	request, err := parseStartArgs(args)
	if err != nil {
		return err
	}

	location, err := currentWorkDir()
	if err != nil {
		return err
	}

	// 레포 세션이든 메인 세션이든 스캔이 먼저다. 메인은 붙일 레포 목록이 필요하고,
	// 레포 세션은 받은 이름이 실제 레포인지와 그 절대경로를 여기서만 알 수 있다.
	repos, err := workdir.ScanRepos(location.absolutePath)
	if err != nil {
		return err
	}

	if request.repoName == "" {
		return startMainSession(out, errOut, location, repos, request, backend)
	}
	return startRepoSession(out, errOut, location, repos, request, backend)
}

// sessionRequest 는 파싱이 끝난 세션 요청이다 — 어느 세션을 어떤 옵션으로 여는가.
//
// 이름에 start 가 없는 이유는 이 요청을 만드는 명령이 둘이기 때문이다. kyu start 는 세션을
// 띄우려고, kyu session-command 는 앱이 띄울 것을 답하려고 같은 것을 묻는다(설계 문서 5.2).
type sessionRequest struct {
	// repoName 이 비어 있으면 메인 세션 요청이다(설계 문서 9.3 의 "인자 없으면 main").
	repoName string

	// loadRepoClaudeMd 는 메인 세션이 추가 디렉토리의 지침 파일까지 읽을지다.
	loadRepoClaudeMd bool

	// bypassPermissions 는 세션의 claude 가 권한 확인을 건너뛸지다.
	bypassPermissions bool
}

func parseStartArgs(args []string) (sessionRequest, error) {
	var request sessionRequest

	for _, arg := range args {
		switch {
		case arg == repoClaudeMdOptionName:
			request.loadRepoClaudeMd = true

		case arg == bypassPermissionsOptionName:
			request.bypassPermissions = true

		// 모르는 옵션을 레포 이름으로 흘려보내면 "없는 레포입니다: --typo" 라는 엉뚱한 안내가 나온다.
		case strings.HasPrefix(arg, "-"):
			return sessionRequest{}, fmt.Errorf("알 수 없는 옵션: %s\n\n%s", arg, startUsageText)

		case request.repoName != "":
			return sessionRequest{}, fmt.Errorf("start 는 레포 이름 하나만 받습니다 (인자 %d 개를 받음)\n\n%s", len(args), startUsageText)

		default:
			request.repoName = arg
		}
	}

	// 레포 세션은 자기 디렉토리에서 뜨므로 그 레포의 CLAUDE.md 를 이미 읽는다. 조용히 무시하면
	// 사용자는 무언가 켜졌다고 믿고, 그 믿음은 세션이 지침을 어길 때까지 드러나지 않는다.
	if request.loadRepoClaudeMd && request.repoName != "" {
		return sessionRequest{}, fmt.Errorf("%s 는 메인 세션 전용입니다 — 레포 세션은 자기 디렉토리의 CLAUDE.md 를 이미 읽습니다", repoClaudeMdOptionName)
	}

	return request, nil
}

// startMainSession 은 워크디렉토리 최상위에서 조율용 세션을 띄운다(설계 문서 5.4).
func startMainSession(out, errOut io.Writer, location workDirLocation, repos []workdir.Repo, request sessionRequest, backend session.SessionBackend) error {
	return createSession(out, errOut, backend,
		session.MainSessionName(location.name), mainSessionLabel,
		location.absolutePath,
		envWrappedCommand(sessionEnvironment(request), mainSessionCommand(repos, request, nil)),
		request.bypassPermissions)
}

// mainSessionCommand 는 메인 세션이 실행할 명령을 조립한다: claude --add-dir <레포 절대경로>...
//
// 레포 목록을 사용자가 관리하는 설정에서 읽지 않고 스캔 결과로 만든다. 클론하면 곧바로 붙는 것이
// 요구사항이고(설계 문서 5.3), 손으로 유지하는 목록은 클론 뒤 한 번 잊는 순간 조용히 어긋난다.
//
// 절대경로로 넘기는 이유: 세션의 cwd 가 워크디렉토리라 상대경로도 당장은 맞지만, 서로 다른
// 워크디렉토리에 같은 이름의 레포가 있을 수 있어 이름과 상대경로는 레포를 특정하지 못한다(설계 문서 11절 4번).
func mainSessionCommand(repos []workdir.Repo, request sessionRequest, conversationFlags []string) []string {
	command := claudeCommand(request.bypassPermissions, conversationFlags)

	// 붙일 레포 수만큼 미리 늘린다. 아래 반복이 레포마다 두 자리씩 더하는데,
	// 그때마다 슬라이스가 자라면 레포가 많은 워크디렉토리에서 같은 목록을 여러 번 복사하게 된다.
	command = slices.Grow(command, 2*len(repos))

	for _, repo := range repos {
		command = append(command, addDirFlagName, repo.AbsolutePath)
	}
	return command
}

// claudeCommand 는 두 세션이 공통으로 실행하는 부분이다: claude 와, 이 세션이 이어갈 대화를
// 가리키는 플래그와, 켜져 있다면 권한 확인 생략 플래그.
//
// 메인 세션은 뒤에 --add-dir 을 잇고 레포 세션은 여기서 끝난다. 공통부를 한 곳에 두는 이유는
// 두 세션이 같은 옵션에 다르게 반응하면 사용자가 그 차이를 세션 안에서야 발견하기 때문이다.
//
// conversationFlags 는 앱이 물을 때만 채워진다. kyu start 는 빈 것을 넘긴다 — CLI 세션은
// detach 로 살아남으므로 이어갈 필요가 없고, 통일하면 CLI 세션과 앱 세션이 같은 대화 ID 를
// 다투게 된다(설계 문서 5.5.5 다).
func claudeCommand(bypassPermissions bool, conversationFlags []string) []string {
	command := make([]string, 0, 2+len(conversationFlags))
	command = append(command, sessionCommandName)
	command = append(command, conversationFlags...)
	if bypassPermissions {
		command = append(command, skipPermissionsFlagName)
	}
	return command
}

// sessionEnvironmentEntry 는 세션이 자기 바탕 환경에 얹어야 하는 환경변수 하나다.
type sessionEnvironmentEntry struct {
	name  string
	value string
}

// sessionEnvironment 는 이번 요청이 세션에 더할 환경변수를 정한다. 더할 것이 없으면 비어 있다.
//
// "무엇을 얹는가" 를 "어떻게 얹는가" 에서 갈라낸 자리다. 얹는 방법은 부르는 쪽마다 다르지만
// (envWrappedCommand 와 kyu session-command 의 env 객체), 무엇을 얹는지는 요청 하나가 정한다.
// 두 곳이 각자 정하면 새 환경변수가 한쪽에만 생기고, 사용자는 그 차이를 세션 안에서야 발견한다.
//
// 순서 있는 목록으로 답하는 이유는 감싸는 쪽 때문이다. Go 의 map 은 순회 순서가 매번 달라서,
// 환경변수가 둘이 되는 날 env 로 감싼 명령의 인자 순서가 실행마다 흔들린다 — 세션이 실행할
// 명령은 그 세션이 사는 동안 사람이 ps 로 들여다보는 값이다.
func sessionEnvironment(request sessionRequest) []sessionEnvironmentEntry {
	if request.loadRepoClaudeMd {
		return []sessionEnvironmentEntry{{name: repoClaudeMdEnvName, value: repoClaudeMdEnvValue}}
	}
	return nil
}

// envWrappedCommand 는 환경변수를 얹어 실행하도록 명령을 env 로 감싼다. 얹을 것이 없으면 그대로 둔다.
//
// SessionBackend.Create 에 환경 인자가 없어서 쓰는 수법이다. 인터페이스를 좁게 유지하려고 고른
// 것이고(envCommandName 의 주석), 프로세스를 직접 띄우는 쪽은 이 제약 아래 있지 않다 —
// kyu session-command 가 환경을 환경으로 답하는 근거가 그것이다(설계 문서 5.2).
func envWrappedCommand(environment []sessionEnvironmentEntry, command []string) []string {
	if len(environment) == 0 {
		return command
	}

	wrapped := make([]string, 0, 1+len(environment)+len(command))
	wrapped = append(wrapped, envCommandName)
	for _, entry := range environment {
		wrapped = append(wrapped, entry.name+"="+entry.value)
	}
	return append(wrapped, command...)
}

// startRepoSession 은 레포 디렉토리에서 작업용 세션을 띄운다.
func startRepoSession(out, errOut io.Writer, location workDirLocation, repos []workdir.Repo, request sessionRequest, backend session.SessionBackend) error {
	repo, err := repoNamed(repos, request.repoName)
	if err != nil {
		return err
	}

	// cwd 를 레포 디렉토리로 주는 이 한 줄이 그 레포의 MCP 설정·지침·에이전트를 살린다.
	// 설정은 오직 세션을 시작한 디렉토리에서만 오기 때문이다(설계 문서 1.3).
	// 같은 레포를 --add-dir 로 붙이면 파일만 보이고 설정은 죽는다 — 그 차이가 이 도구의 존재 이유다.
	return createSession(out, errOut, backend,
		session.RepoSessionName(location.name, repo.Name), repo.Name,
		repo.AbsolutePath,
		envWrappedCommand(sessionEnvironment(request), claudeCommand(request.bypassPermissions, nil)),
		request.bypassPermissions)
}

// repoNamed 는 스캔 결과에서 이름으로 레포 하나를 찾는다.
//
// 세션을 띄우는 쪽과 앱에게 답하는 쪽이 같은 거절을 해야 해서 한 곳에 둔다. 두 곳이 각자
// 찾으면 한쪽만 "찾은 레포 목록" 을 함께 알리게 되고, 사용자는 어느 문으로 들어왔는지에 따라
// 다른 도움을 받는다.
func repoNamed(repos []workdir.Repo, repoName string) (workdir.Repo, error) {
	repoIndex := slices.IndexFunc(repos, func(repo workdir.Repo) bool { return repo.Name == repoName })
	if repoIndex < 0 {
		return workdir.Repo{}, unknownRepoError(repoName, repos)
	}
	return repos[repoIndex], nil
}

// createSession 은 세션을 만들고 그 결과를 사람이 읽을 안내로 옮긴다.
//
// label 은 사용자가 부르는 이름(레포 이름 또는 main)이다. 세션 이름(kyu-...)을 안내에 그대로 쓰면
// 사용자가 그것을 다음 명령의 인자로 되돌려 주게 되는데, 이 도구의 명령은 레포 이름을 받는다.
//
// bypassPermissions 를 받는 이유는 마지막 인자 하나 때문이 아니라, 세션이 이미 떠 있었다는
// 사실을 아는 자리가 여기뿐이어서다. 그 사실과 "이번 실행이 무엇을 요구했는가" 가 만나야
// 경고할지가 정해진다.
func createSession(out, errOut io.Writer, backend session.SessionBackend, sessionName, label, cwd string, command []string, bypassPermissions bool) error {
	err := backend.Create(sessionName, cwd, command)

	// 이미 떠 있는 것은 사용자가 원한 상태와 다르지 않다 — 그 세션에서 작업하면 된다.
	// 실패로 끝내면 kyu start 를 앞에 둔 스크립트가 두 번째 실행부터 깨지므로 안내만 하고 성공으로 끝낸다.
	if errors.Is(err, session.ErrSessionExists) {
		if bypassPermissions {
			if err := warnRunningSessionKeepsTheCommandItWasCreatedWith(errOut, label); err != nil {
				return err
			}
		}
		fmt.Fprintf(out, "%s 세션이 이미 실행 중입니다.\n진입: kyu attach %s\n", label, label)
		return nil
	}
	if err != nil {
		return fmt.Errorf("%s 세션 생성 실패: %w", label, err)
	}

	fmt.Fprintf(out, "%s 세션을 시작했습니다.\n진입: kyu attach %s\n", label, label)
	return nil
}

// warnRunningSessionKeepsTheCommandItWasCreatedWith 는 이번에 켠 옵션이 이미 떠 있는 세션에는
// 닿지 않는다는 사실을 알린다.
//
// 세션이 실행할 명령은 세션을 만드는 순간 정해지고 그 뒤로 바뀌지 않는다 — tmux 에게 다시
// 물어봐도 이미 떠 있는 claude 의 인자를 바꿀 방법은 없다. 조용히 넘어가면 사용자는 권한
// 확인 없이 도는 세션이라고 믿은 채 작업하게 되고, 그 믿음은 첫 확인 프롬프트에서야 깨진다.
//
// 그래도 실패로 끝내지는 않는다. 사용자가 원한 것은 그 세션에서 작업하는 것이고, 떠 있는
// 세션은 그 요구를 이미 채우고 있다.
func warnRunningSessionKeepsTheCommandItWasCreatedWith(errOut io.Writer, label string) error {
	_, err := fmt.Fprintf(errOut,
		"%s 는 이미 실행 중인 %s 세션에는 적용되지 않습니다 — 세션이 실행할 명령은 만들 때 정해집니다.\n"+
			"이 옵션으로 다시 띄우려면 kyu kill %s 뒤에 다시 실행하세요.\n",
		bypassPermissionsOptionName, label, label)
	if err != nil {
		return fmt.Errorf("실행 중인 세션 경고 출력 실패: %w", err)
	}
	return nil
}

// unknownRepoError 는 받은 이름이 레포가 아닐 때, 스캔에서 실제로 찾은 이름을 함께 알린다.
//
// "그런 레포 없음" 만 알리면 사용자는 정확한 이름을 찾으러 목록을 다시 열어야 한다.
// 이 시점에는 이미 스캔이 끝나 있으므로 그 자리에서 답까지 준다.
func unknownRepoError(repoName string, repos []workdir.Repo) error {
	if len(repos) == 0 {
		return fmt.Errorf("없는 레포입니다: %s\n이 워크디렉토리에는 git 레포가 없습니다 — 클론한 뒤 다시 실행하세요", repoName)
	}

	foundRepoNames := make([]string, 0, len(repos))
	for _, repo := range repos {
		foundRepoNames = append(foundRepoNames, repo.Name)
	}
	return fmt.Errorf("없는 레포입니다: %s\n이 워크디렉토리의 레포: %s", repoName, strings.Join(foundRepoNames, ", "))
}
