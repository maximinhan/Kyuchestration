package cli

import (
	"bytes"
	"strings"
	"testing"

	"github.com/maximinhan/Kyuchestration/internal/github"
	"github.com/maximinhan/Kyuchestration/internal/secretstore"
)

const validTestToken = "유효한-토큰"

func newTestGitHub() *fakeGitHub {
	return &fakeGitHub{
		acceptedToken: validTestToken,
		owner:         github.Owner{Login: "maximinhan"},
	}
}

func TestFirstRunRegistersATokenAndStoresItUnderTheNameTheUserGave(t *testing.T) {
	// 저장된 프로필이 하나도 없을 때다. 여기서 사용자를 목록으로 데려가면 고를 것이 없는 목록을
	// 보게 되므로, 곧바로 등록으로 간다.
	tokenStore := newFakeTokenStore(secretstore.StorageKeychain)
	gitHub := newTestGitHub()

	input := strings.NewReader("개인\n" + validTestToken + "\n")
	var out, errOut bytes.Buffer

	_, owner, err := authenticateWithTokenProfile(newInteractivePrompt(input, &out), &errOut, tokenStore, gitHub.newAccess)
	if err != nil {
		t.Fatalf("authenticateWithTokenProfile() 실패: %v", err)
	}

	if owner.Login != "maximinhan" {
		t.Errorf("소유자 = %+v, 토큰의 주인을 기대", owner)
	}

	storedToken, err := tokenStore.LoadToken("개인")
	if err != nil {
		t.Fatalf("등록한 프로필을 꺼내지 못했습니다: %v", err)
	}
	if storedToken != validTestToken {
		t.Errorf("저장된 토큰 = %q, want %q", storedToken, validTestToken)
	}

	// 어느 계정의 토큰인지 그 자리에서 보여줘야 한다. 회사 토큰을 개인 프로필로 저장하는 것은
	// 이 화면에서만 막을 수 있다.
	if !strings.Contains(out.String(), "maximinhan") {
		t.Errorf("출력 = %q, 토큰의 계정을 보여주기를 기대", out.String())
	}
	if strings.Contains(out.String(), validTestToken) {
		t.Errorf("출력 = %q, 토큰 값을 화면에 찍지 않기를 기대", out.String())
	}
}

func TestRejectedTokenIsNotStoredAndTheUserGetsAnotherTry(t *testing.T) {
	// 토큰을 붙여넣다 한 글자를 흘리는 것은 흔한 실수다. 그때 저장부터 하면 다음 실행에서도
	// 같은 실패가 반복되고, 사용자는 자기가 등록한 것이 무엇인지 확인할 방법이 없다.
	tokenStore := newFakeTokenStore(secretstore.StorageKeychain)
	gitHub := newTestGitHub()

	input := strings.NewReader("개인\n잘못-붙여넣은-토큰\n" + validTestToken + "\n")
	var out, errOut bytes.Buffer

	if _, _, err := authenticateWithTokenProfile(newInteractivePrompt(input, &out), &errOut, tokenStore, gitHub.newAccess); err != nil {
		t.Fatalf("authenticateWithTokenProfile() 실패: %v", err)
	}

	storedToken, err := tokenStore.LoadToken("개인")
	if err != nil {
		t.Fatalf("등록한 프로필을 꺼내지 못했습니다: %v", err)
	}
	if storedToken != validTestToken {
		t.Errorf("저장된 토큰 = %q, 거절당한 토큰이 아니라 두 번째 토큰을 기대", storedToken)
	}
	if !strings.Contains(errOut.String(), "거절") {
		t.Errorf("errOut = %q, 토큰이 거절됐다는 안내를 기대", errOut.String())
	}
}

func TestPlaintextFallbackIsAnnouncedBeforeTheTokenIsAsked(t *testing.T) {
	// 사용자의 원칙은 비밀을 평문으로 두지 않는 것이다. 이 머신에서 그것을 지킬 수 없다면,
	// 최소한 토큰을 붙여넣기 전에 알려야 사용자가 그만둘 기회를 갖는다.
	tokenStore := newFakeTokenStore(secretstore.StorageConfigFile)
	gitHub := newTestGitHub()

	input := strings.NewReader("개인\n" + validTestToken + "\n")
	var out, errOut bytes.Buffer

	if _, _, err := authenticateWithTokenProfile(newInteractivePrompt(input, &out), &errOut, tokenStore, gitHub.newAccess); err != nil {
		t.Fatalf("authenticateWithTokenProfile() 실패: %v", err)
	}

	warning := errOut.String()
	if !strings.Contains(warning, "평문") || !strings.Contains(warning, "0600") {
		t.Errorf("errOut = %q, 평문 저장과 권한을 알리기를 기대", warning)
	}
}

