package workdir

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"slices"
	"strings"
)

// 이 파일은 하위 세션이 뜰 자리의 규칙이다 — session-tree-design.md 6.8.
//
// 상위의 모델이 cwd 를 정하지만 그 자리는 워크디렉토리 안이어야 한다. 워크디렉토리가 작업 단위라
// (workdir 부록 B) 밖에 뜬 하위의 기록은 그 폴더를 모르는 곳에 남고, 워크디렉토리를 버려도 그
// 폴더의 변경은 남는다.
//
// **이름이 아니라 링크를 따라간 자리로 판단한다.** 같은 폴더를 두 이름으로 부를 수 있으면 "밖 금지"
// 도 "같은 cwd 금지" 도 링크 하나로 뚫린다. 그래서 답하는 상대경로도 따라간 자리의 것이다.

// newChildSessionDirectoryPermission 은 createDirectory 로 만드는 폴더의 권한이다 — 사람이 만드는
// 폴더와 같다.
const newChildSessionDirectoryPermission = 0o755

// ChildSessionCwdRequest 는 상위가 고른 자리와, 그 자리를 판단하는 데 필요한 앱의 사실이다.
type ChildSessionCwdRequest struct {
	// RequestedPath 는 워크디렉토리 기준 상대경로다. 비어 있으면 워크디렉토리 루트다.
	RequestedPath string

	// CreateDirectory 는 그 자리를 새로 만들라는 뜻이다. 이미 있으면 거절한다.
	CreateDirectory bool

	// LiveChildCwds 는 지금 살아 있는 하위들의 자리다(워크디렉토리 기준 상대경로).
	//
	// 앱이 넘긴다. 누가 살아 있는지는 앱만 안다(원칙 16) — 기록의 endedAt 으로 셈하면 앱이 죽은
	// 날 적히지 못한 endedAt 이 그 자리를 영영 막는다.
	LiveChildCwds []string
}

// ChildSessionCwd 는 하위 세션이 뜰 자리다.
type ChildSessionCwd struct {
	// RelativePath 는 링크를 따라간 자리의 워크디렉토리 기준 / 경로다. 루트는 "." 다.
	//
	// 기록(conversations.json)과 관문(승인 키)이 이 모양을 쓴다 — 워크디렉토리를 옮겨도 맞는다(6.14).
	RelativePath string

	// AbsolutePath 는 앱이 프로세스를 띄울 자리다.
	AbsolutePath string
}

// PrepareChildSessionCwd 는 상위가 고른 자리를 6.8 의 규칙으로 보고, createDirectory 면 만든다.
//
// 만드는 것도 엔진이 한다. "없어야 한다" 를 본 자리와 만드는 자리가 갈라지면 그 사이에 다른
// 무언가가 같은 이름을 만들 수 있다 — 마지막 폴더는 os.Mkdir 로 만들어 "이미 있음" 을 만드는 순간
// 운영체제가 다시 확인하게 한다.
func PrepareChildSessionCwd(workDirPath string, request ChildSessionCwdRequest) (ChildSessionCwd, error) {
	resolvedWorkDirPath, err := filepath.EvalSymlinks(workDirPath)
	if err != nil {
		return ChildSessionCwd{}, fmt.Errorf("워크디렉토리 경로 확인 실패 (%s): %w", workDirPath, err)
	}

	requestedPath := request.RequestedPath
	if requestedPath == "" {
		requestedPath = "."
	}
	if filepath.IsAbs(requestedPath) {
		return ChildSessionCwd{}, fmt.Errorf("cwd 는 워크디렉토리 기준 상대경로로 줍니다 (받은 값: %s)", requestedPath)
	}

	cwd, err := resolveInsideWorkDir(resolvedWorkDirPath, requestedPath)
	if err != nil {
		return ChildSessionCwd{}, err
	}

	if err := refuseCwdOfLiveChild(resolvedWorkDirPath, cwd, request.LiveChildCwds); err != nil {
		return ChildSessionCwd{}, err
	}

	if request.CreateDirectory {
		return cwd, createChildSessionDirectory(cwd)
	}
	return cwd, requireExistingDirectory(cwd, requestedPath)
}

