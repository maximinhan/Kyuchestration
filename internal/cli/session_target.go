package cli

import (
	"fmt"
	"path/filepath"

	"github.com/maximinhan/Kyuchestration/internal/session"
)

// 이 파일은 "사용자가 말한 것"을 "세션 계층이 아는 것"으로 옮기는 자리다.
// 사용자는 실행 디렉토리와 레포 이름으로 말하고, 세션 계층은 세션 이름 하나로만 답한다.
// start·attach·kill 이 모두 이 변환을 거치므로 규칙을 한 곳에 둔다.

// workDirLocation 은 명령이 다루는 워크디렉토리 하나를 절대경로와 이름으로 가리킨다.
type workDirLocation struct {
	absolutePath string
	name         string
}

// currentWorkDir 는 명령을 실행한 디렉토리를 워크디렉토리로 삼는다.
//
// 이름을 절대경로의 마지막 요소에서 얻는다. 세션 이름이 kyu-<workdir>-<repo> 라서(설계 문서 5.2)
// 워크디렉토리 이름 없이는 세션을 특정할 수 없는데, cwd 는 "." 이나 ".." 처럼 이름이 아닌 형태로 들어온다.
func currentWorkDir() (workDirLocation, error) {
	absolutePath, err := filepath.Abs(".")
	if err != nil {
		return workDirLocation{}, fmt.Errorf("현재 디렉토리의 절대경로 변환 실패: %w", err)
	}
	return workDirLocation{absolutePath: absolutePath, name: filepath.Base(absolutePath)}, nil
}

// sessionNameForLabel 은 사용자가 부르는 이름(레포 이름 또는 main)을 세션 이름으로 옮긴다.
//
// main 은 예약된 이름이다. 세션 이름 규칙에서 메인 세션은 레포 자리에 main 이 들어가므로
// (kyu-<workdir>-main), main 이라는 이름의 레포가 실제로 있으면 두 세션의 이름이 겹친다.
// 규칙 자체는 설계 문서 5.2 가 정한 것이라 여기서 바꾸지 않고, main 을 메인 세션으로 못박는다 —
// 조율용 세션은 어느 워크디렉토리에나 있지만 main 이라는 이름의 레포는 그렇지 않다.
func sessionNameForLabel(workDirName, label string) string {
	if label == mainSessionLabel {
		return session.MainSessionName(workDirName)
	}
	return session.RepoSessionName(workDirName, label)
}
