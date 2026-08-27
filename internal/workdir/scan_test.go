package workdir

import (
	"errors"
	"os"
	"path/filepath"
	"slices"
	"testing"
)

// makeGitRepoDirectory 는 워크디렉토리 아래에 .git 디렉토리를 가진 하위 디렉토리를 만든다.
//
// git init 을 실제로 돌리지 않는다. ScanRepos 는 .git 의 존재만 보고 내용은 한 글자도 읽지 않으므로
// 진짜 저장소를 만들어도 검증되는 것은 늘지 않고, 테스트가 git 바이너리 유무에만 묶인다.
// (.git 안을 들여다보는 상태 추론 단계에서는 그때 진짜 저장소가 필요해진다.)
func makeGitRepoDirectory(t *testing.T, workDirPath, repoName string) {
	t.Helper()

	if err := os.MkdirAll(filepath.Join(workDirPath, repoName, ".git"), 0o755); err != nil {
		t.Fatalf("테스트용 레포 디렉토리 생성 실패 (%s): %v", repoName, err)
	}
}

func makePlainDirectory(t *testing.T, workDirPath, directoryName string) {
	t.Helper()

	if err := os.MkdirAll(filepath.Join(workDirPath, directoryName), 0o755); err != nil {
		t.Fatalf("테스트용 일반 디렉토리 생성 실패 (%s): %v", directoryName, err)
	}
}

func TestScanReposFindsOnlyDirectoriesWithGitAndSortsThemByName(t *testing.T) {
	workDirPath := t.TempDir()

	// 만드는 순서를 이름순과 어긋나게 둔다. 결과가 이름순이면 생성 순서에 기대지 않는다는 뜻이다.
	makeGitRepoDirectory(t, workDirPath, "zeta-service")
	makePlainDirectory(t, workDirPath, "notes")
	makeGitRepoDirectory(t, workDirPath, "alpha-commons")
	makeGitRepoDirectory(t, workDirPath, "mid-gateway")

	if err := os.WriteFile(filepath.Join(workDirPath, "README.md"), []byte("최상위 일반 파일"), 0o644); err != nil {
		t.Fatalf("테스트용 일반 파일 생성 실패: %v", err)
	}

	got, err := ScanRepos(workDirPath)
	if err != nil {
		t.Fatalf("ScanRepos() 실패: %v", err)
	}

	want := []Repo{
		{Name: "alpha-commons", AbsolutePath: filepath.Join(workDirPath, "alpha-commons")},
		{Name: "mid-gateway", AbsolutePath: filepath.Join(workDirPath, "mid-gateway")},
		{Name: "zeta-service", AbsolutePath: filepath.Join(workDirPath, "zeta-service")},
	}
	if !slices.Equal(got, want) {
		t.Errorf("ScanRepos() = %+v, want %+v", got, want)
	}
}

func TestScanReposAcceptsGitFileLeftByWorktreeCheckout(t *testing.T) {
	workDirPath := t.TempDir()

	// git worktree·submodule 의 체크아웃에는 .git 디렉토리 대신 gitdir 한 줄이 든 .git 파일이 놓인다.
	worktreeRepoPath := filepath.Join(workDirPath, "gateway-hotfix")
	if err := os.MkdirAll(worktreeRepoPath, 0o755); err != nil {
		t.Fatalf("테스트용 worktree 디렉토리 생성 실패: %v", err)
	}
	gitFileContent := []byte("gitdir: /somewhere/gateway/.git/worktrees/hotfix\n")
	if err := os.WriteFile(filepath.Join(worktreeRepoPath, ".git"), gitFileContent, 0o644); err != nil {
		t.Fatalf("테스트용 .git 파일 생성 실패: %v", err)
	}

	got, err := ScanRepos(workDirPath)
	if err != nil {
		t.Fatalf("ScanRepos() 실패: %v", err)
	}

	want := []Repo{{Name: "gateway-hotfix", AbsolutePath: worktreeRepoPath}}
	if !slices.Equal(got, want) {
		t.Errorf("ScanRepos() = %+v, want %+v", got, want)
	}
}

func TestScanReposReturnsAbsolutePathEvenWhenGivenRelativePath(t *testing.T) {
	workDirPath := t.TempDir()
	makeGitRepoDirectory(t, workDirPath, "alpha-commons")

	t.Chdir(workDirPath)

	got, err := ScanRepos(".")
	if err != nil {
		t.Fatalf("ScanRepos() 실패: %v", err)
	}
	if len(got) != 1 {
		t.Fatalf("ScanRepos() 가 레포 %d 개를 반환, 1 개를 기대", len(got))
	}

	// 경로 문자열을 t.TempDir() 과 직접 비교하지 않는다. macOS 는 /var 가 /private/var 로의
	// 심볼릭 링크라 Getwd 가 해석된 경로를 돌려주므로, 같은 디렉토리인데도 문자열이 어긋난다.
	if !filepath.IsAbs(got[0].AbsolutePath) {
		t.Errorf("AbsolutePath = %q, 절대경로를 기대", got[0].AbsolutePath)
	}
	if filepath.Base(got[0].AbsolutePath) != "alpha-commons" {
		t.Errorf("AbsolutePath = %q, 마지막 요소로 %q 를 기대", got[0].AbsolutePath, "alpha-commons")
	}
}

func TestScanReposOnEmptyWorkDirReturnsNoReposAndNoError(t *testing.T) {
	got, err := ScanRepos(t.TempDir())
	if err != nil {
		t.Fatalf("ScanRepos() 실패: %v", err)
	}
	if len(got) != 0 {
		t.Errorf("ScanRepos() = %+v, 빈 목록을 기대", got)
	}
}

func TestScanReposOnMissingWorkDirReturnsUnwrappableNotExistError(t *testing.T) {
	missingWorkDirPath := filepath.Join(t.TempDir(), "존재하지-않는-워크디렉토리")

	_, err := ScanRepos(missingWorkDirPath)
	if err == nil {
		t.Fatalf("ScanRepos() 가 에러를 반환하지 않음, 워크디렉토리 부재 에러를 기대")
	}
	// %w 로 감싸 전파하는지 확인한다. %v 로 문자열화하면 호출부가 원인을 판별할 수 없다.
	if !errors.Is(err, os.ErrNotExist) {
		t.Errorf("ScanRepos() 에러 = %v, os.ErrNotExist 로 풀리기를 기대", err)
	}
}
