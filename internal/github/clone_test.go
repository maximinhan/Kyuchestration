package github

import (
	"os"
	"os/exec"
	"path/filepath"
	"slices"
	"strings"
	"testing"
)

// isolateGitFromUserConfig 는 클론 테스트가 실행 머신의 git 설정에 좌우되지 않게 한다.
//
// 특히 credential.helper 다. 실행 머신의 전역 설정에 store 가 켜져 있으면, 우리가 무력화하지
// 않았을 때에만 드러나야 할 문제(토큰이 파일에 남는다)가 테스트에서는 반대로 감춰진다.
func isolateGitFromUserConfig(t *testing.T) {
	t.Helper()

	if _, err := exec.LookPath("git"); err != nil {
		t.Skip("git 이 PATH 에 없어 클론 테스트를 건너뜁니다")
	}

	t.Setenv("GIT_CONFIG_GLOBAL", os.DevNull)
	t.Setenv("GIT_CONFIG_SYSTEM", os.DevNull)
}

func runGitForTest(t *testing.T, workingDirectory string, args ...string) {
	t.Helper()

	command := exec.Command("git", append([]string{"-c", "user.name=test", "-c", "user.email=test@test"}, args...)...)
	command.Dir = workingDirectory

	if output, err := command.CombinedOutput(); err != nil {
		t.Fatalf("테스트 준비용 git %v 실패: %v (%s)", args, err, output)
	}
}

// makeBareRepositoryToCloneFrom 은 클론해올 원격 대역을 만든다.
//
// GitHub 을 흉내 내지 않고 실제 git 레포를 쓴다. 이 자리에서 확인해야 하는 것은 "git clone 이
// 끝난 뒤 .git/config 에 무엇이 남는가" 인데, 그것은 git 이 실제로 돌아야만 생기는 결과다.
func makeBareRepositoryToCloneFrom(t *testing.T) string {
	t.Helper()

	sourcePath := filepath.Join(t.TempDir(), "source")
	if err := os.MkdirAll(sourcePath, 0o755); err != nil {
		t.Fatalf("테스트용 원본 레포 디렉토리 생성 실패: %v", err)
	}

	runGitForTest(t, sourcePath, "init", "--quiet", "--initial-branch=main")
	if err := os.WriteFile(filepath.Join(sourcePath, "README.md"), []byte("최초 내용\n"), 0o644); err != nil {
		t.Fatalf("테스트용 파일 쓰기 실패: %v", err)
	}
	runGitForTest(t, sourcePath, "add", "README.md")
	runGitForTest(t, sourcePath, "commit", "--quiet", "-m", "최초 커밋")

	barePath := filepath.Join(t.TempDir(), "remote.git")
	runGitForTest(t, sourcePath, "clone", "--bare", "--quiet", sourcePath, barePath)
	return barePath
}

func TestCloneCommandArgumentsDisableExistingHelpersBeforeAddingOurOwn(t *testing.T) {
	// 순서가 이 조립의 전부다. 빈 helper 를 먼저 두지 않으면 사용자의 전역 credential.helper(store 등)가
	// 그대로 살아 있어, 클론 한 번으로 토큰이 홈 디렉토리의 파일에 적힌다.
	const cloneURL = "https://github.com/maximinhan/Kyuchestration.git"
	const destinationPath = "/tmp/워크디렉토리/Kyuchestration"

	arguments := cloneCommandArguments(cloneURL, destinationPath)

	want := []string{
		"-c", "credential.helper=",
		"-c", "credential.helper=" + tokenCredentialHelperScript,
		"clone", cloneURL, destinationPath,
	}
	if !slices.Equal(arguments, want) {
		t.Errorf("git 인자가 다릅니다.\n--- got ---\n%v\n--- want ---\n%v", arguments, want)
	}

	// 주소에 토큰을 끼워 넣는 방법(https://x-access-token:토큰@github.com/...)을 쓰지 않는다.
	// 그 주소는 그대로 .git/config 의 remote.origin.url 에 남는다.
	if arguments[len(arguments)-2] != cloneURL {
		t.Errorf("클론 주소 = %q, API 가 준 주소를 그대로 쓰기를 기대", arguments[len(arguments)-2])
	}
}

