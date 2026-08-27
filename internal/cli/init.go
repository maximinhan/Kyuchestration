package cli

import (
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"

	"github.com/maximinhan/Kyuchestration/internal/workdir"
)

const initUsageText = `사용법: wd init [name]

  wd init          현재 디렉토리를 워크디렉토리로 초기화한다
  wd init <name>   <name> 디렉토리를 만들어(있으면 그대로 쓴다) 그 안을 초기화한다`

// planFileTemplate 은 wd init 이 만드는 .coord/plan.md 의 초기 내용이다.
//
// 빈 파일을 두지 않고 형식을 가르치는 내용을 넣는다. 계획 파일은 사람이 손으로 쓰는 파일인데
// (설계 문서 6.1) 형식을 설계 문서에서 찾아 옮겨 적게 하면, 그 왕복을 한 번이라도 건너뛴 순간
// 도구가 읽지 못하는 계획이 만들어진다.
//
// 예시를 전부 주석으로 두는 것이 이 템플릿의 핵심이다. 실제 작업으로 살려두면 갓 초기화한
// 워크디렉토리의 wd list 에 있지도 않은 레포의 작업이 찍힌다. 주석뿐인 frontmatter 는
// LoadPlan 이 "계획 없음" 으로 읽으므로(tasks 가 비어 있다) 목록이 오염되지 않는다.
//
// 예시 줄만 # 뒤에 두 칸을 두어 주석 처리했다. 설명 줄은 # 뒤에 한 칸이라, 줄 맨 앞의 # 를
// 지우는 것만으로 예시가 그대로 계획이 된다 — 그 동치를 init_test.go 가 고정한다.
const planFileTemplate = `---
# 이 워크디렉토리의 작업 목록. 도구는 이 frontmatter 만 읽고, 아래 본문은 사람과 세션이 읽는다.
# 필드: id(필수) / repo(필수, 레포 디렉토리명) / title(필수) / needs(선행 작업 id 목록) / status(필수)
# status 에 쓸 수 있는 값: blocked / ready / doing / done
# 아래 예시에서 줄 맨 앞의 # 를 지우면 그대로 계획이 된다.
tasks:

#  - id: commons-event
#    repo: proj-a
#    title: 이벤트 클래스 추가
#    needs: []
#    status: done

#  - id: publish
#    repo: proj-b
#    title: 발행 로직 구현
#    needs: [commons-event]
#    status: ready

#  - id: consume
#    repo: proj-c
#    title: 컨슈머 구현
#    needs: [commons-event]
#    status: blocked
---

## 확정 인터페이스

다른 레포가 의존하는 시그니처를 그대로 적는다. 요약하지 않는다.

## 배경

이 작업을 하는 이유와 결정 근거.

## 작업 순서 근거

어느 작업이 먼저여야 하는지, 어느 작업이 병렬로 가능한지와 그 근거.
`

// coordDirectoryPermission 과 planFilePermission 은 만들어지는 조율 디렉토리와 계획 파일의 권한이다.
// 사람이 자기 에디터로 여는 파일이라 소유자 쓰기가 필요하고, 비밀이 아니므로 읽기는 열어둔다.
const (
	coordDirectoryPermission = 0o755
	planFilePermission       = 0o644
)

