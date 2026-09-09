package secretstore

import (
	"errors"
	"fmt"
	"path/filepath"
	"strings"
)

// 이 파일은 claude 자격 증명 하나를 맡아 두는 자리다.
//
// **왜 프로필이 아닌가.** GitHub 토큰은 사용자가 "개인" 과 "회사" 를 갈라 쓰는 값이라 이름이
// 필요했다. claude 자격 증명은 그렇지 않다 — 앱이 세션을 띄울 때 자식 환경에 싣는 값 하나이고,
// 고를 자리가 없다. 이름을 붙이면 "어느 것으로 세션을 여는가" 라는 물음이 화면에 하나 생기는데,
// 그 물음에 답할 근거가 사용자에게도 없다.
//
// **왜 이 도구가 맡는가.** 브라우저 인증(claude auth login)은 claude 자신이 자격 증명을 챙기므로
// 이 저장소를 지나지 않는다. 여기 오는 것은 폴백 하나다 — 이미 로그인된 다른 머신에서
// `claude setup-token` 으로 얻은 장기 토큰을 새 머신에 붙여넣는 길(chat-ui-design.md 7.3 다).
// 그 토큰은 claude 가 저장해 주지 않으므로 누군가는 맡아야 하고, 이 도구의 비밀이 이미 여기 산다.

// ErrClaudeTokenNotStored 는 저장된 claude 토큰이 없을 때 반환한다.
//
// 센티널로 두는 이유는 ErrProfileNotFound 와 같다 — 부르는 쪽이 "아직 등록하지 않았다" 와
// "저장소가 고장났다" 를 갈라 안내해야 한다. 앞쪽에서 할 일은 인증 화면을 여는 것이다.
var ErrClaudeTokenNotStored = errors.New("저장된 claude 토큰이 없습니다")

const (
	// claudeTokenIndexFileName 은 claude 토큰이 어느 저장소에 있는지만 적어 두는 파일이다.
	//
	// 토큰 값은 여기 담기지 않는다. 프로필 목록(profiles.json)이 값을 담지 않는 것과 같은 이유이고,
	// 저장 위치를 적어 두는 이유도 같다 — 폴백으로 파일에 저장한 뒤 키체인이 생긴 머신에서는
	// 옛 값을 파일에서 꺼내야 하고, 그 사실은 여기 적힌 종류만이 알고 있다.
	claudeTokenIndexFileName = "claude-token.json"

	// claudeTokenAccountName 은 키체인·secret-service 항목의 account 속성이다.
	//
	// 고정 문자열인 것이 뜻이다. 이 이름 공간에 값이 하나뿐이라 고를 것이 없고, 사용자가
	// 자기 비밀번호 관리자에서 이 항목을 보았을 때 그것이 무엇인지 읽히는 이름이어야 한다.
	claudeTokenAccountName = "claude-code-oauth-token"
)

// ClaudeTokenStore 는 claude 자격 증명 토큰 하나를 저장하고 꺼내는 저장소다.
type ClaudeTokenStore struct {
	configDirectory string
	vault           secretVault
}

// NewClaudeTokenStore 는 이 머신에 맞는 claude 토큰 저장소를 만든다.
func NewClaudeTokenStore() (*ClaudeTokenStore, error) {
	configDirectory, err := toolConfigDirectory()
	if err != nil {
		return nil, err
	}
	return newClaudeTokenStore(configDirectory, detectSecretVault(configDirectory, claudeTokenNamespace)), nil
}

// newClaudeTokenStore 는 저장 자리를 직접 지정해 저장소를 만든다. 테스트가 이 문으로 들어온다.
func newClaudeTokenStore(configDirectory string, vault secretVault) *ClaudeTokenStore {
	return &ClaudeTokenStore{configDirectory: configDirectory, vault: vault}
}

// StorageKindForNewToken 은 지금 저장하면 토큰이 놓일 자리의 종류다.
//
// 저장하기 전에 물을 수 있어야 한다. 평문 파일에 저장될 상황이면 화면이 그 사실을 토큰을 받기
// 전에 알려야 하고, 받은 뒤에 알리면 사용자가 할 수 있는 일은 이미 저장된 것을 지우는 것뿐이다.
func (store *ClaudeTokenStore) StorageKindForNewToken() StorageKind {
	return store.vault.kind()
}

