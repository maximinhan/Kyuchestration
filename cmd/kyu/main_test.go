package main

import (
	"bytes"
	"errors"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"

	"github.com/maximinhan/Kyuchestration/internal/session"
)

// 라우팅은 함수 호출로 검증하지 않고 바이너리를 띄워서 확인한다.
//
// 라우팅의 결과물은 "무엇을 안내하고 어떤 종료 코드로 끝나는가" 인데, 종료 코드는 os.Exit 가 정한다.
// 같은 프로세스 안에서 부르면 테스트 프로세스가 그대로 죽으므로 관찰할 방법이 없다.
// 셸이나 CI 가 이 도구의 성패를 판단하는 근거가 종료 코드라, 그 자리는 실제로 실행해서 고정한다.
func buildKyuBinary(t *testing.T, extraBuildArgs ...string) string {
	t.Helper()

	if _, err := exec.LookPath("go"); err != nil {
		t.Skip("go 를 PATH 에서 찾을 수 없어 빌드 스모크 테스트를 건너뜁니다")
	}

	binaryPath := filepath.Join(t.TempDir(), "kyu")
	buildArgs := append([]string{"build"}, extraBuildArgs...)
	buildArgs = append(buildArgs, "-o", binaryPath, ".")

	build := exec.Command("go", buildArgs...)
	if output, err := build.CombinedOutput(); err != nil {
		t.Fatalf("kyu 빌드 실패: %v (%s)", err, output)
	}
	return binaryPath
}

// versionLdflagsSymbolPath 는 릴리스 빌드가 버전을 주입하는 심볼의 전체 경로다.
//
// 이 상수가 존재하는 이유는 링커가 없는 심볼을 조용히 무시하기 때문이다. 경로를 한 글자 틀려도
// 빌드는 성공하고 CI 는 초록이며, 릴리스 바이너리가 dev 라고 답하는 것을 누군가 새 머신에서
// 발견해야만 드러난다. 아래 테스트가 실제로 주입해 보는 것으로 그 자리를 못박는다.
const versionLdflagsSymbolPath = "github.com/maximinhan/Kyuchestration/internal/cli.injectedVersion"

func TestVersionReportsTheVersionInjectedByTheReleaseBuild(t *testing.T) {
	const injectedTestVersion = "v9.9.9-ldflags-test"

	binaryPath := buildKyuBinary(t, "-ldflags", "-X "+versionLdflagsSymbolPath+"="+injectedTestVersion)
	command := exec.Command(binaryPath, "version")
	// tmux 없이도 답해야 한다. 릴리스 바이너리는 tmux 를 아직 깔지 않은 새 머신에 먼저 도착한다.
	command.Env = append(os.Environ(), "PATH=")

	var stdout, stderr bytes.Buffer
	command.Stdout = &stdout
	command.Stderr = &stderr

	if err := command.Run(); err != nil {
		t.Fatalf("kyu version 실행 실패: %v (%s)", err, stderr.String())
	}

	if !strings.Contains(stdout.String(), injectedTestVersion) {
		t.Errorf("stdout = %q, 주입한 %s 를 포함하기를 기대 — 심볼 경로 %s 가 맞는지 확인하세요",
			stdout.String(), injectedTestVersion, versionLdflagsSymbolPath)
	}
}

func TestEveryBuildThatInjectsTheVersionUsesTheSymbolThisTestPins(t *testing.T) {
	// 위 테스트는 "이 테스트가 적은 경로" 로 주입해 볼 뿐이다. 실제로 바이너리를 만드는 쪽이
	// 다른 경로를 적고 있으면 테스트도 CI 도 초록인 채로 그 바이너리만 버전을 잃는다 —
	// 링커가 없는 심볼을 조용히 무시하기 때문이다. 실제로 나가는 명령들을 여기서 잇는다.
	//
	// 주입하는 자리가 둘이다. 릴리스 워크플로가 만드는 CLI 바이너리 셋과, 데스크톱 빌드가
	// 설치 패키지 안에 동봉하는 엔진이다. 뒤엣것이 버전을 잃으면 앱 안의 엔진만 자기를
	// dev 라고 답하는데, 그 사실은 설치한 사람이 kyu version 을 쳐 보기 전에는 드러나지 않는다.
	buildFilePaths := []string{
		filepath.Join("..", "..", ".github", "workflows", "release.yml"),
		filepath.Join("..", "..", "desktop", "build.gradle.kts"),
	}

	// 뒤의 = 까지 함께 본다. 경로만 찾으면 injectedVersionTYPO 처럼 뒤에 덧붙은 오타가
	// 부분 문자열로 걸려 그대로 통과한다 — 링커는 그 오타 심볼을 조용히 무시할 텐데도.
	symbolAssignment := versionLdflagsSymbolPath + "="

	for _, buildFilePath := range buildFilePaths {
		buildFile, err := os.ReadFile(buildFilePath)
		if err != nil {
			t.Fatalf("빌드 파일을 읽지 못했습니다 (%s): %v", buildFilePath, err)
		}

		if !strings.Contains(string(buildFile), symbolAssignment) {
			t.Errorf("%s 가 심볼 %s 에 주입하고 있지 않습니다 — 그 빌드가 낸 바이너리는 버전을 잃습니다",
				buildFilePath, versionLdflagsSymbolPath)
		}
	}
}

