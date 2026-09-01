package workdir

import (
	"os"
	"os/exec"
	"path/filepath"
	"testing"
)

// isolateGitFromUserConfig 는 테스트가 실행 머신의 git 설정에 좌우되지 않게 한다.
//
// 실측: core.autocrlf=true 인 머신(WSL 기본값)에서는 LF 로 쓴 파일이 커밋 직후에도 수정된
// 것으로 잡혀, 깨끗해야 할 레포가 DIRTY 로 판정된다. commit.gpgsign 이 켜져 있으면 커밋
// 자체가 실패한다. 전역·시스템 설정 파일을 /dev/null 로 돌려 아예 읽히지 않게 한다.
func isolateGitFromUserConfig(t *testing.T) {
	t.Helper()

	if _, err := exec.LookPath("git"); err != nil {
		t.Skip("git 이 PATH 에 없어 상태 추론 테스트를 건너뜁니다")
	}

	t.Setenv("GIT_CONFIG_GLOBAL", os.DevNull)
	t.Setenv("GIT_CONFIG_SYSTEM", os.DevNull)
}

// runGitForTest 는 테스트용 레포를 준비하기 위해 git 명령을 실행한다.
//
// 커밋에 필요한 신원은 -c 로 그 호출에만 넘긴다. 사용자의 전역 설정을 건드리지 않으면서,
// isolateGitFromUserConfig 로 전역 설정을 끊어놓은 상태에서도 커밋이 되게 하는 유일한 방법이다.
func runGitForTest(t *testing.T, repoPath string, args ...string) {
	t.Helper()

	command := exec.Command("git", append([]string{"-c", "user.name=test", "-c", "user.email=test@test"}, args...)...)
	command.Dir = repoPath

	if output, err := command.CombinedOutput(); err != nil {
		t.Fatalf("테스트 준비용 git %v 실패: %v (%s)", args, err, output)
	}
}

func writeFileInRepo(t *testing.T, repoPath, fileName, content string) {
	t.Helper()

	if err := os.WriteFile(filepath.Join(repoPath, fileName), []byte(content), 0o644); err != nil {
		t.Fatalf("테스트용 파일 쓰기 실패 (%s): %v", fileName, err)
	}
}

// newCleanGitRepo 는 커밋 하나가 들어있고 워킹 트리가 깨끗하며 업스트림이 없는 레포를 만든다.
//
// git 을 흉내 내지 않고 실제로 돌린다. 상태 추론이 알고 싶은 것은 "git 이 이 디렉토리를 어떻게
// 보는가" 인데, 가짜 출력으로 대신하면 검증되는 것은 우리가 상상한 git 의 답일 뿐이다.
func newCleanGitRepo(t *testing.T) Repo {
	t.Helper()
	isolateGitFromUserConfig(t)

	repoPath := t.TempDir()
	runGitForTest(t, repoPath, "init", "--quiet", "--initial-branch=main")
	writeFileInRepo(t, repoPath, "README.md", "최초 내용\n")
	runGitForTest(t, repoPath, "add", "README.md")
	runGitForTest(t, repoPath, "commit", "--quiet", "-m", "최초 커밋")

	return Repo{Name: filepath.Base(repoPath), AbsolutePath: repoPath}
}

func TestInferRepoStateReturnsIdleWhenNothingIsLeftBehind(t *testing.T) {
	repo := newCleanGitRepo(t)

	got, err := InferRepoState(repo)
	if err != nil {
		t.Fatalf("InferRepoState() 실패: %v", err)
	}
	if got != RepoStateIdle {
		t.Errorf("InferRepoState() = %q, want %q", got, RepoStateIdle)
	}
}

// newBrokenGitRepo 는 git 이 저장소로 인정하지 않는 디렉토리를 만든다.
//
// 그냥 빈 디렉토리를 쓰지 않는 이유: 임시 디렉토리가 어쩌다 다른 git 저장소 안에 놓이면
// git 이 상위를 타고 올라가 그 저장소의 상태를 답해버려 테스트가 환경에 따라 갈린다.
// 존재하지 않는 곳을 가리키는 .git 파일을 두면 어디에 있든 확실히 실패한다.
func newBrokenGitRepo(t *testing.T) Repo {
	t.Helper()
	isolateGitFromUserConfig(t)

	repoPath := t.TempDir()
	writeFileInRepo(t, repoPath, ".git", "gitdir: /존재하지-않는-git-디렉토리\n")

	return Repo{Name: filepath.Base(repoPath), AbsolutePath: repoPath}
}

func TestInferRepoStateReturnsDirtyWhenTrackedFileIsModified(t *testing.T) {
	repo := newCleanGitRepo(t)
	writeFileInRepo(t, repo.AbsolutePath, "README.md", "고친 내용\n")

	got, err := InferRepoState(repo)
	if err != nil {
		t.Fatalf("InferRepoState() 실패: %v", err)
	}
	if got != RepoStateDirty {
		t.Errorf("InferRepoState() = %q, want %q", got, RepoStateDirty)
	}
}

func TestInferRepoStateReturnsDirtyWhenUntrackedFileExists(t *testing.T) {
	repo := newCleanGitRepo(t)

	// 추적되지 않는 새 파일도 "커밋되지 않은 변경" 이다. 세션에서 새로 만든 파일이 여기 해당하는데,
	// 이것을 빠뜨리면 작업 결과가 통째로 남아있는 레포가 IDLE 로 보인다.
	writeFileInRepo(t, repo.AbsolutePath, "새-파일.txt", "아직 add 하지 않은 파일\n")

	got, err := InferRepoState(repo)
	if err != nil {
		t.Fatalf("InferRepoState() 실패: %v", err)
	}
	if got != RepoStateDirty {
		t.Errorf("InferRepoState() = %q, want %q", got, RepoStateDirty)
	}
}

