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

const authUsageText = `사용법: kyu auth <add|list|remove>

  kyu auth add <이름>      토큰을 stdin 으로 받아 확인하고 그 이름으로 저장한다
  kyu auth list            등록된 토큰 프로필 목록
  kyu auth remove <이름>   프로필과 그 토큰을 지운다

옵션 (add, list):
  --json                   사람용 출력 대신 기계용 JSON 을 낸다 (GUI·스크립트 연동용)

토큰은 인자가 아니라 stdin 으로 받는다 — 인자는 같은 머신의 다른 사용자가 ps 로 읽는다.
kyu clone 도 필요한 자리에서 물어보므로, 사람은 어느 쪽으로 들어와도 된다.`

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

// tokenQuestion 은 토큰을 받는 자리의 물음이다.
//
// 두 등록 경로가 같은 문구를 쓴다. 사람이 대화형으로 들어오든 kyu auth add 를 터미널에서 치든
// 화면에 뜨는 것은 같아야, "이 도구는 이렇게 토큰을 묻는다" 가 하나로 남는다.
const tokenQuestion = "GitHub personal access token (입력은 보이지 않습니다): "

// noTokenProfileGuidance 는 등록된 프로필이 하나도 없을 때 보여주는 두 갈래 길이다.
//
// 두 길을 모두 적는다. kyu clone 은 사람이 지나는 길이고 kyu auth add 는 스크립트와 GUI 가
// 지나는 길인데, 한쪽만 적으면 다른 쪽 사용자는 자기 자리에서 쓸 수 없는 방법만 보게 된다.
const noTokenProfileGuidance = `등록된 토큰 프로필이 없습니다.

  kyu auth add <이름>   토큰을 stdin 으로 넘겨 등록한다
  kyu clone             필요한 자리에서 물어본다
`

// newTokenProfileOptionLabel 은 프로필 목록의 마지막 항목이다.
const newTokenProfileOptionLabel = "새 토큰 등록"

// RepositoryAccessFactory 는 토큰 하나를 GitHub 접근 경로로 바꾼다.
//
// 표시 계층이 구현 타입을 직접 만들지 않는 이유는 세션 백엔드와 같다 — 어느 구현을 쓸지는
// 진입점의 결정이고, 그래야 이 흐름을 네트워크 없이 테스트할 수 있다.
type RepositoryAccessFactory func(token string) github.RepositoryAccess

