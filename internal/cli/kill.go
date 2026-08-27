package cli

import (
	"fmt"
	"io"
	"strings"

	"github.com/maximinhan/Kyuchestration/internal/session"
)

const killUsageText = `사용법: wd kill [repo|main]

  wd kill <repo>   해당 레포의 세션을 종료한다
  wd kill main     메인 세션을 종료한다`

// KillSessions 는 wd kill 을 실행한다.
func KillSessions(out io.Writer, args []string, backend session.SessionBackend) error {
	label, err := parseKillArgs(args)
	if err != nil {
		return err
	}

	location, err := currentWorkDir()
	if err != nil {
		return err
	}

	return killOneSession(out, location.name, label, backend)
}

// parseKillArgs 는 종료할 대상 이름을 고른다.
//
// 인자 없는 kill 을 "전부 종료" 로 해석하지 않는다. 이름을 빠뜨린 사용자가 그 한 번으로
// 다른 레포에서 하던 작업까지 날리게 된다 — 되돌릴 수 없는 명령일수록 대상을 명시하게 한다.
func parseKillArgs(args []string) (string, error) {
	if len(args) != 1 {
		return "", fmt.Errorf("kill 은 종료할 이름 하나가 필요합니다 (인자 %d 개를 받음)\n\n%s", len(args), killUsageText)
	}

	if strings.HasPrefix(args[0], "-") {
		return "", fmt.Errorf("알 수 없는 옵션: %s\n\n%s", args[0], killUsageText)
	}
	return args[0], nil
}

// killOneSession 은 이름 하나에 해당하는 세션을 종료한다.
func killOneSession(out io.Writer, workDirName, label string, backend session.SessionBackend) error {
	sessionName := sessionNameForLabel(workDirName, label)

	isAlive, err := backend.IsAlive(sessionName)
	if err != nil {
		return fmt.Errorf("세션 생존 확인 실패 (%s): %w", sessionName, err)
	}

	// 없는 세션을 지우라는 요청은 이미 이뤄진 상태다. 실패로 끝내면 wd kill 을 앞에 둔 정리
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
