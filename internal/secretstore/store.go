// Package secretstore 는 GitHub 토큰을 프로필 이름으로 저장하고 꺼내는 계층이다.
//
// 이 도구는 머신에 굴러다니는 인증을 읽지 않는다 — GITHUB_TOKEN 환경변수도, gh 의 로그인도
// 쓰지 않는다. 그런 것을 집어 쓰면 회사 토큰이 깔린 머신에서 개인 레포를 클론하려던 사용자가
// 엉뚱한 계정의 목록을 보게 되고, 무엇으로 인증했는지 화면에서 확인할 방법도 없다.
// 그래서 사용자가 직접 등록한 토큰만 쓰고, 그 토큰을 여기에 이름 붙여 보관한다.
//
// 저장 자리는 플랫폼마다 다르다(키체인 · secret-service · 설정 파일). 그 차이는 secretVault 뒤에
// 숨기고, 부르는 쪽은 "어느 프로필의 토큰" 으로만 말한다.
package secretstore

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

// ErrProfileNotFound 는 등록되지 않은 프로필을 꺼내거나 지우려 할 때 반환한다.
//
// 센티널로 두는 이유는 부르는 쪽이 "이름을 잘못 적었다" 와 "저장소가 고장났다" 를 갈라
// 안내해야 하기 때문이다. 앞쪽은 사용자가 그 자리에서 고칠 수 있다.
var ErrProfileNotFound = errors.New("등록되지 않은 프로필입니다")

// StorageKind 는 토큰이 실제로 놓인 자리의 종류다.
//
// 값을 파일에 적으므로 사람이 읽는 문구가 아니라 짧은 코드로 둔다. 화면에 보여줄 말은
// Description 이 따로 만든다 — 문구를 고칠 때마다 이미 저장된 파일이 읽히지 않게 되면 안 된다.
type StorageKind string

const (
	// StorageKeychain 은 macOS 키체인이다.
	StorageKeychain StorageKind = "keychain"

	// StorageSecretService 는 리눅스 데스크톱의 secret-service(libsecret)다.
	StorageSecretService StorageKind = "secret-service"

	// StorageConfigFile 은 키체인도 secret-service 도 없을 때의 폴백이다 — 평문 파일이다.
	StorageConfigFile StorageKind = "file"
)

// Description 은 이 저장 위치를 사용자에게 보여줄 말로 옮긴다.
func (kind StorageKind) Description() string {
	switch kind {
	case StorageKeychain:
		return "macOS 키체인"
	case StorageSecretService:
		return "secret-service (libsecret)"
	case StorageConfigFile:
		// "파일" 이라고만 하면 그것이 평문이라는 사실이 전달되지 않는다. 사용자가 그 사실을 알고도
		// 쓰기로 한 것과, 모른 채로 쓰는 것은 다르다.
		return "설정 파일 (평문, 권한 0600)"
	default:
		return string(kind)
	}
}

// Profile 은 저장된 토큰 하나에 붙인 이름과 그 토큰이 놓인 자리다.
type Profile struct {
	// Name 은 사용자가 붙인 이름이다("개인", "회사"). 목록에서 고르고 kyu auth remove 에 적는 이름.
	Name string

	// Storage 는 이 프로필의 토큰이 실제로 저장된 자리다.
	//
	// 프로필마다 따로 기록한다. 한 머신의 저장 자리는 대개 하나지만, 폴백으로 파일에 저장한 뒤
	// 키체인이 생긴 머신에서는 옛 프로필을 파일에서 꺼내야 한다.
	Storage StorageKind
}

// TokenStore 는 프로필 이름으로 토큰을 저장하고 꺼내는 저장소다.
type TokenStore interface {
	// Profiles 는 등록된 프로필 전부를 등록 순서대로 반환한다. 토큰 값은 담기지 않는다.
	Profiles() ([]Profile, error)

	// SaveToken 은 프로필 이름으로 토큰을 저장한다. 같은 이름이 이미 있으면 덮어쓴다.
	SaveToken(profileName, token string) error

	// LoadToken 은 프로필의 토큰을 꺼낸다. 없으면 ErrProfileNotFound 다.
	LoadToken(profileName string) (string, error)

	// RemoveToken 은 프로필과 그 토큰을 지운다. 없으면 ErrProfileNotFound 다.
	RemoveToken(profileName string) error

	// StorageKindForNewToken 은 지금 저장하면 토큰이 놓일 자리의 종류다.
	//
	// 저장하기 전에 물을 수 있어야 한다 — 평문 파일에 저장될 상황이면 사용자에게 먼저 알려야 하고,
	// 그 안내는 토큰을 받기 전에 나가야 의미가 있다.
	StorageKindForNewToken() StorageKind
}