func TestCloneCommandHandsTheTokenToGitThroughTheEnvironmentNotTheArguments(t *testing.T) {
	// 인자는 같은 머신의 다른 사용자가 ps 로 들여다볼 수 있다. 토큰이 거기 실리면 클론이 도는
	// 몇 초 동안 그대로 노출된다.
	if _, err := exec.LookPath("sh"); err != nil {
		t.Skip("sh 가 없어 git 대역 테스트를 건너뜁니다")
	}

	fakeGitDirectory := t.TempDir()
	recordPath := filepath.Join(fakeGitDirectory, "record.txt")
	fakeGitScript := "#!/bin/sh\n{ echo \"인자: $*\"; echo \"환경변수: ${" + cloneTokenEnvName + "}\"; } > \"" + recordPath + "\"\n"
	if err := os.WriteFile(filepath.Join(fakeGitDirectory, "git"), []byte(fakeGitScript), 0o755); err != nil {
		t.Fatalf("git 대역 스크립트 생성 실패: %v", err)
	}
	t.Setenv("PATH", fakeGitDirectory+string(os.PathListSeparator)+os.Getenv("PATH"))

	access := NewTokenAccess(testToken)
	repository := Repository{Name: "Kyuchestration", CloneURL: "https://github.com/maximinhan/Kyuchestration.git"}
	if err := access.CloneRepository(repository, filepath.Join(t.TempDir(), "Kyuchestration")); err != nil {
		t.Fatalf("CloneRepository() 실패: %v", err)
	}

	record, err := os.ReadFile(recordPath)
	if err != nil {
		t.Fatalf("git 대역이 남긴 기록을 읽지 못했습니다: %v", err)
	}

	recordedArguments, recordedEnvironment, _ := strings.Cut(string(record), "\n")
	if strings.Contains(recordedArguments, testToken) {
		t.Errorf("git 인자 = %q, 토큰이 실리지 않기를 기대", recordedArguments)
	}
	if !strings.Contains(recordedEnvironment, testToken) {
		t.Errorf("git 환경변수 = %q, 토큰이 %s 로 전달되기를 기대", recordedEnvironment, cloneTokenEnvName)
	}
}

func TestCloneRepositoryLeavesNoTokenInTheClonedRepositoryConfig(t *testing.T) {
	// 이 PR 의 보안 핵심이다. 클론이 끝난 뒤 .git/config 에 토큰이 남으면, 사용자가 그 워크디렉토리를
	// 지우기 전까지 토큰이 디스크에 평문으로 남고 백업·동기화를 따라 퍼진다.
	isolateGitFromUserConfig(t)

	barePath := makeBareRepositoryToCloneFrom(t)
	destinationPath := filepath.Join(t.TempDir(), "Kyuchestration")

	access := NewTokenAccess(testToken)
	repository := Repository{Name: "Kyuchestration", CloneURL: barePath}
	if err := access.CloneRepository(repository, destinationPath); err != nil {
		t.Fatalf("CloneRepository() 실패: %v", err)
	}

	if _, err := os.Stat(filepath.Join(destinationPath, "README.md")); err != nil {
		t.Fatalf("클론된 내용이 없습니다: %v", err)
	}

	clonedConfig, err := os.ReadFile(filepath.Join(destinationPath, ".git", "config"))
	if err != nil {
		t.Fatalf("클론된 레포의 설정을 읽지 못했습니다: %v", err)
	}
	if strings.Contains(string(clonedConfig), testToken) {
		t.Errorf(".git/config 에 토큰이 남았습니다:\n%s", clonedConfig)
	}
	if !strings.Contains(string(clonedConfig), barePath) {
		t.Errorf(".git/config = %s, 클론 주소가 그대로 남기를 기대", clonedConfig)
	}
}
