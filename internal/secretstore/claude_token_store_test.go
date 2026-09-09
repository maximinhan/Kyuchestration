package secretstore

import (
	"errors"
	"os"
	"path/filepath"
	"testing"
)

// storedClaudeTestToken 은 claude 토큰 시험이 넣고 꺼내는 값이다. 진짜 토큰의 모양만 닮게 둔다.
const storedClaudeTestToken = "sk-ant-oat01-테스트용-가짜-값"

// newFileBackedClaudeStoreForTest 는 임시 디렉토리에 파일로만 저장하는 claude 저장소를 만든다.
//
// 프로필 저장소 시험과 같은 이유로 파일에 고정한다 — 실행 머신에 키체인이 있는지 없는지에
// 결과가 좌우되면, 여기서 확인하려는 규칙(저장·조회·삭제·이름 공간)이 검사되지 않는다.
func newFileBackedClaudeStoreForTest(t *testing.T) (*ClaudeTokenStore, string) {
	t.Helper()

	configDirectory := t.TempDir()
	return newClaudeTokenStore(configDirectory, newFileVault(configDirectory, claudeTokenNamespace)), configDirectory
}

func TestSavedClaudeTokenComesBack(t *testing.T) {
	store, _ := newFileBackedClaudeStoreForTest(t)

	if err := store.SaveToken(storedClaudeTestToken); err != nil {
		t.Fatalf("SaveToken() 실패: %v", err)
	}

	loaded, err := store.LoadToken()
	if err != nil {
		t.Fatalf("LoadToken() 실패: %v", err)
	}
	if loaded != storedClaudeTestToken {
		t.Errorf("LoadToken() = %q, want %q", loaded, storedClaudeTestToken)
	}
}

func TestLoadingBeforeAnythingIsSavedSaysSoInsteadOfAnsweringWithAnEmptyToken(t *testing.T) {
	// 빈 문자열을 돌려주면 앱은 그것을 토큰으로 알고 자식 환경에 실어 보낸다 — 그리고 세션이
	// 401 로 끝난다. "없다" 와 "비어 있다" 가 갈리는 자리다.
	store, _ := newFileBackedClaudeStoreForTest(t)

	if _, err := store.LoadToken(); !errors.Is(err, ErrClaudeTokenNotStored) {
		t.Errorf("LoadToken() 에러 = %v, ErrClaudeTokenNotStored 를 기대", err)
	}
}

func TestSavingAgainReplacesTheStoredClaudeToken(t *testing.T) {
	// 토큰이 만료돼 새로 발급받는 것은 흔한 일이다. 두 번째 저장이 첫 번째를 덮지 않으면
	// 앱은 계속 죽은 토큰으로 세션을 연다.
	store, _ := newFileBackedClaudeStoreForTest(t)

	if err := store.SaveToken("옛-토큰"); err != nil {
		t.Fatalf("첫 SaveToken() 실패: %v", err)
	}
	if err := store.SaveToken("새-토큰"); err != nil {
		t.Fatalf("두 번째 SaveToken() 실패: %v", err)
	}

	loaded, err := store.LoadToken()
	if err != nil {
		t.Fatalf("LoadToken() 실패: %v", err)
	}
	if loaded != "새-토큰" {
		t.Errorf("LoadToken() = %q, 나중에 저장한 값을 기대", loaded)
	}
}

func TestRemovingClaudeTokenIsIdempotent(t *testing.T) {
	// 부르는 사람이 원하는 것은 "이 머신에 내 토큰이 남아 있지 않은 상태" 다. 두 번째 호출이
	// 실패하면 스크립트가 그 상태를 만들어 두고도 1 로 끝난다.
	store, _ := newFileBackedClaudeStoreForTest(t)

	if err := store.SaveToken(storedClaudeTestToken); err != nil {
		t.Fatalf("SaveToken() 실패: %v", err)
	}
	if err := store.RemoveToken(); err != nil {
		t.Fatalf("첫 RemoveToken() 실패: %v", err)
	}
	if err := store.RemoveToken(); err != nil {
		t.Errorf("두 번째 RemoveToken() 실패: %v — 이미 없는 것을 지우는 것은 성공이어야 한다", err)
	}

	if _, err := store.LoadToken(); !errors.Is(err, ErrClaudeTokenNotStored) {
		t.Errorf("지운 뒤 LoadToken() 에러 = %v, ErrClaudeTokenNotStored 를 기대", err)
	}
}

