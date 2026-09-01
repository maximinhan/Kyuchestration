package workdir

import (
	"bytes"
	"errors"
	"fmt"
	"os/exec"
	"strconv"
	"strings"
)

// RepoState 는 도구가 관찰해서 얻은 레포의 현재 상태다(설계 문서 5.3).
//
// 관찰 대상은 git 하나다. 세션 생존을 함께 보던 자리였지만, 엔진이 세션을 만들지도 세지도 않게
// 된 뒤로는 물어볼 상대가 없다 — 앱이 보유한 세션은 앱만 안다(app-owned-sessions-design.md 5.4).
// 값을 캐시하지 않고 매 조회 시 다시 계산한다. 캐시하는 순간 사용자가 터미널에서 직접 커밋한
// 변화를 도구가 모르게 되고, "관찰해서 얻는다" 가 "기억해둔 것을 말한다" 로 바뀐다.
//
// 값을 int 가 아니라 string 으로 둔 이유는 두 가지다.
//
//  1. 이 값은 `kyu list` 에 그대로 찍히는 문자열이다(설계 문서 9.3). int 로 두면 String() 을
//     따로 써야 하고, 그 매핑에서 한 항목을 빠뜨린 채 %v 로 찍으면 사용자에게 숫자가 노출된다.
//  2. 영 값이 유효한 상태와 겹치지 않는다. int 였다면 0 이 네 상태 중 하나가 되어,
//     판정에 실패해 값을 채우지 못한 경우와 정상 판정 결과가 구분되지 않는다.
//     빈 문자열은 어느 상태도 아니므로 그런 실수가 곧바로 드러난다.
type RepoState string

const (
	// RepoStateDirty 는 커밋되지 않은 변경이 남아있는 상태다.
	RepoStateDirty RepoState = "DIRTY"

	// RepoStateAhead 는 워킹 트리는 깨끗하지만 업스트림보다 앞선 커밋이 있는 상태다.
	// 푸시가 남았다는 뜻이다.
	RepoStateAhead RepoState = "AHEAD"

	// RepoStateIdle 은 넘길 것이 남지 않은 상태다.
	RepoStateIdle RepoState = "IDLE"
)

// InferRepoState 는 레포 하나에 무엇이 남아있는지를 git 에게 물어 판정한다.
//
// 판정에 실패하면 영 값과 에러를 반환한다. 관찰하지 못한 것을 IDLE 처럼 그럴듯한 값으로
// 메우면 사용자가 그것을 "문제 없음" 으로 읽는다.
func InferRepoState(repo Repo) (RepoState, error) {
	// --porcelain 은 사람이 읽는 출력과 달리 git 버전이 올라가도 형식이 고정된다.
	// 추적 중인 파일의 수정도, 추적되지 않는 새 파일(?? 로 시작하는 줄)도 한 줄씩 나오므로
	// "커밋되지 않은 변경이 있는가" 는 이 한 번의 호출로 끝난다.
	statusOutput, err := runGitCommand(repo.AbsolutePath, "status", "--porcelain")
	if err != nil {
		return "", err
	}
	if strings.TrimSpace(statusOutput) != "" {
		return RepoStateDirty, nil
	}

	hasUpstream, err := hasResolvableUpstream(repo.AbsolutePath)
	if err != nil {
		return "", err
	}
	if !hasUpstream {
		// 작업 브랜치에 업스트림을 두지 않는 운용도 있으므로, 없는 것을 이상 상태로 취급하지
		// 않는다(설계 문서 5.3). 비교 대상이 없으면 "앞섰다" 는 말 자체가 성립하지 않는다.
		return RepoStateIdle, nil
	}

	aheadCountOutput, err := runGitCommand(repo.AbsolutePath, "rev-list", "--count", "@{u}..HEAD")
	if err != nil {
		return "", err
	}

	aheadCount, err := strconv.Atoi(strings.TrimSpace(aheadCountOutput))
	if err != nil {
		return "", fmt.Errorf("앞선 커밋 수 해석 실패 (%s, git 출력 %q): %w", repo.AbsolutePath, aheadCountOutput, err)
	}
	if aheadCount > 0 {
		return RepoStateAhead, nil
	}

	return RepoStateIdle, nil
}

// gitExitCodeRefNotFound 는 `git rev-parse --verify --quiet` 가 "가리키는 대상이 없다" 를
// 알릴 때 쓰는 종료 코드다. 레포가 아니거나 손상된 경우는 128 로 끝나므로 이 값과 구분된다.
const gitExitCodeRefNotFound = 1

// hasResolvableUpstream 은 현재 브랜치에 업스트림이 있고 그 ref 가 실제로 존재하는지 본다.
//
// `git rev-list --count @{u}..HEAD` 는 업스트림이 없을 때도, 레포가 손상됐을 때도 128 로 끝나
// 종료 코드만으로는 갈라낼 수 없다. stderr 의 "no upstream configured" 를 문자열로 맞춰보는
// 방법은 git 의 메시지 문구나 로케일이 바뀌는 순간 조용히 깨지므로 쓰지 않는다.
// 대신 종료 코드만으로 갈라지는 rev-parse 에게 먼저 물어, 앞의 것만 IDLE 로 흡수한다.
//
// `--verify --quiet` 는 대상이 없다는 사실을 메시지 없이 종료 코드 1 로만 알린다.
// 실측(git 2.43) — 업스트림 미설정 / 원격 추적 ref 삭제됨 / 분리된 HEAD / 커밋 0개 모두 1,
// git 레포가 아니거나 손상된 경우는 128.
//
// 이 확인을 통과했다면 뒤따르는 rev-list 의 실패는 진짜 실패다. 그때는 감추지 않고 전파한다.
func hasResolvableUpstream(repoAbsolutePath string) (bool, error) {
	if _, err := runGitCommand(repoAbsolutePath, "rev-parse", "--verify", "--quiet", "@{u}"); err != nil {
		var exitErr *exec.ExitError
		if errors.As(err, &exitErr) && exitErr.ExitCode() == gitExitCodeRefNotFound {
			return false, nil
		}
		return false, err
	}
	return true, nil
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
