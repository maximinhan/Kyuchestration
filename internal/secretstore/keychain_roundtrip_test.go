package secretstore

import (
	"errors"
	"fmt"
	"os"
	"os/exec"
	"runtime"
	"strings"
	"testing"
	"time"

	"github.com/creack/pty"
)

// 이 파일의 시험은 진짜 security 를 부른다.
//
// 나머지 시험은 명령 조립만 고정한다(vault_test.go). 그것으로는 잡히지 않는 종류의 결함이 있다 —
// 인자는 한 글자도 틀리지 않았는데 저장된 값이 건넨 값과 다른 경우다. 그 결함은 조립을 아무리
// 들여다봐도 보이지 않고, 실제 키체인에 넣었다 꺼내 봐야만 드러난다.

// keychainIntegrationEnvironmentVariable 은 실제 키체인을 건드리는 시험을 켜는 스위치다.
//
// 이 시험은 기본 키체인에 항목을 만들고 지운다. 사람의 로그인 키체인에서 돌면 그 사람의 키체인에
// 시험용 항목이 남고, 접근 허용 창까지 뜬다. 그래서 "버려도 되는 키체인이 기본으로 잡혀 있다" 를
// 부르는 쪽이 선언한 자리에서만 켠다 — CI 의 맥 잡이 그 자리다.
const keychainIntegrationEnvironmentVariable = "KYU_KEYCHAIN_INTEGRATION"

// keychainRoundTripTimeout 은 security 한 번을 기다려 주는 한도다.
//
// 시간을 재는 이유는 성능이 아니라 판정이다. 프롬프트를 띄우고 사람의 입력을 기다리는 경로로
// 빠지면 명령은 실패하지 않고 그냥 멈춘다 — 한도를 두지 않으면 시험이 초록도 빨강도 아닌 채로
// CI 를 붙잡고 있는다.
const keychainRoundTripTimeout = 20 * time.Second

// 시험이 쓰는 토큰은 전부 가짜다. 길이와 문자 집합만 GitHub 토큰을 닮게 두어, 진짜 토큰에서만
// 드러나는 차이(길이·밑줄)가 있으면 여기서도 드러나게 한다.
const (
	firstFakeToken  = "ghp_kyuRoundTripFirstAAAAAAAAAAAAAAAAAAA"
	secondFakeToken = "ghp_kyuRoundTripSecondBBBBBBBBBBBBBBBBBB"
	typedFakeToken  = "ghp_kyuTypedByHandCCCCCCCCCCCCCCCCCCCCC"
)

func TestKeychainGivesBackTheTokenItWasGiven(t *testing.T) {
	vault := keychainVaultForIntegrationTest(t)

	// 공백이 든 이름도 함께 잰다. 사람이 짓는 이름이고("회사 계정"), 이름을 어떻게 실어 보내느냐에
	// 따라 저장은 성공하는데 조회가 빈손이 되는 갈림길이 여기다.
	for _, profileName := range []string{"kyu-왕복-시험", "kyu 왕복 시험"} {
		t.Run(profileName, func(t *testing.T) {
			removeKeychainItemBeforeAndAfter(t, vault, profileName)

			if err := vault.store(profileName, firstFakeToken); err != nil {
				t.Fatalf("store() 실패: %v", err)
			}

			loaded, err := vault.lookup(profileName)
			if err != nil {
				t.Fatalf("lookup() 실패: %v", err)
			}
			if loaded != firstFakeToken {
				t.Fatalf("lookup() = %q (%d 바이트), 저장한 %q (%d 바이트) 를 그대로 기대",
					loaded, len(loaded), firstFakeToken, len(firstFakeToken))
			}
		})
	}
}

func TestKeychainReplacesTheTokenWhenTheSameProfileIsSavedAgain(t *testing.T) {
	// 사용자가 실제로 한 일이다 — 401 을 만나고 새 토큰을 발급받아 같은 프로필 이름으로 다시 등록.
	// 신규 저장과 덮어쓰기는 security 안에서 다른 길을 지나므로(-U), 한쪽이 성해도 다른 쪽이 성한지는
	// 알 수 없다.
	vault := keychainVaultForIntegrationTest(t)
	const profileName = "kyu-덮어쓰기-시험"

	removeKeychainItemBeforeAndAfter(t, vault, profileName)

	if err := vault.store(profileName, firstFakeToken); err != nil {
		t.Fatalf("첫 store() 실패: %v", err)
	}
	if err := vault.store(profileName, secondFakeToken); err != nil {
		t.Fatalf("두 번째 store() 실패: %v", err)
	}

	loaded, err := vault.lookup(profileName)
	if err != nil {
		t.Fatalf("lookup() 실패: %v", err)
	}
	if loaded != secondFakeToken {
		t.Fatalf("lookup() = %q (%d 바이트), 나중에 저장한 %q (%d 바이트) 를 기대",
			loaded, len(loaded), secondFakeToken, len(secondFakeToken))
	}
}

