package cli

import (
	"bytes"
	"errors"
	"os"
	"os/exec"
	"path/filepath"
	"slices"
	"strings"
	"testing"

	"github.com/maximinhan/Kyuchestration/internal/session"
	"github.com/maximinhan/Kyuchestration/internal/workdir"
)

// testWorkDirName 은 테스트가 만드는 워크디렉토리의 이름이다.
//
// t.TempDir() 이 준 경로를 그대로 워크디렉토리로 쓰지 않는다. 그 마지막 요소는 "001" 같은
// 일련번호인데, 워크디렉토리 이름은 세션 이름(wd-<workdir>-<repo>)과 헤더에 그대로 드러나는 값이라
// 기대값을 적으려면 테스트가 이름을 직접 정해야 한다.
const testWorkDirName = "WorkDir-featureX"

// 이 파일의 git 헬퍼는 internal/workdir 의 테스트와 모양이 겹친다. 그런데도 공용 패키지로 뽑지 않았다.
//
//  1. 필요한 모양이 다르다. 여기서는 "워크디렉토리 하나 안에 레포 여러 개" 를 만들어야 하는데
//     저쪽 헬퍼는 레포마다 t.TempDir() 을 따로 잡는다. 그대로는 쓸 수 없고 시그니처부터 갈린다.
//  2. 공용화하려면 앞 PR 의 테스트를 고쳐야 한다. 검증이 끝난 코드를 뒤 PR 이 흔드는 대가가
//     실제 중복(git 명령 몇 줄)보다 크다.
//
// 같은 모양의 세 번째 사용처가 생기면 그때 뽑는다.

// isolateGitFromUserConfig 는 테스트가 실행 머신의 git 설정에 좌우되지 않게 한다.
//
// core.autocrlf=true 인 머신(WSL 기본값)에서는 LF 로 쓴 파일이 커밋 직후에도 수정된 것으로 잡혀
// 깨끗해야 할 레포가 DIRTY 로 판정된다. commit.gpgsign 이 켜져 있으면 커밋 자체가 실패한다.
func isolateGitFromUserConfig(t *testing.T) {
	t.Helper()

	if _, err := exec.LookPath("git"); err != nil {
		t.Skip("git 이 PATH 에 없어 목록 테스트를 건너뜁니다")
	}

	t.Setenv("GIT_CONFIG_GLOBAL", os.DevNull)
	t.Setenv("GIT_CONFIG_SYSTEM", os.DevNull)
}

// runGitForTest 는 테스트용 레포를 준비하기 위해 git 명령을 실행한다.
//
// 커밋에 필요한 신원은 -c 로 그 호출에만 넘긴다. 사용자의 전역 설정을 건드리지 않으면서
// isolateGitFromUserConfig 로 전역 설정을 끊어놓은 상태에서도 커밋이 되게 하는 유일한 방법이다.
func runGitForTest(t *testing.T, repoPath string, args ...string) {
	t.Helper()

	command := exec.Command("git", append([]string{"-c", "user.name=test", "-c", "user.email=test@test"}, args...)...)
	command.Dir = repoPath

	if output, err := command.CombinedOutput(); err != nil {
		t.Fatalf("테스트 준비용 git %v 실패: %v (%s)", args, err, output)
	}
}

// makeWorkDir 는 이름이 정해진 빈 워크디렉토리를 만든다.
func makeWorkDir(t *testing.T) string {
	t.Helper()
	isolateGitFromUserConfig(t)

	workDirPath := filepath.Join(t.TempDir(), testWorkDirName)
	if err := os.MkdirAll(workDirPath, 0o755); err != nil {
		t.Fatalf("테스트용 워크디렉토리 생성 실패: %v", err)
	}
	return workDirPath
}

