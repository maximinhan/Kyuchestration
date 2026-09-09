package cli

import (
	"bytes"
	"encoding/json"
	"errors"
	"strings"
	"testing"

	"github.com/maximinhan/Kyuchestration/internal/secretstore"
)

// fakeClaudeTokenStore 는 저장소를 대신하는 시험용 구현이다.
//
// 진짜 저장소 왕복은 secretstore 가 자기 시험에서 확인한다. 여기서 보려는 것은 그 위의 규칙이다 —
// 토큰이 어디로 들어오고 어디로 나가는가, 문서에 무엇이 담기는가, 실패가 어떻게 끝나는가.
type fakeClaudeTokenStore struct {
	token              string
	isStored           bool
	storedStorage      secretstore.StorageKind
	storageForNewToken secretstore.StorageKind
	saveFailure        error
	removeCallCount    int
}

func (store *fakeClaudeTokenStore) SaveToken(token string) error {
	if store.saveFailure != nil {
		return store.saveFailure
	}
	store.token, store.isStored, store.storedStorage = token, true, store.storageForNewToken
	return nil
}

func (store *fakeClaudeTokenStore) LoadToken() (string, error) {
	if !store.isStored {
		return "", secretstore.ErrClaudeTokenNotStored
	}
	return store.token, nil
}

func (store *fakeClaudeTokenStore) RemoveToken() error {
	store.removeCallCount++
	store.token, store.isStored, store.storedStorage = "", false, ""
	return nil
}

func (store *fakeClaudeTokenStore) StoredStorageKind() (secretstore.StorageKind, bool, error) {
	return store.storedStorage, store.isStored, nil
}

func (store *fakeClaudeTokenStore) StorageKindForNewToken() secretstore.StorageKind {
	return store.storageForNewToken
}

func newFakeClaudeTokenStore() *fakeClaudeTokenStore {
	return &fakeClaudeTokenStore{storageForNewToken: secretstore.StorageKeychain}
}

// runClaudeAuth 는 명령을 한 번 돌리고 두 스트림을 돌려준다.
func runClaudeAuth(t *testing.T, standardInput string, store ClaudeTokenStore, args ...string) (string, string, error) {
	t.Helper()

	var out, errOut bytes.Buffer
	err := ManageClaudeCredentials(strings.NewReader(standardInput), &out, &errOut, args, store)
	return out.String(), errOut.String(), err
}

func TestSetStoresTheTokenGivenOnStandardInput(t *testing.T) {
	store := newFakeClaudeTokenStore()

	if _, _, err := runClaudeAuth(t, "sk-ant-oat01-가짜", store, "set"); err != nil {
		t.Fatalf("set 실패: %v", err)
	}

	if store.token != "sk-ant-oat01-가짜" {
		t.Errorf("저장된 토큰 = %q, stdin 으로 준 값을 기대", store.token)
	}
}

func TestSetKeepsTheTokenOutOfEveryStream(t *testing.T) {
	// 이 명령이 지나는 두 스트림 어디에도 값이 남아서는 안 된다. 앱은 stderr 를 오류 화면에
	// 그대로 올리고, stdout 은 로그로 넘어간다.
	const token = "sk-ant-oat01-절대-찍히면-안-되는-값"
	store := newFakeClaudeTokenStore()

	out, errOut, err := runClaudeAuth(t, token, store, "set")
	if err != nil {
		t.Fatalf("set 실패: %v", err)
	}

	if strings.Contains(out, token) {
		t.Errorf("stdout 에 토큰이 있습니다:\n%s", out)
	}
	if strings.Contains(errOut, token) {
		t.Errorf("stderr 에 토큰이 있습니다:\n%s", errOut)
	}
}