// StoredStorageKind 는 저장된 토큰이 실제로 놓인 자리다. 저장된 것이 없으면 두 번째 값이 false 다.
//
// 값을 꺼내지 않고 답한다. "저장돼 있는가" 를 묻는 자리(kyu claude-auth status)가 그 물음의
// 답을 얻으려고 토큰을 읽어야 한다면, 화면 공유 중에 상태를 확인하는 것만으로도 위험해진다.
func (store *ClaudeTokenStore) StoredStorageKind() (StorageKind, bool, error) {
	index, err := store.readTokenIndex()
	if err != nil {
		return "", false, err
	}
	if index.Storage == "" {
		return "", false, nil
	}
	return index.Storage, true, nil
}

// SaveToken 은 토큰을 저장한다. 이미 있으면 덮어쓴다.
//
// 값을 먼저 저장하고 어디에 두었는지를 나중에 적는다. 순서를 뒤집으면 "저장돼 있다" 고 적혀
// 있는데 값은 없는 상태가 남아, 다음 실행이 세션에 빈 토큰을 실어 보낸다.
func (store *ClaudeTokenStore) SaveToken(token string) error {
	if strings.TrimSpace(token) == "" {
		return errors.New("토큰이 비어 있습니다")
	}

	if err := store.vault.store(claudeTokenAccountName, token); err != nil {
		return err
	}
	return store.writeTokenIndex(claudeTokenIndex{Storage: store.vault.kind()})
}

// LoadToken 은 저장된 토큰을 꺼낸다. 저장된 것이 없으면 ErrClaudeTokenNotStored 다.
func (store *ClaudeTokenStore) LoadToken() (string, error) {
	storage, isStored, err := store.StoredStorageKind()
	if err != nil {
		return "", err
	}
	if !isStored {
		return "", ErrClaudeTokenNotStored
	}

	vault, err := vaultForKind(storage, store.configDirectory, claudeTokenNamespace)
	if err != nil {
		return "", err
	}

	token, err := vault.lookup(claudeTokenAccountName)
	// 적혀 있는데 값이 없는 경우다 — 사용자가 자기 키체인에서 항목만 지우면 이렇게 된다.
	// 저장소 고장이 아니라 "등록된 것이 없다" 로 답해야 화면이 인증을 다시 이끈다.
	if errors.Is(err, ErrProfileNotFound) {
		return "", ErrClaudeTokenNotStored
	}
	if err != nil {
		return "", err
	}
	return token, nil
}

// RemoveToken 은 저장된 토큰을 지운다. 이미 없으면 아무것도 하지 않고 성공으로 끝난다.
//
// 없는 것을 지우라는 요청을 실패로 다루지 않는다. 부르는 사람이 원하는 것은 "이 머신에 내
// 토큰이 남아 있지 않은 상태" 이고, 그것은 이미 이뤄져 있다.
func (store *ClaudeTokenStore) RemoveToken() error {
	storage, isStored, err := store.StoredStorageKind()
	if err != nil {
		return err
	}
	if !isStored {
		return nil
	}

	vault, err := vaultForKind(storage, store.configDirectory, claudeTokenNamespace)
	if err != nil {
		return err
	}

	// 값을 먼저 지운다. 적어 둔 것을 먼저 지우면 이 도구가 그 값을 다시 가리키지 못해,
	// 지워지지 않은 토큰이 저장소에 조용히 남는다.
	if err := vault.clear(claudeTokenAccountName); err != nil {
		return err
	}
	return store.writeTokenIndex(claudeTokenIndex{})
}

// claudeTokenIndex 는 어디에 저장했는지만 적어 두는 파일의 내용이다.
type claudeTokenIndex struct {
	// Storage 가 비어 있으면 저장된 토큰이 없다는 뜻이다.
	Storage StorageKind `json:"storage"`
}

func (store *ClaudeTokenStore) readTokenIndex() (claudeTokenIndex, error) {
	var index claudeTokenIndex
	if err := readJSONFileIfExists(store.tokenIndexPath(), &index); err != nil {
		return claudeTokenIndex{}, err
	}
	return index, nil
}

func (store *ClaudeTokenStore) writeTokenIndex(index claudeTokenIndex) error {
	if err := writeJSONFilePrivately(store.tokenIndexPath(), index); err != nil {
		return fmt.Errorf("claude 토큰의 저장 위치를 적지 못했습니다: %w", err)
	}
	return nil
}

func (store *ClaudeTokenStore) tokenIndexPath() string {
	return filepath.Join(store.configDirectory, claudeTokenIndexFileName)
}