func TestVersionIdentifiesTheCommitWhenTheBuildInjectedNothing(t *testing.T) {
	// 주입 없이 빌드한 바이너리도 자기 출처를 말해야 한다. go 는 .git 이 있는 자리에서 빌드하면
	// 커밋을 박아 주는데(-buildvcs), 그것을 실제로 꺼내 쓰고 있는지는 여기서만 드러난다.
	if _, err := exec.LookPath("git"); err != nil {
		t.Skip("git 이 PATH 에 없어 커밋 표시 테스트를 건너뜁니다")
	}

	headRevision, err := exec.Command("git", "rev-parse", "HEAD").Output()
	if err != nil {
		t.Skipf("현재 커밋을 알 수 없어 건너뜁니다: %v", err)
	}
	abbreviatedHeadRevision := strings.TrimSpace(string(headRevision))[:12]

	command := exec.Command(buildKyuBinary(t), "version")
	command.Env = append(os.Environ(), "PATH=")

	var stdout, stderr bytes.Buffer
	command.Stdout = &stdout
	command.Stderr = &stderr

	if err := command.Run(); err != nil {
		t.Fatalf("kyu version 실행 실패: %v (%s)", err, stderr.String())
	}

	if !strings.Contains(stdout.String(), abbreviatedHeadRevision) {
		t.Errorf("stdout = %q, 빌드에 쓰인 커밋 %s 를 포함하기를 기대", stdout.String(), abbreviatedHeadRevision)
	}
	// go 가 버전을 정하지 못했다는 표시일 뿐이라 사용자에게 그대로 보이면 버전처럼 읽힌다.
	if strings.Contains(stdout.String(), "(devel)") {
		t.Errorf("stdout = %q, go 의 (devel) 을 그대로 내보내지 않기를 기대", stdout.String())
	}
}