func TestSetWithoutATokenFailsInsteadOfStoringNothingQuietly(t *testing.T) {
	// 빈 입력을 성공으로 끝내면 앱은 등록이 됐다고 믿고 인증 화면을 닫는다.
	store := newFakeClaudeTokenStore()

	_, _, err := runClaudeAuth(t, "", store, "set")
	if err == nil {
		t.Fatal("빈 입력이 성공으로 끝났습니다, 거절하기를 기대")
	}
	if store.isStored {
		t.Error("아무것도 받지 않았는데 저장했습니다")
	}
	if !strings.Contains(err.Error(), "stdin") {
		t.Errorf("실패 문구 = %q, 토큰을 어디로 넘기는지 알리기를 기대", err)
	}
}

func TestSetWarnsBeforeStoringIntoAPlaintextFile(t *testing.T) {
	// 받은 뒤에 알리면 사용자가 할 수 있는 일은 이미 저장된 것을 지우는 것뿐이다.
	store := newFakeClaudeTokenStore()
	store.storageForNewToken = secretstore.StorageConfigFile

	_, errOut, err := runClaudeAuth(t, "sk-ant-oat01-가짜", store, "set")
	if err != nil {
		t.Fatalf("set 실패: %v", err)
	}
	if !strings.Contains(errOut, "평문") {
		t.Errorf("stderr = %q, 평문 저장 경고를 기대", errOut)
	}
}

func TestSetJSONReportsWhereTheTokenLandedAndNothingElse(t *testing.T) {
	const token = "sk-ant-oat01-가짜"
	store := newFakeClaudeTokenStore()

	out, _, err := runClaudeAuth(t, token, store, "set", "--json")
	if err != nil {
		t.Fatalf("set --json 실패: %v", err)
	}

	var document struct {
		SchemaVersion int    `json:"schemaVersion"`
		Storage       string `json:"storage"`
	}
	if err := json.Unmarshal([]byte(out), &document); err != nil {
		t.Fatalf("문서를 읽지 못했습니다: %v (%s)", err, out)
	}
	if document.SchemaVersion != claudeAuthSetJSONSchemaVersion {
		t.Errorf("schemaVersion = %d, want %d", document.SchemaVersion, claudeAuthSetJSONSchemaVersion)
	}
	if document.Storage != string(secretstore.StorageKeychain) {
		t.Errorf("storage = %q, want %q", document.Storage, secretstore.StorageKeychain)
	}
	if strings.Contains(out, token) {
		t.Errorf("문서에 토큰이 있습니다:\n%s", out)
	}
}

func TestTokenWritesTheStoredValueWithNothingAddedToIt(t *testing.T) {
	// 앱이 이 값을 그대로 자식 환경에 싣는다. 개행 한 글자가 붙으면 그 환경변수는 다른 값이 된다.
	const token = "sk-ant-oat01-가짜"
	store := newFakeClaudeTokenStore()
	store.token, store.isStored, store.storedStorage = token, true, secretstore.StorageKeychain

	out, _, err := runClaudeAuth(t, "", store, "token")
	if err != nil {
		t.Fatalf("token 실패: %v", err)
	}
	if out != token {
		t.Errorf("stdout = %q, 저장된 값 %q 를 그대로 기대", out, token)
	}
}

func TestTokenFailsWhenNothingIsStoredInsteadOfPrintingAnEmptyLine(t *testing.T) {
	// 빈 값을 성공으로 내면 앱은 그것을 토큰으로 알고 세션 환경에 싣는다.
	store := newFakeClaudeTokenStore()

	out, _, err := runClaudeAuth(t, "", store, "token")
	if err == nil {
		t.Fatalf("저장된 것이 없는데 성공으로 끝났습니다 (stdout: %q)", out)
	}
	if out != "" {
		t.Errorf("stdout = %q, 아무것도 내지 않기를 기대", out)
	}
}

func TestStatusAnswersWithoutEverReadingTheToken(t *testing.T) {
	// 화면 공유 중에 상태를 확인하는 것만으로 위험해져서는 안 된다.
	const token = "sk-ant-oat01-절대-찍히면-안-되는-값"
	store := newFakeClaudeTokenStore()
	store.token, store.isStored, store.storedStorage = token, true, secretstore.StorageSecretService

	out, errOut, err := runClaudeAuth(t, "", store, "status")
	if err != nil {
		t.Fatalf("status 실패: %v", err)
	}
	if strings.Contains(out+errOut, token) {
		t.Errorf("상태 출력에 토큰이 있습니다:\n%s%s", out, errOut)
	}
	if !strings.Contains(out, secretstore.StorageSecretService.Description()) {
		t.Errorf("stdout = %q, 저장 위치를 알리기를 기대", out)
	}
}

