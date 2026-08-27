package cli

import (
	"fmt"
	"io"
	"strings"

	"github.com/maximinhan/Kyuchestration/internal/session"
)

const killUsageText = `사용법: kyu kill [repo|--all]

  kyu kill <repo>   해당 레포의 세션을 종료한다
  kyu kill main     메인 세션을 종료한다
  kyu kill --all    이 워크디렉토리의 세션을 모두 종료한다`

// allSessionsOptionName 은 이 워크디렉토리의 세션을 모두 종료하는 옵션이다.
const allSessionsOptionName = "--all"

// KillSessions 는 kyu kill 을 실행한다. 이름 하나 또는 --all 로 이 워크디렉토리의 세션을 종료한다.
func KillSessions(out io.Writer, args []string, backend session.SessionBackend) error {
	request, err := parseKillArgs(args)
	if err != nil {
		return err
	}

	location, err := currentWorkDir()
	if err != nil {
		return err
	}

	if request.allSessions {
		return killEveryWorkDirSession(out, location.name, backend)
	}
	return killOneSession(out, location.name, request.label, backend)
}

// killRequest 는 파싱이 끝난 kyu kill 요청이다.
type killRequest struct {
	// label 은 종료할 대상의 이름이다. allSessions 가 참이면 비어 있다.
	label string

	allSessions bool
}

// parseKillArgs 는 종료할 대상 이름을 고른다.
//
// 인자 없는 kill 을 "전부 종료" 로 해석하지 않는다. 이름을 빠뜨린 사용자가 그 한 번으로
// 다른 레포에서 하던 작업까지 날리게 된다 — 되돌릴 수 없는 명령일수록 대상을 명시하게 한다.
func parseKillArgs(args []string) (killRequest, error) {
	// 인자 개수를 먼저 못박는다. --all 과 이름을 함께 받았을 때 넓은 쪽으로 해석해 전부 죽이면
	// 되돌릴 방법이 없고, 좁은 쪽으로 해석하면 사용자는 나머지도 죽은 줄 안다.
	if len(args) != 1 {
		return killRequest{}, fmt.Errorf("kill 은 종료할 이름 하나 또는 %s 가 필요합니다 (인자 %d 개를 받음)\n\n%s",
			allSessionsOptionName, len(args), killUsageText)
	}

	if args[0] == allSessionsOptionName {
		return killRequest{allSessions: true}, nil
	}
	if strings.HasPrefix(args[0], "-") {
		return killRequest{}, fmt.Errorf("알 수 없는 옵션: %s\n\n%s", args[0], killUsageText)
	}
	return killRequest{label: args[0]}, nil
}

// killEveryWorkDirSession 은 이 워크디렉토리의 세션을 모두 종료한다.
func killEveryWorkDirSession(out io.Writer, workDirName string, backend session.SessionBackend) error {
	sessionNames, err := backend.List()
	if err != nil {
		return fmt.Errorf("세션 목록 조회 실패: %w", err)
	}

	// 백엔드는 사용자가 직접 띄운 세션과 다른 워크디렉토리의 세션까지 함께 돌려준다.
	// 이름에 워크디렉토리가 들어 있는 덕에 여기서 갈라진다 — 그것이 명명 규칙의 존재 이유다(설계 문서 5.2).
	workDirPrefix := session.WorkDirSessionPrefix(workDirName)

	killedLabels := make([]string, 0, len(sessionNames))
	for _, sessionName := range sessionNames {
		if !strings.HasPrefix(sessionName, workDirPrefix) {
			continue
		}
		if err := backend.Kill(sessionName); err != nil {
			return fmt.Errorf("세션 종료 실패 (%s): %w", sessionName, err)
		}
		killedLabels = append(killedLabels, strings.TrimPrefix(sessionName, workDirPrefix))
	}

	if len(killedLabels) == 0 {
		fmt.Fprintln(out, "종료할 세션이 없습니다.")
		return nil
	}

	// 무엇을 몇 개 죽였는지는 사용자가 되짚을 수 없다 — 세션은 이미 사라졌다. 그 자리에서 알린다.
	fmt.Fprintf(out, "세션 %d 개를 종료했습니다: %s\n", len(killedLabels), strings.Join(killedLabels, ", "))
	return nil
}

// killOneSession 은 이름 하나에 해당하는 세션을 종료한다.
func killOneSession(out io.Writer, workDirName, label string, backend session.SessionBackend) error {
	sessionName := sessionNameForLabel(workDirName, label)

	isAlive, err := backend.IsAlive(sessionName)
	if err != nil {
		return fmt.Errorf("세션 생존 확인 실패 (%s): %w", sessionName, err)
	}

	// 없는 세션을 지우라는 요청은 이미 이뤄진 상태다. 실패로 끝내면 kyu kill 을 앞에 둔 정리
	// 스크립트가 두 번째 실행부터 깨진다. 그래도 조용히 끝내지는 않는다 — 이름을 잘못 적었을 때
	// 아무 말이 없으면 사용자는 그 세션을 죽였다고 믿는다.
	if !isAlive {
		fmt.Fprintf(out, "%s 세션이 없습니다.\n", label)
		return nil
	}

	if err := backend.Kill(sessionName); err != nil {
		return fmt.Errorf("%s 세션 종료 실패: %w", label, err)
	}

	fmt.Fprintf(out, "%s 세션을 종료했습니다.\n", label)
	return nil
}
