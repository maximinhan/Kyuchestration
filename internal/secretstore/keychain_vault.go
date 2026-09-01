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
// 조립만 고정하는 시험으로는 이 구현을 검사할 수 없다. 인자가 한 글자도 틀리지 않은 채로 저장된
// 값만 달라지는 결함이 실제로 있었다(PR 46). 그래서 진짜 security 를 부르는 왕복 시험을 맥 CI 에
// 두고, 이 파일의 전제는 전부 거기서 확인한다(keychain_roundtrip_test.go).
type keychainVault struct {
	securityPath string
}

var _ secretVault = keychainVault{}

func (vault keychainVault) kind() StorageKind {
	return StorageKeychain
}

// store 는 토큰을 키체인 항목으로 저장한다.
//
// 토큰을 인자로 넘기지 않는다. security 는 -w 뒤에 값을 받을 수도 있지만, 그러면 명령이 도는 동안
// 같은 머신의 다른 사용자가 ps 로 토큰을 읽을 수 있다. 값 없이 -w 만 주면 security 가 값을 묻는데,
// 그 물음에 어떻게 답하느냐가 이 함수가 하는 일의 전부다.
//
// security 는 값과 재확인을 차례로 두 번 묻고, 그 물음을 표준 입력이 아니라 제어 터미널에서 읽는다
// (/dev/tty 를 먼저 열고, 열리지 않을 때만 표준 입력으로 물러선다). 그래서 두 가지를 함께 해야
// 파이프로 건넨 토큰이 실제로 저장된다.
//
//	① 새 세션에서 띄워 제어 터미널을 뗀다 — 그래야 security 가 표준 입력을 읽는다.
//	② 토큰을 두 줄로 적어 값과 재확인에 각각 답한다.
//
// 하나라도 빠지면 조용히 어긋난다(맥 러너 실측, PR 46). 한 줄만 보내면 재확인이 EOF 로 끝나
// "passwords don't match" 가 되는데, security 는 세 번까지 다시 묻고 두 번째부터는 양쪽 모두 빈
// 문자열로 읽어 그 둘이 같다고 판정한다 — 빈 토큰을 저장하고 종료 코드 0 으로 끝난다. 제어 터미널을
// 떼지 않으면 터미널에서 부른 kyu 가 사용자 화면에 프롬프트를 띄운 채로 멎는다.
func (vault keychainVault) store(profileName, token string) error {
	command := exec.Command(vault.securityPath, keychainStoreArguments(profileName)...)
	command.Stdin = strings.NewReader(token + "\n" + token + "\n")
	detachFromControllingTerminal(command)

	var stderr bytes.Buffer
	command.Stderr = &stderr

	if err := command.Run(); err != nil {
		return fmt.Errorf("키체인에 저장하지 못했습니다: %w (%s)", err, strings.TrimSpace(stderr.String()))
	}
	return vault.confirmStoredToken(profileName, token)
}

// confirmStoredToken 은 방금 저장한 값을 다시 꺼내 건넨 것과 같은지 본다.
//
// 종료 코드만으로는 저장을 확인할 수 없다는 것이 이 결함이 남긴 사실이다 — security 는 성공으로
// 끝내고 빈 토큰을 남겼고, 사용자는 등록에 성공한 화면을 본 뒤 이후 모든 호출에서 401 을 받았다.
// 한 번 더 물으면 그런 어긋남이 등록하는 그 자리에서, 사용자가 아직 무엇을 할 수 있을 때 드러난다.
func (vault keychainVault) confirmStoredToken(profileName, token string) error {
	stored, err := vault.lookup(profileName)
	if err != nil {
		return fmt.Errorf("키체인에 저장한 토큰을 다시 읽지 못했습니다: %w", err)
	}

	// 값을 메시지에 적지 않는다. 길이만으로도 무엇이 어긋났는지는 충분히 좁혀진다.
	if stored != token {
		return fmt.Errorf("키체인이 저장한 것과 다른 값을 돌려줍니다 (%d 바이트를 넣었는데 %d 바이트가 돌아왔습니다)",
			len(token), len(stored))
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