// makeCleanRepo 는 커밋 하나가 들어있고 워킹 트리가 깨끗하며 업스트림이 없는 레포를 만든다.
// 세션이 없으면 IDLE 로 판정되는 상태다.
func makeCleanRepo(t *testing.T, workDirPath, repoName string) {
	t.Helper()

	repoPath := filepath.Join(workDirPath, repoName)
	if err := os.MkdirAll(repoPath, 0o755); err != nil {
		t.Fatalf("테스트용 레포 디렉토리 생성 실패 (%s): %v", repoName, err)
	}

	runGitForTest(t, repoPath, "init", "--quiet", "--initial-branch=main")
	writeFileInRepo(t, repoPath, "README.md", "최초 내용\n")
	runGitForTest(t, repoPath, "add", "README.md")
	runGitForTest(t, repoPath, "commit", "--quiet", "-m", "최초 커밋")
}

// makeRepoWithUncommittedChange 는 커밋되지 않은 변경이 남은 레포를 만든다. DIRTY 로 판정되는 상태다.
func makeRepoWithUncommittedChange(t *testing.T, workDirPath, repoName string) {
	t.Helper()

	makeCleanRepo(t, workDirPath, repoName)
	writeFileInRepo(t, filepath.Join(workDirPath, repoName), "작업중.txt", "아직 커밋하지 않은 파일\n")
}

// makeBrokenRepo 는 git 이 저장소로 인정하지 않는 디렉토리를 만든다. 상태 판정이 반드시 실패한다.
func makeBrokenRepo(t *testing.T, workDirPath, repoName string) {
	t.Helper()

	repoPath := filepath.Join(workDirPath, repoName)
	if err := os.MkdirAll(repoPath, 0o755); err != nil {
		t.Fatalf("테스트용 레포 디렉토리 생성 실패 (%s): %v", repoName, err)
	}
	writeFileInRepo(t, repoPath, ".git", "gitdir: /존재하지-않는-git-디렉토리\n")
}

func writeFileInRepo(t *testing.T, repoPath, fileName, content string) {
	t.Helper()

	if err := os.WriteFile(filepath.Join(repoPath, fileName), []byte(content), 0o644); err != nil {
		t.Fatalf("테스트용 파일 쓰기 실패 (%s): %v", fileName, err)
	}
}

// fakeSessionBackend 는 어떤 이름의 세션이 살아있는지를 지정해 넣는 테스트용 SessionBackend 다.
//
// SessionBackend 를 nil 인 채로 묻어두고(embed) IsAlive 만 덮어쓴다. 목록이 IsAlive 외의 메서드를
// 부르는 순간 nil 인터페이스 역참조로 그 자리에서 패닉이 나므로, "도구는 세션 내부에 개입하지 않는다"
// (설계 원칙 3)가 테스트로 고정된다.
type fakeSessionBackend struct {
	session.SessionBackend
	aliveSessionNames   map[string]bool
	queriedSessionNames []string
}

func (backend *fakeSessionBackend) IsAlive(sessionName string) (bool, error) {
	backend.queriedSessionNames = append(backend.queriedSessionNames, sessionName)
	return backend.aliveSessionNames[sessionName], nil
}

func newFakeSessionBackend(aliveSessionNames ...string) *fakeSessionBackend {
	alive := make(map[string]bool, len(aliveSessionNames))
	for _, sessionName := range aliveSessionNames {
		alive[sessionName] = true
	}
	return &fakeSessionBackend{aliveSessionNames: alive}
}