const (
	// configDirectoryName 은 사용자 설정 디렉토리 아래에 이 도구가 쓰는 자리다.
	configDirectoryName = "kyu"

	// profileIndexFileName 은 프로필 이름 목록이다. 토큰 값은 여기에 담기지 않는다.
	//
	// 목록을 따로 두는 이유: 키체인은 "이 서비스의 항목을 전부 나열" 을 사용자 확인 없이 해주지 않는다.
	// 이름은 비밀이 아니므로, 나열할 수 있는 것만 우리가 들고 값은 저장소에 맡긴다.
	profileIndexFileName = "profiles.json"

	// credentialsFileName 은 폴백 저장소가 토큰을 적는 파일이다.
	credentialsFileName = "credentials.json"
)

const (
	// configDirectoryPermission 은 이 도구의 설정 디렉토리 권한이다. 폴백 저장소가 이 안에 토큰을
	// 적으므로 소유자만 열 수 있게 둔다.
	configDirectoryPermission = 0o700

	// privateFilePermission 은 이 도구가 만드는 파일의 권한이다.
	privateFilePermission = 0o600
)

// ProfileStore 는 프로필 목록 파일과 비밀 저장소를 묶은 TokenStore 구현이다.
type ProfileStore struct {
	configDirectory string
	vault           secretVault
}

var _ TokenStore = (*ProfileStore)(nil)

// NewTokenStore 는 이 머신에 맞는 토큰 저장소를 만든다.
func NewTokenStore() (*ProfileStore, error) {
	userConfigDirectory, err := os.UserConfigDir()
	if err != nil {
		return nil, fmt.Errorf("설정 디렉토리를 찾지 못했습니다: %w", err)
	}

	configDirectory := filepath.Join(userConfigDirectory, configDirectoryName)
	return newProfileStore(configDirectory, detectSecretVault(configDirectory)), nil
}

// newProfileStore 는 저장 자리를 직접 지정해 저장소를 만든다. 테스트가 이 문으로 들어온다.
func newProfileStore(configDirectory string, vault secretVault) *ProfileStore {
	return &ProfileStore{configDirectory: configDirectory, vault: vault}
}

// StorageKindForNewToken 은 지금 저장하면 토큰이 놓일 자리의 종류다.
func (store *ProfileStore) StorageKindForNewToken() StorageKind {
	return store.vault.kind()
}

// Profiles 는 등록된 프로필 전부를 등록 순서대로 반환한다.
func (store *ProfileStore) Profiles() ([]Profile, error) {
	index, err := store.readProfileIndex()
	if err != nil {
		return nil, err
	}
	return index.Profiles, nil
}

// SaveToken 은 프로필 이름으로 토큰을 저장한다.
//
// 토큰을 먼저 저장하고 목록을 나중에 고친다. 순서를 뒤집으면 목록에는 있는데 값은 없는 프로필이
// 남아, 사용자가 고른 프로필에서 "토큰이 없습니다" 가 나온다. 반대 순서의 중간 실패는
// 목록에 없는 항목이 저장소에 남는 것인데, 같은 이름으로 다시 저장하면 그대로 덮인다.
func (store *ProfileStore) SaveToken(profileName, token string) error {
	name, err := validProfileName(profileName)
	if err != nil {
		return err
	}
	if strings.TrimSpace(token) == "" {
		return errors.New("토큰이 비어 있습니다")
	}

	if err := store.vault.store(name, token); err != nil {
		return err
	}

	index, err := store.readProfileIndex()
	if err != nil {
		return err
	}
	index.upsert(Profile{Name: name, Storage: store.vault.kind()})
	return store.writeProfileIndex(index)
}

// LoadToken 은 프로필의 토큰을 꺼낸다.
func (store *ProfileStore) LoadToken(profileName string) (string, error) {
	profile, err := store.findProfile(profileName)
	if err != nil {
		return "", err
	}

	vault, err := vaultForKind(profile.Storage, store.configDirectory)
	if err != nil {
		return "", err
	}
	return vault.lookup(profile.Name)
}

// RemoveToken 은 프로필과 그 토큰을 지운다.
func (store *ProfileStore) RemoveToken(profileName string) error {
	profile, err := store.findProfile(profileName)
	if err != nil {
		return err
	}

	vault, err := vaultForKind(profile.Storage, store.configDirectory)
	if err != nil {
		return err
	}

	// 값을 먼저 지운다. 목록을 먼저 지우면 값이 남았는지 확인할 길이 없어진다 — 목록에 없는
	// 프로필은 이 도구가 다시 가리키지 못하므로, 지워지지 않은 토큰이 저장소에 조용히 남는다.
	if err := vault.clear(profile.Name); err != nil {
		return err
	}

	index, err := store.readProfileIndex()
	if err != nil {
		return err
	}
	index.remove(profile.Name)
	return store.writeProfileIndex(index)
}

