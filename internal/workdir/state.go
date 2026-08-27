package workdir

import (
	"bytes"
	"fmt"
	"os/exec"
	"strings"

	"github.com/maximinhan/Kyuchestration/internal/session"
)

// RepoState 는 도구가 관찰해서 얻은 레포의 현재 상태다(설계 문서 5.3).
//
// 세션이 자기 상태를 보고하도록 요구하지 않는다. 관찰 가능한 것 — 세션의 생존과 git 의 답 — 에서만
// 추론한다(설계 원칙 6). 값을 캐시하지 않고 매 조회 시 다시 계산하는 것도 같은 이유다.
// 캐시하는 순간 사용자가 터미널에서 직접 커밋한 변화를 도구가 모르게 되고,
// "관찰해서 얻는다" 가 "기억해둔 것을 말한다" 로 바뀐다.
//
// 값을 int 가 아니라 string 으로 둔 이유는 두 가지다.
//
//  1. 이 값은 `wd list` 에 그대로 찍히는 문자열이다(설계 문서 9.3). int 로 두면 String() 을
//     따로 써야 하고, 그 매핑에서 한 항목을 빠뜨린 채 %v 로 찍으면 사용자에게 숫자가 노출된다.
//  2. 영 값이 유효한 상태와 겹치지 않는다. int 였다면 0 이 네 상태 중 하나가 되어,
//     판정에 실패해 값을 채우지 못한 경우와 정상 판정 결과가 구분되지 않는다.
//     빈 문자열은 어느 상태도 아니므로 그런 실수가 곧바로 드러난다.
type RepoState string

const (
	// RepoStateRunning 은 그 레포의 세션이 살아있는 상태다. 지금 누군가 작업 중이라는 뜻이다.
	RepoStateRunning RepoState = "RUNNING"

	// RepoStateDirty 는 세션이 없는데 커밋되지 않은 변경이 남아있는 상태다.
	RepoStateDirty RepoState = "DIRTY"

	// RepoStateAhead 는 세션이 없고 워킹 트리도 깨끗하지만 업스트림보다 앞선 커밋이 있는 상태다.
	// 푸시가 남았다는 뜻이다.
	RepoStateAhead RepoState = "AHEAD"

	// RepoStateIdle 은 세션도 없고 넘길 것도 남지 않은 상태다.
	RepoStateIdle RepoState = "IDLE"
)

// InferRepoState 는 레포 하나의 현재 상태를 관찰해 판정한다.
//
// 세션 이름을 계산하지 않고 받는다. 이름 규칙(wd-<workdir>-<repo>)은 워크디렉토리 이름까지
// 알아야 만들 수 있는데 Repo 는 그것을 모르고, 이름을 만드는 책임을 session 패키지 한 곳에
// 남겨두면 규칙이 바뀌어도 이 함수는 그대로다.
//
// backend 는 SessionBackend 인터페이스로만 받는다. 조율 계층이 tmux 를 알게 되는 순간
// 플랫폼 무관이라는 이 계층의 존재 이유가 사라진다(설계 원칙 4).
//
// 판정에 실패하면 영 값과 에러를 반환한다. 관찰하지 못한 것을 IDLE 처럼 그럴듯한 값으로
// 메우면 사용자가 그것을 "문제 없음" 으로 읽는다.
func InferRepoState(repo Repo, sessionName string, backend session.SessionBackend) (RepoState, error) {
	// 세션 생존을 가장 먼저 본다. 세션이 떠 있다면 그 안에서 파일이 계속 바뀌는 중이라
	// 그 순간의 git 상태는 결론이 아니라 스쳐 지나가는 스냅숏이다.
	isSessionAlive, err := backend.IsAlive(sessionName)
	if err != nil {
		return "", fmt.Errorf("세션 생존 확인 실패 (%s): %w", sessionName, err)
	}
	if isSessionAlive {
		return RepoStateRunning, nil
	}

	return inferStateFromGit(repo.AbsolutePath)
}

// inferStateFromGit 은 세션이 없는 레포에 무엇이 남아있는지를 git 에게 물어 판정한다.
func inferStateFromGit(repoAbsolutePath string) (RepoState, error) {
	// --porcelain 은 사람이 읽는 출력과 달리 git 버전이 올라가도 형식이 고정된다.
	// 추적 중인 파일의 수정도, 추적되지 않는 새 파일(?? 로 시작하는 줄)도 한 줄씩 나오므로
	// "커밋되지 않은 변경이 있는가" 는 이 한 번의 호출로 끝난다.
	statusOutput, err := runGitCommand(repoAbsolutePath, "status", "--porcelain")
	if err != nil {
		return "", err
	}
	if strings.TrimSpace(statusOutput) != "" {
		return RepoStateDirty, nil
	}

	return RepoStateIdle, nil
}

// runGitCommand 는 레포 디렉토리에서 git 을 실행하고 표준 출력을 돌려준다.
//
// git 라이브러리를 쓰지 않고 CLI 를 부른다. 외부 의존을 0 으로 유지하려는 이유도 있지만,
// 더 중요한 것은 사용자가 쓰는 바로 그 git 이 그 레포의 설정(.gitattributes, core.autocrlf,
// .gitignore)까지 그대로 적용해 답한다는 점이다. 도구가 보는 상태와 사용자가 터미널에서
// `git status` 로 보는 상태가 어긋나면, 관찰로 상태를 얻는다는 전제 자체가 무너진다.
//
// 실패 시 git 이 남긴 stderr 를 함께 감싼다. 종료 코드만으로는 원인이 드러나지 않는다.
func runGitCommand(repoAbsolutePath string, args ...string) (string, error) {
	command := exec.Command("git", args...)

	// 레포를 절대경로로 지정한다. 프로세스의 cwd 를 옮기지 않으므로 여러 레포를 훑는 도중에도
	// 서로 간섭하지 않고, 호출부가 어디에서 실행되든 같은 레포를 본다.
	command.Dir = repoAbsolutePath

	var stdout, stderr bytes.Buffer
	command.Stdout = &stdout
	command.Stderr = &stderr

	if err := command.Run(); err != nil {
		return "", fmt.Errorf("git %s 실패 (%s): %w (%s)",
			strings.Join(args, " "), repoAbsolutePath, err, strings.TrimSpace(stderr.String()))
	}
	return stdout.String(), nil
}