func TestKyuWithoutArgumentsEntersTheWorkDirInsteadOfListingIt(t *testing.T) {
	// 기본 명령이 바뀐 자리다. 인자 없는 kyu 가 list 로 남아 있으면 여기서 목록과 종료 코드 0 이 나온다.
	//
	// 관측점으로 홈 디렉토리의 자동 초기화 거절을 쓴다. 진입 플로우에만 있는 반응이고, 세션을
	// 만들기 전에 끝나므로 이 테스트가 실제 tmux 세션이나 claude 프로세스를 남기지 않는다.
	// 진입의 마지막 한 걸음은 어차피 TTY 를 잡아 자동 테스트로 따라갈 수 없다.
	if _, err := exec.LookPath("tmux"); err != nil {
		t.Skip("tmux 가 PATH 에 없어 진입 라우팅 테스트를 건너뜁니다")
	}

	homeDirectoryPath := t.TempDir()

	command := exec.Command(buildKyuBinary(t))
	command.Dir = homeDirectoryPath
	command.Env = append(os.Environ(), "HOME="+homeDirectoryPath)

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
	if !strings.Contains(stderr.String(), "kyu init") {
		t.Errorf("stderr = %q, 명시적 초기화 방법 안내를 기대", stderr.String())
	}

	// 목록 헤더가 찍혔다면 라우팅이 아직 list 를 가리키고 있다는 뜻이다.
	if strings.Contains(stdout.String(), "WorkDir:") {
		t.Errorf("stdout = %q, 목록이 아니라 진입으로 가기를 기대", stdout.String())
	}
	if _, statErr := os.Stat(filepath.Join(homeDirectoryPath, ".coord")); statErr == nil {
		t.Errorf("홈 디렉토리에 조율 디렉토리가 만들어졌습니다: %s", homeDirectoryPath)
	}
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
	if !strings.Contains(stderr.String(), "사용법: kyu kill") {
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

func TestKyuWithAnOptionRoutesToTheEntryFlowInsteadOfFailingAsAnUnknownCommand(t *testing.T) {
	// 진입 명령이 옵션을 받게 된 뒤로, 첫 인자가 - 로 시작하는 실행은 서브커맨드 이름을 찾는
	// 갈림에 걸리면 안 된다. 걸리면 kyu --bypass-permissions 가 "알 수 없는 명령" 으로 끝난다.
	//
	// 관측점은 인자 없는 kyu 와 같은 홈 디렉토리 자동 초기화 거절이다. 진입 플로우에만 있는
	// 반응이고, 세션을 만들기 전에 끝나므로 tmux 세션도 claude 프로세스도 남기지 않는다.
	if _, err := exec.LookPath("tmux"); err != nil {
		t.Skip("tmux 가 PATH 에 없어 진입 라우팅 테스트를 건너뜁니다")
	}

	homeDirectoryPath := t.TempDir()

	command := exec.Command(buildKyuBinary(t), "--bypass-permissions")
	command.Dir = homeDirectoryPath
	command.Env = append(os.Environ(), "HOME="+homeDirectoryPath)

	var stdout, stderr bytes.Buffer
	command.Stdout = &stdout
	command.Stderr = &stderr

	err := command.Run()

	var exitErr *exec.ExitError
	if !errors.As(err, &exitErr) {
		t.Fatalf("kyu --bypass-permissions 실행 결과 = %v, 종료 코드로 끝나기를 기대", err)
	}
	if strings.Contains(stderr.String(), "알 수 없는 명령") {
		t.Errorf("stderr = %q, 옵션을 명령으로 읽지 않기를 기대", stderr.String())
	}
	if !strings.Contains(stderr.String(), "kyu init") {
		t.Errorf("stderr = %q, 진입 플로우의 명시적 초기화 안내를 기대", stderr.String())
	}
}

func TestCloneRefusesArgumentsWithItsOwnUsageAndExitsWithCodeOne(t *testing.T) {
	// 라우팅이 실제로 이어졌는지는 "명령이 자기 사용법으로 거절하는가" 로 드러난다.
	// 인자를 붙여 부르면 GitHub 에 붙기 전에 끝나므로, 이 테스트는 네트워크도 토큰도 쓰지 않는다.
	//
	// 진입점이 세션 백엔드를 먼저 조립하므로 tmux 가 없으면 인자를 보기도 전에 그쪽으로 끝난다.
	if _, err := exec.LookPath("tmux"); err != nil {
		t.Skip("tmux 가 PATH 에 없어 라우팅 테스트를 건너뜁니다")
	}

	command := exec.Command(buildKyuBinary(t), "clone", "maximinhan")
	command.Dir = t.TempDir()
	// 저장소를 뒤지더라도 사용자의 실제 설정 디렉토리를 건드리지 않게 홈을 옮긴다.
	command.Env = append(os.Environ(), "HOME="+t.TempDir(), "XDG_CONFIG_HOME="+t.TempDir())

	var stdout, stderr bytes.Buffer
	command.Stdout = &stdout
	command.Stderr = &stderr

	err := command.Run()

	var exitErr *exec.ExitError
	if !errors.As(err, &exitErr) {
		t.Fatalf("kyu clone 실행 결과 = %v, 종료 코드로 끝나기를 기대", err)
	}
	if exitErr.ExitCode() != 1 {
		t.Errorf("종료 코드 = %d, want 1", exitErr.ExitCode())
	}
	if !strings.Contains(stderr.String(), "사용법: kyu clone") {
		t.Errorf("stderr = %q, clone 의 사용법 안내를 기대", stderr.String())
	}
}

func TestAuthAnswersWithoutNeedingTmux(t *testing.T) {
	// auth 는 저장해둔 토큰을 보고 지우는 일이라 tmux 와 무관하다. 라우팅이 다른 명령과 같은 길로
	// 지나가면 tmux 없는 머신에서 자기 토큰 목록조차 볼 수 없게 된다.
	configDirectoryPath := t.TempDir()

	command := exec.Command(buildKyuBinary(t), "auth", "list")
	command.Dir = t.TempDir()
	// PATH 를 비워 tmux 도 security 도 secret-tool 도 찾을 수 없게 만든다.
	command.Env = append(os.Environ(), "PATH=", "HOME="+configDirectoryPath, "XDG_CONFIG_HOME="+configDirectoryPath)

	var stdout, stderr bytes.Buffer
	command.Stdout = &stdout
	command.Stderr = &stderr

	if err := command.Run(); err != nil {
		t.Fatalf("kyu auth list 실행 실패: %v (%s)", err, stderr.String())
	}

	if !strings.Contains(stdout.String(), "kyu clone") {
		t.Errorf("stdout = %q, 등록이 일어나는 자리를 알리기를 기대", stdout.String())
	}
}

func TestUsageDoesNotAdvertiseTheHiddenSuperviseCommand(t *testing.T) {
	// 감독을 띄우는 하위 명령은 사용자가 부를 것이 아니다 — 기동은 세션 생성이 알아서 한다.
	// 사용법에 실리면 사용자가 그것을 직접 부르는 길이 생기고, 그 길에는 준비 파이프도
	// 부모도 없다(설계 문서 5.1).
	if strings.Contains(usageText, session.SuperviseCommandName) {
		t.Errorf("사용법에 숨은 하위 명령 %s 가 실려 있습니다", session.SuperviseCommandName)
	}
}

func TestSuperviseCommandIsRoutedToTheSupervisorInsteadOfBeingUnknown(t *testing.T) {
	// 감독은 이 바이너리 자신을 다시 실행한 것이다. 라우팅이 없으면 세션 생성이 "알 수 없는
	// 명령" 으로 끝나는데, 그 실패는 감독의 기록 파일 안에만 남아 찾기 어렵다.
	command := exec.Command(buildKyuBinary(t), session.SuperviseCommandName)

	var stdout, stderr bytes.Buffer
	command.Stdout = &stdout
	command.Stderr = &stderr

	err := command.Run()

	var exitErr *exec.ExitError
	if !errors.As(err, &exitErr) {
		t.Fatalf("kyu %s 실행 결과 = %v, 종료 코드로 끝나기를 기대", session.SuperviseCommandName, err)
	}
	if strings.Contains(stderr.String(), "알 수 없는 명령") {
		t.Errorf("stderr = %q, 라우팅이 이어지지 않았습니다", stderr.String())
	}
	if !strings.Contains(stderr.String(), "--name") {
		t.Errorf("stderr = %q, 인자가 없다는 감독의 거절을 기대", stderr.String())
	}
}

func TestUnknownSessionBackendNamesTheChoicesInsteadOfFallingBackQuietly(t *testing.T) {
	command := exec.Command(buildKyuBinary(t), "list")
	command.Dir = t.TempDir()
	command.Env = append(os.Environ(), session.BackendSelectionEnvName+"=오타난이름")

	var stdout, stderr bytes.Buffer
	command.Stdout = &stdout
	command.Stderr = &stderr

	err := command.Run()

	var exitErr *exec.ExitError
	if !errors.As(err, &exitErr) {
		t.Fatalf("kyu list 실행 결과 = %v, 종료 코드로 끝나기를 기대", err)
	}
	// 조용히 기본값으로 돌아가면 사용자는 자기가 고른 줄 알았던 백엔드가 아닌 것으로 세션을
	// 만들고, 그 사실을 세션이 안 보일 때 알게 된다.
	for _, expected := range []string{session.TmuxBackendName, session.SupervisorBackendName} {
		if !strings.Contains(stderr.String(), expected) {
			t.Errorf("stderr = %q, 고를 수 있는 이름 %q 를 포함하기를 기대", stderr.String(), expected)
		}
	}
}

func TestSupervisorBackendIsChosenByEnvironmentAndDoesNotAskForTmux(t *testing.T) {
	// tmux 를 걷어내는 것이 이 작업의 목적이므로, 감독 백엔드를 고른 실행은 tmux 가 없는
	// 머신에서도 그 사실을 화제로 삼지 않아야 한다(설계 문서 1.2).
	command := exec.Command(buildKyuBinary(t), "list")
	command.Dir = t.TempDir()
	command.Env = append(os.Environ(),
		"PATH=",
		session.BackendSelectionEnvName+"="+session.SupervisorBackendName,
		"KYU_RUNTIME_DIR="+t.TempDir(),
	)

	var stdout, stderr bytes.Buffer
	command.Stdout = &stdout
	command.Stderr = &stderr
	command.Run()

	if strings.Contains(stderr.String(), "tmux") || strings.Contains(stdout.String(), "tmux") {
		t.Errorf("stdout = %q, stderr = %q, 감독 백엔드는 tmux 를 요구하지 않아야 한다",
			stdout.String(), stderr.String())
	}
}
