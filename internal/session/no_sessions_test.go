package session

import (
	"errors"
	"testing"
)

// 이 백엔드는 동등성 매트릭스(backend_parity_test.go)에 들어가지 않는다. 까닭은 그 파일에 있다.

// noBackendOnThisMachine 은 백엔드를 세우지 못한 까닭이다.
// 실제로는 tmux 미설치가 들어오지만, 이 백엔드는 그 까닭이 무엇인지 해석하지 않고 그대로 전한다.
var noBackendOnThisMachine = errors.New("이 머신에는 세션 백엔드가 없습니다")

func TestNoSessionsBackendAnswersThatThisMachineHasNoSession(t *testing.T) {
	backend := NewNoSessionsBackend(noBackendOnThisMachine)

	sessionNames, err := backend.List()
	if err != nil {
		t.Fatalf("List() 오류 = %v, 없기를 기대 — 목록은 이 백엔드가 답해야 하는 것이다", err)
	}
	if len(sessionNames) != 0 {
		t.Errorf("List() = %q, 빈 목록을 기대", sessionNames)
	}

	// 이름을 가리지 않는다. 세션을 만들 수단이 없는 머신에는 어떤 이름의 세션도 있을 수 없다.
	for _, sessionName := range []string{MainSessionName("workdir"), RepoSessionName("workdir", "proj-a")} {
		isAlive, err := backend.IsAlive(sessionName)
		if err != nil {
			t.Fatalf("IsAlive(%q) 오류 = %v, 없기를 기대", sessionName, err)
		}
		if isAlive {
			t.Errorf("IsAlive(%q) = true, false 를 기대", sessionName)
		}
	}

	// 빠져나올 키가 없다는 것이 이 백엔드의 사실이다. 붙을 수 있는 세션이 없으므로 나올 자리도 없다.
	if key := backend.DetachKey(); key != "" {
		t.Errorf("DetachKey() = %q, 빈 값을 기대", key)
	}
}

func TestNoSessionsBackendRefusesSessionCommandsWithTheReasonItWasChosen(t *testing.T) {
	backend := NewNoSessionsBackend(noBackendOnThisMachine)

	refusals := map[string]error{
		"Create": backend.Create(MainSessionName("workdir"), t.TempDir(), []string{"claude"}),
		"Attach": backend.Attach(MainSessionName("workdir")),
		"Kill":   backend.Kill(MainSessionName("workdir")),
	}

	for methodName, err := range refusals {
		// 조용히 성공하면 사용자는 세션을 만들었다고, 또는 죽였다고 믿은 채로 다음 걸음을 밟는다.
		if err == nil {
			t.Errorf("%s 오류 = nil, 거절을 기대 — 이 백엔드는 세션을 만지지 못한다", methodName)
			continue
		}
		// 까닭을 감싸서 전한다. "세션을 만들 수 없습니다" 만으로는 무엇을 설치해야 하는지 알 수 없다.
		if !errors.Is(err, noBackendOnThisMachine) {
			t.Errorf("%s 오류 = %v, 백엔드가 없는 까닭을 감싸기를 기대", methodName, err)
		}
	}
}
