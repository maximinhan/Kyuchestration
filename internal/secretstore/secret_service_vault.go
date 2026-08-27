package secretstore

import (
	"bytes"
	"errors"
	"fmt"
	"os/exec"
	"strings"
)

// secretServiceVault 는 리눅스 데스크톱의 비밀번호 관리자(secret-service)에 토큰을 맡기는 구현이다.
type secretServiceVault struct {
	secretToolPath string
}

var _ secretVault = secretServiceVault{}

func (vault secretServiceVault) kind() StorageKind {
	return StorageSecretService
}

// store 는 토큰을 secret-service 항목으로 저장한다.
//
// secret-tool 은 비밀 값을 인자로 받지 않는다 — 표준 입력에서만 읽는다. 그 설계가 여기서 원하는
// 것과 같다(토큰이 ps 에 보이지 않는다).
func (vault secretServiceVault) store(profileName, token string) error {
	command := exec.Command(vault.secretToolPath, secretToolStoreArguments(profileName)...)
	command.Stdin = strings.NewReader(token)

	var stderr bytes.Buffer
	command.Stderr = &stderr

	if err := command.Run(); err != nil {
		return fmt.Errorf("secret-service 에 저장하지 못했습니다: %w (%s)", err, strings.TrimSpace(stderr.String()))
	}
	return nil
}

func (vault secretServiceVault) lookup(profileName string) (string, error) {
	command := exec.Command(vault.secretToolPath, secretToolLookupArguments(profileName)...)

	var stdout, stderr bytes.Buffer
	command.Stdout = &stdout
	command.Stderr = &stderr

	if err := command.Run(); err != nil {
		// secret-tool 은 항목이 없을 때도 종료 코드 1 로 끝난다. 그때는 아무것도 출력하지 않고,
		// 저장소 자체에 닿지 못한 경우에만 stderr 에 이유를 적는다.
		var exitErr *exec.ExitError
		if errors.As(err, &exitErr) && stderr.Len() == 0 {
			return "", fmt.Errorf("%w: %s (secret-service 에 항목이 없습니다)", ErrProfileNotFound, profileName)
		}
		return "", fmt.Errorf("secret-service 에서 토큰을 꺼내지 못했습니다: %w (%s)", err, strings.TrimSpace(stderr.String()))
	}

	// secret-tool 은 값 뒤에 개행을 붙이지 않지만, 붙는 버전을 만나도 토큰이 깨지지 않게 걷어낸다.
	return strings.TrimRight(stdout.String(), "\r\n"), nil
}

func (vault secretServiceVault) clear(profileName string) error {
	command := exec.Command(vault.secretToolPath, secretToolClearArguments(profileName)...)

	var stderr bytes.Buffer
	command.Stderr = &stderr

	if err := command.Run(); err != nil {
		// 이미 없는 항목을 지우면 secret-tool 도 종료 코드 1 로 끝난다 — 지운 것과 같은 상태다.
		var exitErr *exec.ExitError
		if errors.As(err, &exitErr) && stderr.Len() == 0 {
			return nil
		}
		return fmt.Errorf("secret-service 에서 지우지 못했습니다: %w (%s)", err, strings.TrimSpace(stderr.String()))
	}
	return nil
}

// secretToolStoreArguments 는 저장 명령의 인자다.
//
// service 와 account 두 속성으로 항목을 특정한다. 조회·삭제가 같은 속성을 써야 같은 항목을
// 가리키므로 세 함수가 나란히 붙어 있다 — 한 곳만 고치면 저장은 되는데 조회는 빈손이 된다.
func secretToolStoreArguments(profileName string) []string {
	return []string{"store", "--label=" + secretToolItemLabel, "service", secretServiceName, "account", profileName}
}

func secretToolLookupArguments(profileName string) []string {
	return []string{"lookup", "service", secretServiceName, "account", profileName}
}

func secretToolClearArguments(profileName string) []string {
	return []string{"clear", "service", secretServiceName, "account", profileName}
}

// exitCode 는 실행 실패에서 종료 코드를 꺼낸다. 실행 자체가 안 된 경우(바이너리 없음 등)는 -1 이다.
//
// 이 파일에 두는 이유는 secretVault 구현들이 함께 쓰기 때문이다 — 키체인은 44 를,
// secret-service 는 종료 코드와 stderr 를 함께 본다.
func exitCode(err error) int {
	var exitErr *exec.ExitError
	if errors.As(err, &exitErr) {
		return exitErr.ExitCode()
	}
	return -1
}