func TestStatusJSONTellsWhereANewTokenWouldLandEvenWhenNothingIsStored(t *testing.T) {
	// 화면이 평문 경고를 저장 전에 내려면 이 값이 저장 여부와 무관하게 와야 한다.
	store := newFakeClaudeTokenStore()
	store.storageForNewToken = secretstore.StorageConfigFile

	out, _, err := runClaudeAuth(t, "", store, "status", "--json")
	if err != nil {
		t.Fatalf("status --json 실패: %v", err)
	}

	var document struct {
		SchemaVersion      int    `json:"schemaVersion"`
		Stored             bool   `json:"stored"`
		Storage            string `json:"storage"`
		StorageForNewToken string `json:"storageForNewToken"`
	}
	if err := json.Unmarshal([]byte(out), &document); err != nil {
		t.Fatalf("문서를 읽지 못했습니다: %v (%s)", err, out)
	}
	if document.SchemaVersion != claudeAuthStatusJSONSchemaVersion {
		t.Errorf("schemaVersion = %d, want %d", document.SchemaVersion, claudeAuthStatusJSONSchemaVersion)
	}
	if document.Stored {
		t.Error("stored = true, 저장한 적이 없으면 false 를 기대")
	}
	if document.Storage != "" {
		t.Errorf("storage = %q, 저장된 것이 없으면 빈 문자열을 기대", document.Storage)
	}
	if document.StorageForNewToken != string(secretstore.StorageConfigFile) {
		t.Errorf("storageForNewToken = %q, want %q", document.StorageForNewToken, secretstore.StorageConfigFile)
	}
}

func TestClearSucceedsWhenThereWasNothingToClear(t *testing.T) {
	// 부르는 사람이 원한 것은 "내 토큰이 남아 있지 않은 상태" 이고, 그 상태는 이미 이뤄져 있다.
	store := newFakeClaudeTokenStore()

	if _, _, err := runClaudeAuth(t, "", store, "clear"); err != nil {
		t.Fatalf("clear 실패: %v", err)
	}
	if store.removeCallCount != 1 {
		t.Errorf("RemoveToken 호출 = %d, 한 번을 기대", store.removeCallCount)
	}
}

func TestUnknownSubcommandAndStrayArgumentsAreRefusedWithTheUsageText(t *testing.T) {
	// 조용히 흘려보내면 오타 난 호출이 성공으로 끝나고, 부르는 쪽은 자기가 시킨 일이 됐다고 믿는다.
	store := newFakeClaudeTokenStore()

	for _, args := range [][]string{
		{},
		{"reset"},
		{"token", "--json"},
		{"clear", "지금"},
		{"status", "--jsonn"},
	} {
		if _, _, err := runClaudeAuth(t, "", store, args...); err == nil {
			t.Errorf("claude-auth %v 가 성공으로 끝났습니다, 거절하기를 기대", args)
		}
	}
}

func TestSaveFailureIsReportedInsteadOfLookingLikeSuccess(t *testing.T) {
	store := newFakeClaudeTokenStore()
	store.saveFailure = errors.New("키체인이 잠겨 있습니다")

	out, _, err := runClaudeAuth(t, "sk-ant-oat01-가짜", store, "set", "--json")
	if err == nil {
		t.Fatal("저장에 실패했는데 성공으로 끝났습니다")
	}
	if out != "" {
		t.Errorf("stdout = %q, 실패했을 때는 문서를 내지 않기를 기대", out)
	}
	if !strings.Contains(err.Error(), "키체인이 잠겨 있습니다") {
		t.Errorf("실패 문구 = %q, 원인을 그대로 싣기를 기대", err)
	}
}
