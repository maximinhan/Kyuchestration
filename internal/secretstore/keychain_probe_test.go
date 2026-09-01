//go:build darwin || linux

package secretstore

// 임시 파일 — 맥 러너에서 security 가 표준 입력을 어떻게 다루는지 재기 위한 것이다.
//
// WSL 에는 security 가 없어 "값 없는 -w 에 파이프로 토큰을 먹이면 무엇이 저장되는가" 를 손으로
// 확인할 방법이 없다. 짐작으로 고치지 않기 위해, 후보가 되는 먹이는 방식들을 한 벌로 지나 보고
// 각각이 실제로 무엇을 저장하는지 로그로 남긴다. 원인이 확정되면 이 파일은 걷어낸다.

import (
	"bytes"
	"encoding/hex"
	"fmt"
	"os/exec"
	"strings"
	"syscall"
	"testing"
	"time"
)

const probeTimeout = 15 * time.Second

// probeFirstToken 과 probeSecondToken 은 가짜다. 두 번째 저장이 -U 경로(사용자가 실제로 지난 길)다.
const (
	probeFirstToken  = "ghp_kyuProbeFirst111111111111111111111"
	probeSecondToken = "ghp_kyuProbeSecond22222222222222222222"
)

func TestKeychainProbeMeasuresWhatSecurityActuallyStores(t *testing.T) {
	vault := keychainVaultForIntegrationTest(t)
	securityPath := vault.securityPath

	strategies := []struct {
		name  string
		store func(securityPath, account, token string) string
	}{
		{"① 현재 코드 — 토큰 한 줄을 파이프로", storeWithPipedLines(1)},
		{"② 토큰을 두 줄로 — 재확인 입력까지", storeWithPipedLines(2)},
		{"③ 개행 없이 한 줄", storeWithPipeNoNewline},
		{"④ 새 세션(setsid) + 한 줄", storeWithNewSession(1)},
		{"⑤ 새 세션(setsid) + 두 줄", storeWithNewSession(2)},
		{"⑥ security -i 대화형 — 따옴표 없이", storeWithInteractiveMode(false)},
		{"⑦ security -i 대화형 — 따옴표로 감싸서", storeWithInteractiveMode(true)},
		{"⑧ PTY 프롬프트에 두 번 타이핑", storeByTypingIntoPromptForProbe},
		{"⑨ 대조군 — 토큰을 인자로 (운영에는 쓰지 않는다)", storeWithTokenInArguments},
	}

	for strategyIndex, strategy := range strategies {
		t.Run(strategy.name, func(t *testing.T) {
			for _, account := range []string{
				fmt.Sprintf("kyu-프로브-%d", strategyIndex),
				fmt.Sprintf("kyu 프로브 %d", strategyIndex), // 공백이 든 이름도 함께 잰다
			} {
				deleteProbeItem(securityPath, account)

				t.Logf("[%s] 신규 저장: %s", account, strategy.store(securityPath, account, probeFirstToken))
				t.Logf("[%s] 신규 조회: %s", account, describeStoredValue(securityPath, account, probeFirstToken))

				t.Logf("[%s] 재저장(-U): %s", account, strategy.store(securityPath, account, probeSecondToken))
				t.Logf("[%s] 재조회: %s", account, describeStoredValue(securityPath, account, probeSecondToken))

				deleteProbeItem(securityPath, account)
			}
		})
	}

	t.Run("⑩ security -i 가 실패를 종료 코드로 알리는가", func(t *testing.T) {
		// -i 를 답으로 고르려면 이것이 먼저 서야 한다. 실패를 0 으로 답하면 저장 실패가 조용히
		// 성공으로 지나가고, 그것은 지금 고치려는 결함과 같은 부류다.
		for _, line := range []string{
			"find-generic-password -s kyu -a kyu-없는-항목-프로브 -w",
			"이런-부명령은-없다",
			`add-generic-password -U -s kyu -a "따옴표가 안 닫힌`,
		} {
			command := exec.Command(securityPath, "-i")
			t.Logf("입력 %q → %s", line, describeRun(command, line+"\n"))
		}
	})

	t.Run("⑪ 아스키가 아닌 값을 -w 조회가 어떻게 찍는가", func(t *testing.T) {
		// lookup 은 -w 의 출력을 그대로 토큰으로 삼는다. security 가 값에 따라 표기를 바꾸면
		// (예: 16진수) 그 전제가 무너지므로 여기서 함께 확인한다.
		const account = "kyu-프로브-비아스키"
		const value = "토큰-한글-값"

		deleteProbeItem(securityPath, account)
		t.Logf("저장: %s", storeWithTokenInArguments(securityPath, account, value))
		t.Logf("조회: %s", describeStoredValue(securityPath, account, value))
		deleteProbeItem(securityPath, account)
	})
}