func TestInspectWorkDirCollectsRepoStatesInNameOrderAndPutsMainLast(t *testing.T) {
	workDirPath := makeWorkDir(t)

	// 만드는 순서를 이름순과 어긋나게 둔다. 결과가 이름순이면 생성 순서에 기대지 않는다는 뜻이다.
	makeCleanRepo(t, workDirPath, "zeta-service")
	makeRepoWithUncommittedChange(t, workDirPath, "beta-gateway")
	makeCleanRepo(t, workDirPath, "alpha-commons")

	backend := newFakeSessionBackend(session.RepoSessionName(testWorkDirName, "zeta-service"))

	listing, err := inspectWorkDir(workDirPath, backend)
	if err != nil {
		t.Fatalf("inspectWorkDir() 실패: %v", err)
	}

	if listing.workDirName != testWorkDirName {
		t.Errorf("workDirName = %q, want %q", listing.workDirName, testWorkDirName)
	}

	want := []listRow{
		{label: "alpha-commons", state: workdir.RepoStateIdle},
		{label: "beta-gateway", state: workdir.RepoStateDirty},
		{label: "zeta-service", state: workdir.RepoStateRunning},
		{label: "main", state: workdir.RepoStateIdle},
	}
	if got := listing.displayRows(); !slices.Equal(got, want) {
		t.Errorf("displayRows() = %+v, want %+v", got, want)
	}
	if got := listing.liveSessionCount(); got != 1 {
		t.Errorf("liveSessionCount() = %d, want 1", got)
	}
}

func TestInspectWorkDirCountsMainSessionAsLiveWhenItIsAlive(t *testing.T) {
	workDirPath := makeWorkDir(t)
	makeCleanRepo(t, workDirPath, "alpha-commons")

	backend := newFakeSessionBackend(session.MainSessionName(testWorkDirName))

	listing, err := inspectWorkDir(workDirPath, backend)
	if err != nil {
		t.Fatalf("inspectWorkDir() 실패: %v", err)
	}

	want := listRow{label: "main", state: workdir.RepoStateRunning}
	if listing.mainRow != want {
		t.Errorf("mainRow = %+v, want %+v", listing.mainRow, want)
	}
	// 메인도 이 워크디렉토리의 세션이므로 숫자에 들어간다(설계 문서 9.3 의 "2 sessions" 가 메인을 포함한다).
	if got := listing.liveSessionCount(); got != 1 {
		t.Errorf("liveSessionCount() = %d, 메인 세션도 세어 1 을 기대", got)
	}
}

func TestInspectWorkDirDoesNotJudgeGitStateOfMainRow(t *testing.T) {
	// 메인 세션이 뜨는 곳은 워크디렉토리 최상위이고 그곳은 레포가 아니다(설계 문서 5.4).
	// 최상위에 커밋되지 않은 변경처럼 보이는 파일이 있어도 메인 행은 DIRTY 가 되지 않는다.
	workDirPath := makeWorkDir(t)
	writeFileInRepo(t, workDirPath, "메모.txt", "워크디렉토리 최상위 메모\n")

	listing, err := inspectWorkDir(workDirPath, newFakeSessionBackend())
	if err != nil {
		t.Fatalf("inspectWorkDir() 실패: %v", err)
	}

	want := listRow{label: "main", state: workdir.RepoStateIdle}
	if listing.mainRow != want {
		t.Errorf("mainRow = %+v, want %+v", listing.mainRow, want)
	}
}

func TestInspectWorkDirAsksOnlyForSessionNamesScopedToThisWorkDir(t *testing.T) {
	// 세션 이름에 워크디렉토리 이름이 빠지면, 같은 이름의 레포를 가진 다른 워크디렉토리의 세션을
	// 자기 것으로 착각한다(설계 문서 11절 4번). 물어본 이름 자체를 고정한다.
	workDirPath := makeWorkDir(t)
	makeCleanRepo(t, workDirPath, "alpha-commons")
	makeCleanRepo(t, workDirPath, "beta-gateway")

	backend := newFakeSessionBackend()
	if _, err := inspectWorkDir(workDirPath, backend); err != nil {
		t.Fatalf("inspectWorkDir() 실패: %v", err)
	}

	want := []string{
		"wd-WorkDir-featureX-alpha-commons",
		"wd-WorkDir-featureX-beta-gateway",
		"wd-WorkDir-featureX-main",
	}
	if !slices.Equal(backend.queriedSessionNames, want) {
		t.Errorf("생존을 물어본 세션 이름 = %q, want %q", backend.queriedSessionNames, want)
	}
}

