// Package workdir 는 워크디렉토리를 관찰해 조율에 필요한 사실을 얻는 계층이다.
//
// 설계 문서 5.1 의 3층 구조에서 조율 계층에 해당한다. 플랫폼에 의존하지 않는 것이 이 계층의
// 존재 이유이므로, 세션 백엔드(tmux 등)를 이 패키지에서 직접 호출하지 않는다.
package workdir

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
)

// Repo 는 워크디렉토리 바로 아래에서 발견한 git 레포 하나다.
type Repo struct {
	// Name 은 워크디렉토리 바로 아래의 디렉토리명이다. 목록에 보여주고 명령 인자로 받는 이름.
	Name string

	// AbsolutePath 는 레포의 절대경로다.
	//
	// 이름은 레포를 특정하지 못한다 — 서로 다른 워크디렉토리에 같은 이름의 레포가 클론돼 있을 수
	// 있다(설계 문서 11절 4번). 게다가 이 값은 세션의 cwd 이자 git 명령을 돌릴 위치라,
	// 상대경로가 한 번이라도 섞이면 호출부의 cwd 에 따라 엉뚱한 레포를 건드린다.
	// 필드 이름에 절대경로임을 박아 호출부가 그 전제를 잊지 못하게 한다.
	AbsolutePath string
}

// ScanRepos 는 워크디렉토리 바로 아래에서 git 레포를 찾아 이름순으로 반환한다.
//
// 설정 파일에 레포 목록을 유지하지 않는다. 클론하면 곧바로 인식되는 것이 요구사항이다(설계 문서 5.3).
// 하위로 재귀하지 않는 이유는 레포 안의 submodule·vendor 체크아웃까지 독립 레포로 잡히기 때문이다.
func ScanRepos(workDirPath string) ([]Repo, error) {
	absoluteWorkDirPath, err := filepath.Abs(workDirPath)
	if err != nil {
		return nil, fmt.Errorf("워크디렉토리 절대경로 변환 실패 (%s): %w", workDirPath, err)
	}

	// os.ReadDir 은 파일명 기준 정렬을 보장한다. 그 순서를 흐트러뜨리지 않고 걸러내기만 하므로
	// 결과도 이름순이고, 같은 워크디렉토리를 몇 번 스캔하든 순서가 같다.
	entries, err := os.ReadDir(absoluteWorkDirPath)
	if err != nil {
		return nil, fmt.Errorf("워크디렉토리 읽기 실패 (%s): %w", absoluteWorkDirPath, err)
	}

	var repos []Repo
	for _, entry := range entries {
		if !entry.IsDir() {
			continue
		}

		repoPath := filepath.Join(absoluteWorkDirPath, entry.Name())

		// .git 이 디렉토리인지 파일인지 따지지 않고 존재만 본다.
		// 일반 클론의 .git 은 디렉토리지만, git worktree 와 submodule 의 체크아웃에는
		// `gitdir: <실제 경로>` 한 줄이 든 .git 파일이 놓인다. 디렉토리로 못 박으면 그런
		// 체크아웃을 레포가 아니라고 잘못 판정한다.
		if _, statErr := os.Stat(filepath.Join(repoPath, ".git")); statErr != nil {
			if errors.Is(statErr, os.ErrNotExist) {
				continue
			}
			return nil, fmt.Errorf(".git 확인 실패 (%s): %w", repoPath, statErr)
		}

		repos = append(repos, Repo{Name: entry.Name(), AbsolutePath: repoPath})
	}

	return repos, nil
}