// findProfile 은 목록에서 이름이 같은 프로필을 찾는다.
func (store *ProfileStore) findProfile(profileName string) (Profile, error) {
	name, err := validProfileName(profileName)
	if err != nil {
		return Profile{}, err
	}

	index, err := store.readProfileIndex()
	if err != nil {
		return Profile{}, err
	}

	for _, profile := range index.Profiles {
		if profile.Name == name {
			return profile, nil
		}
	}
	return Profile{}, fmt.Errorf("%w: %s", ErrProfileNotFound, name)
}

// validProfileName 은 프로필 이름을 다듬고 쓸 수 없는 이름을 거절한다.
//
// 이름 없는 프로필은 목록에서 고를 수도 지울 수도 없다 — 저장된 채로 영영 남는다.
func validProfileName(profileName string) (string, error) {
	name := strings.TrimSpace(profileName)
	if name == "" {
		return "", errors.New("프로필 이름이 비어 있습니다")
	}
	return name, nil
}

// profileIndex 는 프로필 목록 파일의 내용이다.
type profileIndex struct {
	Profiles []Profile `json:"profiles"`
}

// upsert 는 같은 이름이 있으면 덮어쓰고 없으면 뒤에 붙인다.
//
// 같은 이름을 새 행으로 쌓지 않는다. 토큰을 다시 발급받아 같은 이름으로 등록하는 것은 흔한 일인데,
// 행이 늘어나면 목록에 같은 이름이 두 번 찍혀 어느 것이 살아있는 토큰인지 알 수 없다.
func (index *profileIndex) upsert(profile Profile) {
	for existingIndex, existing := range index.Profiles {
		if existing.Name == profile.Name {
			index.Profiles[existingIndex] = profile
			return
		}
	}
	index.Profiles = append(index.Profiles, profile)
}

func (index *profileIndex) remove(profileName string) {
	remaining := index.Profiles[:0]
	for _, profile := range index.Profiles {
		if profile.Name != profileName {
			remaining = append(remaining, profile)
		}
	}
	index.Profiles = remaining
}

func (store *ProfileStore) readProfileIndex() (profileIndex, error) {
	var index profileIndex
	if err := readJSONFileIfExists(filepath.Join(store.configDirectory, profileIndexFileName), &index); err != nil {
		return profileIndex{}, err
	}
	return index, nil
}

func (store *ProfileStore) writeProfileIndex(index profileIndex) error {
	return writeJSONFilePrivately(filepath.Join(store.configDirectory, profileIndexFileName), index)
}

// readJSONFileIfExists 는 파일이 있으면 JSON 으로 읽는다. 없으면 target 을 그대로 둔다.
//
// 파일 없음을 실패로 다루지 않는 이유: 첫 실행에는 아무 파일도 없다. 그것을 에러로 올리면
// kyu clone 이 토큰 등록 흐름에 닿기도 전에 끝난다.
func readJSONFileIfExists(path string, target any) error {
	content, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		return nil
	}
	if err != nil {
		return fmt.Errorf("%s 읽기 실패: %w", path, err)
	}

	if err := json.Unmarshal(content, target); err != nil {
		// 사람이 손으로 고쳤거나 반쯤 쓰인 파일이다. 조용히 빈 것으로 취급하면 등록해둔 프로필이
		// 사라진 것처럼 보이고, 그 위에 새로 저장하는 순간 남은 내용까지 덮인다.
		return fmt.Errorf("%s 를 읽지 못했습니다 (형식이 깨졌습니다): %w", path, err)
	}
	return nil
}

// writeJSONFilePrivately 는 소유자만 읽을 수 있는 파일로 쓴다.
//
// 임시 파일에 쓴 뒤 이름을 바꾼다. 곧바로 덮어쓰다가 중간에 실패하면 반쯤 쓰인 파일이 남고,
// 그 파일에는 등록해둔 다른 프로필까지 함께 잘려 있다.
func writeJSONFilePrivately(path string, value any) error {
	if err := os.MkdirAll(filepath.Dir(path), configDirectoryPermission); err != nil {
		return fmt.Errorf("설정 디렉토리 생성 실패 (%s): %w", filepath.Dir(path), err)
	}

	content, err := json.MarshalIndent(value, "", "  ")
	if err != nil {
		return fmt.Errorf("설정 내용을 만들지 못했습니다: %w", err)
	}
	content = append(content, '\n')

	temporaryPath := path + ".tmp"
	if err := os.WriteFile(temporaryPath, content, privateFilePermission); err != nil {
		return fmt.Errorf("%s 쓰기 실패: %w", temporaryPath, err)
	}
	// 임시 파일이 이미 있었다면 그때의 권한이 남는다. WriteFile 의 권한은 새로 만들 때만 적용된다.
	if err := os.Chmod(temporaryPath, privateFilePermission); err != nil {
		return fmt.Errorf("%s 권한 설정 실패: %w", temporaryPath, err)
	}

	if err := os.Rename(temporaryPath, path); err != nil {
		return fmt.Errorf("%s 로 옮기지 못했습니다: %w", path, err)
	}
	return nil
}
