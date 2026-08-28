package cli

import (
	"bytes"
	"encoding/json"
	"io"
	"strings"
	"testing"

	"github.com/maximinhan/Kyuchestration/internal/secretstore"
)

// authListJSONDocumentForTest 는 kyu auth list --json 이 내보내는 문서를 테스트가 직접 다시 적어둔 모양이다.
type authListJSONDocumentForTest struct {
	SchemaVersion int                          `json:"schemaVersion"`
	Profiles      []authListJSONProfileForTest `json:"profiles"`
}

type authListJSONProfileForTest struct {
	Name    string `json:"name"`
	Storage string `json:"storage"`
}

// decodeMachineJSONForTest 는 stdout 에 문서 하나만 있는지 확인하고 그것을 되읽는다.
//
// 되읽는 것만으로는 부족하다. 안내 한 줄이 문서 뒤에 붙어도 앞부분만 파싱하면 통과해버리는데,
// 읽는 쪽(GUI)은 스트림 전체를 하나의 문서로 다루므로 그 한 줄에 통째로 걸려 넘어진다.
// --json 을 보는 모든 테스트가 그 사실을 함께 지키도록 여기 한 곳에 둔다.
func decodeMachineJSONForTest(t *testing.T, stdout string, target any) {
	t.Helper()

	reader := strings.NewReader(stdout)
	decoder := json.NewDecoder(reader)

	if err := decoder.Decode(target); err != nil {
		t.Fatalf("stdout 을 JSON 으로 읽지 못했습니다: %v\n--- stdout ---\n%s", err, stdout)
	}

	// Decoder 가 미리 읽어둔 것과 아직 읽지 않은 것을 모두 합쳐야 "문서 뒤에 아무것도 없다" 를 말할 수 있다.
	trailing, err := io.ReadAll(io.MultiReader(decoder.Buffered(), reader))
	if err != nil {
		t.Fatalf("stdout 의 남은 바이트를 읽지 못했습니다: %v", err)
	}
	if strings.TrimSpace(string(trailing)) != "" {
		t.Fatalf("stdout 에 문서 말고 다른 것이 있습니다: %q", string(trailing))
	}
}

// runAuthListJSONForTest 는 auth list --json 을 실행하고 stdout 을 문서로 되읽는다.
func runAuthListJSONForTest(t *testing.T, tokenStore secretstore.TokenStore) authListJSONDocumentForTest {
	t.Helper()

	var out, errOut bytes.Buffer
	if err := ManageTokenProfiles(nil, &out, &errOut, []string{"list", machineJSONOptionName}, nil, tokenStore); err != nil {
		t.Fatalf("ManageTokenProfiles() 실패: %v", err)
	}

	var document authListJSONDocumentForTest
	decodeMachineJSONForTest(t, out.String(), &document)
	return document
}

func TestAuthListWithJSONNamesEveryProfileAndWhereItsTokenLives(t *testing.T) {
	// GUI 의 첫 화면이 보는 자리다 — 어느 프로필로 클론할지 고르게 하려면 이름이 필요하고,
	// 저장 위치는 "이 머신에서는 평문으로 저장된다" 를 사용자에게 알리는 근거다.
	tokenStore := newFakeTokenStore(secretstore.StorageSecretService)
	for _, profileName := range []string{"개인", "회사"} {
		if err := tokenStore.SaveToken(profileName, validTestToken); err != nil {
			t.Fatalf("준비 실패: %v", err)
		}
	}

	document := runAuthListJSONForTest(t, tokenStore)

	if document.SchemaVersion != 1 {
		t.Errorf("schemaVersion = %d, want 1", document.SchemaVersion)
	}
	want := []authListJSONProfileForTest{
		{Name: "개인", Storage: "secret-service"},
		{Name: "회사", Storage: "secret-service"},
	}
	if len(document.Profiles) != len(want) {
		t.Fatalf("profiles = %+v, want %+v", document.Profiles, want)
	}
	for profileIndex, wantProfile := range want {
		if document.Profiles[profileIndex] != wantProfile {
			t.Errorf("profiles[%d] = %+v, want %+v", profileIndex, document.Profiles[profileIndex], wantProfile)
		}
	}
}

func TestAuthListWithJSONCarriesTheStorageCodeNotTheScreenWording(t *testing.T) {
	// 사람용 목록이 보여주는 "macOS 키체인" 은 언제든 다듬을 수 있는 화면의 말이다.
	// 읽는 쪽이 그것으로 분기하면 문구를 고치는 순간 함께 깨진다.
	tokenStore := newFakeTokenStore(secretstore.StorageKeychain)
	if err := tokenStore.SaveToken("개인", validTestToken); err != nil {
		t.Fatalf("준비 실패: %v", err)
	}

	document := runAuthListJSONForTest(t, tokenStore)

	if document.Profiles[0].Storage != "keychain" {
		t.Errorf("storage = %q, want %q", document.Profiles[0].Storage, "keychain")
	}
	if document.Profiles[0].Storage == secretstore.StorageKeychain.Description() {
		t.Errorf("storage = %q, 화면 문구가 아니라 코드를 기대", document.Profiles[0].Storage)
	}
}

func TestAuthListWithJSONNeverCarriesTheTokenValue(t *testing.T) {
	// 이 출력은 로그와 파일에 그대로 남기 쉽다. 사람용 목록이 토큰을 찍지 않는 이유가
	// 기계용에서 사라지지 않는다.
	tokenStore := newFakeTokenStore(secretstore.StorageConfigFile)
	if err := tokenStore.SaveToken("개인", validTestToken); err != nil {
		t.Fatalf("준비 실패: %v", err)
	}

	var out, errOut bytes.Buffer
	if err := ManageTokenProfiles(nil, &out, &errOut, []string{"list", machineJSONOptionName}, nil, tokenStore); err != nil {
		t.Fatalf("ManageTokenProfiles() 실패: %v", err)
	}

	if strings.Contains(out.String(), validTestToken) {
		t.Errorf("stdout = %q, 토큰 값을 절대 싣지 않기를 기대", out.String())
	}
}

func TestAuthListWithJSONGivesAnEmptyArrayWhenNothingIsRegistered(t *testing.T) {
	// 사람용 모드는 이 자리에서 등록 방법을 안내한다. 그 안내가 stdout 으로 나가면 읽는 쪽의
	// 파싱이 깨지므로, 기계용에서는 빈 배열이 그 사실 전부다.
	document := runAuthListJSONForTest(t, newFakeTokenStore(secretstore.StorageKeychain))

	if document.Profiles == nil {
		t.Error("profiles = null, 빈 배열을 기대 — 없음을 두 모양으로 다루게 하지 않는다")
	}
	if len(document.Profiles) != 0 {
		t.Errorf("profiles = %+v, 빈 배열을 기대", document.Profiles)
	}
}
