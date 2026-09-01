//go:build unix

package secretstore

import (
	"os/exec"
	"syscall"
)

// detachFromControllingTerminal 은 자식을 새 세션에서 띄운다 — 제어 터미널이 없는 상태를 만든다.
//
// security 의 비밀번호 물음은 /dev/tty 를 먼저 열고, 열리지 않을 때만 표준 입력으로 물러선다.
// 그래서 터미널에서 부른 kyu 와 앱이 부른 kyu 가 서로 다른 길을 지난다 — 앞의 것은 사용자에게
// 토큰을 두 번 타이핑하라고 묻고(파이프로 건넨 값은 아무도 읽지 않는다), 뒤의 것만 파이프를 읽는다.
// 세션을 갈라 그 갈림길 자체를 없앤다.
func detachFromControllingTerminal(command *exec.Cmd) {
	command.SysProcAttr = &syscall.SysProcAttr{Setsid: true}
}
