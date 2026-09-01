package cli

import (
	"fmt"
	"path/filepath"
)

// workDirLocation 은 명령이 다루는 워크디렉토리 하나를 절대경로와 이름으로 가리킨다.
type workDirLocation struct {
	absolutePath string
	name         string
}

// currentWorkDir 는 명령을 실행한 디렉토리를 워크디렉토리로 삼는다.
//
// 이름을 절대경로의 마지막 요소에서 얻는다. 화면과 답 문서에 그대로 실리는 값인데,
// cwd 는 "." 이나 ".." 처럼 이름이 아닌 형태로 들어온다.
func currentWorkDir() (workDirLocation, error) {
	absolutePath, err := filepath.Abs(".")
	if err != nil {
		return workDirLocation{}, fmt.Errorf("현재 디렉토리의 절대경로 변환 실패: %w", err)
	}
	return workDirLocation{absolutePath: absolutePath, name: filepath.Base(absolutePath)}, nil
}
