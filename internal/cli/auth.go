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

토큰은 인자가 아니라 stdin 으로 받는다 — 인자는 같은 머신의 다른 사용자가 ps 로 읽는다.`

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
const tokenQuestion = "GitHub personal access token (입력은 보이지 않습니다): "

// noTokenProfileGuidance 는 등록된 프로필이 하나도 없을 때 보여주는 다음 걸음이다.
//
// 빈 목록만 보여주면 사용자는 도구가 못 찾은 것인지 등록한 적이 없는 것인지 모른다.
const noTokenProfileGuidance = `등록된 토큰 프로필이 없습니다.

  kyu auth add <이름>   토큰을 stdin 으로 넘겨 등록한다
`

// RepositoryAccessFactory 는 토큰 하나를 GitHub 접근 경로로 바꾼다.
//
// 표시 계층이 구현 타입을 직접 만들지 않는 이유는 세션 백엔드와 같다 — 어느 구현을 쓸지는
// 진입점의 결정이고, 그래야 이 흐름을 네트워크 없이 테스트할 수 있다.
type RepositoryAccessFactory func(token string) github.RepositoryAccess

// ManageTokenProfiles 는 kyu auth 를 실행한다.
//
// 입력과 stderr 까지 받는 이유는 add 다. 토큰은 인자로 받지 않으므로 어딘가에서 읽어야 하고,
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

// accessWithStoredToken 은 이름으로 고른 프로필의 토큰으로 GitHub 에 붙는다.
//
// 저장된 토큰이 거절당하면 그 자리에서 새 토큰을 받지 않고 실패로 끝낸다. 되물을 상대가
// 없기 때문이다 — 등록을 이끄는 것은 앱의 화면이다.
//
// 붙은 계정까지 돌려준다. 토큰이 유효한지 확인하려면 어차피 GitHub 에 한 번 물어야 하고,
// 그 답이 곧 개인 계정이 누구인지다 — 뒤이은 소유자 목록이 같은 것을 다시 묻지 않아도 된다.
func accessWithStoredToken(profileName string, tokenStore secretstore.TokenStore, newAccess RepositoryAccessFactory) (github.RepositoryAccess, github.Owner, error) {
	token, err := tokenStore.LoadToken(profileName)
	if errors.Is(err, secretstore.ErrProfileNotFound) {
		// 없는 이름으로 끝내지 않고 등록된 이름을 함께 알린다. 앱은 그 목록으로 사용자에게
		// 무엇을 고르라고 할지 정할 수 있다.
		return nil, github.Owner{}, unknownProfileError(profileName, tokenStore)
	}
	if err != nil {
		return nil, github.Owner{}, fmt.Errorf("%s 프로필의 토큰을 꺼내지 못했습니다: %w", profileName, err)
	}

	access := newAccess(token)
	owner, err := access.AuthenticatedOwner()
	if err != nil {
		return nil, github.Owner{}, fmt.Errorf("%s 프로필로 GitHub 에 붙지 못했습니다: %w", profileName, err)
	}
	return access, owner, nil
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

// verifyAndStoreToken 은 토큰이 살아 있는지 GitHub 에 먼저 묻고, 그 답을 받은 뒤에만 저장한다.
//
// 이 순서가 이 함수의 전부다. 저장부터 하면 거절당한 토큰이 프로필에 남아 다음 실행에서도
// 같은 실패가 반복된다.
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