func TestInferRepoStatePropagatesGitFailure(t *testing.T) {
	repo := newBrokenGitRepo(t)

	got, err := InferRepoState(repo)
	if err == nil {
		t.Fatalf("InferRepoState() = %q, 에러 없이 끝남. git 실패는 전파되어야 한다", got)
	}
	if got != "" {
		t.Errorf("InferRepoState() = %q, 판정 실패 시 빈 값을 기대", got)
	}
}

// newGitRepoWithUpstream 은 로컬 bare 레포를 원격 삼아 업스트림이 설정된 레포를 만든다.
//
// 네트워크가 필요 없고, git 이 실제로 refs/remotes/origin/main 을 만들어 준다.
// 업스트림을 config 에 손으로 써넣는 방법도 있지만 그건 우리가 아는 config 키를 검증할 뿐이라,
// git 이 실제로 어떤 상태를 만드는지는 알려주지 않는다.
func newGitRepoWithUpstream(t *testing.T) Repo {
	t.Helper()

	repo := newCleanGitRepo(t)

	bareRemotePath := t.TempDir()
	runGitForTest(t, bareRemotePath, "init", "--quiet", "--bare")

	runGitForTest(t, repo.AbsolutePath, "remote", "add", "origin", bareRemotePath)
	runGitForTest(t, repo.AbsolutePath, "push", "--quiet", "--set-upstream", "origin", "main")

	return repo
}

// commitInRepo 는 레포에 커밋 하나를 더 쌓는다. 푸시하지 않으므로 업스트림보다 앞서게 된다.
func commitInRepo(t *testing.T, repo Repo, fileName, content, message string) {
	t.Helper()

	writeFileInRepo(t, repo.AbsolutePath, fileName, content)
	runGitForTest(t, repo.AbsolutePath, "add", fileName)
	runGitForTest(t, repo.AbsolutePath, "commit", "--quiet", "-m", message)
}

func TestInferRepoStateReturnsAheadWhenLocalCommitsAreNotPushed(t *testing.T) {
	repo := newGitRepoWithUpstream(t)
	commitInRepo(t, repo, "기능.txt", "구현 완료\n", "기능 구현")

	got, err := InferRepoState(repo)
	if err != nil {
		t.Fatalf("InferRepoState() 실패: %v", err)
	}
	if got != RepoStateAhead {
		t.Errorf("InferRepoState() = %q, want %q", got, RepoStateAhead)
	}
}

func TestInferRepoStateReturnsIdleWhenBranchMatchesUpstream(t *testing.T) {
	repo := newGitRepoWithUpstream(t)

	got, err := InferRepoState(repo)
	if err != nil {
		t.Fatalf("InferRepoState() 실패: %v", err)
	}
	if got != RepoStateIdle {
		t.Errorf("InferRepoState() = %q, want %q", got, RepoStateIdle)
	}
}

func TestInferRepoStateReturnsIdleWhenBranchHasNoUpstreamEvenWithUnpushedCommits(t *testing.T) {
	// 작업 브랜치에 업스트림을 두지 않는 운용도 있다. 없는 것을 이상 상태로 취급하지 않는다(설계 문서 5.3).
	repo := newCleanGitRepo(t)
	commitInRepo(t, repo, "기능.txt", "구현 완료\n", "기능 구현")

	got, err := InferRepoState(repo)
	if err != nil {
		t.Fatalf("InferRepoState() 실패: %v", err)
	}
	if got != RepoStateIdle {
		t.Errorf("InferRepoState() = %q, 업스트림이 없으면 %q 를 기대", got, RepoStateIdle)
	}
}

func TestInferRepoStateReturnsIdleWhenUpstreamRefIsGone(t *testing.T) {
	// 머지된 브랜치가 원격에서 지워진 뒤 fetch --prune 을 하면 업스트림 설정만 남고
	// 가리키는 ref 는 사라진다. 흔한 상황이므로 에러로 올리지 않는다.
	repo := newGitRepoWithUpstream(t)
	runGitForTest(t, repo.AbsolutePath, "update-ref", "-d", "refs/remotes/origin/main")

	got, err := InferRepoState(repo)
	if err != nil {
		t.Fatalf("InferRepoState() 실패: %v", err)
	}
	if got != RepoStateIdle {
		t.Errorf("InferRepoState() = %q, 업스트림 ref 가 사라졌으면 %q 를 기대", got, RepoStateIdle)
	}
}

func TestInferRepoStateReturnsDirtyWhenBranchIsAheadAndWorkingTreeIsDirty(t *testing.T) {
	// 둘 다 해당할 때 먼저 알려야 하는 것은 커밋되지 않은 변경이다. 푸시는 커밋 뒤에 오고,
	// 워킹 트리에 남은 변경은 워크디렉토리를 버리는 순간 함께 사라진다.
	repo := newGitRepoWithUpstream(t)
	commitInRepo(t, repo, "기능.txt", "구현 완료\n", "기능 구현")
	writeFileInRepo(t, repo.AbsolutePath, "README.md", "아직 커밋하지 않은 수정\n")

	got, err := InferRepoState(repo)
	if err != nil {
		t.Fatalf("InferRepoState() 실패: %v", err)
	}
	if got != RepoStateDirty {
		t.Errorf("InferRepoState() = %q, want %q", got, RepoStateDirty)
	}
}
