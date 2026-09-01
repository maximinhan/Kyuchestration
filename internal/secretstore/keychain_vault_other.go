//go:build !unix

package secretstore

import "os/exec"

// 윈도우에는 제어 터미널도 security 도 없다. 이 파일은 컴파일을 위해 있다 —
// keychainVault 는 맥에서만 만들어진다(detectSecretVault).
func detachFromControllingTerminal(*exec.Cmd) {}
