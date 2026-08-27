// Package cli 는 명령 하나하나의 동작을 담는 표시 계층이다.
//
// 설계 문서 5.1 의 3층 구조에서 가장 위이고, 교체 가능한 껍데기다 — v2 의 TUI 가 이 자리를 대신한다.
// 그래서 이 패키지는 판정 로직을 갖지 않는다. 조율 계층(internal/workdir)에 묻고,
// 그 답을 사람이 읽을 형태로 옮기는 것까지가 이 패키지의 몫이다.
package cli

import (
	"fmt"
	"path/filepath"

	"github.com/maximinhan/Kyuchestration/internal/session"
	"github.com/maximinhan/Kyuchestration/internal/workdir"
)

// mainRowLabel 은 메인 세션 행에 찍히는 이름이다.
//
// 세션 이름 규칙에서 레포 자리에 들어가는 main 과 같은 낱말이지만, 이쪽은 화면에 보이는 표시다.
// 사용자가 `wd attach main` 이라고 부를 때의 이름이 세션 이름 규칙과 함께 움직여야 할 이유가 없어
// session 패키지의 상수를 끌어오지 않는다.
const mainRowLabel = "main"

// listRow 는 목록의 한 줄이다. 레포 하나 또는 메인 세션에 대응한다.
type listRow struct {
	// label 은 화면에 찍히고 사용자가 명령 인자로 되돌려 주는 이름이다.
	label string

	// state 는 관찰로 얻은 상태다. 메인 행도 같은 어휘를 쓰지만 RUNNING 아니면 IDLE 뿐이다
	// — 워크디렉토리 최상위는 레포가 아니라 DIRTY·AHEAD 라는 말이 성립하지 않는다.
	state workdir.RepoState
}

// workDirListing 은 화면에 옮기기 직전의 관찰 결과다.
//
// 관찰과 출력을 이 타입에서 끊는다. 출력 형식을 바꿀 때 판정 코드를 건드리지 않아도 되고,
// v2 에서 TUI 가 표시 계층을 대신할 때 가져갈 것이 무엇인지도 이 타입이 알려준다.
type workDirListing struct {
	workDirName string
	repoRows    []listRow
	mainRow     listRow
}

// displayRows 는 화면에 찍을 순서대로 행을 늘어놓는다.
//
// 레포는 ScanRepos 가 준 이름순 그대로이고 메인은 마지막이다. 메인은 레포가 아니라 그 묶음을
// 관장하는 자리라, 이름순에 섞여 들어가면 정렬 규칙이 깨진 것처럼 보인다.
func (listing workDirListing) displayRows() []listRow {
	rows := make([]listRow, 0, len(listing.repoRows)+1)
	rows = append(rows, listing.repoRows...)
	return append(rows, listing.mainRow)
}

// liveSessionCount 는 이 워크디렉토리에서 살아있는 세션 수를 센다(메인 포함).
//
// backend.List() 를 접두사로 걸러 세는 방법도 있지만, 그러면 디렉토리가 지워진 레포의 세션까지
// 잡혀 헤더의 숫자가 화면에 보이는 ● 개수와 어긋난다. 사용자가 직접 세어볼 수 있는 값과 같게 둔다.
func (listing workDirListing) liveSessionCount() int {
	liveCount := 0
	for _, row := range listing.displayRows() {
		if row.state == workdir.RepoStateRunning {
			liveCount++
		}
	}
	return liveCount
}

// inspectWorkDir 는 워크디렉토리를 훑어 레포별 상태와 메인 세션 상태를 모은다.
//
// backend 는 SessionBackend 인터페이스로만 받는다. 어느 백엔드를 쓸지는 진입점(cmd/wd)의 결정이고,
// 표시 계층이 tmux 를 직접 만들면 백엔드가 늘어날 때마다 이 파일이 함께 바뀐다.
func inspectWorkDir(workDirPath string, backend session.SessionBackend) (workDirListing, error) {
	// 세션 이름에 워크디렉토리 이름이 들어가므로(wd-<workdir>-<repo>) 이름을 먼저 확정해야 한다.
	// 인자로 흔히 들어오는 "." 이나 ".." 는 그 자체로는 이름이 아니라서, 절대경로로 편 뒤 마지막 요소를 쓴다.
	absoluteWorkDirPath, err := filepath.Abs(workDirPath)
	if err != nil {
		return workDirListing{}, fmt.Errorf("워크디렉토리 절대경로 변환 실패 (%s): %w", workDirPath, err)
	}
	workDirName := filepath.Base(absoluteWorkDirPath)

	repos, err := workdir.ScanRepos(absoluteWorkDirPath)
	if err != nil {
		return workDirListing{}, err
	}

	repoRows := make([]listRow, 0, len(repos))
	for _, repo := range repos {
		repoSessionName := session.RepoSessionName(workDirName, repo.Name)

		state, err := workdir.InferRepoState(repo, repoSessionName, backend)
		if err != nil {
			// 레포가 여러 개일 때 "git status 실패" 만으로는 어느 레포인지 알 수 없다.
			// 사용자가 아는 이름을 붙여야 그 디렉토리를 바로 열어볼 수 있다.
			return workDirListing{}, fmt.Errorf("레포 상태 판정 실패 (%s): %w", repo.Name, err)
		}

		repoRows = append(repoRows, listRow{label: repo.Name, state: state})
	}

	mainRow, err := inspectMainSession(workDirName, backend)
	if err != nil {
		return workDirListing{}, err
	}

	return workDirListing{workDirName: workDirName, repoRows: repoRows, mainRow: mainRow}, nil
}

// inspectMainSession 은 메인 세션 행을 만든다.
//
// 레포와 달리 git 에 묻지 않는다. 메인 세션이 뜨는 곳은 워크디렉토리 최상위이고 그곳은 레포가 아니다
// (설계 문서 5.4). 거기에 굴러다니는 파일은 커밋할 대상이 아니므로 DIRTY 라고 말하면 거짓이 된다.
// 판정 근거는 세션 생존 하나뿐이다.
func inspectMainSession(workDirName string, backend session.SessionBackend) (listRow, error) {
	mainSessionName := session.MainSessionName(workDirName)

	isAlive, err := backend.IsAlive(mainSessionName)
	if err != nil {
		return listRow{}, fmt.Errorf("메인 세션 생존 확인 실패 (%s): %w", mainSessionName, err)
	}

	if isAlive {
		return listRow{label: mainRowLabel, state: workdir.RepoStateRunning}, nil
	}
	return listRow{label: mainRowLabel, state: workdir.RepoStateIdle}, nil
}
