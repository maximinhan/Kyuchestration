package session

import "fmt"

// NoSessionsBackend 는 세션 백엔드를 세울 수 없는 머신에서 "여기에는 CLI 세션이 없다" 만 답하는
// 구현이다(설계 문서 5.7 의 구멍 · 8절 5번 (가)).
//
// 이 백엔드가 답하는 것은 추측이 아니라 사실이다. 세션은 백엔드를 거쳐야만 생기므로, 백엔드가
// 없는 머신에는 CLI 세션도 있을 수 없다. 그래서 목록은 비어 있고 어떤 이름도 살아있지 않다.
//
// 이것이 있어야 kyu list 가 tmux 없는 머신에서 선다는 사실이 사라진다. 앱은 자기 세션 상태를
// 스스로 알므로(설계 문서 5.4) 엔진의 답이 "세션 없음" 이어도 대시보드는 그대로 성립한다.
//
// 반대로 세션을 만지는 세 가지는 거절한다. 조용히 성공으로 끝내면 사용자는 세션을 띄웠다고,
// 또는 죽였다고 믿은 채 다음 걸음을 밟게 되는데, 이 백엔드에는 그 일을 할 수단이 애초에 없다.
type NoSessionsBackend struct {
	// backendAbsenceReason 은 세션 백엔드를 세우지 못한 까닭이다.
	//
	// 까닭을 해석하지 않고 들고 있다가 거절할 때 그대로 감싼다. 이 백엔드가 tmux 를 아는 순간
	// 백엔드가 늘어날 때마다 이 파일이 함께 바뀌고, 사용자는 "세션을 만들 수 없습니다" 만 듣고
	// 무엇을 설치해야 하는지는 듣지 못한다.
	backendAbsenceReason error
}

// 인터페이스 충족 여부를 컴파일 시점에 확인한다.
var _ SessionBackend = (*NoSessionsBackend)(nil)

// NewNoSessionsBackend 는 백엔드를 세우지 못한 까닭을 들고 있는 백엔드를 만든다.
func NewNoSessionsBackend(backendAbsenceReason error) *NoSessionsBackend {
	return &NoSessionsBackend{backendAbsenceReason: backendAbsenceReason}
}

// Create 는 세션을 만들지 못한다는 사실을 까닭과 함께 알린다.
func (b *NoSessionsBackend) Create(name, cwd string, cmd []string) error {
	return b.refuseBecauseThereIsNoBackend(name)
}

// Attach 는 붙을 세션이 없다는 사실을 까닭과 함께 알린다.
func (b *NoSessionsBackend) Attach(name string) error {
	return b.refuseBecauseThereIsNoBackend(name)
}

// Kill 은 세션을 종료하지 못한다는 사실을 까닭과 함께 알린다.
//
// 없는 세션에 대한 Kill 을 nil 로 답하는 멱등 조항(backend.go)을 여기서는 따르지 않는다.
// 그 조항은 "찾아보니 없더라" 를 위한 것이고, 여기는 찾을 수단 자체가 없는 자리다.
// 성공으로 끝내면 정리 스크립트가 아무것도 지우지 못한 채 다음 걸음으로 넘어간다.
func (b *NoSessionsBackend) Kill(name string) error {
	return b.refuseBecauseThereIsNoBackend(name)
}

// List 는 빈 목록을 답한다. 백엔드가 없는 머신에는 CLI 세션이 하나도 없다.
func (b *NoSessionsBackend) List() ([]string, error) {
	return nil, nil
}

// IsAlive 는 어떤 이름에 대해서도 거짓을 답한다.
//
// 실패가 아니라 답이라는 것이 이 메서드의 요점이다. 여기서 에러를 올리면 그것을 부르는
// kyu list 가 다시 종료 코드 1 로 끝나고, 이 백엔드를 둔 까닭이 사라진다.
func (b *NoSessionsBackend) IsAlive(name string) (bool, error) {
	return false, nil
}

// DetachKey 는 빈 값을 답한다. 붙을 수 있는 세션이 없으므로 빠져나올 키도 없다.
//
// 표시 계층이 이 값을 쓰는 자리는 진입 직전 안내 한 줄인데(internal/cli/attach.go), 거기에
// 닿으려면 IsAlive 가 참이어야 한다 — 이 백엔드가 답하지 않는 값이다.
func (b *NoSessionsBackend) DetachKey() string {
	return ""
}

// refuseBecauseThereIsNoBackend 는 세션을 만지는 요청을 까닭과 함께 거절한다.
//
// 무엇을 하려던 참인지는 적지 않는다. 부르는 쪽이 이미 "main 세션 생성 실패" 처럼 감싸므로,
// 여기서 또 적으면 사용자는 같은 말을 두 번 듣는다. 이 계층에만 있는 사실은 두 가지다 —
// 어느 세션이었는가, 그리고 백엔드가 왜 없는가.
func (b *NoSessionsBackend) refuseBecauseThereIsNoBackend(name string) error {
	return fmt.Errorf("이 머신에는 세션 백엔드가 없습니다 (세션 %s): %w", name, b.backendAbsenceReason)
}
