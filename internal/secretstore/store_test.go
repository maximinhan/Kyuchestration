package secretstore

import (
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// storedTestToken 은 저장 테스트가 넣고 꺼내는 값이다.
const storedTestToken = "테스트-토큰-값"

// newFileBackedStoreForTest 는 임시 디렉토리에 파일로만 저장하는 저장소를 만든다.
//
// 실행 머신에 키체인이 있는지 없는지에 테스트가 좌우되면 안 된다. 여기서 검증하는 것은
// 프로필을 다루는 규칙 — 목록·덮어쓰기·삭제·권한 — 이고, 그것은 어느 자리에 저장하든 같다.
func newFileBackedStoreForTest(t *testing.T) (*ProfileStore, string) {
	t.Helper()

	configDirectory := t.TempDir()
	return newProfileStore(configDirectory, newFileVault(configDirectory)), configDirectory
}

func TestSavedTokenComesBackByProfileName(t *testing.T) {
	store, _ := newFileBackedStoreForTest(t)

	if err := store.SaveToken("개인", storedTestToken); err != nil {
		t.Fatalf("SaveToken() 실패: %v", err)
	}

	loaded, err := store.LoadToken("개인")
	if err != nil {
		t.Fatalf("LoadToken() 실패: %v", err)
	}
	if loaded != storedTestToken {
		t.Errorf("LoadToken() = %q, want %q", loaded, storedTestToken)
	}
}

func TestProfilesListsEveryNameWithWhereItsTokenLives(t *testing.T) {
	// kyu auth list 가 보여주는 것이 이 목록이다. 저장 위치까지 함께 말해야, 평문 파일에 저장된
	// 프로필이 있다는 사실이 사용자에게 계속 보인다.
	store, _ := newFileBackedStoreForTest(t)

	if err := store.SaveToken("개인", storedTestToken); err != nil {
		t.Fatalf("SaveToken() 실패: %v", err)
	}
	if err := store.SaveToken("회사", "다른-토큰"); err != nil {
		t.Fatalf("SaveToken() 실패: %v", err)
	}

	profiles, err := store.Profiles()
	if err != nil {
		t.Fatalf("Profiles() 실패: %v", err)
	}

	if len(profiles) != 2 {
		t.Fatalf("프로필 = %+v, 두 개를 기대", profiles)
	}
	if profiles[0].Name != "개인" || profiles[1].Name != "회사" {
		t.Errorf("프로필 = %+v, 등록한 순서대로 나오기를 기대", profiles)
	}
	for _, profile := range profiles {
		if profile.Storage != StorageConfigFile {
			t.Errorf("%s 의 저장 위치 = %q, want %q", profile.Name, profile.Storage, StorageConfigFile)
		}
	}
}

func TestSavingTheSameProfileTwiceReplacesTheTokenInsteadOfAddingARow(t *testing.T) {
	// 토큰을 다시 발급받아 같은 이름으로 등록하는 것은 흔한 일이다. 행이 늘어나면 목록에 같은
	// 이름이 두 번 찍히고, 어느 것이 살아있는 토큰인지 알 수 없게 된다.
	store, _ := newFileBackedStoreForTest(t)

	if err := store.SaveToken("개인", "옛-토큰"); err != nil {
		t.Fatalf("SaveToken() 실패: %v", err)
	}
	if err := store.SaveToken("개인", "새-토큰"); err != nil {
		t.Fatalf("SaveToken() 실패: %v", err)
	}

	profiles, err := store.Profiles()
	if err != nil {
		t.Fatalf("Profiles() 실패: %v", err)
	}
	if len(profiles) != 1 {
		t.Fatalf("프로필 = %+v, 하나로 남기를 기대", profiles)
	}

	loaded, err := store.LoadToken("개인")
	if err != nil {
		t.Fatalf("LoadToken() 실패: %v", err)
	}
	if loaded != "새-토큰" {
		t.Errorf("LoadToken() = %q, 나중에 저장한 토큰을 기대", loaded)
	}
}

func TestRemovedProfileDisappearsFromTheListAndCannotBeLoaded(t *testing.T) {
	store, _ := newFileBackedStoreForTest(t)

	if err := store.SaveToken("개인", storedTestToken); err != nil {
		t.Fatalf("SaveToken() 실패: %v", err)
	}
	if err := store.RemoveToken("개인"); err != nil {
		t.Fatalf("RemoveToken() 실패: %v", err)
	}

	profiles, err := store.Profiles()
	if err != nil {
		t.Fatalf("Profiles() 실패: %v", err)
	}
	if len(profiles) != 0 {
		t.Errorf("프로필 = %+v, 비어 있기를 기대", profiles)
	}

	if _, err := store.LoadToken("개인"); !errors.Is(err, ErrProfileNotFound) {
		t.Errorf("LoadToken() 에러 = %v, ErrProfileNotFound 를 기대", err)
	}
}

func TestRemovingAProfileThatWasNeverRegisteredSaysSoInsteadOfSucceedingQuietly(t *testing.T) {
	// 조용히 성공하면 이름을 잘못 적은 사용자는 지웠다고 믿는다 — 토큰은 그대로 남아 있는데도.
	store, _ := newFileBackedStoreForTest(t)

	if err := store.RemoveToken("없는-프로필"); !errors.Is(err, ErrProfileNotFound) {
		t.Errorf("RemoveToken() 에러 = %v, ErrProfileNotFound 를 기대", err)
	}
}

func TestFileFallbackWritesTheTokenOwnerOnlyAndKeepsItOutOfTheProfileIndex(t *testing.T) {
	// 키체인이 없는 머신에서는 토큰이 평문으로 디스크에 남는다. 그 사실을 사용자에게 알리는 것과
	// 별개로, 최소한 다른 사용자가 읽지는 못하게 해야 한다.
	store, configDirectory := newFileBackedStoreForTest(t)

	if err := store.SaveToken("개인", storedTestToken); err != nil {
		t.Fatalf("SaveToken() 실패: %v", err)
	}

	credentialsInfo, err := os.Stat(filepath.Join(configDirectory, credentialsFileName))
	if err != nil {
		t.Fatalf("자격증명 파일이 없습니다: %v", err)
	}
	if permission := credentialsInfo.Mode().Perm(); permission != 0o600 {
		t.Errorf("자격증명 파일 권한 = %o, want 600", permission)
	}

	// 프로필 목록은 이름만 담는다. 목록 파일까지 토큰을 담으면 저장 위치가 둘로 늘어나고,
	// 키체인을 쓰는 머신에서도 토큰이 파일에 남는다.
	profileIndex, err := os.ReadFile(filepath.Join(configDirectory, profileIndexFileName))
	if err != nil {
		t.Fatalf("프로필 목록 파일을 읽지 못했습니다: %v", err)
	}
	if strings.Contains(string(profileIndex), storedTestToken) {
		t.Errorf("프로필 목록 파일에 토큰이 있습니다:\n%s", profileIndex)
	}
}

func TestEmptyProfileNameIsRejectedBeforeAnythingIsWritten(t *testing.T) {
	// 이름 없는 프로필은 목록에서 고를 수 없고 지울 수도 없다 — 저장된 채로 남는다.
	store, configDirectory := newFileBackedStoreForTest(t)

	if err := store.SaveToken("  ", storedTestToken); err == nil {
		t.Fatal("SaveToken() 이 빈 이름을 받았습니다, 거절하기를 기대")
	}

	if _, err := os.Stat(filepath.Join(configDirectory, credentialsFileName)); !errors.Is(err, os.ErrNotExist) {
		t.Errorf("거절했는데 자격증명 파일이 생겼습니다: %v", err)
	}
}

func TestStoreWithoutAnySavedProfileReportsAnEmptyList(t *testing.T) {
	// 첫 실행에는 목록 파일 자체가 없다. 그것을 실패로 다루면 kyu clone 이 등록 흐름에 닿지 못한다.
	store, _ := newFileBackedStoreForTest(t)

	profiles, err := store.Profiles()
	if err != nil {
		t.Fatalf("Profiles() 실패: %v", err)
	}
	if len(profiles) != 0 {
		t.Errorf("프로필 = %+v, 비어 있기를 기대", profiles)
	}
}