// ManageTokenProfiles 는 kyu auth 를 실행한다.
//
// 등록하는 하위 명령(add)이 뒤늦게 생긴 이유는 GUI 다. 대화형 등록은 kyu clone 안에 있고 그것이
// 사람이 실제로 지나는 길이지만, 앱은 물음에 답할 수 없다 — 물음 없이 토큰 하나를 건네고
// 성공·실패만 돌려받는 표면이 따로 있어야 앱이 자기 화면에서 등록을 마칠 수 있다.
//
// 입력과 stderr 까지 받는 이유가 그 add 다. 토큰은 인자로 받지 않으므로 어딘가에서 읽어야 하고,
// 물음과 경고는 stdout 을 오염시키면 안 된다 — stdout 은 --json 문서가 통째로 쓰는 자리다.
//
// GitHub 접근 경로도 받는다. 등록은 저장이 아니라 확인이 먼저인 일이라(verifyAndStoreToken),
// 이 명령만 GitHub 을 모른 채로 남을 수는 없다.
func ManageTokenProfiles(in io.Reader, out, errOut io.Writer, args []string, newAccess RepositoryAccessFactory, tokenStore secretstore.TokenStore) error {
	if len(args) == 0 {
		return fmt.Errorf("auth 는 하위 명령이 필요합니다\n\n%s", authUsageText)
	}

	switch args[0] {
	case "add":
		return addTokenProfile(in, out, errOut, args[1:], newAccess, tokenStore)

	case "list":
		return listTokenProfiles(out, args[1:], tokenStore)

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
func listTokenProfiles(out io.Writer, args []string, tokenStore secretstore.TokenStore) error {
	asJSON, err := parseListTokenProfilesArgs(args)
	if err != nil {
		return err
	}

	profiles, err := tokenStore.Profiles()
	if err != nil {
		return err
	}

	// 기계용 문서는 빈 목록도 같은 모양으로 낸다. 사람용이 이 자리에서 하는 등록 안내는
	// stdout 으로 나갈 수 없고(읽는 쪽의 파싱이 깨진다), 다른 스트림으로 빼면 그냥 사라진다.
	if asJSON {
		return writeTokenProfilesAsJSON(out, profiles)
	}

	if len(profiles) == 0 {
		fmt.Fprint(out, noTokenProfileGuidance)
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

// parseListTokenProfilesArgs 는 목록에 붙은 인자를 읽는다. 받는 것은 --json 하나뿐이다.
//
// 모르는 인자를 조용히 버리지 않는다. 오타 난 옵션(--jsonn)을 흘려보내면 사람용 표가 나오는데,
// 그것을 파싱하려던 쪽에는 "JSON 이 깨졌다" 로만 보인다.
func parseListTokenProfilesArgs(args []string) (bool, error) {
	asJSON := false
	for _, arg := range args {
		if arg != machineJSONOptionName {
			return false, fmt.Errorf("auth list 는 %s 말고 인자를 받지 않습니다: %s\n\n%s", machineJSONOptionName, arg, authUsageText)
		}
		asJSON = true
	}
	return asJSON, nil
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

	if err := warnWhenTheTokenWouldBeStoredInPlaintext(errOut, tokenStore); err != nil {
		return nil, github.Owner{}, err
	}

	// 거절당한 토큰으로 되묻는다. 토큰을 붙여넣다 한 글자를 흘리는 것은 흔한 실수인데,
	// 그때마다 명령을 처음부터 다시 실행하게 하면 프로필 선택부터 다시 지나야 한다.
	for {
		token, err := prompt.askHidden(tokenQuestion)
		if err != nil {
			return nil, github.Owner{}, err
		}
		if token == "" {
			return nil, github.Owner{}, errInputClosed
		}

		access, owner, err := verifyAndStoreToken(profileName, token, tokenStore, newAccess)
		if errors.Is(err, github.ErrInvalidToken) {
			// 되묻는 것은 사람이 앞에 있을 때만 할 수 있는 일이다. kyu auth add 는 같은 실패를
			// 종료 코드로 돌려준다 — 파이프 너머에는 다시 붙여넣을 상대가 없다.
			fmt.Fprintf(errOut, "GitHub 이 이 토큰을 거절했습니다 — 저장하지 않았습니다. 다시 붙여넣으세요.\n")
			continue
		}
		if err != nil {
			return nil, github.Owner{}, err
		}

		writeTokenStoredNotice(prompt.out, profileName, owner, tokenStore)
		return access, owner, nil
	}
}

// verifyAndStoreToken 은 토큰이 살아 있는지 GitHub 에 먼저 묻고, 그 답을 받은 뒤에만 저장한다.
//
// 이 순서가 이 함수의 전부이고, 대화형 등록과 kyu auth add 가 이 함수를 함께 쓰는 이유다.
// 저장부터 하면 거절당한 토큰이 프로필에 남아 다음 실행에서도 같은 실패가 반복되는데,
// 두 등록 경로가 그 순서를 각자 지키면 한쪽만 어긋나는 날이 온다.
//
// 붙은 계정까지 돌려준다. 확인하려면 어차피 GitHub 에 한 번 물어야 하고, 그 답이 곧 사용자가
// 화면에서 확인해야 하는 것이다 — 회사 토큰을 개인 프로필로 저장하는 것은 그 자리에서만 막힌다.
func verifyAndStoreToken(profileName, token string, tokenStore secretstore.TokenStore, newAccess RepositoryAccessFactory) (github.RepositoryAccess, github.Owner, error) {
	access := newAccess(token)

	owner, err := access.AuthenticatedOwner()
	if err != nil {
		return nil, github.Owner{}, err
	}

	if err := tokenStore.SaveToken(profileName, token); err != nil {
		return nil, github.Owner{}, fmt.Errorf("토큰 저장 실패: %w", err)
	}
	return access, owner, nil
}

// warnWhenTheTokenWouldBeStoredInPlaintext 는 이 머신에 키체인이 없다는 사실을 토큰을 받기 전에 알린다.
//
// 받은 뒤에 알리면 사용자가 할 수 있는 일은 이미 저장된 것을 지우는 것뿐이다.
func warnWhenTheTokenWouldBeStoredInPlaintext(errOut io.Writer, tokenStore secretstore.TokenStore) error {
	if tokenStore.StorageKindForNewToken() != secretstore.StorageConfigFile {
		return nil
	}
	if _, err := fmt.Fprint(errOut, plaintextStorageWarning); err != nil {
		return fmt.Errorf("평문 저장 경고 출력 실패: %w", err)
	}
	return nil
}

// writeTokenStoredNotice 는 어느 계정의 토큰을 어디에 저장했는지 알린다.
func writeTokenStoredNotice(out io.Writer, profileName string, owner github.Owner, tokenStore secretstore.TokenStore) {
	fmt.Fprintf(out, "토큰 확인 완료 — %s 계정입니다.\n", owner.Login)
	fmt.Fprintf(out, "토큰을 저장했습니다: %s (%s)\n", profileName, tokenStore.StorageKindForNewToken().Description())
}
