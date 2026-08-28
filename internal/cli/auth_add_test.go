package cli

import (
	"bytes"
	"encoding/json"
	"strings"
	"testing"

	"github.com/maximinhan/Kyuchestration/internal/secretstore"
)

func TestAuthAddReadsTheTokenFromStdinAndStoresItUnderTheGivenName(t *testing.T) {
	// 이 명령의 값어치가 이 한 줄이다 — 물음 없이 토큰 하나가 프로필이 된다.
	// GUI 는 대화를 이어갈 수 없으므로 이 길이 없으면 앱 안에서 토큰을 등록할 방법이 없다.
	tokenStore := newFakeTokenStore(secretstore.StorageKeychain)
	gitHub := newTestGitHub()

	var out, errOut bytes.Buffer
	err := ManageTokenProfiles(strings.NewReader(validTestToken+"\n"), &out, &errOut,
		[]string{"add", "개인"}, gitHub.newAccess, tokenStore)
	if err != nil {
		t.Fatalf("ManageTokenProfiles() 실패: %v", err)
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
	if strings.Contains(out.String(), validTestToken) || strings.Contains(errOut.String(), validTestToken) {
		t.Errorf("출력 = %q / %q, 토큰 값을 어느 스트림에도 찍지 않기를 기대", out.String(), errOut.String())
	}
}

func TestAuthAddDoesNotStoreATokenThatGitHubRejects(t *testing.T) {
	// 거절당한 토큰을 저장하면 다음 실행에서도 같은 실패가 반복된다. 대화형 등록은 그 자리에서
	// 되묻지만, 기계용 표면에는 되물을 상대가 없으므로 실패로 끝나야 한다.
	tokenStore := newFakeTokenStore(secretstore.StorageKeychain)
	gitHub := newTestGitHub()

	var out, errOut bytes.Buffer
	err := ManageTokenProfiles(strings.NewReader("잘못된-토큰\n"), &out, &errOut,
		[]string{"add", "개인"}, gitHub.newAccess, tokenStore)
	if err == nil {
		t.Fatal("ManageTokenProfiles() 가 거절당한 토큰을 성공으로 끝냈습니다")
	}

	if _, loadErr := tokenStore.LoadToken("개인"); loadErr == nil {
		t.Error("거절당한 토큰이 저장됐습니다")
	}
	if strings.Contains(err.Error(), "잘못된-토큰") {
		t.Errorf("에러 = %q, 토큰 값을 에러에 싣지 않기를 기대", err.Error())
	}
}

func TestAuthAddRefusesATokenGivenAsAnArgument(t *testing.T) {
	// 토큰을 인자로 받으면 같은 머신의 다른 사용자가 ps 로 읽는다. 그 길을 열어두지 않았다는 것을
	// "인자를 하나 더 받으면 거절한다" 로 못박는다 — 조용히 무시하면 사용자는 등록됐다고 믿는다.
	tokenStore := newFakeTokenStore(secretstore.StorageKeychain)

	var out, errOut bytes.Buffer
	err := ManageTokenProfiles(strings.NewReader(""), &out, &errOut,
		[]string{"add", "개인", validTestToken}, newTestGitHub().newAccess, tokenStore)
	if err == nil {
		t.Fatal("ManageTokenProfiles() 가 인자로 넘긴 토큰을 받아들였습니다")
	}
	if !strings.Contains(err.Error(), "사용법: kyu auth") {
		t.Errorf("에러 = %q, 사용법 안내를 기대", err.Error())
	}
	if len(tokenStore.tokensByProfileName) != 0 {
		t.Errorf("저장된 프로필 = %v, 아무것도 저장하지 않기를 기대", tokenStore.tokensByProfileName)
	}
}

func TestAuthAddWithoutATokenOnStdinSaysWhereTheTokenShouldCome(t *testing.T) {
	// 빈 stdin 으로 성공하면 GUI 는 등록됐다고 믿고 다음 걸음으로 넘어간다.
	tokenStore := newFakeTokenStore(secretstore.StorageKeychain)

	var out, errOut bytes.Buffer
	err := ManageTokenProfiles(strings.NewReader(""), &out, &errOut,
		[]string{"add", "개인"}, newTestGitHub().newAccess, tokenStore)
	if err == nil {
		t.Fatal("ManageTokenProfiles() 가 토큰 없이 성공했습니다")
	}
	if !strings.Contains(err.Error(), "stdin") {
		t.Errorf("에러 = %q, 토큰을 어디로 넘기는지 알리기를 기대", err.Error())
	}
}

func TestAuthAddNeedsAProfileName(t *testing.T) {
	// 이름 없는 프로필은 목록에서 고를 수도 지울 수도 없다.
	var out, errOut bytes.Buffer
	err := ManageTokenProfiles(strings.NewReader(validTestToken+"\n"), &out, &errOut,
		[]string{"add"}, newTestGitHub().newAccess, newFakeTokenStore(secretstore.StorageKeychain))
	if err == nil {
		t.Fatal("ManageTokenProfiles() 가 이름 없이 성공했습니다")
	}
	if !strings.Contains(err.Error(), "사용법: kyu auth") {
		t.Errorf("에러 = %q, 사용법 안내를 기대", err.Error())
	}
}

func TestAuthAddAnnouncesPlaintextStorageBeforeReadingTheToken(t *testing.T) {
	// 사용자의 원칙은 비밀을 평문으로 두지 않는 것이다. 대화형 등록이 지키는 이 경고를
	// 기계용 표면이 조용히 건너뛰면, 같은 머신에서 같은 일을 하는데 한쪽만 알려주게 된다.
	tokenStore := newFakeTokenStore(secretstore.StorageConfigFile)

	var out, errOut bytes.Buffer
	if err := ManageTokenProfiles(strings.NewReader(validTestToken+"\n"), &out, &errOut,
		[]string{"add", "개인"}, newTestGitHub().newAccess, tokenStore); err != nil {
		t.Fatalf("ManageTokenProfiles() 실패: %v", err)
	}

	warning := errOut.String()
	if !strings.Contains(warning, "평문") || !strings.Contains(warning, "0600") {
		t.Errorf("errOut = %q, 평문 저장과 권한을 알리기를 기대", warning)
	}
}

func TestAuthAddKeepsStdoutFreeOfTheQuestionItAsked(t *testing.T) {
	// 물음이 stdout 에 섞이면 --json 을 붙이는 순간 읽는 쪽의 파싱이 통째로 깨진다.
	// 사람용 모드에서도 물음은 명령의 결과가 아니다.
	tokenStore := newFakeTokenStore(secretstore.StorageKeychain)

	var out, errOut bytes.Buffer
	if err := ManageTokenProfiles(strings.NewReader(validTestToken+"\n"), &out, &errOut,
		[]string{"add", "개인"}, newTestGitHub().newAccess, tokenStore); err != nil {
		t.Fatalf("ManageTokenProfiles() 실패: %v", err)
	}

	if strings.Contains(out.String(), "personal access token") {
		t.Errorf("stdout = %q, 물음은 stderr 로 나가기를 기대", out.String())
	}
}

// authAddJSONDocumentForTest 는 kyu auth add --json 이 내보내는 문서를 테스트가 직접 다시 적어둔 모양이다.
//
// 생산 코드의 직렬화 타입을 재사용하지 않는다. 그것을 쓰면 필드 이름을 바꿔도 테스트가 함께
// 따라 움직여, 계약이 바뀐 사실이 어디에서도 드러나지 않는다. 읽는 쪽(GUI)은 자기 코드에
// 이 모양을 적어두고 그것에 맞춰 파싱하므로, 테스트도 똑같이 적어두어야 여기에서 먼저 깨진다.
type authAddJSONDocumentForTest struct {
	SchemaVersion int    `json:"schemaVersion"`
	Profile       string `json:"profile"`
	Login         string `json:"login"`
}

func TestAuthAddWithJSONTellsTheCallerWhichAccountTheTokenBelongsTo(t *testing.T) {
	// GUI 가 보는 자리다. 앱은 "저장됐다" 만으로는 사용자에게 무엇이 저장됐는지 보여줄 수 없다.
	tokenStore := newFakeTokenStore(secretstore.StorageKeychain)

	var out, errOut bytes.Buffer
	if err := ManageTokenProfiles(strings.NewReader(validTestToken+"\n"), &out, &errOut,
		[]string{"add", "개인", machineJSONOptionName}, newTestGitHub().newAccess, tokenStore); err != nil {
		t.Fatalf("ManageTokenProfiles() 실패: %v", err)
	}

	var document authAddJSONDocumentForTest
	if err := json.Unmarshal(out.Bytes(), &document); err != nil {
		t.Fatalf("stdout 을 JSON 으로 읽지 못했습니다: %v\n--- stdout ---\n%s", err, out.String())
	}

	if document.SchemaVersion != 1 {
		t.Errorf("schemaVersion = %d, want 1", document.SchemaVersion)
	}
	if document.Profile != "개인" {
		t.Errorf("profile = %q, want %q", document.Profile, "개인")
	}
	if document.Login != "maximinhan" {
		t.Errorf("login = %q, want %q", document.Login, "maximinhan")
	}
	if strings.Contains(out.String(), validTestToken) {
		t.Errorf("stdout = %q, 토큰 값을 문서에 싣지 않기를 기대", out.String())
	}
}

func TestAuthAddWithJSONPutsNothingButTheDocumentOnStdout(t *testing.T) {
	// 사람용 안내가 한 줄이라도 섞이면 읽는 쪽의 파싱이 통째로 깨진다.
	tokenStore := newFakeTokenStore(secretstore.StorageConfigFile)

	var out, errOut bytes.Buffer
	if err := ManageTokenProfiles(strings.NewReader(validTestToken+"\n"), &out, &errOut,
		[]string{"add", "개인", machineJSONOptionName}, newTestGitHub().newAccess, tokenStore); err != nil {
		t.Fatalf("ManageTokenProfiles() 실패: %v", err)
	}

	// 평문 경고가 나가는 저장소를 골랐다. 그 경고가 stdout 으로 새면 여기에서 걸린다.
	if !strings.Contains(errOut.String(), "평문") {
		t.Errorf("errOut = %q, 평문 저장 경고를 기대", errOut.String())
	}
	if strings.Contains(out.String(), "토큰을 저장했습니다") || strings.Contains(out.String(), "평문") {
		t.Errorf("stdout = %q, 문서 말고 아무것도 나오지 않기를 기대", out.String())
	}
}
