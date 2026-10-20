package workdir

import (
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// workDirWithFoldersForTest 는 폴더 몇 개를 가진 워크디렉토리를 만든다.
//
// 워크디렉토리 경로를 심볼릭 링크를 따라간 모양으로 돌려준다. 어떤 시스템은 임시 디렉토리 자체가
// 링크 아래 있어(macOS 의 /var → /private/var) 답한 절대경로와 비교할 기준이 그 모양이어야 한다.
func workDirWithFoldersForTest(t *testing.T, folderPaths ...string) string {
	t.Helper()

	workDirPath, err := filepath.EvalSymlinks(t.TempDir())
	if err != nil {
		t.Fatalf("임시 워크디렉토리 경로 확인 실패: %v", err)
	}
	for _, folderPath := range folderPaths {
		if err := os.MkdirAll(filepath.Join(workDirPath, filepath.FromSlash(folderPath)), 0o755); err != nil {
			t.Fatalf("테스트용 폴더 생성 실패 (%s): %v", folderPath, err)
		}
	}
	return workDirPath
}

func prepareForTest(t *testing.T, workDirPath string, request ChildSessionCwdRequest) ChildSessionCwd {
	t.Helper()

	cwd, err := PrepareChildSessionCwd(workDirPath, request)
	if err != nil {
		t.Fatalf("PrepareChildSessionCwd(%+v) 실패: %v", request, err)
	}
	return cwd
}

// assertRefusedForTest 는 그 요청이 거절되고, 거절 문구가 이유를 말하는지 본다.
//
// 문구를 보는 이유는 이 거절이 도구 오류로 상위의 모델에게 가기 때문이다. "거절" 만 알면 모델은
// 다른 이름으로 같은 실수를 되풀이한다 — 무엇을 고치면 되는지가 문구에 있어야 한다.
func assertRefusedForTest(t *testing.T, workDirPath string, request ChildSessionCwdRequest, wantReason string) {
	t.Helper()

	cwd, err := PrepareChildSessionCwd(workDirPath, request)
	if err == nil {
		t.Fatalf("PrepareChildSessionCwd(%+v) = %+v, 거절을 기대", request, cwd)
	}
	if !strings.Contains(err.Error(), wantReason) {
		t.Errorf("거절 문구 = %q, %q 를 담기를 기대", err, wantReason)
	}
}

func TestAnOmittedCwdIsTheWorkDirRoot(t *testing.T) {
	// 상위와 같은 자리다(6.8). 레포 여럿을 함께 보는 일을 하위에게 맡길 때다.
	workDirPath := workDirWithFoldersForTest(t)

	cwd := prepareForTest(t, workDirPath, ChildSessionCwdRequest{})

	if cwd.RelativePath != "." || cwd.AbsolutePath != workDirPath {
		t.Errorf("cwd = %+v, 루트(. · %s)를 기대", cwd, workDirPath)
	}
}

func TestAnExistingFolderIsAnsweredAsBothRelativeAndAbsolute(t *testing.T) {
	// 기록은 상대경로로 적고(6.14 — 워크디렉토리를 옮겨도 맞다), 앱은 절대경로로 프로세스를 띄운다.
	workDirPath := workDirWithFoldersForTest(t, "proj-a/web")

	cwd := prepareForTest(t, workDirPath, ChildSessionCwdRequest{RequestedPath: "proj-a/web/"})

	if cwd.RelativePath != "proj-a/web" {
		t.Errorf("RelativePath = %q, 정리한 / 경로 proj-a/web 를 기대", cwd.RelativePath)
	}
	if cwd.AbsolutePath != filepath.Join(workDirPath, "proj-a", "web") {
		t.Errorf("AbsolutePath = %q, 워크디렉토리 안 proj-a/web 를 기대", cwd.AbsolutePath)
	}
}

func TestCreateDirectoryMakesTheNewFolderInsideTheWorkDir(t *testing.T) {
	// "만든다고 말한다" 의 다른 쪽이다(6.8). 새 폴더는 워크디렉토리에 생긴다 — 레포 안에 넣는 것이
	// 아니다(원칙 2).
	workDirPath := workDirWithFoldersForTest(t)

	cwd := prepareForTest(t, workDirPath, ChildSessionCwdRequest{RequestedPath: "scratch-x", CreateDirectory: true})

	info, err := os.Stat(filepath.Join(workDirPath, "scratch-x"))
	if err != nil || !info.IsDir() {
		t.Fatalf("scratch-x 가 폴더로 생기기를 기대 (err=%v)", err)
	}
	if cwd.RelativePath != "scratch-x" {
		t.Errorf("RelativePath = %q, want scratch-x", cwd.RelativePath)
	}
}

func TestASymlinkInsideTheWorkDirIsAnsweredAsWhereItLeads(t *testing.T) {
	// 같은 폴더를 두 이름으로 부를 수 있으면 "같은 cwd 금지" 가 이름 하나 바꾸는 것으로 뚫린다.
	// 링크를 따라간 자리로 답해야 기록과 비교가 한 이름으로 선다.
	workDirPath := workDirWithFoldersForTest(t, "proj-a")
	if err := os.Symlink(filepath.Join(workDirPath, "proj-a"), filepath.Join(workDirPath, "alias-a")); err != nil {
		t.Fatalf("준비 실패: %v", err)
	}

	cwd := prepareForTest(t, workDirPath, ChildSessionCwdRequest{RequestedPath: "alias-a"})

	if cwd.RelativePath != "proj-a" {
		t.Errorf("RelativePath = %q, 링크가 가리키는 proj-a 를 기대", cwd.RelativePath)
	}
}

// 아래 여섯이 6.8 의 거절 여섯 가지다(7 절 3 단계 완료 확인).

func TestRefusesAnAbsolutePath(t *testing.T) {
	// 거절 1. 워크디렉토리 기준 상대경로만 받는다. 절대경로가 우연히 안을 가리켜도 받지 않는다 —
	// 받기 시작하면 "안을 가리키는 절대경로" 와 "밖을 가리키는 절대경로" 를 가르는 규칙이 하나 더 는다.
	workDirPath := workDirWithFoldersForTest(t, "proj-a")

	assertRefusedForTest(t, workDirPath,
		ChildSessionCwdRequest{RequestedPath: filepath.Join(workDirPath, "proj-a")}, "상대경로")
}

func TestRefusesAPathThatLeavesTheWorkDir(t *testing.T) {
	// 거절 2. 밖에 뜬 하위의 기록은 그 폴더를 모르는 곳에 남고, 워크디렉토리를 버려도 그 폴더의
	// 변경은 남는다(6.8). 정리한 뒤에도, 링크를 따라간 뒤에도 안이어야 한다.
	workDirPath := workDirWithFoldersForTest(t, "proj-a")
	outsidePath := t.TempDir()
	if err := os.Symlink(outsidePath, filepath.Join(workDirPath, "escape")); err != nil {
		t.Fatalf("준비 실패: %v", err)
	}

	assertRefusedForTest(t, workDirPath, ChildSessionCwdRequest{RequestedPath: "proj-a/../../elsewhere"}, "워크디렉토리 밖")
	assertRefusedForTest(t, workDirPath, ChildSessionCwdRequest{RequestedPath: "escape"}, "워크디렉토리 밖")
	assertRefusedForTest(t, workDirPath, ChildSessionCwdRequest{RequestedPath: "escape/new", CreateDirectory: true}, "워크디렉토리 밖")

	if _, err := os.Stat(filepath.Join(outsidePath, "new")); !errors.Is(err, os.ErrNotExist) {
		t.Errorf("거절한 요청이 워크디렉토리 밖에 폴더를 만들었습니다 (err=%v)", err)
	}
}

func TestRefusesTheCoordDirectoryAndAnythingBelowIt(t *testing.T) {
	// 거절 3. 도구가 쓰고 도구가 읽는 자리다(app-owned 5.5.2). 거기 뜬 하위가 conversations.json 을
	// 고치면 이어갈 대화가 조용히 바뀐다.
	workDirPath := workDirWithFoldersForTest(t, ".coord/runs")
	if err := os.Symlink(filepath.Join(workDirPath, ".coord"), filepath.Join(workDirPath, "coord-alias")); err != nil {
		t.Fatalf("준비 실패: %v", err)
	}

	assertRefusedForTest(t, workDirPath, ChildSessionCwdRequest{RequestedPath: ".coord"}, ".coord")
	assertRefusedForTest(t, workDirPath, ChildSessionCwdRequest{RequestedPath: ".coord/runs"}, ".coord")
	assertRefusedForTest(t, workDirPath, ChildSessionCwdRequest{RequestedPath: "coord-alias"}, ".coord")
}

func TestRefusesToCreateAFolderThatIsAlreadyThere(t *testing.T) {
	// 거절 4. "새로 만든다" 고 했는데 있던 것을 쓰면 상위가 모르는 파일 위에서 하위가 일한다(6.2).
	workDirPath := workDirWithFoldersForTest(t, "proj-a")

	assertRefusedForTest(t, workDirPath, ChildSessionCwdRequest{RequestedPath: "proj-a", CreateDirectory: true}, "이미 있")
}

func TestRefusesAFolderThatIsNotThereWithoutCreateDirectory(t *testing.T) {
	// 거절 5. 오타로 새 폴더가 생기지 않게 한다 — 만들려면 만든다고 말한다(6.8).
	workDirPath := workDirWithFoldersForTest(t, "proj-a")

	assertRefusedForTest(t, workDirPath, ChildSessionCwdRequest{RequestedPath: "proj-b"}, "createDirectory")

	if _, err := os.Stat(filepath.Join(workDirPath, "proj-b")); !errors.Is(err, os.ErrNotExist) {
		t.Errorf("거절한 요청이 proj-b 를 만들었습니다 (err=%v)", err)
	}
}

func TestRefusesACwdALiveChildIsAlreadyWorkingIn(t *testing.T) {
	// 거절 6. 같은 자리의 둘은 서로의 수정을 덮는다(orchestration 5.7). 살아 있는 하위는 앱만 알고
	// (원칙 16) 그 목록을 앱이 넘긴다 — 엔진은 그 목록과 같은 이름 규칙(링크를 따라간 자리)으로 비교한다.
	workDirPath := workDirWithFoldersForTest(t, "proj-a", "proj-b")
	if err := os.Symlink(filepath.Join(workDirPath, "proj-a"), filepath.Join(workDirPath, "alias-a")); err != nil {
		t.Fatalf("준비 실패: %v", err)
	}

	assertRefusedForTest(t, workDirPath,
		ChildSessionCwdRequest{RequestedPath: "alias-a", LiveChildCwds: []string{"proj-b", "./proj-a/"}}, "살아 있는 하위")
	assertRefusedForTest(t, workDirPath,
		ChildSessionCwdRequest{LiveChildCwds: []string{"."}}, "살아 있는 하위")

	// 다른 자리는 그대로 받는다.
	prepareForTest(t, workDirPath, ChildSessionCwdRequest{RequestedPath: "proj-b", LiveChildCwds: []string{"proj-a"}})
}
