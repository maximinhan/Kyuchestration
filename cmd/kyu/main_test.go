package main

import (
	"bufio"
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"slices"
	"strings"
	"testing"
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
	// PATH 가 비어도 답해야 한다. 릴리스 바이너리는 아무것도 갖추지 않은 새 머신에 먼저 도착한다.
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

func TestKyuWithoutArgumentsPrintsUsageAndTouchesNothing(t *testing.T) {
	// 인자 없는 kyu 는 이 디렉토리를 워크디렉토리로 삼지 않는다. 초기화하고 세션을 만들어
	// 들어가던 자리였는데, 그 일은 이제 앱이 한다 — 엔진은 무엇을 할 수 있는지만 답한다.
	workDirPath := t.TempDir()

	command := exec.Command(buildKyuBinary(t))
	command.Dir = workDirPath
	command.Env = append(os.Environ(), "HOME="+t.TempDir())

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
	if !strings.Contains(stderr.String(), "사용법") {
		t.Errorf("stderr = %q, 사용법 안내를 기대", stderr.String())
	}
	if stdout.Len() != 0 {
		t.Errorf("stdout = %q, 안내는 stdout 을 오염시키지 않기를 기대", stdout.String())
	}

	// 진입 플로우는 계획 파일을 만들고 들어갔다. 그 부작용이 남아 있으면 안 된다.
	if _, statErr := os.Stat(filepath.Join(workDirPath, ".coord")); statErr == nil {
		t.Errorf("조율 디렉토리가 만들어졌습니다: %s", workDirPath)
	}
}

func TestRetiredSessionCommandsAreRejectedAsUnknown(t *testing.T) {
	// 세션을 만들고 붙고 죽이던 셋은 은퇴했다(app-owned-sessions-design.md 6절). 라우팅에 남아
	// 있으면 사용자는 그 명령이 아직 있다고 믿고, 앱이 보유한 세션을 그것으로 찾으려 한다.
	binaryPath := buildKyuBinary(t)

	for _, retiredCommandArgs := range [][]string{{"start"}, {"attach", "main"}, {"kill", "--all"}} {
		t.Run(strings.Join(retiredCommandArgs, " "), func(t *testing.T) {
			command := exec.Command(binaryPath, retiredCommandArgs...)
			command.Dir = t.TempDir()

			var stdout, stderr bytes.Buffer
			command.Stdout = &stdout
			command.Stderr = &stderr

			err := command.Run()

			var exitErr *exec.ExitError
			if !errors.As(err, &exitErr) {
				t.Fatalf("실행 결과 = %v, 종료 코드로 끝나기를 기대 (stdout %q)", err, stdout.String())
			}
			if exitErr.ExitCode() != 1 {
				t.Errorf("종료 코드 = %d, want 1", exitErr.ExitCode())
			}
			if !strings.Contains(stderr.String(), "알 수 없는 명령") {
				t.Errorf("stderr = %q, 알 수 없는 명령으로 거절하기를 기대", stderr.String())
			}
		})
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

func TestInitCreatesThePlanFileWithoutNeedingTmux(t *testing.T) {
	// init 은 파일을 만드는 일이라 바깥 명령을 하나도 부르지 않는다. 그 사실을 여기서 고정한다 —
	// 라우팅이 무언가를 먼저 조립하기 시작하면 갓 설치한 머신에서 초기화조차 못 하게 된다.
	workDirPath := t.TempDir()

	command := exec.Command(buildKyuBinary(t), "init")
	command.Dir = workDirPath
	// PATH 를 비워 아무 명령도 찾을 수 없게 만든다. 그래도 성공해야 한다.
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

func TestCloneRefusesArgumentsWithItsOwnUsageAndExitsWithCodeOne(t *testing.T) {
	// 라우팅이 실제로 이어졌는지는 "명령이 자기 사용법으로 거절하는가" 로 드러난다.
	// 인자를 붙여 부르면 GitHub 에 붙기 전에 끝나므로, 이 테스트는 네트워크도 토큰도 쓰지 않는다.
	command := exec.Command(buildKyuBinary(t), "clone", "maximinhan")
	command.Dir = t.TempDir()
	// 저장소를 뒤지더라도 사용자의 실제 설정 디렉토리를 건드리지 않게 홈을 옮긴다.
	command.Env = append(os.Environ(),
		"HOME="+t.TempDir(), "XDG_CONFIG_HOME="+t.TempDir(), searchPathWithOnly(t))

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
	// auth 는 저장해둔 토큰을 보고 지우는 일이다. 라우팅이 다른 명령과 같은 길로 지나가면
	// 갓 설치한 머신에서 자기 토큰 목록조차 볼 수 없게 된다.
	configDirectoryPath := t.TempDir()

	command := exec.Command(buildKyuBinary(t), "auth", "list")
	command.Dir = t.TempDir()
	// PATH 를 비워 security 도 secret-tool 도 찾을 수 없게 만든다.
	command.Env = append(os.Environ(), "PATH=", "HOME="+configDirectoryPath, "XDG_CONFIG_HOME="+configDirectoryPath)

	var stdout, stderr bytes.Buffer
	command.Stdout = &stdout
	command.Stderr = &stderr

	if err := command.Run(); err != nil {
		t.Fatalf("kyu auth list 실행 실패: %v (%s)", err, stderr.String())
	}

	if !strings.Contains(stdout.String(), "kyu auth add") {
		t.Errorf("stdout = %q, 등록이 일어나는 자리를 알리기를 기대", stdout.String())
	}
}

func TestSessionCommandAnswersOnAMachineWithoutTmux(t *testing.T) {
	// 앱은 claude 를 자기 PTY 에서 직접 띄운다. 이 명령은 그 앞에서 "무엇을 띄울까" 에만 답하므로
	// 바깥 명령을 하나도 부르지 않아야 하고, 그 사실을 라우팅 자리에서 고정한다(설계 문서 5.2).
	//
	// PATH 를 비워 실행한다. git 도 없는 상태이고, 그래도 답이 나와야 한다.
	workDirPath := t.TempDir()

	command := exec.Command(buildKyuBinary(t), "session-command", "--json")
	command.Dir = workDirPath
	command.Env = append(os.Environ(), "PATH=")

	var stdout, stderr bytes.Buffer
	command.Stdout = &stdout
	command.Stderr = &stderr

	if err := command.Run(); err != nil {
		t.Fatalf("kyu session-command 실행 실패: %v (%s)", err, stderr.String())
	}

	// 앱이 하는 일이 이것이다 — stdout 을 통째로 JSON 으로 읽는다.
	var answer struct {
		SchemaVersion int      `json:"schemaVersion"`
		Command       []string `json:"command"`
		Cwd           string   `json:"cwd"`
	}
	if err := json.Unmarshal(stdout.Bytes(), &answer); err != nil {
		t.Fatalf("stdout 을 JSON 으로 읽지 못했습니다: %v\n--- stdout ---\n%s", err, stdout.String())
	}
	if answer.SchemaVersion != 1 {
		t.Errorf("schemaVersion = %d, want 1", answer.SchemaVersion)
	}
	if len(answer.Command) == 0 || answer.Command[0] != "claude" {
		t.Errorf("command = %q, claude 로 시작하기를 기대", answer.Command)
	}

	// 임시 디렉토리 경로는 심볼릭 링크를 거쳐 올 수 있어(macOS 의 /var) 문자열로 비교하지 않는다.
	answeredCwd, err := filepath.EvalSymlinks(answer.Cwd)
	if err != nil {
		t.Fatalf("답한 cwd 를 확인하지 못했습니다: %v", err)
	}
	expectedCwd, err := filepath.EvalSymlinks(workDirPath)
	if err != nil {
		t.Fatalf("워크디렉토리 경로를 확인하지 못했습니다: %v", err)
	}
	if answeredCwd != expectedCwd {
		t.Errorf("cwd = %q, want %q", answeredCwd, expectedCwd)
	}
}

// searchPathWithOnly 는 적어준 명령만 들어 있는 PATH 를 만든다.
//
// PATH 를 통째로 비우면 필요한 것(git)까지 사라져서, 시험이 "git 을 찾을 수 없습니다" 로 끝난다.
// 그러면 무엇을 증명한 것인지 알 수 없으므로, 필요한 명령만 심볼릭 링크로 모은 디렉토리 하나를
// PATH 로 준다 — 나머지가 하나도 없다는 것이 이 환경이 말하는 사실이다.
func searchPathWithOnly(t *testing.T, requiredCommands ...string) string {
	t.Helper()

	searchDirectoryPath := t.TempDir()
	for _, commandName := range requiredCommands {
		commandPath, err := exec.LookPath(commandName)
		if err != nil {
			t.Skipf("%s 를 PATH 에서 찾을 수 없어 건너뜁니다: %v", commandName, err)
		}
		if err := os.Symlink(commandPath, filepath.Join(searchDirectoryPath, commandName)); err != nil {
			t.Fatalf("%s 링크 생성 실패: %v", commandName, err)
		}
	}
	return "PATH=" + searchDirectoryPath
}

// initGitRepoForListing 은 목록이 상태를 판정할 수 있는 최소한의 레포를 만든다.
//
// 커밋을 남기지 않는다. 커밋이 없어도 git status 와 업스트림 확인은 답하고(IDLE),
// 이 시험이 묻는 것은 상태 낱말이 무엇인가가 아니라 세션 백엔드 없이 판정까지 가는가다.
func initGitRepoForListing(t *testing.T, repoAbsolutePath string) {
	t.Helper()

	if _, err := exec.LookPath("git"); err != nil {
		t.Skip("git 이 PATH 에 없어 목록 시험을 건너뜁니다")
	}
	if err := os.MkdirAll(repoAbsolutePath, 0o755); err != nil {
		t.Fatalf("레포 디렉토리 생성 실패: %v", err)
	}

	initialize := exec.Command("git", "init", "--quiet")
	initialize.Dir = repoAbsolutePath
	if output, err := initialize.CombinedOutput(); err != nil {
		t.Fatalf("git init 실패: %v (%s)", err, output)
	}
}

func TestListAnswersWithNothingButGitOnThePath(t *testing.T) {
	// 대시보드가 3 초마다 부르는 명령이다. 이 자리가 종료 코드 1 로 끝나면 앱은 목록을 통째로
	// 잃는다. git 만 있는 PATH 로 부르는 것은 목록이 다른 무엇도 요구하게 되는 날을 막기 위해서다.
	workDirPath := t.TempDir()
	initGitRepoForListing(t, filepath.Join(workDirPath, "proj-a"))

	command := exec.Command(buildKyuBinary(t), "list", "--json")
	command.Dir = workDirPath
	command.Env = append(os.Environ(), searchPathWithOnly(t, "git"))

	var stdout, stderr bytes.Buffer
	command.Stdout = &stdout
	command.Stderr = &stderr

	if err := command.Run(); err != nil {
		t.Fatalf("kyu list --json 실행 실패: %v (%s)", err, stderr.String())
	}
	if stderr.Len() != 0 {
		t.Errorf("stderr = %q, 목록은 할 말이 없으면 아무것도 쓰지 않아야 한다", stderr.String())
	}

	// 앱이 하는 일이 이것이다 — stdout 을 통째로 JSON 으로 읽는다.
	var answer struct {
		SchemaVersion int `json:"schemaVersion"`
		Repos         []struct {
			Name  string `json:"name"`
			State string `json:"state"`
		} `json:"repos"`
		MainSession struct {
			Alive bool `json:"alive"`
		} `json:"mainSession"`
	}
	if err := json.Unmarshal(stdout.Bytes(), &answer); err != nil {
		t.Fatalf("stdout 을 JSON 으로 읽지 못했습니다: %v\n--- stdout ---\n%s", err, stdout.String())
	}
	if answer.SchemaVersion != 1 {
		t.Errorf("schemaVersion = %d, want 1", answer.SchemaVersion)
	}
	if len(answer.Repos) != 1 || answer.Repos[0].Name != "proj-a" {
		t.Fatalf("repos = %+v, proj-a 하나를 기대", answer.Repos)
	}

	// 상태는 이제 git 하나로만 정해진다. RUNNING 은 세션을 보던 시절의 값이라 나오지 않는다.
	if answer.Repos[0].State == "RUNNING" {
		t.Errorf("proj-a 의 state = %q, git 만으로 판정하기를 기대", answer.Repos[0].State)
	}
	if answer.MainSession.Alive {
		t.Error("mainSession.alive = true, 엔진이 세션을 세지 않으므로 false 를 기대")
	}
}

// TestMCPServeSpeaksTheProtocolOverStdioToARealProcess 는 실제 프로세스와 줄을 주고받는다.
//
// 이 서버의 상대는 우리 코드가 아니라 claude 의 MCP 클라이언트다. 함수 호출로만 검증하면
// "stdout 에 진단 한 줄이 섞였다" 나 "프로세스가 곧바로 끝난다" 같은, 이 표면에서 가장 흔한
// 실패가 통째로 빠진다 — 그것들은 프로세스 경계에서만 드러난다.
func TestMCPServeSpeaksTheProtocolOverStdioToARealProcess(t *testing.T) {
	workDirPath := t.TempDir()
	initGitRepoForListing(t, filepath.Join(workDirPath, "proj-a"))

	command := exec.Command(buildKyuBinary(t), "mcp", "serve", workDirPath)
	// 서버는 자기가 뜬 디렉토리를 보지 않는다. 인자로 받은 워크디렉토리를 훑는 것이 계약이고,
	// 다른 곳에서 띄워도 같은 답이 나와야 그 계약이 성립한다.
	command.Dir = t.TempDir()
	command.Env = append(os.Environ(), searchPathWithOnly(t, "git"))

	requestWriter, err := command.StdinPipe()
	if err != nil {
		t.Fatalf("stdin 파이프 생성 실패: %v", err)
	}
	answerReader, err := command.StdoutPipe()
	if err != nil {
		t.Fatalf("stdout 파이프 생성 실패: %v", err)
	}
	var stderr bytes.Buffer
	command.Stderr = &stderr

	if err := command.Start(); err != nil {
		t.Fatalf("kyu mcp serve 실행 실패: %v", err)
	}

	requestLines := []string{
		`{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"claude","version":"2.1.252"}}}`,
		`{"jsonrpc":"2.0","method":"notifications/initialized"}`,
		`{"jsonrpc":"2.0","id":2,"method":"tools/list"}`,
		`{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"list_repos","arguments":{}}}`,
	}
	for _, requestLine := range requestLines {
		if _, err := fmt.Fprintln(requestWriter, requestLine); err != nil {
			t.Fatalf("요청을 보내지 못했습니다: %v", err)
		}
	}

	// 요청 순서로 답을 받지 않는다. 서버는 요청마다 따로 답하므로(동시 위임이 그 이유다)
	// 답의 짝은 순서가 아니라 id 가 짓는다.
	answers := readJSONRPCAnswers(t, answerReader, len(requestLines)-1)

	// stdin 을 닫는 것이 claude 가 세션을 접는 자리다. 그때 서버도 끝나야 프로세스가 남지 않는다.
	requestWriter.Close()
	if err := command.Wait(); err != nil {
		t.Fatalf("kyu mcp serve 가 깨끗하게 끝나지 않았습니다: %v (stderr %q)", err, stderr.String())
	}
	if stderr.Len() != 0 {
		t.Errorf("stderr = %q, 서버는 할 말이 없으면 아무것도 쓰지 않아야 한다", stderr.String())
	}

	initialized := answers[1]
	if initialized.Result.ServerInfo.Name != "kyu" {
		t.Errorf("serverInfo.name = %q, want kyu", initialized.Result.ServerInfo.Name)
	}
	if initialized.Result.Capabilities.Tools == nil {
		t.Error("capabilities.tools 가 없습니다 — 클라이언트가 도구를 묻지 않게 됩니다")
	}

	listedTools := answers[2].Result.Tools
	toolNames := make([]string, 0, len(listedTools))
	for _, tool := range listedTools {
		toolNames = append(toolNames, tool.Name)
	}
	if !slices.Contains(toolNames, "list_repos") {
		t.Errorf("tools/list = %q, list_repos 를 기대", toolNames)
	}

	calledContent := answers[3].Result.Content
	if len(calledContent) != 1 {
		t.Fatalf("tools/call 의 content = %+v, 텍스트 한 덩어리를 기대", calledContent)
	}
	if !strings.Contains(calledContent[0].Text, "proj-a") {
		t.Errorf("list_repos 의 답 = %q, 클론해 둔 proj-a 를 기대", calledContent[0].Text)
	}
}

// jsonRPCAnswerForTest 는 서버가 내보낸 한 줄을 되읽은 것이다. 이 시험이 보는 필드만 담는다.
type jsonRPCAnswerForTest struct {
	JSONRPC string `json:"jsonrpc"`
	ID      int    `json:"id"`
	Result  struct {
		Capabilities struct {
			Tools *struct{} `json:"tools"`
		} `json:"capabilities"`
		ServerInfo struct {
			Name string `json:"name"`
		} `json:"serverInfo"`
		Tools []struct {
			Name string `json:"name"`
		} `json:"tools"`
		Content []struct {
			Text string `json:"text"`
		} `json:"content"`
	} `json:"result"`
	Error *struct {
		Message string `json:"message"`
	} `json:"error"`
}

// readJSONRPCAnswers 는 답 줄을 기대한 수만큼 읽어 요청 id 로 찾을 수 있게 모은다.
//
// 도착 순서로 모으지 않는다. 서버는 요청마다 따로 답하므로 오래 도는 도구 뒤의 요청이 먼저
// 답할 수 있고, 순서로 짝을 지으면 그때부터 이 시험이 엉뚱한 답을 본다.
//
// stdout 을 통째로 모아 마지막에 파싱하지도 않는다. 서버가 끝나기 전에 답이 와야 한다는 것이
// 이 시험이 확인하려는 것이고, 다 읽고 나서 보면 그 구분이 사라진다.
func readJSONRPCAnswers(t *testing.T, answerReader io.Reader, wantedCount int) map[int]jsonRPCAnswerForTest {
	t.Helper()

	reader := bufio.NewReader(answerReader)
	answers := make(map[int]jsonRPCAnswerForTest, wantedCount)

	for len(answers) < wantedCount {
		line, err := reader.ReadString('\n')
		if err != nil {
			t.Fatalf("답을 %d 개까지 읽고 끊겼습니다: %v", len(answers), err)
		}

		var answer jsonRPCAnswerForTest
		if err := json.Unmarshal([]byte(line), &answer); err != nil {
			t.Fatalf("답한 줄을 JSON 으로 읽지 못했습니다: %v\n--- 줄 ---\n%s", err, line)
		}
		if answer.JSONRPC != "2.0" {
			t.Errorf("jsonrpc = %q, want 2.0", answer.JSONRPC)
		}
		if answer.Error != nil {
			t.Fatalf("서버가 거절했습니다: %s", answer.Error.Message)
		}
		answers[answer.ID] = answer
	}
	return answers
}

func TestMCPApproveCannotBeAnsweredThroughAPipe(t *testing.T) {
	// 관문의 요점은 사람이 직접 본다는 것이다(orchestration-tools-design.md 5.6). 파이프로 y 를
	// 흘려보내 통과할 수 있으면, 관문에 막힌 그 메인 세션이 자기 Bash 도구로 자기 관문을 연다.
	//
	// 프로세스로 확인한다 — 터미널인지 아닌지는 파일 서술자의 성질이라 함수 호출로는 재현되지 않는다.
	workDirPath := t.TempDir()
	repoPath := filepath.Join(workDirPath, "proj-a")
	initGitRepoForListing(t, repoPath)
	if err := os.WriteFile(filepath.Join(repoPath, ".mcp.json"), []byte(`{"mcpServers":{}}`), 0o644); err != nil {
		t.Fatalf("테스트용 .mcp.json 생성 실패: %v", err)
	}

	command := exec.Command(buildKyuBinary(t), "mcp", "approve", "proj-a")
	command.Dir = workDirPath
	command.Env = append(os.Environ(), searchPathWithOnly(t, "git"))
	command.Stdin = strings.NewReader("y\ny\ny\n")

	var stdout, stderr bytes.Buffer
	command.Stdout = &stdout
	command.Stderr = &stderr

	err := command.Run()

	var exitErr *exec.ExitError
	if !errors.As(err, &exitErr) {
		t.Fatalf("실행 결과 = %v, 종료 코드로 거절하기를 기대 (stdout %q)", err, stdout.String())
	}
	if exitErr.ExitCode() != 1 {
		t.Errorf("종료 코드 = %d, want 1", exitErr.ExitCode())
	}
	if !strings.Contains(stderr.String(), "터미널") {
		t.Errorf("stderr = %q, 터미널이 필요하다는 것을 알리기를 기대", stderr.String())
	}

	// 관문이 실제로 닫혀 있어야 한다. 거절해 놓고 기록을 남기면 다음 위임이 통과한다.
	if _, statErr := os.Stat(filepath.Join(workDirPath, ".coord", "mcp-approvals.json")); statErr == nil {
		t.Error("파이프 너머의 답으로 승인 기록이 만들어졌습니다")
	}
}
