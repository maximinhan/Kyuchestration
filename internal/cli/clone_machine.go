package cli

import (
	"fmt"
	"io"
	"strings"

	"github.com/maximinhan/Kyuchestration/internal/github"
	"github.com/maximinhan/Kyuchestration/internal/secretstore"
	"github.com/maximinhan/Kyuchestration/internal/session"
)

// 이 파일은 묻지 않는 kyu clone 이다 — 무엇을 클론할지 미리 적어 보내는 길.
//
// 대화형 흐름(clone.go)과 나눠 갖지 않는 것은 대화뿐이다. 일회성 credential helper 로 토큰을
// 디스크에 남기지 않는 것, 같은 이름이 이미 있으면 건너뛰는 것, 하나가 실패해도 나머지를 계속
// 시도하는 것 — 클론이 실제로 하는 일은 전부 공용 코드(cloneOneRow)가 한다. 그것들이 갈라지면
// 앱으로 클론한 레포와 손으로 클론한 레포가 다른 규칙 아래 놓인다.

// repositoryReference 는 --repo 로 받은 owner/name 하나다.
type repositoryReference struct {
	ownerLogin     string
	repositoryName string

	// asTyped 는 사용자가 적은 그대로다.
	//
	// 결과 문서의 repo 필드가 이 값이다. GitHub 이 준 이름으로 바꿔 돌려주면 대소문자가 달라질 수
	// 있는데(로그인은 대소문자를 가리지 않는다), 그러면 앱은 자기가 보낸 줄과 돌아온 결과를 맞추지 못한다.
	asTyped string
}

// cloneRequest 는 파싱이 끝난 kyu clone 요청이다.
type cloneRequest struct {
	profileName string

	// repositoryReferences 는 묻지 않고 클론할 레포다. 비어 있으면 대화형 흐름으로 간다.
	repositoryReferences []repositoryReference

	asJSON bool
}

// parseCloneArgs 는 인자를 클론 요청으로 옮긴다.
func parseCloneArgs(args []string) (cloneRequest, error) {
	var request cloneRequest

	for argIndex := 0; argIndex < len(args); argIndex++ {
		switch args[argIndex] {
		case machineJSONOptionName:
			request.asJSON = true

		case profileOptionName:
			value, err := optionValueAt(args, argIndex, profileOptionName, cloneUsageText)
			if err != nil {
				return cloneRequest{}, err
			}
			request.profileName = value
			argIndex++

		case repoOptionName:
			value, err := optionValueAt(args, argIndex, repoOptionName, cloneUsageText)
			if err != nil {
				return cloneRequest{}, err
			}
			reference, err := parseRepositoryReference(value)
			if err != nil {
				return cloneRequest{}, err
			}
			request.repositoryReferences = append(request.repositoryReferences, reference)
			argIndex++

		default:
			// 옛 거절 문구를 그대로 둔다. 인자를 붙여 부르는 실행(kyu clone maximinhan)은
			// 이 명령이 처음부터 거절해온 것이고, 옵션이 생겼다고 그 안내가 달라질 이유가 없다.
			return cloneRequest{}, fmt.Errorf("clone 은 인자를 받지 않습니다 (%s 를 받음)\n\n%s", args[argIndex], cloneUsageText)
		}
	}

	if len(request.repositoryReferences) == 0 {
		// 옵션만 적힌 실행을 대화형으로 흘려보내지 않는다. 프로필을 골라 보낸 사용자는 그것이
		// 쓰였다고 믿는데, 대화형 흐름은 프로필을 다시 묻는다.
		if request.profileName != "" || request.asJSON {
			return cloneRequest{}, fmt.Errorf("묻지 않는 클론에는 %s 가 필요합니다 — %s 없이 실행하면 목록에서 고릅니다\n\n%s",
				repoOptionName, repoOptionName, cloneUsageText)
		}
		return request, nil
	}

	if request.profileName == "" {
		return cloneRequest{}, fmt.Errorf("묻지 않는 클론은 어느 토큰으로 붙을지 알아야 합니다 — %s <이름>\n\n%s", profileOptionName, cloneUsageText)
	}
	return request, nil
}

// parseRepositoryReference 는 owner/name 을 읽는다.
//
// 이름만 적은 것을 받아주지 않는다. 프로필의 개인 계정으로 대신 정해주면, 같은 이름의 조직
// 레포를 적었다고 믿는 사용자가 엉뚱한 레포를 받는다.
func parseRepositoryReference(value string) (repositoryReference, error) {
	ownerLogin, repositoryName, isSplit := strings.Cut(value, "/")
	if !isSplit || ownerLogin == "" || repositoryName == "" || strings.Contains(repositoryName, "/") {
		return repositoryReference{}, fmt.Errorf("%s 는 owner/name 꼴이어야 합니다: %s\n\n%s", repoOptionName, value, cloneUsageText)
	}
	return repositoryReference{ownerLogin: ownerLogin, repositoryName: repositoryName, asTyped: value}, nil
}

