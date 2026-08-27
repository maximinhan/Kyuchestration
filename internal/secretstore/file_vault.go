package secretstore

import (
	"fmt"
	"path/filepath"
)

// fileVault 는 키체인도 secret-service 도 없을 때 토큰을 파일에 적는 폴백 저장소다.
//
// 평문이다. 암호를 걸면 그 암호를 다시 어딘가에 두어야 하고, 결국 같은 문제로 돌아온다 —
// 도구가 스스로 풀 수 있는 암호는 그것을 읽을 수 있는 사람에게도 풀린다.
// 그래서 감추는 대신 드러낸다: 권한을 소유자 전용으로 두고, 저장 위치 종류(StorageConfigFile)가
// 목록에 계속 보이며, 등록할 때 부르는 쪽이 그 사실을 알린다.
type fileVault struct {
	credentialsPath string
}

var _ secretVault = fileVault{}

func newFileVault(configDirectory string) fileVault {
	return fileVault{credentialsPath: filepath.Join(configDirectory, credentialsFileName)}
}

func (vault fileVault) kind() StorageKind {
	return StorageConfigFile
}

func (vault fileVault) store(profileName, token string) error {
	tokens, err := vault.readTokens()
	if err != nil {
		return err
	}

	tokens[profileName] = token
	return writeJSONFilePrivately(vault.credentialsPath, storedTokens{Tokens: tokens})
}

func (vault fileVault) lookup(profileName string) (string, error) {
	tokens, err := vault.readTokens()
	if err != nil {
		return "", err
	}

	token, isStored := tokens[profileName]
	if !isStored {
		return "", fmt.Errorf("%w: %s (%s 에 항목이 없습니다)", ErrProfileNotFound, profileName, vault.credentialsPath)
	}
	return token, nil
}

func (vault fileVault) clear(profileName string) error {
	tokens, err := vault.readTokens()
	if err != nil {
		return err
	}

	if _, isStored := tokens[profileName]; !isStored {
		return nil
	}

	delete(tokens, profileName)
	return writeJSONFilePrivately(vault.credentialsPath, storedTokens{Tokens: tokens})
}

// storedTokens 는 자격증명 파일의 내용이다.
//
// 맵을 그대로 최상위에 두지 않고 필드 하나로 감싼다. 나중에 이 파일에 다른 것을 적어야 할 때
// 형식을 통째로 바꾸지 않아도 되고, 무엇보다 파일을 열어본 사람이 그것이 토큰임을 바로 안다.
type storedTokens struct {
	Tokens map[string]string `json:"tokens"`
}

func (vault fileVault) readTokens() (map[string]string, error) {
	var stored storedTokens
	if err := readJSONFileIfExists(vault.credentialsPath, &stored); err != nil {
		return nil, err
	}

	if stored.Tokens == nil {
		stored.Tokens = map[string]string{}
	}
	return stored.Tokens, nil
}