func TestKeychainReadsAnItemThatSomeoneTypedIntoTheSecurityPromptByHand(t *testing.T) {
	// 이 결함을 만난 사용자에게 안내된 우회가 "터미널에서 security 로 직접 넣으세요" 다. 그렇게 넣은
	// 항목을 kyu 가 계속 읽을 수 있어야 우회한 사람이 수정판에서 다시 막히지 않는다.
	vault := keychainVaultForIntegrationTest(t)
	const profileName = "kyu-손으로-넣은-시험"

	removeKeychainItemBeforeAndAfter(t, vault, profileName)

	transcript, err := typeTokenIntoSecurityPrompt(vault.securityPath, profileName, typedFakeToken)
	if err != nil {
		t.Fatalf("손으로 넣는 저장이 끝나지 않았습니다: %v (프롬프트: %q)", err, transcript)
	}

	loaded, err := vault.lookup(profileName)
	if err != nil {
		t.Fatalf("lookup() 실패: %v", err)
	}
	if loaded != typedFakeToken {
		t.Fatalf("lookup() = %q (%d 바이트), 손으로 넣은 %q (%d 바이트) 를 기대",
			loaded, len(loaded), typedFakeToken, len(typedFakeToken))
	}
}

func TestKeychainSaysTheProfileIsMissingInsteadOfAnsweringWithAnEmptyToken(t *testing.T) {
	// 빈 문자열을 돌려주면 부르는 쪽은 그것을 토큰으로 알고 GitHub 에 보낸다 — 그리고 401 을 받는다.
	// "없다" 와 "비어 있다" 가 갈리는 자리가 여기다.
	vault := keychainVaultForIntegrationTest(t)
	const profileName = "kyu-등록한-적-없는-시험"

	removeKeychainItemBeforeAndAfter(t, vault, profileName)

	if _, err := vault.lookup(profileName); !errors.Is(err, ErrProfileNotFound) {
		t.Errorf("lookup() 에러 = %v, ErrProfileNotFound 를 기대", err)
	}
}

func keychainVaultForIntegrationTest(t *testing.T) keychainVault {
	t.Helper()

	if runtime.GOOS != "darwin" {
		t.Skip("맥이 아니어서 키체인 왕복 시험을 건너뜁니다")
	}
	if os.Getenv(keychainIntegrationEnvironmentVariable) != "1" {
		t.Skipf("%s=1 이 아니어서 키체인 왕복 시험을 건너뜁니다 (버려도 되는 키체인에서만 켭니다)",
			keychainIntegrationEnvironmentVariable)
	}

	securityPath, err := exec.LookPath("security")
	if err != nil {
		t.Skipf("security 가 PATH 에 없어 키체인 왕복 시험을 건너뜁니다: %v", err)
	}
	return keychainVault{securityPath: securityPath}
}

// removeKeychainItemBeforeAndAfter 는 시험 앞뒤로 항목을 지운다.
//
// 앞에서도 지우는 이유: 앞선 시험이 실패로 끊겨 항목이 남았다면, 다음 실행의 "신규 저장" 이
// 사실은 덮어쓰기가 된다. 그러면 신규 저장 경로가 검사되지 않은 채로 초록이 뜬다.
func removeKeychainItemBeforeAndAfter(t *testing.T, vault keychainVault, profileName string) {
	t.Helper()

	if err := vault.clear(profileName); err != nil {
		t.Fatalf("시험 시작 전 정리 실패: %v", err)
	}
	t.Cleanup(func() {
		if err := vault.clear(profileName); err != nil {
			t.Errorf("시험 뒤 정리 실패: %v", err)
		}
	})
}

// typeTokenIntoSecurityPrompt 는 사람이 터미널에서 손으로 넣는 길을 그대로 지난다.
//
// PTY 를 열어 자식에게 제어 터미널로 물려준다. security 의 프롬프트는 표준 입력이 아니라
// 제어 터미널을 읽으므로, 파이프로는 이 길을 흉내 낼 수 없다.
//
// 오간 글자를 함께 돌려준다. 이 경로가 어긋나면 "무엇이 안 됐는가" 는 프롬프트 문구에만 남는다.
func typeTokenIntoSecurityPrompt(securityPath, profileName, token string) (string, error) {
	command := exec.Command(securityPath, keychainStoreArguments(profileName)...)

	terminal, err := pty.Start(command)
	if err != nil {
		return "", fmt.Errorf("PTY 를 열지 못했습니다: %w", err)
	}
	defer func() { _ = terminal.Close() }()

	// 프롬프트를 본 뒤에 타이핑한다. security 는 프롬프트 직전에 밀린 입력을 버리므로,
	// 미리 써 두면 그 글자들은 어디에도 닿지 않는다.
	transcript := make(chan string, 1)
	go func() {
		var seen strings.Builder
		buffer := make([]byte, 256)
		answeredPrompts := 0

		for {
			readCount, readErr := terminal.Read(buffer)
			if readCount > 0 {
				chunk := string(buffer[:readCount])
				seen.WriteString(chunk)

				// 값과 재확인, 두 번 묻는다. 문구는 판마다 다를 수 있어 password 만 본다.
				if answeredPrompts < 2 && strings.Contains(strings.ToLower(chunk), "password") {
					answeredPrompts++
					if _, writeErr := terminal.Write([]byte(token + "\n")); writeErr != nil {
						break
					}
				}
			}
			if readErr != nil {
				break
			}
		}
		transcript <- seen.String()
	}()

	waitErr := waitWithin(command, keychainRoundTripTimeout)
	return <-transcript, waitErr
}

// waitWithin 은 자식이 한도 안에 끝나기를 기다린다. 넘기면 죽이고 그 사실을 에러로 돌려준다.
func waitWithin(command *exec.Cmd, limit time.Duration) error {
	finished := make(chan error, 1)
	go func() { finished <- command.Wait() }()

	select {
	case err := <-finished:
		return err
	case <-time.After(limit):
		if err := command.Process.Kill(); err != nil {
			return err
		}
		<-finished
		return errors.New("한도 안에 끝나지 않아 죽였습니다")
	}
}