// cloneWithoutAsking 은 적어 보낸 레포를 그대로 클론한다.
func cloneWithoutAsking(out, errOut io.Writer, request cloneRequest, location workDirLocation, newAccess RepositoryAccessFactory, tokenStore secretstore.TokenStore, backend session.SessionBackend) error {
	access, personalOwner, err := accessWithStoredToken(request.profileName, tokenStore, newAccess)
	if err != nil {
		return err
	}

	planned, err := planRequestedClones(access, personalOwner, request.repositoryReferences, location.absolutePath)
	if err != nil {
		return err
	}

	attempts := make([]cloneAttempt, 0, len(planned))
	for _, plan := range planned {
		if plan.resolutionFailure != "" {
			attempts = append(attempts, cloneAttempt{label: plan.label, status: cloneAttemptFailed, message: plan.resolutionFailure})
			continue
		}
		attempts = append(attempts, cloneOneRow(access, plan.label, plan.row))
	}

	if request.asJSON {
		// 세션 안내를 문서 안으로 들여야 하므로 여기서 먼저 판정한다. 사람용은 같은 판정을
		// finishClone 안에서 하고 stderr 한 줄로 낸다.
		restartNeeded, err := runningMainSessionWouldMissNewRepos(location.name, backend, attempts)
		if err != nil {
			return err
		}
		// 실패가 섞여 있어도 문서를 먼저 낸다. 어느 레포가 왜 실패했는지는 이 문서에만 있으므로,
		// 종료 코드만 돌려주면 앱은 사용자에게 아무것도 설명하지 못한다.
		if err := writeCloneResultsAsJSON(out, attempts, restartNeeded); err != nil {
			return err
		}
		return failedCloneError(attempts)
	}

	writeCloneAttempts(out, attempts)
	return finishClone(errOut, attempts, location.name, backend)
}

// plannedClone 은 --repo 하나에 대해 무엇을 할지 정해진 상태다.
type plannedClone struct {
	// label 은 사용자가 적은 owner/name 이다. 결과 문서의 repo 필드가 이 값이다.
	label string

	// row 는 클론할 대상이다. resolutionFailure 가 비어 있을 때만 뜻이 있다.
	row repositoryRow

	// resolutionFailure 는 그 소유자의 레포 목록에서 이 이름을 찾지 못한 이유다. 찾았으면 빈 문자열이다.
	resolutionFailure string
}

// planRequestedClones 는 --repo 로 받은 이름들을 GitHub 이 준 레포와 맞춘다.
//
// 이름으로 클론 주소를 조립하지 않는다. github 패키지가 적어둔 이유와 같다 — 조립하면 GitHub
// Enterprise 처럼 호스트가 다른 곳에서 엉뚱한 주소를 만든다. API 가 준 CloneURL 을 그대로 쓰려면
// 그 레포를 목록에서 찾아내는 수밖에 없고, 그 목록은 대화형 흐름이 보는 것과 같은 것이다.
//
// 소유자마다 목록을 한 번씩만 받아온다. 레포마다 물으면 같은 계정에 대해 같은 왕복을 되풀이하고,
// 100 개를 넘는 계정에서는 그 목록 하나가 여러 페이지다.
func planRequestedClones(access github.RepositoryAccess, personalOwner github.Owner, references []repositoryReference, absoluteWorkDirPath string) ([]plannedClone, error) {
	repositoriesByOwnerLogin := make(map[string]map[string]github.Repository)

	planned := make([]plannedClone, 0, len(references))
	for _, reference := range references {
		repositoriesByName, isLoaded := repositoriesByOwnerLogin[reference.ownerLogin]
		if !isLoaded {
			repositories, err := access.Repositories(ownerToAskForRepositories(reference.ownerLogin, personalOwner))
			// 목록을 받지 못한 것은 이 레포 하나의 실패가 아니다. 토큰이 그 계정을 볼 수 없거나
			// 네트워크가 끊긴 것이라, 결과 문서에 "이 레포가 실패했다" 로 적으면 거짓이 된다.
			if err != nil {
				return nil, err
			}

			repositoriesByName = make(map[string]github.Repository, len(repositories))
			for _, repository := range repositories {
				repositoriesByName[repository.Name] = repository
			}
			repositoriesByOwnerLogin[reference.ownerLogin] = repositoriesByName
		}

		repository, isFound := repositoriesByName[reference.repositoryName]
		if !isFound {
			// 조용히 건너뛰지 않는다. 오타는 흔한데, 건너뛰면 앱은 클론했다고 믿고 다음 걸음으로 간다.
			planned = append(planned, plannedClone{
				label:             reference.asTyped,
				resolutionFailure: fmt.Sprintf("%s 의 레포 목록에 %s 가 없습니다", reference.ownerLogin, reference.repositoryName),
			})
			continue
		}

		planned = append(planned, plannedClone{
			label: reference.asTyped,
			row:   repositoryRowFor(repository, absoluteWorkDirPath),
		})
	}
	return planned, nil
}