// InitWorkDir 는 wd init 을 실행한다. 워크디렉토리에 .coord/plan.md 템플릿을 만든다.
//
// 세션 백엔드를 받지 않는다. 초기화는 파일을 만드는 일이라 tmux 가 필요 없는데, 백엔드를 받으면
// 진입점이 그것을 먼저 조립하느라 tmux 가 없는 머신에서 초기화조차 못 하게 된다.
func InitWorkDir(out io.Writer, args []string) error {
	workDirPath, err := workDirPathFromInitArgs(args)
	if err != nil {
		return err
	}

	// 절대경로로 편 뒤에 쓴다. 안내에 찍히는 경로가 "." 이면 사용자는 어디에 무엇이 생겼는지 모르고,
	// 서로 다른 워크디렉토리에 같은 이름의 레포가 있을 수 있어 상대경로는 자리를 특정하지 못한다
	// (설계 문서 11절 4번).
	absoluteWorkDirPath, err := filepath.Abs(workDirPath)
	if err != nil {
		return fmt.Errorf("워크디렉토리 절대경로 변환 실패 (%s): %w", workDirPath, err)
	}

	planFilePath := workdir.PlanFilePath(absoluteWorkDirPath)

	// MkdirAll 이 워크디렉토리와 .coord 를 한 번에 만든다. 이미 있으면 그대로 두므로,
	// 레포를 먼저 클론해두고 나중에 초기화하는 순서에서도 있던 것이 지워지지 않는다.
	coordDirectoryPath := filepath.Dir(planFilePath)
	if err := os.MkdirAll(coordDirectoryPath, coordDirectoryPermission); err != nil {
		return fmt.Errorf("조율 디렉토리 생성 실패 (%s): %w", coordDirectoryPath, err)
	}

	if err := createPlanFileFromTemplate(planFilePath); err != nil {
		return err
	}

	fmt.Fprintf(out, "워크디렉토리를 초기화했습니다: %s\n계획 파일: %s\n\n다음: 이 디렉토리 아래에 레포를 클론한 뒤 wd list\n",
		absoluteWorkDirPath, planFilePath)
	return nil
}

// workDirPathFromInitArgs 는 초기화할 자리를 정한다. 인자가 없으면 현재 디렉토리다.
func workDirPathFromInitArgs(args []string) (string, error) {
	switch len(args) {
	case 0:
		return ".", nil

	case 1:
		// 옵션처럼 생긴 것을 이름으로 흘려보내면 --typo 라는 이름의 디렉토리가 조용히 만들어진다.
		if strings.HasPrefix(args[0], "-") {
			return "", fmt.Errorf("알 수 없는 옵션: %s\n\n%s", args[0], initUsageText)
		}
		return args[0], nil

	default:
		return "", fmt.Errorf("init 은 워크디렉토리 이름 하나만 받습니다 (인자 %d 개를 받음)\n\n%s", len(args), initUsageText)
	}
}

// createPlanFileFromTemplate 은 계획 파일이 아직 없을 때만 템플릿을 써넣는다.
func createPlanFileFromTemplate(planFilePath string) error {
	// O_EXCL 로 "없을 때만 만든다" 를 파일 시스템에 맡긴다. 있는지 먼저 보고 그다음 쓰는 방식은
	// 그 사이에 파일이 생기면 덮어쓰는데, 계획 파일은 사람이 쓴 글이라 덮어쓰면 되돌릴 수 없다.
	planFile, err := os.OpenFile(planFilePath, os.O_WRONLY|os.O_CREATE|os.O_EXCL, planFilePermission)
	if errors.Is(err, os.ErrExist) {
		return fmt.Errorf("이미 계획 파일이 있습니다: %s\n사람이 쓴 계획을 덮어쓰지 않습니다 — 새로 만들려면 이 파일을 직접 지우거나 옮기세요",
			planFilePath)
	}
	if err != nil {
		return fmt.Errorf("계획 파일 생성 실패 (%s): %w", planFilePath, err)
	}

	if _, writeErr := planFile.WriteString(planFileTemplate); writeErr != nil {
		// 닫기 실패는 쓰기 실패를 덮지 못한다 — 사용자가 알아야 할 원인은 앞의 것이다.
		planFile.Close()
		return fmt.Errorf("계획 파일 쓰기 실패 (%s): %w", planFilePath, writeErr)
	}

	// 닫기를 defer 로 미루지 않고 여기서 확인한다. 버퍼가 디스크로 내려가지 못한 실패는 Close 에서만
	// 드러나는데, defer 로 두면 그 에러를 버리게 되어 내용이 잘린 계획 파일이 조용히 남는다.
	if err := planFile.Close(); err != nil {
		return fmt.Errorf("계획 파일 닫기 실패 (%s): %w", planFilePath, err)
	}
	return nil
}