// resolveInsideWorkDir 는 상대경로를 링크를 따라간 자리로 옮기고, 그 자리가 워크디렉토리 안이며
// .coord 밖인지 본다.
//
// 아직 없는 자리(createDirectory)도 판단해야 한다. 있는 가장 가까운 조상까지 링크를 따라가고 나머지
// 이름을 잇는다 — 조상 하나가 밖을 가리키는 링크면 그 아래 새로 만들 자리도 밖이다.
func resolveInsideWorkDir(resolvedWorkDirPath, requestedPath string) (ChildSessionCwd, error) {
	joinedPath := filepath.Join(resolvedWorkDirPath, filepath.FromSlash(requestedPath))

	resolvedPath, err := resolveExistingAncestor(joinedPath)
	if err != nil {
		return ChildSessionCwd{}, err
	}

	relativePath, err := filepath.Rel(resolvedWorkDirPath, resolvedPath)
	if err != nil || relativePath == ".." || strings.HasPrefix(relativePath, ".."+string(filepath.Separator)) {
		return ChildSessionCwd{}, fmt.Errorf("cwd 가 워크디렉토리 밖을 가리킵니다 (%s → %s) — 하위 세션은 워크디렉토리 안에서만 뜹니다",
			requestedPath, resolvedPath)
	}

	slashRelativePath := filepath.ToSlash(relativePath)
	if slashRelativePath == coordDirectoryName || strings.HasPrefix(slashRelativePath, coordDirectoryName+"/") {
		return ChildSessionCwd{}, fmt.Errorf("cwd 로 %s 와 그 아래는 쓸 수 없습니다 (%s) — 도구가 쓰고 읽는 자리입니다",
			coordDirectoryName, requestedPath)
	}

	return ChildSessionCwd{RelativePath: slashRelativePath, AbsolutePath: resolvedPath}, nil
}

// resolveExistingAncestor 는 있는 가장 가까운 조상까지 링크를 따라가고 남은 이름을 잇는다.
func resolveExistingAncestor(path string) (string, error) {
	var missingNames []string
	existingPath := path

	for {
		resolvedPath, err := filepath.EvalSymlinks(existingPath)
		if err == nil {
			slices.Reverse(missingNames)
			return filepath.Join(append([]string{resolvedPath}, missingNames...)...), nil
		}
		if !errors.Is(err, os.ErrNotExist) {
			return "", fmt.Errorf("cwd 경로 확인 실패 (%s): %w", existingPath, err)
		}

		parentPath := filepath.Dir(existingPath)
		if parentPath == existingPath {
			return "", fmt.Errorf("cwd 경로 확인 실패 (%s): 있는 조상이 없습니다", path)
		}
		missingNames = append(missingNames, filepath.Base(existingPath))
		existingPath = parentPath
	}
}

// refuseCwdOfLiveChild 는 살아 있는 하위가 이미 그 자리에서 일하고 있으면 거절한다.
//
// 앱이 넘긴 자리도 같은 규칙으로 옮겨 비교한다. 앱이 "./proj-a/" 로 적어 두었든 링크 이름으로
// 적어 두었든 같은 폴더면 같은 자리다. 옮기지 못한 항목(그 사이 지워진 폴더)은 거기서 일할 수
// 없으므로 건너뛴다.
func refuseCwdOfLiveChild(resolvedWorkDirPath string, cwd ChildSessionCwd, liveChildCwds []string) error {
	for _, liveChildCwd := range liveChildCwds {
		if liveChildCwd == "" {
			liveChildCwd = "."
		}
		liveCwd, err := resolveInsideWorkDir(resolvedWorkDirPath, liveChildCwd)
		if err != nil {
			continue
		}
		if liveCwd.RelativePath == cwd.RelativePath {
			return fmt.Errorf("살아 있는 하위 세션이 이미 %s 에서 일하고 있습니다 — 같은 자리의 둘은 서로의 수정을 덮습니다. 다른 cwd 를 고르거나 그 세션을 끝낸 뒤 띄우세요",
				cwd.RelativePath)
		}
	}
	return nil
}

// createChildSessionDirectory 는 새 자리를 만든다. 이미 있으면 거절한다.
func createChildSessionDirectory(cwd ChildSessionCwd) error {
	if err := os.MkdirAll(filepath.Dir(cwd.AbsolutePath), newChildSessionDirectoryPermission); err != nil {
		return fmt.Errorf("새 cwd 의 상위 폴더 생성 실패 (%s): %w", filepath.Dir(cwd.AbsolutePath), err)
	}

	err := os.Mkdir(cwd.AbsolutePath, newChildSessionDirectoryPermission)
	if errors.Is(err, os.ErrExist) {
		return fmt.Errorf("createDirectory 로 만들 %s 가 이미 있습니다 — 있던 폴더를 쓰려면 createDirectory 없이 띄우세요",
			cwd.RelativePath)
	}
	if err != nil {
		return fmt.Errorf("새 cwd 생성 실패 (%s): %w", cwd.AbsolutePath, err)
	}
	return nil
}

// requireExistingDirectory 는 createDirectory 없이 고른 자리가 실제로 있는 폴더인지 본다.
func requireExistingDirectory(cwd ChildSessionCwd, requestedPath string) error {
	info, err := os.Stat(cwd.AbsolutePath)
	if errors.Is(err, os.ErrNotExist) {
		return fmt.Errorf("cwd %s 가 없습니다 — 새로 만들려면 createDirectory 를 참으로 줍니다", requestedPath)
	}
	if err != nil {
		return fmt.Errorf("cwd 확인 실패 (%s): %w", cwd.AbsolutePath, err)
	}
	if !info.IsDir() {
		return fmt.Errorf("cwd %s 는 폴더가 아닙니다", requestedPath)
	}
	return nil
}
