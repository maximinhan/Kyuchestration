package secretstore

import (
	"bytes"
	"fmt"
	"os/exec"
	"strings"
)

// keychainItemNotFoundExitCode 는 security 가 "그런 항목이 없다" 로 끝낼 때의 종료 코드다.
//
// 실패 문자열로 판정하지 않는다. security 의 메시지는 macOS 버전과 사용자 언어에 따라 달라지지만
// 이 코드는 그렇지 않다.
const keychainItemNotFoundExitCode = 44

// keychainVault 는 macOS 키체인에 토큰을 맡기는 secretVault 구현이다.
//
// 이 머신(WSL)에서는 실행 경로를 검증할 수 없다. 그래서 명령 조립만 테스트로 고정하고,
// 실제 동작은 macOS 에서의 손 검증으로 남긴다.
type keychainVault struct {
	securityPath string
}

var _ secretVault = keychainVault{}

func (vault keychainVault) kind() StorageKind {
	return StorageKeychain
}

// store 는 토큰을 키체인 항목으로 저장한다.
//
// 토큰을 인자로 넘기지 않는다. security 는 -w 뒤에 값을 받을 수도 있지만, 그러면 클론이 도는
// 동안 같은 머신의 다른 사용자가 ps 로 토큰을 읽을 수 있다. 값 없이 -w 만 주면 표준 입력에서 읽는다.
func (vault keychainVault) store(profileName, token string) error {
	command := exec.Command(vault.securityPath, keychainStoreArguments(profileName)...)
	command.Stdin = strings.NewReader(token + "\n")

	var stderr bytes.Buffer
	command.Stderr = &stderr

	if err := command.Run(); err != nil {
		return fmt.Errorf("키체인에 저장하지 못했습니다: %w (%s)", err, strings.TrimSpace(stderr.String()))
	}
	return nil
}

func (vault keychainVault) lookup(profileName string) (string, error) {
	command := exec.Command(vault.securityPath, keychainLookupArguments(profileName)...)

	var stdout, stderr bytes.Buffer
	command.Stdout = &stdout
	command.Stderr = &stderr

	if err := command.Run(); err != nil {
		if exitCode(err) == keychainItemNotFoundExitCode {
			return "", fmt.Errorf("%w: %s (키체인에 항목이 없습니다)", ErrProfileNotFound, profileName)
		}
		return "", fmt.Errorf("키체인에서 토큰을 꺼내지 못했습니다: %w (%s)", err, strings.TrimSpace(stderr.String()))
	}

	// -w 는 비밀번호만 찍고 끝에 개행을 붙인다. 그 개행이 토큰에 섞이면 GitHub 이 401 로 답한다.
	return strings.TrimRight(stdout.String(), "\r\n"), nil
}

func (vault keychainVault) clear(profileName string) error {
	command := exec.Command(vault.securityPath, keychainDeleteArguments(profileName)...)

	var stderr bytes.Buffer
	command.Stderr = &stderr

	if err := command.Run(); err != nil {
		// 이미 없는 것을 지우라는 요청은 이미 이뤄진 상태다.
		if exitCode(err) == keychainItemNotFoundExitCode {
			return nil
		}
		return fmt.Errorf("키체인에서 지우지 못했습니다: %w (%s)", err, strings.TrimSpace(stderr.String()))
	}
	return nil
}

// keychainStoreArguments 는 저장 명령의 인자다. -U 는 같은 항목이 있으면 덮어쓴다는 뜻이다.
func keychainStoreArguments(profileName string) []string {
	return []string{"add-generic-password", "-U", "-s", secretServiceName, "-a", profileName, "-w"}
}

func keychainLookupArguments(profileName string) []string {
	return []string{"find-generic-password", "-s", secretServiceName, "-a", profileName, "-w"}
}

func keychainDeleteArguments(profileName string) []string {
	return []string{"delete-generic-password", "-s", secretServiceName, "-a", profileName}
}
