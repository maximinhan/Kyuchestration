package cli

import (
	"bytes"
	"errors"
	"fmt"
	"io"
	"strings"
	"text/tabwriter"

	"github.com/maximinhan/Kyuchestration/internal/github"
	"github.com/maximinhan/Kyuchestration/internal/secretstore"
)

const authUsageText = `사용법: kyu auth <list|remove>

  kyu auth list            등록된 토큰 프로필 목록
  kyu auth remove <이름>   프로필과 그 토큰을 지운다

토큰 등록은 따로 명령을 두지 않는다 — kyu clone 이 필요한 자리에서 물어본다.`

// tokenRegistrationGuidance 는 토큰을 받기 전에 보여주는 안내다.
//
// 필요한 권한을 여기 적는 이유: 사용자는 이 화면에서 발급 페이지로 건너갔다가 돌아온다.
// 권한 목록이 이 자리에 없으면 README 를 찾으러 가야 하고, 대개는 되는 대로 넓은 권한을 준다.
const tokenRegistrationGuidance = `GitHub personal access token 을 등록합니다.

  발급: https://github.com/settings/tokens
  필요한 권한: 클래식 토큰이면 repo, fine-grained 이면 Contents 읽기
              (조직 레포까지 보려면 그 조직을 토큰의 대상에 포함해야 합니다)

이 토큰은 이 머신에만 저장되고 GitHub 말고 어디에도 보내지 않습니다.
`

// plaintextStorageWarning 은 토큰이 평문 파일에 저장될 때의 경고다.
//
// 토큰을 받기 전에 내보낸다. 붙여넣은 뒤에 알리면 사용자가 할 수 있는 일은 이미 저장된 것을
// 지우는 것뿐이다.
const plaintextStorageWarning = "경고: 이 머신에는 키체인도 secret-service 도 없어 토큰을 설정 파일에 평문으로 저장합니다 (권한 0600).\n" +
	"      토큰을 남기고 싶지 않다면 지금 중단하고(Ctrl-C), 키체인이 있는 머신에서 실행하세요.\n"

// newTokenProfileOptionLabel 은 프로필 목록의 마지막 항목이다.
const newTokenProfileOptionLabel = "새 토큰 등록"

// RepositoryAccessFactory 는 토큰 하나를 GitHub 접근 경로로 바꾼다.
//
// 표시 계층이 구현 타입을 직접 만들지 않는 이유는 세션 백엔드와 같다 — 어느 구현을 쓸지는
// 진입점의 결정이고, 그래야 이 흐름을 네트워크 없이 테스트할 수 있다.
type RepositoryAccessFactory func(token string) github.RepositoryAccess

// ManageTokenProfiles 는 kyu auth 를 실행한다.
//
// 등록하는 하위 명령을 두지 않았다. 토큰이 필요한 자리는 kyu clone 하나뿐이고, 거기서 물어보는
// 것이 사용자가 실제로 지나는 길이다. 등록 명령을 따로 두면 "먼저 등록하고 나서 클론" 이라는
// 순서를 사용자가 기억해야 한다.
func ManageTokenProfiles(out io.Writer, args []string, tokenStore secretstore.TokenStore) error {
	if len(args) == 0 {
		return fmt.Errorf("auth 는 하위 명령이 필요합니다\n\n%s", authUsageText)
	}

	switch args[0] {
	case "list":
		if len(args) != 1 {
			return fmt.Errorf("auth list 는 인자를 받지 않습니다 (인자 %d 개를 받음)\n\n%s", len(args)-1, authUsageText)
		}
		return listTokenProfiles(out, tokenStore)

	case "remove":
		if len(args) != 2 {
			return fmt.Errorf("auth remove 는 지울 프로필 이름 하나가 필요합니다 (인자 %d 개를 받음)\n\n%s", len(args)-1, authUsageText)
		}
		return removeTokenProfile(out, args[1], tokenStore)

	default:
		return fmt.Errorf("알 수 없는 auth 명령: %s\n\n%s", args[0], authUsageText)
	}
}