func TestStoredStorageKindAnswersWithoutReadingTheToken(t *testing.T) {
	// kyu claude-auth status 가 부르는 자리다. 값을 꺼내지 않아야 화면 공유 중에도 안전하다.
	store, _ := newFileBackedClaudeStoreForTest(t)

	if _, isStored, err := store.StoredStorageKind(); err != nil || isStored {
		t.Errorf("StoredStorageKind() = (_, %v, %v), 저장 전에는 (_, false, nil) 을 기대", isStored, err)
	}

	if err := store.SaveToken(storedClaudeTestToken); err != nil {
		t.Fatalf("SaveToken() 실패: %v", err)
	}

	storage, isStored, err := store.StoredStorageKind()
	if err != nil {
		t.Fatalf("StoredStorageKind() 실패: %v", err)
	}
	if !isStored || storage != StorageConfigFile {
		t.Errorf("StoredStorageKind() = (%q, %v, nil), (%q, true, nil) 을 기대", storage, isStored, StorageConfigFile)
	}
}

func TestClaudeTokenAndGitHubProfilesDoNotShareAStorageSlot(t *testing.T) {
	// 이름 공간을 가른 이유가 이 시험이다. 같은 자리를 썼다면 claude 라는 이름의 GitHub 프로필을
	// 만든 사용자가 자기 claude 토큰을 소리 없이 덮어쓴다.
	configDirectory := t.TempDir()
	claudeStore := newClaudeTokenStore(configDirectory, newFileVault(configDirectory, claudeTokenNamespace))
	gitHubStore := newProfileStore(configDirectory, newFileVault(configDirectory, gitHubTokenNamespace))

	if err := claudeStore.SaveToken(storedClaudeTestToken); err != nil {
		t.Fatalf("claude SaveToken() 실패: %v", err)
	}
	// claude 저장소가 쓰는 계정 이름과 똑같은 이름의 GitHub 프로필을 만든다 — 가장 겹치기 쉬운 자리다.
	if err := gitHubStore.SaveToken(claudeTokenAccountName, "ghp_전혀-다른-값"); err != nil {
		t.Fatalf("GitHub SaveToken() 실패: %v", err)
	}

	loaded, err := claudeStore.LoadToken()
	if err != nil {
		t.Fatalf("claude LoadToken() 실패: %v", err)
	}
	if loaded != storedClaudeTestToken {
		t.Errorf("claude LoadToken() = %q, GitHub 쪽 저장에 덮이지 않기를 기대", loaded)
	}

	// GitHub 목록에 claude 토큰이 섞여 보이지도 않아야 한다.
	profiles, err := gitHubStore.Profiles()
	if err != nil {
		t.Fatalf("Profiles() 실패: %v", err)
	}
	if len(profiles) != 1 {
		t.Errorf("GitHub 프로필 = %+v, 하나만 있기를 기대 — claude 토큰은 이 목록의 것이 아니다", profiles)
	}
}

func TestClaudeTokenFilesAreReadableOnlyByTheirOwner(t *testing.T) {
	// 폴백 저장소는 평문이다. 그 사실을 숨기지 않는 대신, 같은 머신의 다른 사용자에게는 닫아 둔다.
	store, configDirectory := newFileBackedClaudeStoreForTest(t)

	if err := store.SaveToken(storedClaudeTestToken); err != nil {
		t.Fatalf("SaveToken() 실패: %v", err)
	}

	for _, fileName := range []string{claudeTokenNamespace.credentialsFileName, claudeTokenIndexFileName} {
		info, err := os.Stat(filepath.Join(configDirectory, fileName))
		if err != nil {
			t.Fatalf("%s 를 읽지 못했습니다: %v", fileName, err)
		}
		if permission := info.Mode().Perm(); permission != privateFilePermission {
			t.Errorf("%s 의 권한 = %o, %o 를 기대", fileName, permission, privateFilePermission)
		}
	}
}
