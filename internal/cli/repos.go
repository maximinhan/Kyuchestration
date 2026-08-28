package cli

import (
	"errors"
	"fmt"
	"io"

	"github.com/maximinhan/Kyuchestration/internal/github"
	"github.com/maximinhan/Kyuchestration/internal/secretstore"
)

// 이 파일은 kyu repos 다 — GitHub 에 무엇이 있는지만 묻고 아무것도 바꾸지 않는 명령.
//
// kyu clone 이 대화 안에서 하던 걸음(어느 계정의 레포를 볼까)을 밖으로 꺼낸 것이다. GUI 는 그
// 걸음을 자기 화면으로 그려야 하는데, 대화형 흐름은 답을 표로만 내보내므로 앱이 그 표를
// 되파싱하는 수밖에 없었다.
//
// 이름이 kyu list 와 겹쳐 보이지만 보는 곳이 다르다 — kyu list 는 이 머신의 워크디렉토리를 훑고,
// kyu repos 는 GitHub 에 묻는다. 그래서 이 명령에는 워크디렉토리도 세션 백엔드도 필요 없다.

const reposUsageText = `사용법: kyu repos owners --profile <이름> --json

  kyu repos owners --profile <이름> --json   이 토큰으로 볼 수 있는 개인 계정과 소속 조직

이 명령은 --json 으로만 답한다 — GUI·스크립트가 엔진을 부르는 자리다.
사람이 레포를 골라 클론하는 자리는 kyu clone 이다.`

// profileOptionName 은 어느 토큰으로 GitHub 에 붙을지 고르는 옵션이다.
//
// 이름을 필수로 받는다. 등록된 것이 하나뿐일 때 그것을 집어 쓰면 두 번째 프로필이 생기는 날
// 같은 명령이 다른 계정을 보게 되고, 그 변화는 엉뚱한 레포를 클론한 뒤에야 드러난다.
const profileOptionName = "--profile"

// BrowseGitHubRepositories 는 kyu repos 를 실행한다.
//
// 입력을 받지 않는다. 이 명령은 아무것도 묻지 않는다는 것이 설계의 전부라, 물음을 읽을 자리를
// 두지 않는 것으로 그 사실을 못박는다.
func BrowseGitHubRepositories(out, errOut io.Writer, args []string, newAccess RepositoryAccessFactory, tokenStore secretstore.TokenStore) error {
	if len(args) == 0 {
		return fmt.Errorf("repos 는 하위 명령이 필요합니다\n\n%s", reposUsageText)
	}

	// 하위 명령을 먼저 본다. 인자를 다 읽고 GitHub 에 붙은 뒤에 거절하면, 오타 하나에
	// 네트워크 왕복이 따라붙는다.
	subcommand, subcommandArgs := args[0], args[1:]
	if subcommand != "owners" {
		return fmt.Errorf("알 수 없는 repos 명령: %s\n\n%s", subcommand, reposUsageText)
	}

	request, err := parseReposArgs(subcommandArgs)
	if err != nil {
		return err
	}

	access, personalOwner, err := accessWithStoredToken(request.profileName, tokenStore, newAccess)
	if err != nil {
		return err
	}

	owners, err := listOwnersTheTokenCanSee(errOut, access, personalOwner)
	if err != nil {
		return err
	}
	return writeOwnersAsJSON(out, owners)
}

// reposRequest 는 파싱이 끝난 kyu repos 요청이다.
type reposRequest struct {
	profileName string
}

// parseReposArgs 는 인자를 요청으로 옮긴다.
//
// 모르는 인자를 조용히 버리지 않는다. 오타 난 옵션을 흘려보내면 명령은 성공으로 끝나는데,
// 사용자가 지정했다고 믿는 조건은 아무 데도 반영되지 않는다.
func parseReposArgs(args []string) (reposRequest, error) {
	var request reposRequest
	asJSON := false

	for argIndex := 0; argIndex < len(args); argIndex++ {
		switch args[argIndex] {
		case machineJSONOptionName:
			asJSON = true

		case profileOptionName:
			value, err := optionValueAt(args, argIndex, profileOptionName, reposUsageText)
			if err != nil {
				return reposRequest{}, err
			}
			request.profileName = value
			argIndex++

		default:
			return reposRequest{}, fmt.Errorf("알 수 없는 인자: %s\n\n%s", args[argIndex], reposUsageText)
		}
	}

	if request.profileName == "" {
		return reposRequest{}, fmt.Errorf("repos 는 어느 토큰으로 붙을지 알아야 합니다 — %s <이름>\n\n%s", profileOptionName, reposUsageText)
	}
	if !asJSON {
		return reposRequest{}, fmt.Errorf("repos 는 %s 로만 답합니다 — 사람이 레포를 골라 클론하는 자리는 kyu clone 입니다\n\n%s",
			machineJSONOptionName, reposUsageText)
	}
	return request, nil
}

// optionValueAt 은 --이름 <값> 꼴에서 값을 꺼낸다.
//
// 값이 빠진 것을 거절한다. 다음 옵션을 값으로 집어 삼키면 kyu repos owners --profile --json 이
// "--json 이라는 이름의 프로필" 을 찾다가 엉뚱한 곳에서 실패한다.
func optionValueAt(args []string, optionIndex int, optionName, usageText string) (string, error) {
	if optionIndex+1 >= len(args) {
		return "", fmt.Errorf("%s 뒤에 값이 없습니다\n\n%s", optionName, usageText)
	}

	value := args[optionIndex+1]
	if value == "" || value[0] == '-' {
		return "", fmt.Errorf("%s 뒤에 값이 없습니다 (%q 를 받음)\n\n%s", optionName, value, usageText)
	}
	return value, nil
}

// listOwnersTheTokenCanSee 는 개인 계정과 소속 조직을 한 목록으로 모은다. 개인 계정이 늘 첫 자리다.
//
// 대화형 clone 의 소유자 선택(chooseRepositoryOwner)도 이 함수를 쓴다. 403 을 통과시키는 규칙이
// 두 곳에 각자 적혀 있으면, fine-grained 토큰을 쓰는 사용자가 어느 문으로 들어왔는지에 따라
// 한쪽에서만 막히게 된다.
func listOwnersTheTokenCanSee(errOut io.Writer, access github.RepositoryAccess, personalOwner github.Owner) ([]github.Owner, error) {
	organizations, err := access.Organizations()

	// fine-grained 토큰은 조직 목록에 권한이 없으면 403 으로 답한다. 그것으로 명령을 끝내면
	// 개인 레포를 보려던 사용자가 아무것도 하지 못한다 — 이 실패만 통과시킨다.
	if errors.Is(err, github.ErrForbidden) {
		fmt.Fprintf(errOut, "이 토큰으로는 조직 목록을 볼 수 없습니다 — %s 계정의 레포만 보여줍니다.\n", personalOwner.Login)
		return []github.Owner{personalOwner}, nil
	}
	if err != nil {
		return nil, err
	}

	owners := make([]github.Owner, 0, len(organizations)+1)
	owners = append(owners, personalOwner)
	return append(owners, organizations...), nil
}