// listTokenProfiles 는 등록된 프로필을 이름과 저장 위치로 보여준다.
//
// 토큰 값을 꺼내지 않는다 — 이 명령은 저장소에 값을 물어보지도 않는다. 목록을 띄운 채로
// 화면을 공유하거나 캡처하는 일이 흔하고, 한 번 새 나간 토큰은 폐기 전까지 계속 유효하다.
func listTokenProfiles(out io.Writer, tokenStore secretstore.TokenStore) error {
	profiles, err := tokenStore.Profiles()
	if err != nil {
		return err
	}

	if len(profiles) == 0 {
		fmt.Fprintf(out, "등록된 토큰 프로필이 없습니다.\nkyu clone 을 실행하면 그 자리에서 등록합니다.\n")
		return nil
	}

	var rendered bytes.Buffer
	fmt.Fprintf(&rendered, "토큰 프로필 %d 개\n\n", len(profiles))

	table := tabwriter.NewWriter(&rendered, 0, 0, tableColumnPadding, ' ', 0)
	for _, profile := range profiles {
		fmt.Fprintf(table, "%s%s\t%s\n", rowIndent, profile.Name, profile.Storage.Description())
	}
	if err := table.Flush(); err != nil {
		return fmt.Errorf("프로필 표 정렬 실패: %w", err)
	}

	fmt.Fprintf(&rendered, "\n지우기: kyu auth remove <이름>\n")
	if _, err := out.Write(rendered.Bytes()); err != nil {
		return fmt.Errorf("프로필 목록 출력 실패: %w", err)
	}
	return nil
}

// removeTokenProfile 은 프로필과 그 토큰을 지운다.
func removeTokenProfile(out io.Writer, profileName string, tokenStore secretstore.TokenStore) error {
	err := tokenStore.RemoveToken(profileName)
	if errors.Is(err, secretstore.ErrProfileNotFound) {
		return unknownProfileError(profileName, tokenStore)
	}
	if err != nil {
		return fmt.Errorf("%s 프로필 삭제 실패: %w", profileName, err)
	}

	fmt.Fprintf(out, "%s 프로필과 그 토큰을 지웠습니다.\n", profileName)
	return nil
}

// unknownProfileError 는 없는 이름을 받았을 때 실제로 등록된 이름을 함께 알린다.
func unknownProfileError(profileName string, tokenStore secretstore.TokenStore) error {
	profiles, err := tokenStore.Profiles()
	if err != nil {
		return err
	}
	if len(profiles) == 0 {
		return fmt.Errorf("등록된 프로필이 없습니다: %s", profileName)
	}

	registeredNames := make([]string, 0, len(profiles))
	for _, profile := range profiles {
		registeredNames = append(registeredNames, profile.Name)
	}
	return fmt.Errorf("등록되지 않은 프로필입니다: %s\n등록된 프로필: %s", profileName, strings.Join(registeredNames, ", "))
}

// authenticateWithTokenProfile 은 저장된 프로필을 고르거나 새 토큰을 등록해 GitHub 에 붙는다.
//
// 머신의 다른 인증(GITHUB_TOKEN 환경변수, gh 의 로그인)을 보지 않는다. 그것을 집어 쓰면 회사
// 토큰이 깔린 머신에서 개인 레포를 클론하려던 사용자가 엉뚱한 계정의 목록을 보게 되고,
// 무엇으로 인증했는지 화면에서 확인할 방법도 없다. 여기서 고른 프로필이 곧 그 답이다.
//
// 붙은 계정(Owner)까지 함께 돌려준다. 토큰이 유효한지 확인하려면 어차피 GitHub 에 한 번 물어야
// 하는데, 그 답을 버리면 뒤이은 소유자 선택이 같은 것을 다시 묻게 된다.
func authenticateWithTokenProfile(prompt *interactivePrompt, errOut io.Writer, tokenStore secretstore.TokenStore, newAccess RepositoryAccessFactory) (github.RepositoryAccess, github.Owner, error) {
	profiles, err := tokenStore.Profiles()
	if err != nil {
		return nil, github.Owner{}, err
	}

	// 등록된 것이 없으면 고를 것이 없는 목록을 보여주지 않고 곧바로 등록으로 간다.
	if len(profiles) == 0 {
		return registerTokenProfile(prompt, errOut, tokenStore, newAccess, "")
	}

	chosenIndex, err := askWhichTokenProfile(prompt, profiles)
	if err != nil {
		return nil, github.Owner{}, err
	}
	if chosenIndex == len(profiles) {
		return registerTokenProfile(prompt, errOut, tokenStore, newAccess, "")
	}

	profile := profiles[chosenIndex]
	token, err := tokenStore.LoadToken(profile.Name)
	if err != nil {
		return nil, github.Owner{}, fmt.Errorf("%s 프로필의 토큰을 꺼내지 못했습니다: %w", profile.Name, err)
	}

	access := newAccess(token)
	owner, err := access.AuthenticatedOwner()

	// 토큰은 만료되고 폐기된다. "거절됐습니다" 로 끝내면 사용자는 지우고 다시 등록하는 두 걸음을
	// 스스로 찾아야 하는데, 그 두 걸음이 지금 이 자리에서 할 수 있는 일이다.
	if errors.Is(err, github.ErrInvalidToken) {
		fmt.Fprintf(errOut, "%s 프로필의 토큰을 GitHub 이 거절했습니다 — 같은 이름으로 새 토큰을 등록합니다.\n", profile.Name)
		return registerTokenProfile(prompt, errOut, tokenStore, newAccess, profile.Name)
	}
	if err != nil {
		return nil, github.Owner{}, err
	}

	fmt.Fprintf(prompt.out, "%s 프로필로 붙었습니다 — %s 계정입니다.\n", profile.Name, owner.Login)
	return access, owner, nil
}

