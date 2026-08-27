package cli

import (
	"github.com/maximinhan/Kyuchestration/internal/session"
)

// createdSession 은 recordingSessionBackend 가 받아 적은 Create 호출 하나다.
type createdSession struct {
	name    string
	cwd     string
	command []string
}

// recordingSessionBackend 는 백엔드 호출을 받아 적고 미리 정한 답을 돌려주는 테스트용 SessionBackend 다.
//
// 세션 명령이 하는 일의 대부분은 "무엇을 조립해 백엔드에 넘기는가" 다 — 세션 이름, cwd, 실행할 명령.
// 실제 tmux 로는 그 조립 결과를 되돌려 볼 수 없고(세션이 뜨는 순간 인자는 프로세스 안으로 사라진다),
// 무엇보다 claude 가 진짜로 떠버려 자동 테스트에 쓸 수 없다. 조립 결과는 여기서 고정한다.
//
// tmux 를 실제로 부르는 쪽은 internal/session 의 백엔드 테스트가 이미 검증한다. 표시 계층 테스트가
// 그것을 다시 검증하면 같은 것을 두 곳에서 지키게 되고, tmux 없는 환경에서 통째로 건너뛰게 된다.
type recordingSessionBackend struct {
	// aliveSessionNames 는 IsAlive 의 답이다. Kill 이 호출되면 여기서 빠진다.
	aliveSessionNames map[string]bool

	// listedSessionNames 는 List 의 답이다. 다른 워크디렉토리의 세션과 사용자가 직접 띄운 세션을
	// 섞어 넣어, 접두사 필터가 그것들을 건드리지 않는지 확인하는 데 쓴다.
	listedSessionNames []string

	// createError 는 Create 가 돌려줄 에러다. ErrSessionExists 처리를 확인할 때 채운다.
	createError error

	// attachError 는 Attach 가 돌려줄 에러다. ErrNestedSession 처리를 확인할 때 채운다.
	attachError error

	createdSessions      []createdSession
	attachedSessionNames []string
	killedSessionNames   []string
}

var _ session.SessionBackend = (*recordingSessionBackend)(nil)

func newRecordingSessionBackend(aliveSessionNames ...string) *recordingSessionBackend {
	alive := make(map[string]bool, len(aliveSessionNames))
	for _, sessionName := range aliveSessionNames {
		alive[sessionName] = true
	}
	return &recordingSessionBackend{aliveSessionNames: alive}
}

func (backend *recordingSessionBackend) Create(name, cwd string, command []string) error {
	backend.createdSessions = append(backend.createdSessions, createdSession{name: name, cwd: cwd, command: command})
	if backend.createError != nil {
		return backend.createError
	}

	// 실제 백엔드에서 Create 가 성공하면 그 세션은 그 순간부터 살아있다. 만들자마자 붙는 흐름이
	// 생겼으므로(인자 없는 kyu) 이 대역도 그 사실을 지켜야 한다 — 아니면 실제로는 이어지는 일이
	// 테스트에서만 "세션이 없습니다" 로 끊긴다.
	backend.aliveSessionNames[name] = true
	return nil
}

func (backend *recordingSessionBackend) Attach(name string) error {
	backend.attachedSessionNames = append(backend.attachedSessionNames, name)
	return backend.attachError
}

func (backend *recordingSessionBackend) Kill(name string) error {
	backend.killedSessionNames = append(backend.killedSessionNames, name)
	delete(backend.aliveSessionNames, name)
	return nil
}

func (backend *recordingSessionBackend) List() ([]string, error) {
	return backend.listedSessionNames, nil
}

func (backend *recordingSessionBackend) IsAlive(name string) (bool, error) {
	return backend.aliveSessionNames[name], nil
}
