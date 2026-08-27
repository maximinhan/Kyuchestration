package cli

import (
	"errors"
	"fmt"
	"io"
	"strings"

	"github.com/maximinhan/Kyuchestration/internal/session"
)

const attachUsageText = `사용법: wd attach <repo>

  wd attach <repo>   해당 레포의 세션으로 진입한다
  wd attach main     메인 세션으로 진입한다`

// detachGuidance 는 세션에서 빠져나오는 방법이다.
//
// 사용자는 tmux 를 몰라도 되지만(설계 문서 8.2) 빠져나오는 방법 하나만은 알아야 한다.
// 세션에 들어간 뒤에는 이 도구가 말을 걸 수 없으므로, 진입 직전이 그것을 알릴 유일한 기회다.
const detachGuidance = "빠져나오기: Ctrl-b d"

// AttachSession 은 wd attach 를 실행한다. 사용자가 세션에서 빠져나올 때까지 블로킹된다.
func AttachSession(out io.Writer, args []string, backend session.SessionBackend) error {
	label, err := parseAttachArgs(args)
	if err != nil {
		return err
	}

	location, err := currentWorkDir()
	if err != nil {
		return err
	}
	sessionName := sessionNameForLabel(location.name, label)

	// 붙기 전에 생존을 먼저 묻는다. 없는 세션에 붙으려 하면 백엔드도 실패하지만, 그 실패는
	// 다음에 무엇을 해야 하는지 알려주지 않는다.
	isAlive, err := backend.IsAlive(sessionName)
	if err != nil {
		return fmt.Errorf("세션 생존 확인 실패 (%s): %w", sessionName, err)
	}
	if !isAlive {
		return fmt.Errorf("%s 세션이 없습니다\n시작: wd start %s", label, label)
	}

	fmt.Fprintln(out, detachGuidance)

	if err := backend.Attach(sessionName); err != nil {
		// 백엔드는 "중첩이다" 까지만 말한다. 그것을 사용자가 할 수 있는 행동으로 옮기는 것은 이쪽 몫이다.
		if errors.Is(err, session.ErrNestedSession) {
			return errors.New("이 터미널은 이미 세션 안입니다 — Ctrl-b d 로 빠져나온 뒤 다시 실행하세요")
		}
		return fmt.Errorf("%s 세션 진입 실패: %w", label, err)
	}
	return nil
}

// parseAttachArgs 는 진입할 대상 이름을 고른다.
//
// 인자 없이 부른 것을 메인 진입으로 해석하지 않는다. 그러면 사용자가 레포 이름을 빠뜨렸을 때
// 엉뚱한 세션으로 끌려가고, 그 사실을 세션 안에서야 알게 된다.
func parseAttachArgs(args []string) (string, error) {
	if len(args) != 1 {
		return "", fmt.Errorf("attach 는 진입할 이름 하나가 필요합니다 (인자 %d 개를 받음)\n\n%s", len(args), attachUsageText)
	}

	// attach 에는 옵션이 없다. 옵션처럼 생긴 것을 이름으로 흘려보내면 "--typo 세션이 없습니다" 가 나온다.
	if strings.HasPrefix(args[0], "-") {
		return "", fmt.Errorf("알 수 없는 옵션: %s\n\n%s", args[0], attachUsageText)
	}
	return args[0], nil
}
