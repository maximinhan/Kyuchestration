package secretstore

import (
	"errors"
	"fmt"
	"os/exec"
	"runtime"
)

// secretServiceName 은 키체인·secret-service 항목에 붙는 서비스 이름이다.
//
// 사용자가 자기 키체인에서 이 항목을 찾아 직접 지울 수 있어야 하므로, 도구 이름을 그대로 쓴다.
const secretServiceName = "kyu"

// secretToolItemLabel 은 secret-service 항목에 붙는 표시 이름이다.
// 사용자의 비밀번호 관리자 화면에 그대로 보이는 문구다.
const secretToolItemLabel = "kyu GitHub token"

// secretVault 는 비밀 값 하나를 이름으로 넣고 꺼내는 플랫폼 의존부다.
//
// 이 인터페이스 뒤가 이 패키지의 유일한 플랫폼 의존부다(internal/session 의 SessionBackend 와 같은 자리).
// 부르는 쪽은 어느 저장소인지 모른 채 프로필 이름으로만 말한다.
type secretVault interface {
	// kind 는 이 저장소의 종류다. 프로필 목록에 기록되고 사용자에게도 보인다.
	kind() StorageKind

	// store 는 토큰을 저장한다. 같은 이름이 있으면 덮어쓴다.
	store(profileName, token string) error

	// lookup 은 저장된 토큰을 꺼낸다. 항목이 없으면 ErrProfileNotFound 다.
	lookup(profileName string) (string, error)

	// clear 는 저장된 토큰을 지운다. 이미 없으면 성공으로 끝난다(멱등).
	clear(profileName string) error
}

// detectSecretVault 는 이 머신에서 쓸 저장소를 고른다.
//
// 순서: macOS 키체인 → secret-service → 설정 파일. 앞의 둘은 OS 가 잠금 해제와 접근 제어를
// 맡아주므로 먼저 본다. 파일은 그것들이 없을 때의 폴백이고, 평문이라는 사실을 부르는 쪽이
// 사용자에게 알린다(StorageKindForNewToken).
//
// 저장 자체를 거절하는 길은 택하지 않았다. WSL 처럼 키체인이 없는 환경이 이 도구의 주 사용처인데
// (설계 문서의 대상 플랫폼), 거기서 kyu clone 을 아예 못 쓰게 만드는 대가가 더 크다.
func detectSecretVault(configDirectory string) secretVault {
	if runtime.GOOS == "darwin" {
		if securityPath, err := exec.LookPath("security"); err == nil {
			return keychainVault{securityPath: securityPath}
		}
	}

	if secretToolPath, err := exec.LookPath("secret-tool"); err == nil && secretToolAnswers(secretToolPath) {
		return secretServiceVault{secretToolPath: secretToolPath}
	}

	return newFileVault(configDirectory)
}

// vaultForKind 는 프로필에 기록된 저장 위치에 맞는 저장소를 만든다.
//
// 지금 이 머신이 고르는 저장소와 다를 수 있다. 폴백으로 파일에 저장한 뒤 키체인이 생긴 머신에서는
// 옛 프로필을 파일에서 꺼내야 하고, 그 사실은 프로필에 적힌 종류만이 알고 있다.
func vaultForKind(kind StorageKind, configDirectory string) (secretVault, error) {
	switch kind {
	case StorageConfigFile:
		return newFileVault(configDirectory), nil

	case StorageKeychain:
		securityPath, err := exec.LookPath("security")
		if err != nil {
			return nil, fmt.Errorf("키체인에 저장된 프로필인데 security 를 찾을 수 없습니다: %w", err)
		}
		return keychainVault{securityPath: securityPath}, nil

	case StorageSecretService:
		secretToolPath, err := exec.LookPath("secret-tool")
		if err != nil {
			return nil, fmt.Errorf("secret-service 에 저장된 프로필인데 secret-tool 을 찾을 수 없습니다: %w", err)
		}
		return secretServiceVault{secretToolPath: secretToolPath}, nil

	default:
		return nil, fmt.Errorf("알 수 없는 저장 위치입니다: %s", kind)
	}
}

// secretToolAnswers 는 secret-tool 이 실제로 답하는지 확인한다.
//
// PATH 에 있는 것만으로는 부족하다. secret-tool 은 D-Bus 세션 너머의 비밀번호 관리자에게 묻는데,
// WSL 이나 데스크톱 없이 붙은 SSH 세션에는 그 상대가 없다. 그 경우 명령은 설치돼 있어도 실패하므로,
// 없는 것으로 치고 폴백해야 한다.
//
// 판정 근거는 "없는 항목을 물었을 때의 반응" 이다. 상대가 있으면 secret-tool 은 아무것도 출력하지
// 않고 종료 코드 1 로 끝난다. 상대가 없으면 종료 코드는 같지만 stderr 에 실패 이유를 적는다.
func secretToolAnswers(secretToolPath string) bool {
	// 실제로 있을 리 없는 이름을 묻는다. 사용자의 프로필 이름을 쓰면 그 항목의 잠금 해제를
	// 요구하는 창이 뜰 수 있는데, 여기서 알고 싶은 것은 저장소의 존재뿐이다.
	command := exec.Command(secretToolPath, secretToolLookupArguments("__kyu-probe__")...)

	output, err := command.CombinedOutput()
	if err == nil {
		return true
	}

	var exitErr *exec.ExitError
	if !errors.As(err, &exitErr) {
		return false
	}
	return len(output) == 0
}
