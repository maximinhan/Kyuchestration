package main

import (
	"bytes"
	"errors"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
)

// 라우팅은 함수 호출로 검증하지 않고 바이너리를 띄워서 확인한다.
//
// 라우팅의 결과물은 "무엇을 안내하고 어떤 종료 코드로 끝나는가" 인데, 종료 코드는 os.Exit 가 정한다.
// 같은 프로세스 안에서 부르면 테스트 프로세스가 그대로 죽으므로 관찰할 방법이 없다.
// 셸이나 CI 가 이 도구의 성패를 판단하는 근거가 종료 코드라, 그 자리는 실제로 실행해서 고정한다.
func buildKyuBinary(t *testing.T) string {
	t.Helper()

	if _, err := exec.LookPath("go"); err != nil {
		t.Skip("go 를 PATH 에서 찾을 수 없어 빌드 스모크 테스트를 건너뜁니다")
	}

	binaryPath := filepath.Join(t.TempDir(), "kyu")
	build := exec.Command("go", "build", "-o", binaryPath, ".")
	if output, err := build.CombinedOutput(); err != nil {
		t.Fatalf("kyu 빌드 실패: %v (%s)", err, output)
	}
	return binaryPath
}

func TestUnknownCommandPrintsUsageToStderrAndExitsWithCodeOne(t *testing.T) {
	command := exec.Command(buildKyuBinary(t), "존재하지-않는-명령")

	var stdout, stderr bytes.Buffer
	command.Stdout = &stdout
	command.Stderr = &stderr

	err := command.Run()

	var exitErr *exec.ExitError
	if !errors.As(err, &exitErr) {
		t.Fatalf("kyu 실행 결과 = %v, 종료 코드로 끝나기를 기대", err)
	}
	if exitErr.ExitCode() != 1 {
		t.Errorf("종료 코드 = %d, want 1", exitErr.ExitCode())
	}

	// 안내는 stderr 로 보낸다. stdout 은 목록처럼 다른 명령의 입력으로 넘길 수 있는 것만 쓴다.
	if stdout.Len() != 0 {
		t.Errorf("stdout = %q, 실패 안내는 stdout 을 오염시키지 않기를 기대", stdout.String())
	}
	if !strings.Contains(stderr.String(), "사용법") {
		t.Errorf("stderr = %q, 사용법 안내를 포함하기를 기대", stderr.String())
	}
}

func TestKillWithoutArgumentsPrintsUsageToStderrAndExitsWithCodeOne(t *testing.T) {
	// 라우팅이 실제로 이어졌는지는 "명령이 자기 사용법으로 거절하는가" 로 드러난다.
	// 미구현 안내가 남아 있으면 그 자리에서 다른 문구가 나온다.
	//
	// 진입점이 세션 백엔드를 먼저 조립하므로 tmux 가 없으면 인자를 보기도 전에 그쪽으로 끝난다.
	if _, err := exec.LookPath("tmux"); err != nil {
		t.Skip("tmux 가 PATH 에 없어 라우팅 테스트를 건너뜁니다")
	}

	command := exec.Command(buildKyuBinary(t), "kill")
	// 워크디렉토리를 훑기 전에 인자에서 걸리지만, 이 레포를 워크디렉토리로 오해할 여지 자체를 없앤다.
	command.Dir = t.TempDir()

	var stdout, stderr bytes.Buffer
	command.Stdout = &stdout
	command.Stderr = &stderr

	err := command.Run()

	var exitErr *exec.ExitError
	if !errors.As(err, &exitErr) {
		t.Fatalf("kyu kill 실행 결과 = %v, 종료 코드로 끝나기를 기대", err)
	}
	if exitErr.ExitCode() != 1 {
		t.Errorf("종료 코드 = %d, want 1", exitErr.ExitCode())
	}
	if !strings.Contains(stderr.String(), "사용법: wd kill") {
		t.Errorf("stderr = %q, kill 의 사용법 안내를 기대", stderr.String())
	}
}

func TestInitCreatesThePlanFileWithoutNeedingTmux(t *testing.T) {
	// init 은 파일을 만드는 일이라 세션 백엔드를 거치지 않는다. tmux 를 건너뛰는지까지 여기서 고정한다
	// — 라우팅이 다른 명령과 같은 길로 지나가면 tmux 없는 머신에서 초기화조차 못 하게 된다.
	workDirPath := t.TempDir()

	command := exec.Command(buildKyuBinary(t), "init")
	command.Dir = workDirPath
	// PATH 를 비워 tmux 를 찾을 수 없게 만든다. 그래도 성공해야 백엔드를 거치지 않는다는 뜻이다.
	command.Env = append(os.Environ(), "PATH=")

	var stdout, stderr bytes.Buffer
	command.Stdout = &stdout
	command.Stderr = &stderr

	if err := command.Run(); err != nil {
		t.Fatalf("kyu init 실행 실패: %v (%s)", err, stderr.String())
	}

	if _, err := os.Stat(filepath.Join(workDirPath, ".coord", "plan.md")); err != nil {
		t.Errorf("계획 파일이 만들어지지 않았습니다: %v", err)
	}
	if !strings.Contains(stdout.String(), "초기화") {
		t.Errorf("stdout = %q, 초기화 안내를 기대", stdout.String())
	}
}