func TestStoredProfileIsChosenByNumberAndItsOwnTokenIsUsed(t *testing.T) {
	// 프로필이 여럿이면 어느 것으로 붙었는지가 곧 어느 계정의 레포를 보게 되는지다.
	tokenStore := newFakeTokenStore(secretstore.StorageKeychain)
	if err := tokenStore.SaveToken("회사", "회사-토큰"); err != nil {
		t.Fatalf("준비 실패: %v", err)
	}
	if err := tokenStore.SaveToken("개인", validTestToken); err != nil {
		t.Fatalf("준비 실패: %v", err)
	}
	gitHub := newTestGitHub()

	input := strings.NewReader("2\n")
	var out, errOut bytes.Buffer

	if _, _, err := authenticateWithTokenProfile(newInteractivePrompt(input, &out), &errOut, tokenStore, gitHub.newAccess); err != nil {
		t.Fatalf("authenticateWithTokenProfile() 실패: %v", err)
	}

	if len(gitHub.tokensAskedFor) != 1 || gitHub.tokensAskedFor[0] != validTestToken {
		t.Errorf("붙는 데 쓴 토큰 = %v, 두 번째 프로필의 토큰을 기대", gitHub.tokensAskedFor)
	}
	if strings.Contains(out.String(), "회사-토큰") || strings.Contains(out.String(), validTestToken) {
		t.Errorf("출력 = %q, 토큰 값을 화면에 찍지 않기를 기대", out.String())
	}
}

func TestLastOptionOfTheProfileListRegistersANewToken(t *testing.T) {
	// 토큰을 새로 발급받았을 때 들어오는 길이다. 이것이 없으면 프로필이 하나라도 있는 사용자는
	// 새 토큰을 등록할 방법을 찾지 못한다.
	tokenStore := newFakeTokenStore(secretstore.StorageKeychain)
	if err := tokenStore.SaveToken("회사", "회사-토큰"); err != nil {
		t.Fatalf("준비 실패: %v", err)
	}
	gitHub := newTestGitHub()

	input := strings.NewReader("2\n개인\n" + validTestToken + "\n")
	var out, errOut bytes.Buffer

	if _, _, err := authenticateWithTokenProfile(newInteractivePrompt(input, &out), &errOut, tokenStore, gitHub.newAccess); err != nil {
		t.Fatalf("authenticateWithTokenProfile() 실패: %v", err)
	}

	storedToken, err := tokenStore.LoadToken("개인")
	if err != nil {
		t.Fatalf("새로 등록한 프로필을 꺼내지 못했습니다: %v", err)
	}
	if storedToken != validTestToken {
		t.Errorf("저장된 토큰 = %q, want %q", storedToken, validTestToken)
	}
}

func TestStoredTokenThatGitHubNowRejectsLeadsToReRegistrationOfTheSameProfile(t *testing.T) {
	// 토큰은 만료되거나 폐기된다. 그때 "거절됐습니다" 로 끝내면 사용자는 kyu auth remove 로 지우고
	// 다시 등록하는 두 걸음을 스스로 찾아야 한다.
	tokenStore := newFakeTokenStore(secretstore.StorageKeychain)
	if err := tokenStore.SaveToken("개인", "만료된-토큰"); err != nil {
		t.Fatalf("준비 실패: %v", err)
	}
	gitHub := newTestGitHub()

	input := strings.NewReader("1\n" + validTestToken + "\n")
	var out, errOut bytes.Buffer

	if _, _, err := authenticateWithTokenProfile(newInteractivePrompt(input, &out), &errOut, tokenStore, gitHub.newAccess); err != nil {
		t.Fatalf("authenticateWithTokenProfile() 실패: %v", err)
	}

	storedToken, err := tokenStore.LoadToken("개인")
	if err != nil {
		t.Fatalf("프로필을 꺼내지 못했습니다: %v", err)
	}
	if storedToken != validTestToken {
		t.Errorf("저장된 토큰 = %q, 같은 프로필에 새 토큰이 덮이기를 기대", storedToken)
	}
}