func TestInspectWorkDirOnMissingWorkDirReturnsUnwrappableNotExistError(t *testing.T) {
	missingWorkDirPath := filepath.Join(t.TempDir(), "존재하지-않는-워크디렉토리")

	_, err := inspectWorkDir(missingWorkDirPath, newFakeSessionBackend())
	if err == nil {
		t.Fatalf("inspectWorkDir() 가 에러를 반환하지 않음, 워크디렉토리 부재 에러를 기대")
	}
	// %w 로 감싸 전파하는지 확인한다. %v 로 문자열화하면 호출부가 원인을 판별할 수 없다.
	if !errors.Is(err, os.ErrNotExist) {
		t.Errorf("inspectWorkDir() 에러 = %v, os.ErrNotExist 로 풀리기를 기대", err)
	}
}

func TestInspectWorkDirNamesTheRepoWhoseStateCouldNotBeJudged(t *testing.T) {
	// 레포가 여러 개일 때 "git status 실패" 만으로는 어느 레포인지 알 수 없다.
	// 사용자가 아는 이름(디렉토리명)이 메시지에 있어야 바로 그 디렉토리를 열어볼 수 있다.
	workDirPath := makeWorkDir(t)
	makeCleanRepo(t, workDirPath, "alpha-commons")
	makeBrokenRepo(t, workDirPath, "beta-gateway")

	_, err := inspectWorkDir(workDirPath, newFakeSessionBackend())
	if err == nil {
		t.Fatalf("inspectWorkDir() 가 에러를 반환하지 않음, 상태 판정 실패를 기대")
	}
	if !strings.Contains(err.Error(), "beta-gateway") {
		t.Errorf("inspectWorkDir() 에러 = %v, 판정에 실패한 레포 이름 %q 를 포함하기를 기대", err, "beta-gateway")
	}
}

// wantListOutput 은 기대 출력을 그대로 적기 위한 것이다.
//
// 열 너비나 공백 개수를 계산해서 비교하지 않는다. 목록의 값어치는 "한 화면에 읽히는가" 이고
// 그것은 최종 문자열에만 드러난다. 기대값을 눈에 보이는 그대로 적어두면, 정렬이 어긋나는 변경이
// diff 에서 곧바로 보인다.
func assertListOutput(t *testing.T, got, want string) {
	t.Helper()

	if got != want {
		t.Errorf("ListWorkDir() 출력이 다릅니다.\n--- got ---\n%s\n--- want ---\n%s", got, want)
	}
}

func TestListWorkDirWritesEachRepoWithMarkerAndStateInAlignedColumns(t *testing.T) {
	workDirPath := makeWorkDir(t)
	makeCleanRepo(t, workDirPath, "alpha-commons")
	makeRepoWithUncommittedChange(t, workDirPath, "beta-gateway")
	makeCleanRepo(t, workDirPath, "zeta-service")

	backend := newFakeSessionBackend(session.RepoSessionName(testWorkDirName, "zeta-service"))

	var out bytes.Buffer
	if err := ListWorkDir(&out, []string{workDirPath}, backend); err != nil {
		t.Fatalf("ListWorkDir() 실패: %v", err)
	}

	assertListOutput(t, out.String(), `WorkDir: WorkDir-featureX   (3 repos, 1 session)

  ○  alpha-commons  IDLE
  ○  beta-gateway   DIRTY
  ●  zeta-service   RUNNING
  ○  main           IDLE

wd attach <repo> 로 진입
`)
}