// askWhichTokenProfile 은 프로필 목록을 보여주고 하나를 고르게 한다.
// 마지막 항목은 새 토큰 등록이라, 돌려주는 값이 len(profiles) 이면 그 뜻이다.
func askWhichTokenProfile(prompt *interactivePrompt, profiles []secretstore.Profile) (int, error) {
	fmt.Fprintf(prompt.out, "\n토큰 프로필을 고르세요.\n\n")

	table := tabwriter.NewWriter(prompt.out, 0, 0, tableColumnPadding, ' ', 0)
	for profileNumber, profile := range profiles {
		fmt.Fprintf(table, "%s%d)\t%s\t%s\n", rowIndent, profileNumber+1, profile.Name, profile.Storage.Description())
	}
	fmt.Fprintf(table, "%s%d)\t%s\t\n", rowIndent, len(profiles)+1, newTokenProfileOptionLabel)
	if err := table.Flush(); err != nil {
		return 0, fmt.Errorf("프로필 표 정렬 실패: %w", err)
	}

	chosenNumber, err := prompt.askForNumberedChoice("\n번호: ", len(profiles)+1)
	if err != nil {
		return 0, err
	}
	return chosenNumber, nil
}

// registerTokenProfile 은 토큰을 받아 확인하고 저장한다.
//
// presetProfileName 이 비어 있지 않으면 이름을 묻지 않는다 — 이미 있는 프로필의 토큰을 갈아 끼우는 길이다.
func registerTokenProfile(prompt *interactivePrompt, errOut io.Writer, tokenStore secretstore.TokenStore, newAccess RepositoryAccessFactory, presetProfileName string) (github.RepositoryAccess, github.Owner, error) {
	fmt.Fprintf(prompt.out, "\n%s", tokenRegistrationGuidance)

	profileName := presetProfileName
	if profileName == "" {
		typedName, err := prompt.ask("\n프로필 이름 (예: 개인, 회사): ")
		if err != nil {
			return nil, github.Owner{}, err
		}
		// 이름을 적지 않은 것은 그만두겠다는 뜻이다. 대신 정해주면 사용자가 짓지 않은 이름의
		// 프로필이 남는다.
		if typedName == "" {
			return nil, github.Owner{}, errInputClosed
		}
		profileName = typedName
	}

	if tokenStore.StorageKindForNewToken() == secretstore.StorageConfigFile {
		if _, err := fmt.Fprint(errOut, plaintextStorageWarning); err != nil {
			return nil, github.Owner{}, fmt.Errorf("평문 저장 경고 출력 실패: %w", err)
		}
	}

	// 거절당한 토큰으로 되묻는다. 토큰을 붙여넣다 한 글자를 흘리는 것은 흔한 실수인데,
	// 그때마다 명령을 처음부터 다시 실행하게 하면 프로필 선택부터 다시 지나야 한다.
	for {
		token, err := prompt.askHidden("GitHub personal access token (입력은 보이지 않습니다): ")
		if err != nil {
			return nil, github.Owner{}, err
		}
		if token == "" {
			return nil, github.Owner{}, errInputClosed
		}

		access := newAccess(token)
		owner, err := access.AuthenticatedOwner()
		if errors.Is(err, github.ErrInvalidToken) {
			// 거절당한 토큰은 저장하지 않는다. 저장해두면 다음 실행에서도 같은 실패가 반복되고,
			// 사용자는 자기가 등록한 것이 무엇인지 확인할 방법이 없다.
			fmt.Fprintf(errOut, "GitHub 이 이 토큰을 거절했습니다 — 저장하지 않았습니다. 다시 붙여넣으세요.\n")
			continue
		}
		if err != nil {
			return nil, github.Owner{}, err
		}

		fmt.Fprintf(prompt.out, "토큰 확인 완료 — %s 계정입니다.\n", owner.Login)

		if err := tokenStore.SaveToken(profileName, token); err != nil {
			return nil, github.Owner{}, fmt.Errorf("토큰 저장 실패: %w", err)
		}
		fmt.Fprintf(prompt.out, "토큰을 저장했습니다: %s (%s)\n", profileName, tokenStore.StorageKindForNewToken().Description())

		return access, owner, nil
	}
}
