// Package session 은 세션의 생성·진입·종료를 담당하는 계층이다.
//
// 설계 문서 5.1 의 3층 구조에서 유일한 플랫폼 의존부이며,
// 이 패키지 밖에서는 tmux 를 직접 호출하지 않는다.
// 그 규칙이 지켜지는 한 다른 플랫폼 지원은 이 패키지에 파일 하나를 더하는 것으로 끝난다.
package session

import "errors"

// ErrSessionExists 는 만들려는 이름의 세션이 이미 있을 때 Create 가 반환한다.
//
// 호출부가 "이미 떠 있으니 새로 만들지 말고 붙어라" 를 판단해야 하므로
// 문자열 비교가 아닌 errors.Is 로 식별할 수 있는 센티널 에러로 둔다.
var ErrSessionExists = errors.New("세션이 이미 존재합니다")

// ErrNestedSession 은 이미 세션 안에서 돌고 있는 터미널을 다시 세션에 붙이려 할 때 Attach 가 반환한다.
//
// "세션 안에 있는가" 를 판정하는 방법은 백엔드마다 다르므로(tmux 는 환경변수 TMUX) 그 지식은
// 세션 계층에 남기고, 호출부에는 센티널 에러로만 알린다. 표시 계층이 이것을 직접 판정하면
// 백엔드가 늘어날 때마다 표시 계층이 함께 바뀐다.
var ErrNestedSession = errors.New("이미 세션 안에서 실행 중입니다")

// BackendSelectionEnvName 은 어느 세션 백엔드를 쓸지 고르는 환경변수다.
//
// 값을 읽어 실제로 조립하는 곳은 진입점 한 군데다. 이름만 여기 두는 이유는 세션 계층도 자기
// 안내에서 이것을 말해야 하기 때문이다 — 감독 백엔드가 아직 못 하는 일을 알릴 때 돌아갈 자리를
// 함께 일러준다. 두 곳에 문자열을 따로 적으면 이름을 바꿀 때 안내만 옛것으로 남는다.
const BackendSelectionEnvName = "KYU_SESSION_BACKEND"

// 고를 수 있는 백엔드 이름이다.
//
// tmux 를 지우지 않고 남긴다. 검증된 폴백이 있는 것과 없는 것은 사용자가 감수하는 위험이
// 다르다 — 감독이 어떤 터미널·어떤 셸에서 무너져도 자기 작업을 멈추지 않고 환경변수 하나로
// 돌아갈 수 있어야 한다(설계 문서 6절).
const (
	TmuxBackendName       = "tmux"
	SupervisorBackendName = "supervisor"
)

// SessionBackend 는 세션 백엔드가 제공해야 하는 최소 기능이다.
// 플랫폼 의존부는 이 5개 함수가 전부다.
type SessionBackend interface {
	// Create 는 cwd 에서 cmd 를 실행하는 백그라운드 세션을 만든다.
	// 이미 같은 이름의 세션이 있으면 ErrSessionExists 를 반환한다.
	Create(name, cwd string, cmd []string) error

	// Attach 는 호출한 터미널을 해당 세션에 연결한다.
	// 사용자가 빠져나올 때까지 블로킹된다.
	// 이미 세션 안에서 실행 중이면 ErrNestedSession 을 반환한다.
	Attach(name string) error

	// Kill 은 세션을 종료한다. 없으면 nil 을 반환한다(멱등).
	Kill(name string) error

	// List 는 이 백엔드가 관리하는 세션 이름 전체를 반환한다.
	List() ([]string, error)

	// IsAlive 는 세션의 생존 여부를 반환한다.
	IsAlive(name string) (bool, error)

	// DetachKey 는 이 백엔드의 세션에서 빠져나오는 키다.
	//
	// 키를 돌려주고 문장은 돌려주지 않는다. 무엇을 어떻게 말할지는 표시 계층의 몫이다 —
	// 백엔드가 완성된 문장을 주면 CLI 와 GUI 가 같은 문장을 쓰게 되는데, 두 곳은 사용자가
	// 있는 자리가 달라 할 말도 다르다(설계 문서 5.8).
	DetachKey() string
}
