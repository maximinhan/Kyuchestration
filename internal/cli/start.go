package cli

import (
	"errors"
	"fmt"
	"io"
	"path/filepath"
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

const startUsageText = `사용법: wd start [repo]

  wd start           워크디렉토리 최상위에서 메인 세션을 띄운다
  wd start <repo>    해당 레포 디렉토리에서 세션을 띄운다`

// StartSession 은 wd start 를 실행한다. 인자가 없으면 메인 세션을, 레포 이름이 있으면 그 레포의 세션을 띄운다.
//
// 대상 워크디렉토리는 명령을 실행한 디렉토리다. list 처럼 경로 인자를 받지 않는 이유는,
// 세션을 띄우는 일은 그 워크디렉토리에서 작업을 시작하겠다는 뜻이라 다른 디렉토리를 가리킬 일이 없어서다.
func StartSession(out io.Writer, args []string, backend session.SessionBackend) error {
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
		return startMainSession(out, location, repos, backend)
	}
	return startRepoSession(out, location, repos, request.repoName, backend)
}

// startRequest 는 파싱이 끝난 wd start 요청이다.
type startRequest struct {
	// repoName 이 비어 있으면 메인 세션 요청이다(설계 문서 9.3 의 "인자 없으면 main").
	repoName string
}

func parseStartArgs(args []string) (startRequest, error) {
	var request startRequest

	for _, arg := range args {
		// 모르는 옵션을 레포 이름으로 흘려보내면 "없는 레포입니다: --typo" 라는 엉뚱한 안내가 나온다.
		if strings.HasPrefix(arg, "-") {
			return startRequest{}, fmt.Errorf("알 수 없는 옵션: %s\n\n%s", arg, startUsageText)
		}
		if request.repoName != "" {
			return startRequest{}, fmt.Errorf("start 는 레포 이름 하나만 받습니다 (인자 %d 개를 받음)\n\n%s", len(args), startUsageText)
		}
		request.repoName = arg
	}

	return request, nil
}

// workDirLocation 은 명령이 다루는 워크디렉토리 하나를 절대경로와 이름으로 가리킨다.
type workDirLocation struct {
	absolutePath string
	name         string
}

// currentWorkDir 는 명령을 실행한 디렉토리를 워크디렉토리로 삼는다.
//
// 이름을 절대경로의 마지막 요소에서 얻는다. 세션 이름이 wd-<workdir>-<repo> 라서(설계 문서 5.2)
// 워크디렉토리 이름 없이는 세션을 특정할 수 없는데, cwd 는 "." 이나 ".." 처럼 이름이 아닌 형태로 들어온다.
func currentWorkDir() (workDirLocation, error) {
	absolutePath, err := filepath.Abs(".")
	if err != nil {
		return workDirLocation{}, fmt.Errorf("현재 디렉토리의 절대경로 변환 실패: %w", err)
	}
	return workDirLocation{absolutePath: absolutePath, name: filepath.Base(absolutePath)}, nil
}

// startMainSession 은 워크디렉토리 최상위에서 조율용 세션을 띄운다(설계 문서 5.4).
func startMainSession(out io.Writer, location workDirLocation, repos []workdir.Repo, backend session.SessionBackend) error {
	return createSession(out, backend,
		session.MainSessionName(location.name), mainRowLabel,
		location.absolutePath, mainSessionCommand(repos))
}

// mainSessionCommand 는 메인 세션이 실행할 명령을 조립한다: claude --add-dir <레포 절대경로>...
//
// 레포 목록을 사용자가 관리하는 설정에서 읽지 않고 스캔 결과로 만든다. 클론하면 곧바로 붙는 것이
// 요구사항이고(설계 문서 5.3), 손으로 유지하는 목록은 클론 뒤 한 번 잊는 순간 조용히 어긋난다.
//
// 절대경로로 넘기는 이유: 세션의 cwd 가 워크디렉토리라 상대경로도 당장은 맞지만, 서로 다른
// 워크디렉토리에 같은 이름의 레포가 있을 수 있어 이름과 상대경로는 레포를 특정하지 못한다(설계 문서 11절 4번).
func mainSessionCommand(repos []workdir.Repo) []string {
	command := make([]string, 0, 1+2*len(repos))
	command = append(command, sessionCommandName)
	for _, repo := range repos {
		command = append(command, addDirFlagName, repo.AbsolutePath)
	}
	return command
}

// startRepoSession 은 레포 디렉토리에서 작업용 세션을 띄운다.
func startRepoSession(out io.Writer, location workDirLocation, repos []workdir.Repo, repoName string, backend session.SessionBackend) error {
	repoIndex := slices.IndexFunc(repos, func(repo workdir.Repo) bool { return repo.Name == repoName })
	if repoIndex < 0 {
		return unknownRepoError(repoName, repos)
	}
	repo := repos[repoIndex]

	// cwd 를 레포 디렉토리로 주는 이 한 줄이 그 레포의 MCP 설정·지침·에이전트를 살린다.
	// 설정은 오직 세션을 시작한 디렉토리에서만 오기 때문이다(설계 문서 1.3).
	// 같은 레포를 --add-dir 로 붙이면 파일만 보이고 설정은 죽는다 — 그 차이가 이 도구의 존재 이유다.
	return createSession(out, backend,
		session.RepoSessionName(location.name, repo.Name), repo.Name,
		repo.AbsolutePath, []string{sessionCommandName})
}

// createSession 은 세션을 만들고 그 결과를 사람이 읽을 안내로 옮긴다.
//
// label 은 사용자가 부르는 이름(레포 이름 또는 main)이다. 세션 이름(wd-...)을 안내에 그대로 쓰면
// 사용자가 그것을 다음 명령의 인자로 되돌려 주게 되는데, 이 도구의 명령은 레포 이름을 받는다.
func createSession(out io.Writer, backend session.SessionBackend, sessionName, label, cwd string, command []string) error {
	err := backend.Create(sessionName, cwd, command)

	// 이미 떠 있는 것은 사용자가 원한 상태와 다르지 않다 — 그 세션에서 작업하면 된다.
	// 실패로 끝내면 wd start 를 앞에 둔 스크립트가 두 번째 실행부터 깨지므로 안내만 하고 성공으로 끝낸다.
	if errors.Is(err, session.ErrSessionExists) {
		fmt.Fprintf(out, "%s 세션이 이미 실행 중입니다.\n진입: wd attach %s\n", label, label)
		return nil
	}
	if err != nil {
		return fmt.Errorf("%s 세션 생성 실패: %w", label, err)
	}

	fmt.Fprintf(out, "%s 세션을 시작했습니다.\n진입: wd attach %s\n", label, label)
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
