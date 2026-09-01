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

	if !strings.Contains(out.String(), "kyu auth add") {
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