func storeWithPipedLines(lineCount int) func(securityPath, account, token string) string {
	return func(securityPath, account, token string) string {
		command := exec.Command(securityPath, keychainStoreArguments(account)...)
		return describeRun(command, strings.Repeat(token+"\n", lineCount))
	}
}

func storeWithPipeNoNewline(securityPath, account, token string) string {
	command := exec.Command(securityPath, keychainStoreArguments(account)...)
	return describeRun(command, token)
}

// storeWithNewSession 은 자식을 새 세션에서 띄운다 — 제어 터미널이 없는 상태를 강제한다.
//
// security 의 프롬프트는 /dev/tty 를 먼저 열고, 열리지 않을 때만 표준 입력으로 물러선다.
// 터미널에서 kyu 를 부른 사용자와 앱이 부른 kyu 가 서로 다른 길을 지나는 자리가 여기여서,
// 그 차이를 없애는 방법으로 잰다.
func storeWithNewSession(lineCount int) func(securityPath, account, token string) string {
	return func(securityPath, account, token string) string {
		command := exec.Command(securityPath, keychainStoreArguments(account)...)
		command.SysProcAttr = &syscall.SysProcAttr{Setsid: true}
		return describeRun(command, strings.Repeat(token+"\n", lineCount))
	}
}

// storeWithInteractiveMode 는 명령줄 자체를 표준 입력으로 보낸다.
//
// 이러면 토큰이 -w 뒤에 오더라도 argv 에는 실리지 않는다(ps 에는 "security -i" 만 보인다).
// 대신 security 의 자체 파서를 지나므로, 공백과 따옴표를 어떻게 다루는지가 답의 일부다.
func storeWithInteractiveMode(quoted bool) func(securityPath, account, token string) string {
	return func(securityPath, account, token string) string {
		line := fmt.Sprintf("add-generic-password -U -s %s -a %s -w %s", secretServiceName, account, token)
		if quoted {
			line = fmt.Sprintf("add-generic-password -U -s %q -a %q -w %q", secretServiceName, account, token)
		}

		command := exec.Command(securityPath, "-i")
		return fmt.Sprintf("입력=%q → %s", line, describeRun(command, line+"\n"))
	}
}

// storeWithTokenInArguments 는 대조군이다 — 저장 자체와 조회가 성한지를 가른다.
//
// 이 방식은 운영에 쓰지 않는다. 같은 머신의 다른 사용자가 ps 로 토큰을 읽는다.
func storeWithTokenInArguments(securityPath, account, token string) string {
	arguments := append(keychainStoreArguments(account), token)
	command := exec.Command(securityPath, arguments...)
	return describeRun(command, "")
}

func storeByTypingIntoPromptForProbe(securityPath, account, token string) string {
	transcript, err := typeTokenIntoSecurityPrompt(securityPath, account, token)
	return fmt.Sprintf("결과=%v 프롬프트=%q", err, transcript)
}

func describeRun(command *exec.Cmd, stdinText string) string {
	command.Stdin = strings.NewReader(stdinText)

	var stdout, stderr bytes.Buffer
	command.Stdout = &stdout
	command.Stderr = &stderr

	startedAt := time.Now()
	if err := command.Start(); err != nil {
		return fmt.Sprintf("띄우지 못함: %v", err)
	}

	err := waitWithin(command, probeTimeout)
	return fmt.Sprintf("결과=%v 소요=%s stdout=%q stderr=%q",
		err, time.Since(startedAt).Round(time.Millisecond), stdout.String(), stderr.String())
}

// describeStoredValue 는 저장된 값을 꺼내 바이트까지 적는다.
//
// 눈으로는 같아 보이는데 다른 경우가 이 결함의 본체이므로, 16진수까지 함께 남긴다.
func describeStoredValue(securityPath, account, expected string) string {
	command := exec.Command(securityPath, keychainLookupArguments(account)...)

	var stdout, stderr bytes.Buffer
	command.Stdout = &stdout
	command.Stderr = &stderr

	err := command.Run()
	raw := stdout.String()
	trimmed := strings.TrimRight(raw, "\r\n")

	return fmt.Sprintf("결과=%v 원본=%q hex=%s 트림후=%q 기대(%q)와같은가=%t stderr=%q",
		err, raw, hex.EncodeToString([]byte(raw)), trimmed, expected, trimmed == expected, stderr.String())
}

func deleteProbeItem(securityPath, account string) {
	command := exec.Command(securityPath, keychainDeleteArguments(account)...)
	_ = command.Run()
}