func TestAuthListShowsNamesAndStorageButNeverTheToken(t *testing.T) {
	tokenStore := newFakeTokenStore(secretstore.StorageConfigFile)
	if err := tokenStore.SaveToken("개인", validTestToken); err != nil {
		t.Fatalf("준비 실패: %v", err)
	}

	var out, errOut bytes.Buffer
	if err := ManageTokenProfiles(nil, &out, &errOut, []string{"list"}, nil, tokenStore); err != nil {
		t.Fatalf("ManageTokenProfiles() 실패: %v", err)
	}

	if !strings.Contains(out.String(), "개인") {
		t.Errorf("출력 = %q, 프로필 이름을 기대", out.String())
	}
	if !strings.Contains(out.String(), secretstore.StorageConfigFile.Description()) {
		t.Errorf("출력 = %q, 저장 위치를 기대", out.String())
	}
	// 목록을 화면에 띄운 채 화면을 공유하거나 캡처하는 일이 흔하다.
	if strings.Contains(out.String(), validTestToken) {
		t.Errorf("출력 = %q, 토큰 값을 절대 찍지 않기를 기대", out.String())
	}
}

func TestAuthListWithoutAnyProfileTellsHowOneGetsRegistered(t *testing.T) {
	var out, errOut bytes.Buffer
	if err := ManageTokenProfiles(nil, &out, &errOut, []string{"list"}, nil, newFakeTokenStore(secretstore.StorageKeychain)); err != nil {
		t.Fatalf("ManageTokenProfiles() 실패: %v", err)
	}

	if !strings.Contains(out.String(), "kyu clone") {
		t.Errorf("출력 = %q, 등록이 일어나는 자리를 알리기를 기대", out.String())
	}
}

func TestAuthRemoveDeletesTheProfileAndSaysWhichOne(t *testing.T) {
	tokenStore := newFakeTokenStore(secretstore.StorageKeychain)
	if err := tokenStore.SaveToken("개인", validTestToken); err != nil {
		t.Fatalf("준비 실패: %v", err)
	}

	var out, errOut bytes.Buffer
	if err := ManageTokenProfiles(nil, &out, &errOut, []string{"remove", "개인"}, nil, tokenStore); err != nil {
		t.Fatalf("ManageTokenProfiles() 실패: %v", err)
	}

	if _, err := tokenStore.LoadToken("개인"); err == nil {
		t.Error("프로필이 남아 있습니다")
	}
	if !strings.Contains(out.String(), "개인") {
		t.Errorf("출력 = %q, 무엇을 지웠는지 알리기를 기대", out.String())
	}
}

func TestAuthRemoveOfAnUnknownProfileListsTheOnesThatExist(t *testing.T) {
	// 이름을 잘못 적었을 때 "없습니다" 만 말하면 사용자는 정확한 이름을 찾으러 목록을 다시 열어야 한다.
	tokenStore := newFakeTokenStore(secretstore.StorageKeychain)
	if err := tokenStore.SaveToken("개인", validTestToken); err != nil {
		t.Fatalf("준비 실패: %v", err)
	}

	var out, errOut bytes.Buffer
	err := ManageTokenProfiles(nil, &out, &errOut, []string{"remove", "게인"}, nil, tokenStore)
	if err == nil {
		t.Fatal("ManageTokenProfiles() 가 없는 프로필을 지웠다고 답했습니다")
	}
	if !strings.Contains(err.Error(), "개인") {
		t.Errorf("에러 = %q, 등록된 프로필 이름을 함께 알리기를 기대", err.Error())
	}
}

func TestAuthWithoutASubcommandExplainsWhatItCanDo(t *testing.T) {
	var out, errOut bytes.Buffer
	err := ManageTokenProfiles(nil, &out, &errOut, nil, nil, newFakeTokenStore(secretstore.StorageKeychain))
	if err == nil {
		t.Fatal("ManageTokenProfiles() 가 인자 없이 성공했습니다")
	}
	if !strings.Contains(err.Error(), "사용법: kyu auth") {
		t.Errorf("에러 = %q, 사용법 안내를 기대", err.Error())
	}
}