func TestListWorkDirCountsEveryLiveSessionIncludingMain(t *testing.T) {
	workDirPath := makeWorkDir(t)
	makeCleanRepo(t, workDirPath, "alpha-commons")
	makeCleanRepo(t, workDirPath, "beta-gateway")
	makeCleanRepo(t, workDirPath, "zeta-service")

	backend := newFakeSessionBackend(
		session.RepoSessionName(testWorkDirName, "alpha-commons"),
		session.RepoSessionName(testWorkDirName, "zeta-service"),
		session.MainSessionName(testWorkDirName),
	)

	var out bytes.Buffer
	if err := ListWorkDir(&out, []string{workDirPath}, backend); err != nil {
		t.Fatalf("ListWorkDir() 실패: %v", err)
	}

	assertListOutput(t, out.String(), `WorkDir: WorkDir-featureX   (3 repos, 3 sessions)

  ●  alpha-commons  RUNNING
  ○  beta-gateway   IDLE
  ●  zeta-service   RUNNING
  ●  main           RUNNING

wd attach <repo> 로 진입
`)
}

func TestListWorkDirWritesSingularNounsWhenThereIsExactlyOne(t *testing.T) {
	// "1 repos" 는 매일 보는 한 줄에 남는 어긋남이다.
	workDirPath := makeWorkDir(t)
	makeCleanRepo(t, workDirPath, "alpha-commons")

	backend := newFakeSessionBackend(session.MainSessionName(testWorkDirName))

	var out bytes.Buffer
	if err := ListWorkDir(&out, []string{workDirPath}, backend); err != nil {
		t.Fatalf("ListWorkDir() 실패: %v", err)
	}

	assertListOutput(t, out.String(), `WorkDir: WorkDir-featureX   (1 repo, 1 session)

  ○  alpha-commons  IDLE
  ●  main           RUNNING

wd attach <repo> 로 진입
`)
}

func TestListWorkDirGuidesToCloneWhenThereIsNoRepo(t *testing.T) {
	// 빈 목록만 보여주면 사용자는 도구가 레포를 못 찾은 것인지 아직 아무것도 없는 것인지 모른다.
	// 메인 행은 그대로 둔다 — 레포가 하나도 없어도 메인 세션은 떠 있을 수 있다.
	workDirPath := makeWorkDir(t)

	var out bytes.Buffer
	if err := ListWorkDir(&out, []string{workDirPath}, newFakeSessionBackend()); err != nil {
		t.Fatalf("ListWorkDir() 실패: %v", err)
	}

	assertListOutput(t, out.String(), `WorkDir: WorkDir-featureX   (0 repos, 0 sessions)

  레포 없음 — 이 디렉토리 아래에 git 레포를 클론하세요

  ○  main  IDLE

wd attach <repo> 로 진입
`)
}

func TestListWorkDirWithoutArgumentsListsCurrentDirectory(t *testing.T) {
	workDirPath := makeWorkDir(t)
	makeCleanRepo(t, workDirPath, "alpha-commons")

	t.Chdir(workDirPath)

	var out bytes.Buffer
	if err := ListWorkDir(&out, nil, newFakeSessionBackend()); err != nil {
		t.Fatalf("ListWorkDir() 실패: %v", err)
	}

	assertListOutput(t, out.String(), `WorkDir: WorkDir-featureX   (1 repo, 0 sessions)

  ○  alpha-commons  IDLE
  ○  main           IDLE

wd attach <repo> 로 진입
`)
}

func TestListWorkDirRejectsMoreThanOnePath(t *testing.T) {
	// 경로를 두 개 받으면 어느 쪽을 보여줘야 할지 알 수 없다. 하나를 골라 나머지를 조용히 버리면
	// 사용자는 자기가 지정한 워크디렉토리를 보고 있다고 착각한다.
	var out bytes.Buffer
	err := ListWorkDir(&out, []string{"첫-번째-경로", "두-번째-경로"}, newFakeSessionBackend())

	if err == nil {
		t.Fatalf("ListWorkDir() 가 에러를 반환하지 않음, 인자 개수 에러를 기대")
	}
	if out.Len() != 0 {
		t.Errorf("ListWorkDir() 가 %q 를 출력, 인자가 잘못됐으면 아무것도 쓰지 않기를 기대", out.String())
	}
}
